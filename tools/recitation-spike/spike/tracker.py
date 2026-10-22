"""Streaming recitation tracker: online CTC forced alignment against the known Quran text.

The reader is assumed to recite a known sequence of words. Every audio frame, the tracker
extends a Viterbi alignment of the CTC acoustic model's frame scores against that word sequence,
so pauses and elongated vowels simply stay in the current state, however long they last.

Uthmani text and the model's (standard) spelling disagree on vowel marks, alif and hamza, so
both are reduced to a consonant skeleton: those symbols are folded into the CTC blank.
"""
from dataclasses import dataclass

import numpy as np

# Model symbols that carry no weight for alignment (folded into blank).
FOLDED = set("اٱأإآءـۥۦ|") | {chr(c) for c in range(0x064B, 0x0660)} | {"ٰ"}
# Letter variants that align as the same letter.
EQUIVALENT = {"ى": "ي", "ئ": "ي", "ی": "ي", "ؤ": "و", "ة": "ه", "ۦ": "ي", "ۧ": "ي",
              "ۥ": "و"}
LETTERS = [chr(c) for c in range(0x0621, 0x063B)] + [chr(c) for c in range(0x0641, 0x064B)]


def skeleton(word: str) -> str:
  out = []
  for c in word:
    c = EQUIVALENT.get(c, c)
    if c in LETTERS and c not in FOLDED:
      out.append(c)
  return "".join(out)


class SkeletonScores:
  """Maps a CTC model's per-frame log-probabilities onto blank + consonant classes."""

  def __init__(self, vocab: dict[str, int], blank_id: int):
    classes = sorted({skeleton(ch) for ch in LETTERS} - {""})
    self.class_of = {letter: i + 1 for i, letter in enumerate(classes)}
    groups = [[blank_id]] + [[] for _ in classes]
    for symbol, token_id in vocab.items():
      if token_id == blank_id:
        continue
      letter = skeleton(symbol)
      if len(symbol) == 1 and letter in self.class_of:
        groups[self.class_of[letter]].append(token_id)
      elif len(symbol) == 1 and (symbol in FOLDED or symbol == " "):
        groups[0].append(token_id)
    self.groups = groups

  def __call__(self, log_probs: np.ndarray) -> np.ndarray:
    """(frames, vocab) log-probabilities -> (frames, classes) scores."""
    out = np.full((log_probs.shape[0], len(self.groups)), -1e9)
    for k, ids in enumerate(self.groups):
      if ids:
        out[:, k] = np.logaddexp.reduce(log_probs[:, ids], axis=1)
    return out

  def tokens(self, word: str) -> list[int]:
    return [self.class_of[c] for c in skeleton(word)]


@dataclass
class TrackerConfig:
  # Score per frame of the filler state (audio before the text, e.g. basmala), relative to the
  # best class score of that frame.
  filler_penalty: float = 1.5
  # Frames the best path must stay past a word before it counts as recited (20 ms per frame).
  stable_frames: int = 5


ISTIADHA = "أَعُوذُ بِٱللَّهِ مِنَ ٱلشَّيۡطَٰنِ ٱلرَّجِيمِ".split()
BASMALA = "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ".split()


def opening_phrases(sura: int, first_ayah: int = 1) -> list[list[str]]:
  """Phrases a reader may say before the ayah text: ta'awwudh, then basmala at a sura start."""
  phrases = [ISTIADHA]
  if first_ayah == 1 and sura not in (1, 9):  # al-Fatiha's basmala is its first ayah
    phrases.append(BASMALA)
  return phrases


class RecitationTracker:
  """Follows a reader through `words` one frame at a time.

  `optional_phrases` (e.g. ta'awwudh and basmala) may each be recited or skipped before the text.
  """

  def __init__(
    self,
    words: list[str],
    scores: SkeletonScores,
    config: TrackerConfig = TrackerConfig(),
    optional_phrases: list[list[str]] = (),
  ):
    self.config = config
    # CTC states: blank, t1, blank, t2, ..., blank. Phrases and text are chained in order.
    tokens = []
    segment_starts, segment_ends = [], []

    def add_segment(segment_words):
      first_blank = 2 * len(tokens)
      for word in segment_words:
        tokens.extend(scores.tokens(word))
      segment_starts.append(first_blank)
      segment_ends.append(2 * len(tokens))
      return first_blank

    for phrase in optional_phrases:
      add_segment(phrase)
    text_start = add_segment([])
    last_state_of_word = []
    for word in words:
      tokens.extend(scores.tokens(word))
      last_state_of_word.append(2 * len(tokens))  # the blank after the word's last letter
    self.n_states = 2 * len(tokens) + 1
    self.text_start = text_start
    self.emission_class = np.zeros(self.n_states, dtype=int)
    self.emission_class[1::2] = tokens
    self.last_state_of_word = np.array(last_state_of_word)
    # A token state may skip the preceding blank unless it repeats the previous token.
    self.can_skip = np.zeros(self.n_states, dtype=bool)
    for j in range(3, self.n_states, 2):
      self.can_skip[j] = self.emission_class[j] != self.emission_class[j - 2]
    # Entry points from the filler: the start of every phrase and of the text.
    self.entries = sorted({s for s in segment_starts} | {s + 1 for s in segment_starts})
    # The end of any phrase may jump straight to the text, skipping later phrases.
    self.jumps = [
      (source, target)
      for end in segment_ends[: len(optional_phrases)]
      for source in (end - 1, end)  # the phrase's last letter or the blank after it
      for target in (text_start, text_start + 1)
    ]

    self.delta = np.full(self.n_states, -np.inf)
    self.filler = 0.0
    self.frames = 0
    self.completed = 0  # number of words recited so far
    self._stable = 0

  def push(self, frame_scores: np.ndarray) -> int:
    """Consumes one frame of class scores and returns the number of completed words."""
    emit = frame_scores[self.emission_class]
    prev = self.delta
    best = prev.copy()
    np.maximum(best[1:], prev[:-1], out=best[1:])
    skip = np.full(self.n_states, -np.inf)
    skip[2:] = prev[:-2]
    best = np.where(self.can_skip, np.maximum(best, skip), best)
    for source, target in self.jumps:
      best[target] = max(best[target], prev[source])
    for entry in self.entries:
      best[entry] = max(best[entry], self.filler)
    self.delta = best + emit
    self.filler += frame_scores.max() - self.config.filler_penalty
    self.frames += 1

    position = int(np.argmax(self.delta))
    if self.filler > self.delta[position] or position < self.text_start:
      return self.completed  # still before the text
    target = self.completed
    while target < len(self.last_state_of_word) and position >= self.last_state_of_word[target]:
      target += 1
    if target > self.completed:
      self._stable += 1
      if self._stable >= self.config.stable_frames:
        self.completed = target
        self._stable = 0
    else:
      self._stable = 0
    return self.completed

  def renormalize(self) -> None:
    """Keeps scores in a numerically comfortable range on long recitations."""
    shift = max(self.delta.max(), self.filler)
    self.delta -= shift
    self.filler -= shift

"""Checks recited words for possible mistakes (goodness of pronunciation).

Once the tracker confirms a word, the frames from when it reached the word until it confirmed it
are aligned twice: freely (the model's best class per frame) and to the word's expected letters
as pronounced. The difference, the word's deficit, is 0 when the model heard exactly the expected
letters and more negative the more the audio disagrees.
"""
import numpy as np

from .tracker import skeleton

MARKS = {chr(c) for c in range(0x064B, 0x0660)} | {"\u0670"} | {chr(c) for c in range(0x06D6, 0x06EE)}
SHADDA = "\u0651"
# Spacing characters inside some words of the Quran text, e.g. a hair space before a dagger alif.
INVISIBLE = set("\u200a\u200b\u200c\u200d\u2060")


def spoken_letters(word: str) -> list[tuple[str, bool]]:
  """The word's consonant skeleton as pronounced, as (letter, optional) pairs.

  Tajweed differs from spelling: a sun-letter lam (ٱل before a doubled letter) is silent; alif
  maqsura (ى) is a long a; ؤ and ئ carry a hamza; a و or ي with no vowel mark of its own is a
  lengthened vowel (madd). The model may not emit those as letters: silent letters are dropped,
  the others optional.
  """
  units = []  # (base character, its marks)
  for c in word:
    if c in INVISIBLE:
      continue
    if c in MARKS and units:
      units[-1] = (units[-1][0], units[-1][1] + c)
    else:
      units.append((c, ""))
  letters = []
  for i, (base, marks) in enumerate(units):
    letter = skeleton(base)
    if not letter:
      continue
    previous = units[i - 1][0] if i > 0 else ""
    following_marks = units[i + 1][1] if i + 1 < len(units) else ""
    if base == "ل" and previous == "ٱ" and not marks and SHADDA in following_marks:
      continue  # sun-letter lam: silent
    # ى is a long a; ؤ and ئ are hamza sounds; an unmarked و or ي is a lengthened vowel.
    optional = base in "ىؤئ" or (base in "وي" and not marks)
    letters.append((letter, optional))
  return letters


def word_deficit(frames, tokens, skip_penalty=4.0, optional=None, letters=False):
  """How much worse the word's expected letters explain these frames than the model's free choice.

  Local CTC alignment: free frames before, the word's letters in order (blank between repeats,
  a letter may be skipped at `skip_penalty`), free frames after. Returns (deficit, worst letter
  index): deficit is 0 when the expected letters are exactly what the model heard, more negative
  the more the audio disagrees. With `letters`, also each letter's share of the deficit.
  """
  free = frames.max(axis=1)
  n = 2 * len(tokens) + 1
  emission_class = np.zeros(n, dtype=int)
  emission_class[1::2] = tokens
  # States: 0 = free before, 1..n = CTC states of the word, n + 1 = free after.
  NEG = -1e18
  delta = np.full(n + 2, NEG)
  back = []
  for t, frame in enumerate(frames):
    prev = delta
    best = np.full(n + 2, NEG)
    arg = np.zeros(n + 2, dtype=int)
    def offer(target, source, penalty=0.0):
      # source -1 is "start of the span": only possible on the first frame.
      if source == -1:
        value = 0.0 if t == 0 else NEG
      else:
        value = prev[source] if t > 0 else NEG
      value -= penalty
      if value > best[target]:
        best[target] = value
        arg[target] = source
    for j in range(n + 2):
      if j == 0:
        offer(0, 0); offer(0, -1)
        continue
      if j == n + 1:
        offer(j, j); offer(j, n); offer(j, n - 1)
        if optional is not None and optional[-1] and n >= 4:
          offer(j, n - 2); offer(j, n - 3)  # stop before an optional last letter
        continue
      offer(j, j); offer(j, j - 1)
      if j in (1, 2) or (j in (3, 4) and optional is not None and optional[0]):
        offer(j, -1)
        offer(j, 0)  # from free frames before the word (past an optional first letter)
      if j % 2 == 0 and j >= 3 and emission_class[j - 1] != emission_class[j - 3]:
        offer(j, j - 2)  # token state (1-based j even -> 0-based odd)
      if j % 2 == 0 and j >= 5:
        # Skip the letter before this one; free when that letter is optional (a lengthened vowel).
        skipped = j // 2 - 2
        cost = 0.0 if optional is not None and optional[skipped] else skip_penalty
        offer(j, j - 4, cost); offer(j, j - 3, cost)
    emit = np.empty(n + 2)
    emit[0] = emit[n + 1] = free[t]
    emit[1:n + 1] = frame[emission_class]
    delta = best + emit
    back.append(arg)
  ends = [n + 1, n, n - 1] + ([n - 2, n - 3] if optional is not None and optional[-1] and n >= 4 else [])
  end = max(ends, key=lambda j: delta[j])
  deficit = delta[end] - free.sum()
  # Per letter: the deficit of the frames aligned to it (skipped letters cost the skip penalty).
  per_letter = np.zeros(len(tokens))
  visited = np.zeros(len(tokens), dtype=bool)
  state = end
  for t in range(len(frames) - 1, -1, -1):
    if 1 <= state <= n and state % 2 == 0:
      k = state // 2 - 1
      per_letter[k] += frames[t][emission_class[state - 1]] - free[t]
      visited[k] = True
    state = back[t][state]
    if state == -1:
      break
  per_letter[~visited] -= skip_penalty
  worst = int(np.argmin(per_letter)) if len(tokens) else 0
  if letters:
    return deficit, worst, per_letter
  return deficit, worst




class WordChecker:
  """Scores each word as the tracker confirms it, from the frames it spent reaching it.

  Feed it every frame right after the tracker has consumed it. `deficits[w]` is (deficit, worst
  letter index) once word w is confirmed. When the reader goes back over checked words, each is
  checked again as the reader passes it and keeps its latest attempt, so a word said wrong and
  then recited again correctly ends up clear (and the other way round). The tracker's position can
  flicker back a word for a moment without the reader going back, so a return counts as an
  attempt only if it lasted MIN_REVISIT frames or jumped back at least MIN_JUMP words (the tracker
  may notice a real repeat late, after the reader already said the word again).
  """

  MARGIN = 30  # frames before the tracker reached a word that still count as the word's audio
  MIN_REVISIT = 20  # frames (0.4 s) back that make a return an attempt
  MIN_JUMP = 2  # words jumped back that make a return an attempt

  def __init__(self, tracker, words: list[str], class_of: dict[str, int], skip_penalty: float = 4.0):
    self.tracker = tracker
    self.spoken = [[(class_of[l], o) for l, o in spoken_letters(w) if l in class_of] for w in words]
    self.skip_penalty = skip_penalty
    self.frames = []
    self.reached = {}
    self.deficits = {}
    self.revisited = {}  # checked word -> (frame the reader went back to or before it, words jumped)
    self.furthest = 0  # furthest word the reader has been on

  def _score(self, w, start, i):
    spoken = self.spoken[w]
    if not spoken:
      return (0.0, 0)
    span = np.array(self.frames[max(0, start - self.MARGIN):i + 1])
    return word_deficit(span, [t for t, _ in spoken], self.skip_penalty, [o for _, o in spoken])

  def push(self, frame) -> list[int]:
    """Returns the words checked (or checked again) with this frame."""
    i = len(self.frames)
    self.frames.append(frame)
    for w in range(len(self.reached), min(self.tracker.current_word, len(self.spoken) - 1) + 1):
      self.reached[w] = i
    scored = []
    for w in range(len(self.deficits), self.tracker.completed):
      self.reached.setdefault(w, i)
      self.deficits[w] = self._score(w, self.reached[w], i)
      scored.append(w)
    # Going back: checked words at or after the reader's position are being recited again.
    current = self.tracker.current_word
    for w in range(current, len(self.deficits)):
      self.revisited.setdefault(w, (i, self.furthest - current))
    self.furthest = max(self.furthest, current)
    for w in sorted(self.revisited):
      if current > w:  # passed it again
        start, jumped = self.revisited.pop(w)
        if i - start >= self.MIN_REVISIT or jumped >= self.MIN_JUMP:
          self.deficits[w] = self._score(w, start, i)
          scored.append(w)
    return scored

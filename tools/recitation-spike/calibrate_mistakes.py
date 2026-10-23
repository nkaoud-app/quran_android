"""Calibrates mistake detection: how often correct words get flagged, and whether real slips do.

  python calibrate_mistakes.py --model MODEL.onnx --clean rec1 ... --mistakes rec.mp4 \\
      [--sura 67 --ayahs 1 10] [--expect 6:1 ...]

A word's evidence is its weakest letter: the lowest, over the word's letters, of the best
log-probability the model gave that letter while the tracker was on it (-inf if the tracker never
stopped on it). Words of correct recitations should score high; mistaken words low.
"""
import argparse
import math

import numpy as np
import torch

from spike.acoustic import AcousticModel, StreamingConfig
from spike.data import sura_audio, sura_words
from spike.mistakes import spoken_letters, word_deficit
from spike.tracker import RecitationTracker, SkeletonScores, TrackerConfig, opening_phrases
from track_recording import load_audio

THRESHOLDS = [-1, -2, -3, -4, -6, -8, -10]


def full_context_frames(model, scores, audio, window_seconds=12):
  """Frames computed with context on both sides (as when re-scoring a finished ayah)."""
  step = window_seconds // 2 * 16000
  pad = step // 2
  out = []
  for s in range(0, len(audio), step):
    a = max(0, s - pad)
    log_probs = model._log_probs(audio[a:min(len(audio), s + step + pad)])
    first = (s - a) // 320
    out.append(log_probs[first:first + step // 320])
  return scores(np.concatenate(out))


def word_scores(model, audio, words, sura, first_ayah, streaming, config, rescore=False):
  scores = SkeletonScores(model.vocab, model.blank_id)
  tracker = RecitationTracker([w.text for w in words], scores, config,
                              optional_phrases=opening_phrases(sura, first_ayah))
  frames = scores(model.streaming_log_probs(audio, streaming))
  frames = frames - np.logaddexp.reduce(frames, axis=1, keepdims=True)
  # Tracking always uses streaming frames; mistakes may be scored on re-computed frames.
  scoring = frames
  if rescore:
    full = full_context_frames(model, scores, audio)
    full = full - np.logaddexp.reduce(full, axis=1, keepdims=True)
    scoring = full[:len(frames)] if len(full) >= len(frames) else np.vstack([full, frames[len(full):]])
  reached = {}  # word -> first frame the tracker was on it
  confirmed = {}  # word -> frame it was confirmed
  for i, frame in enumerate(frames):
    tracker.push(frame)
    if i % 500 == 0:
      tracker.renormalize()
    for w in range(min(tracker.current_word, len(words) - 1) + 1):
      reached.setdefault(w, i)
    for w in range(tracker.completed):
      confirmed.setdefault(w, i)
  result = []
  for w in range(tracker.completed):
    spoken = [(l, o) for l, o in spoken_letters(words[w].text) if l in scores.class_of]
    if not spoken:
      result.append((0.0, 0))
      continue
    tokens = [scores.class_of[l] for l, _ in spoken]
    span = scoring[max(0, reached[w] - 30):confirmed[w] + 1]
    result.append(word_deficit(span, tokens, optional=[o for _, o in spoken]))
  return result


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("--model", required=True)
  parser.add_argument("--clean", nargs="*", default=[])
  parser.add_argument("--mistakes", nargs="*", default=[])
  parser.add_argument("--expect", nargs="*", default=[], help="ayah:word of known mistakes")
  parser.add_argument("--sura", type=int, default=67)
  parser.add_argument("--ayahs", type=int, nargs=2, default=[1, 10])
  parser.add_argument("--hop", type=float, default=0.5)
  parser.add_argument("--context", type=float, default=2.0)
  parser.add_argument("--lookahead", type=float, default=0.3)
  parser.add_argument("--stable-frames", type=int, default=15)
  parser.add_argument("--skip-letter-penalty", type=float, default=0.5)
  parser.add_argument("--rescore", action="store_true", help="score mistakes on full-context frames")
  args = parser.parse_args()

  torch.set_num_threads(4)
  model = AcousticModel(args.model)
  streaming = StreamingConfig(args.hop, args.context, args.lookahead)
  config = TrackerConfig(stable_frames=args.stable_frames, skip_letter_penalty=args.skip_letter_penalty)
  first, last = args.ayahs
  words = [w for w in sura_words(args.sura) if first <= w.ayah <= last]
  print(f"{streaming} {config}")

  # Correct recitations: every flag is a false alarm. Minshawi's whole suras count too.
  clean = []
  for sura in (1, 67):
    clean += [d for d, _ in word_scores(model, sura_audio(sura), sura_words(sura), sura, 1, streaming, config, args.rescore)]
  for path in args.clean:
    clean += [d for d, _ in word_scores(model, load_audio(path), words, args.sura, first, streaming, config, args.rescore)]
  clean = np.array(clean)
  print(f"\ncorrect words: {len(clean)}; deficit percentiles 50/90/99: "
        f"{np.percentile(clean, 50):.2f} {np.percentile(clean, 10):.2f} {np.percentile(clean, 1):.2f}")
  for t in THRESHOLDS:
    print(f"  flag if deficit < {t:<4}: {np.mean(clean < t) * 100:5.1f}% of correct words flagged")

  expected = {tuple(map(int, e.split(":"))) for e in args.expect}
  for path in args.mistakes:
    scored = word_scores(model, load_audio(path), words, args.sura, first, streaming, config, args.rescore)
    print(f"\n{path.rsplit('/', 1)[-1]}: {len(scored)} words tracked; lowest deficits:")
    ranked = sorted(range(len(scored)), key=lambda k: scored[k][0])
    for k in ranked[:12] + [k for k in range(len(scored)) if (words[k].ayah, words[k].index) in expected and k not in ranked[:12]]:
      deficit, worst = scored[k]
      word = words[k]
      marker = "<-- known mistake" if (word.ayah, word.index) in expected else ""
      spoken = [l for l, _ in spoken_letters(word.text)]
      letter = spoken[worst] if worst < len(spoken) else ""
      print(f"  {word.ayah}:{word.index:<3} {word.text:<16} deficit {deficit:7.2f}  worst letter {letter} {marker}")


if __name__ == "__main__":
  main()

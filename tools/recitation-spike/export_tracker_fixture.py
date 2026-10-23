"""Writes a test fixture so the Kotlin tracker can be checked against this Python one.

  python export_tracker_fixture.py MODEL.onnx OUT_DIR [--sura 1]

OUT_DIR/frames.bin holds the streaming model's per-frame class log-probabilities for the sura
(little-endian float32, frames x classes). Plain text files hold the words (one per line), the
opening phrases (one per line) and, per frame, the tracker's completed word count and current
word (space separated), and per word its mistake check deficit and weakest letter (one word per
line). The tracker runs with default TrackerConfig, renormalizing every 500 frames.
"""
import argparse
from pathlib import Path

import numpy as np

from spike.acoustic import AcousticModel, StreamingConfig
from spike.data import sura_audio, sura_words
from spike.mistakes import WordChecker
from spike.tracker import RecitationTracker, SkeletonScores, TrackerConfig, opening_phrases


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("model")
  parser.add_argument("out")
  parser.add_argument("--sura", type=int, default=1)
  args = parser.parse_args()

  model = AcousticModel(args.model)
  streaming = StreamingConfig(hop_seconds=1.0, context_seconds=3.0, lookahead_seconds=1.0)
  frames = model.streaming_log_probs(sura_audio(args.sura), streaming).astype(np.float32)
  scores = SkeletonScores(model.vocab, model.blank_id)
  words = [w.text for w in sura_words(args.sura)]
  phrases = opening_phrases(args.sura)
  config = TrackerConfig()
  tracker = RecitationTracker(words, scores, config, optional_phrases=phrases)

  checker = WordChecker(tracker, words, scores.class_of)
  completed, current = [], []
  for i, frame in enumerate(scores(frames)):
    tracker.push(frame)
    if i % 500 == 0:
      tracker.renormalize()
    checker.push(frame)
    completed.append(tracker.completed)
    current.append(tracker.current_word)

  out = Path(args.out)
  out.mkdir(parents=True, exist_ok=True)
  frames.astype("<f4").tofile(out / "frames.bin")
  (out / "words.txt").write_text("\n".join(words) + "\n")
  (out / "phrases.txt").write_text("\n".join(" ".join(p) for p in phrases) + "\n")
  (out / "completed.txt").write_text(" ".join(map(str, completed)) + "\n")
  (out / "current_word.txt").write_text(" ".join(map(str, current)) + "\n")
  (out / "deficits.txt").write_text(
    "".join(f"{float(checker.deficits[w][0])!r} {int(checker.deficits[w][1])}\n" for w in sorted(checker.deficits))
  )
  print(f"{len(completed)} frames, {tracker.completed}/{len(words)} words completed")


if __name__ == "__main__":
  main()

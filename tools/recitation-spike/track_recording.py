"""Runs the streaming tracker on any recording and prints when each word was detected.

Usage: python track_recording.py recording.m4a --sura 67 --ayahs 1 10
"""
import argparse
import math
import subprocess

import numpy as np
import torch

from spike.acoustic import FRAME_SECONDS, AcousticModel, StreamingConfig
from spike.data import SAMPLE_RATE, sura_words
from spike.tracker import RecitationTracker, SkeletonScores, TrackerConfig, opening_phrases


def load_audio(path: str) -> np.ndarray:
  raw = subprocess.run(
    ["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "f32le", "-"],
    check=True, capture_output=True,
  ).stdout
  return np.frombuffer(raw, dtype=np.float32)


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("recording")
  parser.add_argument("--sura", type=int, required=True)
  parser.add_argument("--ayahs", type=int, nargs=2, metavar=("FIRST", "LAST"), required=True)
  parser.add_argument("--model", default="rabah2026/wav2vec2-large-xlsr-53-arabic-quran-v_final")
  parser.add_argument("--hop", type=float, default=0.5)
  parser.add_argument("--context", type=float, default=6.0)
  parser.add_argument("--lookahead", type=float, default=1.0)
  args = parser.parse_args()

  torch.set_num_threads(4)
  first, last = args.ayahs
  words = [w for w in sura_words(args.sura) if first <= w.ayah <= last]
  audio = load_audio(args.recording)
  model = AcousticModel(args.model)
  streaming = StreamingConfig(args.hop, args.context, args.lookahead)
  scores = SkeletonScores(model.vocab, model.blank_id)
  frame_scores = scores(model.streaming_log_probs(audio, streaming))
  tracker = RecitationTracker(
    [w.text for w in words], scores, TrackerConfig(),
    optional_phrases=opening_phrases(args.sura, first),
  )
  frames_per_hop = round(streaming.hop_seconds / FRAME_SECONDS)

  detected = [None] * len(words)
  for i, frame in enumerate(frame_scores):
    before = tracker.completed
    after = tracker.push(frame)
    available = math.ceil((i + 1) / frames_per_hop) * streaming.hop_seconds + streaming.lookahead_seconds
    for w in range(before, after):
      detected[w] = available
    if i % 500 == 0:
      tracker.renormalize()

  print(f"{args.recording}: {len(audio) / SAMPLE_RATE:.1f}s, sura {args.sura} ayahs {first}-{last}")
  for word, at in zip(words, detected):
    when = f"{at:7.2f}s" if at is not None else "    not detected"
    print(f"  {word.ayah:>3}:{word.index:<3} {when}  {word.text}")
  print(f"detected {sum(d is not None for d in detected)} of {len(words)} words")


if __name__ == "__main__":
  main()

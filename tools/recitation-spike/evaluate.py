"""Measures how well the streaming tracker follows professional recitations.

For every word, compares the moment the tracker marks it recited with the reciter's reference
word timings. Usage: python evaluate.py [--suras 1 67] [--model ID] [--hop 0.5] [--context 3]
"""
import argparse
import math

import numpy as np
import torch

from spike.acoustic import FRAME_SECONDS, AcousticModel, StreamingConfig
from spike.data import sura_audio, sura_words
from spike.tracker import RecitationTracker, SkeletonScores, TrackerConfig, opening_phrases


def percentile(values, q):
  return float(np.percentile(values, q)) if values else math.nan


def evaluate_sura(model, sura, streaming, tracker_config, verbose):
  words = sura_words(sura)
  log_probs = model.streaming_log_probs(sura_audio(sura), streaming)
  scores = SkeletonScores(model.vocab, model.blank_id)
  frame_scores = scores(log_probs)
  tracker = RecitationTracker(
    [w.text for w in words], scores, tracker_config, optional_phrases=opening_phrases(sura)
  )
  frames_per_hop = round(streaming.hop_seconds / FRAME_SECONDS)

  completed_at = [None] * len(words)
  for i, frame in enumerate(frame_scores):
    before = tracker.completed
    after = tracker.push(frame)
    # The frame is only available once its whole hop of audio has been heard.
    available = math.ceil((i + 1) / frames_per_hop) * streaming.hop_seconds + streaming.lookahead_seconds
    for w in range(before, after):
      completed_at[w] = available
    if i % 500 == 0:
      tracker.renormalize()

  lags, premature, missed = [], 0, 0
  ayah_lags = []
  for w, word in enumerate(words):
    done = completed_at[w]
    if done is None:
      missed += 1
      continue
    if done < word.start:
      premature += 1
    lags.append(done - word.end)
    is_last = w + 1 == len(words) or words[w + 1].ayah != word.ayah
    if is_last:
      ayah_lags.append(done - word.end)
    if verbose:
      print(f"  {word.ayah:>3}:{word.index:<3} {word.text:<20} spoken {word.start:7.2f}-{word.end:7.2f}"
            f"  tracked {done if done is not None else float('nan'):7.2f}")
  return dict(words=len(words), missed=missed, premature=premature, lags=lags, ayah_lags=ayah_lags)


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("--suras", type=int, nargs="+", default=[1, 67])
  parser.add_argument("--model", default="rabah2026/wav2vec2-large-xlsr-53-arabic-quran-v_final")
  parser.add_argument("--hop", type=float, default=0.5)
  parser.add_argument("--context", type=float, default=6.0)
  parser.add_argument("--lookahead", type=float, default=1.0)
  parser.add_argument("--filler-penalty", type=float, default=1.5)
  parser.add_argument("--stable-frames", type=int, default=5)
  parser.add_argument("--skip-letter-penalty", type=float, default=TrackerConfig.skip_letter_penalty)
  parser.add_argument("--verbose", action="store_true")
  args = parser.parse_args()

  torch.set_num_threads(4)
  model = AcousticModel(args.model)
  streaming = StreamingConfig(args.hop, args.context, args.lookahead)
  tracker_config = TrackerConfig(args.filler_penalty, args.stable_frames,
                                 skip_letter_penalty=args.skip_letter_penalty)
  print(f"model={args.model} {streaming} {tracker_config}")
  for sura in args.suras:
    r = evaluate_sura(model, sura, streaming, tracker_config, args.verbose)
    print(
      f"sura {sura:>3}: {r['words']} words, missed {r['missed']}, premature {r['premature']} | "
      f"word lag after end median {percentile(r['lags'], 50):+.2f}s p90 {percentile(r['lags'], 90):+.2f}s | "
      f"ayah advance lag median {percentile(r['ayah_lags'], 50):+.2f}s "
      f"max {max(r['ayah_lags'], default=math.nan):+.2f}s"
    )


if __name__ == "__main__":
  main()

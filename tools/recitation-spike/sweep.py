"""Sweeps streaming and tracker settings to find where tracking breaks.

  python sweep.py --recordings rec1.m4a ... [--sura 67 --ayahs 1 10]

For every combination, tracks Minshawi's al-Fatiha and al-Mulk (reference word timings) and the
given straight recitations (references from the teacher's offline alignment), and prints one row:
words missed (not counting a recording's last word), words marked early, ayah switch delay, and the
phone load relative to the default settings (which ran at 0.45x real time on the user's phone).
"""
import argparse
import itertools
import math

import numpy as np
import torch

from compare_recordings import reference_word_ends, track
from spike.acoustic import FRAME_SECONDS, AcousticModel, StreamingConfig
from spike.data import sura_audio, sura_words
from spike.tracker import TrackerConfig
from track_recording import load_audio

PHONE_DEFAULT_LOAD = 0.45  # measured: hop 1.0, context 3, look-ahead 1.0


def relative_load(streaming: StreamingConfig) -> float:
  """Audio the model processes per second, relative to the default settings."""
  window = streaming.context_seconds + streaming.hop_seconds + streaming.lookahead_seconds
  return (window / streaming.hop_seconds) / 5.0


def score(detected, starts, ends, last_ayah_words, ignore_last):
  # A recording that stops right after its last word cannot confirm it; that is not a miss.
  missed = sum(d is None for d in (detected[:-1] if ignore_last else detected))
  early = sum(1 for d, s in zip(detected, starts) if d is not None and s is not None and d < s)
  ahead = sum(1 for d, e in zip(detected, ends) if d is not None and d < e - 0.3)
  ayah_lags = [detected[k] - ends[k] for k in last_ayah_words if detected[k] is not None]
  return missed, early, ahead, ayah_lags


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("--model", required=True)
  parser.add_argument("--recordings", nargs="*", default=[])
  parser.add_argument("--sura", type=int, default=67)
  parser.add_argument("--ayahs", type=int, nargs=2, default=[1, 10])
  parser.add_argument("--lookahead", type=float, nargs="+", default=[0.0, 0.1, 0.2, 0.3])
  parser.add_argument("--hop", type=float, nargs="+", default=[0.25, 0.5])
  parser.add_argument("--context", type=float, nargs="+", default=[1.0, 2.0])
  parser.add_argument("--stable-frames", type=int, nargs="+", default=[5])
  parser.add_argument("--skip-letter-penalty", type=float, nargs="+", default=[1.0])
  args = parser.parse_args()

  torch.set_num_threads(4)
  model = AcousticModel(args.model)

  # (name, audio, words, sura, first ayah, word starts or None, word ends, ignore last word)
  cases = []
  for sura in (1, 67):
    words = sura_words(sura)
    cases.append((f"minshawi {sura}", sura_audio(sura), words, sura, 1,
                  [w.start for w in words], [w.end for w in words], False))
  first, last = args.ayahs
  words = [w for w in sura_words(args.sura) if first <= w.ayah <= last]
  for path in args.recordings:
    audio = load_audio(path)
    ends = reference_word_ends(path, audio, words, args.sura, first)
    # An early mark is one before the previous word ended (the reference has no start times).
    starts = [0.0] + ends[:-1]
    cases.append((path.rsplit("/", 1)[-1][:12], audio, words, args.sura, first, starts, ends, True))

  # early: marked before the word started; ahead: marked over 0.3 s before the word ended.
  print("lookahead hop context stable skip | missed early ahead | ayah switch median p90 max | phone load")
  for lookahead, hop, context in itertools.product(args.lookahead, args.hop, args.context):
    streaming = StreamingConfig(hop, context, lookahead)
    for stable, skip in itertools.product(args.stable_frames, args.skip_letter_penalty):
      config = TrackerConfig(stable_frames=stable, skip_letter_penalty=skip)
      missed = early = ahead = 0
      lags = []
      for name, audio, case_words, sura, first_ayah, starts, ends, ignore_last in cases:
        detected = track(model, audio, case_words, sura, first_ayah, streaming, config)
        last_words = [k for k, w in enumerate(case_words)
                      if k + 1 == len(case_words) or case_words[k + 1].ayah != w.ayah]
        m, e, a, l = score(detected, starts, ends, last_words, ignore_last)
        missed, early, ahead, lags = missed + m, early + e, ahead + a, lags + l
      load = relative_load(streaming) * PHONE_DEFAULT_LOAD
      print(f"{lookahead:9.2f} {hop:4.2f} {context:6.1f} {stable:6d} {skip:4.1f} | {missed:6d} {early:5d} {ahead:5d} | "
            f"{np.median(lags):+11.2f} {np.percentile(lags, 90):+5.2f} {max(lags):+5.2f} | {load:9.2f}x",
            flush=True)


if __name__ == "__main__":
  main()

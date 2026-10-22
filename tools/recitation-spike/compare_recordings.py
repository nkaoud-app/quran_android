"""Scores a model's streaming tracking on arbitrary recordings, without reference timings.

Each recording is first aligned offline by the teacher with full context (cached); that serves
as the reference for when each word ends. Recordings that stray from the text (repeats, mistakes)
make a poor reference, so only use straight recitations here.

  python compare_recordings.py --model MODEL --sura 67 --ayahs 1 10 rec1.m4a rec2.mp3 ...
"""
import argparse
import hashlib
import json
import math

import numpy as np
import torch
import torchaudio.functional as F

from spike.acoustic import FRAME_SECONDS, AcousticModel, StreamingConfig
from spike.data import DATA, sura_words
from spike.tracker import (BASMALA, ISTIADHA, RecitationTracker, SkeletonScores, TrackerConfig,
                           opening_phrases)
from track_recording import load_audio

TEACHER = "rabah2026/wav2vec2-large-xlsr-53-arabic-quran-v_final"


def full_context_scores(model, scores, audio):
  step, pad = 160000, 80000
  frames = []
  for s in range(0, len(audio), step):
    a = max(0, s - pad)
    log_probs = model._log_probs(audio[a:min(len(audio), s + step + pad)])
    frames.append(log_probs[(s - a) // 320:(s - a) // 320 + step // 320])
  classes = scores(np.concatenate(frames))
  return classes - np.logaddexp.reduce(classes, axis=1, keepdims=True)


def reference_word_ends(path, audio, words, sura, first_ayah):
  """Teacher's offline forced alignment: end time of each word, trying each opening."""
  key = hashlib.sha1(f"{path}|{len(audio)}|{[w.text for w in words]}".encode()).hexdigest()[:16]
  cache = DATA / "reference" / f"{key}.json"
  if cache.exists():
    return json.loads(cache.read_text())
  teacher = AcousticModel(TEACHER)
  scores = SkeletonScores(teacher.vocab, teacher.blank_id)
  classes = torch.tensor(full_context_scores(teacher, scores, audio), dtype=torch.float32)[None]
  candidates = [[], [ISTIADHA]] + ([[BASMALA], [ISTIADHA, BASMALA]] if first_ayah == 1 and sura not in (1, 9) else [])
  best = None
  for phrases in candidates:
    tokens, owner = [], []
    for word in [w for phrase in phrases for w in phrase]:
      t = scores.tokens(word)
      tokens += t
      owner += [-1] * len(t)
    for k, word in enumerate(words):
      t = scores.tokens(word.text)
      tokens += t
      owner += [k] * len(t)
    alignment, frame_scores = F.forced_align(classes, torch.tensor([tokens], dtype=torch.int32), blank=0)
    score = frame_scores.sum().item()
    if best is None or score > best[0]:
      best = (score, alignment[0].numpy(), owner)
  _, alignment, owner = best
  ends, token = {}, -1
  for t, c in enumerate(alignment):
    if c != 0 and (t == 0 or alignment[t - 1] != c):
      token += 1
    if c != 0 and owner[token] >= 0:
      ends[owner[token]] = (t + 1) * FRAME_SECONDS
  result = [ends[k] for k in range(len(words))]
  cache.parent.mkdir(parents=True, exist_ok=True)
  cache.write_text(json.dumps(result))
  return result


def track(model, audio, words, sura, first_ayah, streaming, config=TrackerConfig()):
  scores = SkeletonScores(model.vocab, model.blank_id)
  frame_scores = scores(model.streaming_log_probs(audio, streaming))
  tracker = RecitationTracker([w.text for w in words], scores, config,
                              optional_phrases=opening_phrases(sura, first_ayah))
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
  return detected


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("recordings", nargs="+")
  parser.add_argument("--model", default=TEACHER)
  parser.add_argument("--sura", type=int, required=True)
  parser.add_argument("--ayahs", type=int, nargs=2, required=True)
  parser.add_argument("--hop", type=float, default=0.5)
  parser.add_argument("--context", type=float, default=6.0)
  parser.add_argument("--lookahead", type=float, default=1.0)
  parser.add_argument("--skip-letter-penalty", type=float, default=TrackerConfig.skip_letter_penalty)
  args = parser.parse_args()

  torch.set_num_threads(4)
  first, last = args.ayahs
  words = [w for w in sura_words(args.sura) if first <= w.ayah <= last]
  ayah_ends = [k for k, w in enumerate(words) if k + 1 == len(words) or words[k + 1].ayah != w.ayah]
  streaming = StreamingConfig(args.hop, args.context, args.lookahead)
  model = AcousticModel(args.model)
  print(f"model={args.model} {streaming} skip_letter_penalty={args.skip_letter_penalty}")
  for path in args.recordings:
    audio = load_audio(path)
    reference = reference_word_ends(path, audio, words, args.sura, first)
    config = TrackerConfig(skip_letter_penalty=args.skip_letter_penalty)
    detected = track(model, audio, words, args.sura, first, streaming, config)
    lags = np.array([d - r for d, r in zip(detected, reference) if d is not None])
    ayah_lags = np.array([detected[k] - reference[k] for k in ayah_ends if detected[k] is not None])
    print(f"  {path.rsplit('/', 1)[-1][:28]:<28} missed {sum(d is None for d in detected)} "
          f"early {int((lags < -0.3).sum())} | word lag median {np.median(lags):+.2f}s "
          f"p90 {np.percentile(lags, 90):+.2f}s | ayah lag median {np.median(ayah_lags):+.2f}s "
          f"max {ayah_lags.max():+.2f}s")


if __name__ == "__main__":
  main()

"""Distils the large Quran CTC model into a phone-sized student that predicts skeleton classes.

  python distill.py label   # teacher labels training audio (slow, cached)
  python distill.py train --out student [--layers 12] [--steps 3000]

The teacher sees whole recordings with full context; the student is trained on short windows
shaped like streaming inference (past context + new audio + look-ahead) to match the teacher's
per-frame skeleton class probabilities. Both models emit one frame per 320 samples, so frames
line up one to one.
"""
import argparse
import json
import random
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from transformers import Wav2Vec2ForCTC

from spike.acoustic import DEVICE, AcousticModel
from spike.data import DATA, SAMPLE_RATE, TRAINING_RECITERS, sura_audio
from spike.tracker import SkeletonScores

TEACHER = "rabah2026/wav2vec2-large-xlsr-53-arabic-quran-v_final"
STUDENT_INIT = "TBOGamer22/wav2vec2-quran-phonetics"
# Shortest first: labelling goes sura by sura across all reciters, so a partial run still covers
# every voice.
TRAINING_SURAS = [86, 87, 82, 84, 85, 88, 81, 89, 83, 79, 80, 78, 36]
HOP = 320  # samples per frame
LABELS = DATA / "distill" / "labels"


def label(args):
  """Teacher skeleton-class log-probabilities for every training recording, full context."""
  torch.set_num_threads(4)
  teacher = AcousticModel(TEACHER)
  scores = SkeletonScores(teacher.vocab, teacher.blank_id)
  LABELS.mkdir(parents=True, exist_ok=True)
  (LABELS / "classes.json").write_text(json.dumps(
    {"blank": 0, "letters": sorted(scores.class_of, key=scores.class_of.get)}, ensure_ascii=False
  ))
  step, pad = 500 * HOP, 150 * HOP  # 10 s kept per 16 s window; multiples of HOP keep frames aligned
  for sura in TRAINING_SURAS:
    for reciter in TRAINING_RECITERS:
      out = LABELS / f"{reciter.replace('/', '_')}_{sura:03d}.npy"
      if out.exists():
        continue
      started = time.time()
      audio = sura_audio(sura, reciter)
      frames = []
      for s in range(0, len(audio), step):
        a = max(0, s - pad)
        log_probs = teacher._log_probs(audio[a:min(len(audio), s + step + pad)])
        offset = (s - a) // HOP
        frames.append(log_probs[offset:offset + step // HOP])
      classes = scores(np.concatenate(frames))
      classes -= np.logaddexp.reduce(classes, axis=1, keepdims=True)
      np.save(out, classes.astype(np.float16))
      print(f"labelled {out.name}: {len(audio) / SAMPLE_RATE:.0f}s in {time.time() - started:.0f}s",
            flush=True)


def student_model(n_classes: int, layers: int) -> Wav2Vec2ForCTC:
  model = Wav2Vec2ForCTC.from_pretrained(
    STUDENT_INIT, vocab_size=n_classes, pad_token_id=0, ignore_mismatched_sizes=True,
    layerdrop=0.0, mask_time_prob=0.0,
  )
  if layers < model.config.num_hidden_layers:
    keep = np.linspace(0, model.config.num_hidden_layers - 1, layers).round().astype(int)
    model.wav2vec2.encoder.layers = torch.nn.ModuleList(
      [model.wav2vec2.encoder.layers[i] for i in keep]
    )
    model.config.num_hidden_layers = layers
  return model


def augment(audio: np.ndarray, rng: random.Random) -> np.ndarray:
  """Rough phone-recording conditions: gain, background noise and a band-limited microphone."""
  audio = audio * rng.uniform(0.3, 1.5)
  if rng.random() < 0.7:
    audio = audio + np.random.randn(len(audio)).astype(np.float32) * rng.uniform(0.001, 0.02)
  if rng.random() < 0.5:
    spectrum = np.fft.rfft(audio)
    freqs = np.fft.rfftfreq(len(audio), 1 / SAMPLE_RATE)
    spectrum[(freqs < rng.uniform(80, 300)) | (freqs > rng.uniform(3400, 7000))] *= 0.1
    audio = np.fft.irfft(spectrum, n=len(audio)).astype(np.float32)
  return audio


def train(args):
  torch.set_num_threads(4)
  classes = json.loads((LABELS / "classes.json").read_text())
  n_classes = 1 + len(classes["letters"])
  items = []
  for path in sorted(LABELS.glob("*.npy")):
    reciter, sura = path.stem.rsplit("_", 1)
    items.append((reciter, int(sura), path))
  reciter_dirs = {r.replace("/", "_"): r for r in TRAINING_RECITERS}
  audio = {p: sura_audio(s, reciter_dirs[r]) for r, s, p in items}
  labels = {p: np.load(p).astype(np.float32) for _, _, p in items}
  total = sum(len(a) for a in audio.values()) / SAMPLE_RATE / 3600
  print(f"{len(items)} recordings, {total:.1f} h of audio", flush=True)

  model = student_model(n_classes, args.layers).to(DEVICE)
  model.freeze_feature_encoder()
  model.train()
  optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=args.lr)
  schedule = torch.optim.lr_scheduler.OneCycleLR(optimizer, args.lr, total_steps=args.steps,
                                                 pct_start=0.1)
  scaler = torch.amp.GradScaler(enabled=DEVICE == "cuda")
  rng = random.Random(0)
  window_frames = round(args.window * SAMPLE_RATE / HOP)
  keys = list(audio)
  out = Path(args.out)
  started = time.time()
  for step in range(1, args.steps + 1):
    batch, targets = [], []
    for _ in range(args.batch):
      key = rng.choice(keys)
      n_frames = min(len(labels[key]), len(audio[key]) // HOP) - window_frames - 1
      first = rng.randrange(max(1, n_frames))
      chunk = audio[key][first * HOP:(first + window_frames) * HOP + HOP]
      chunk = augment(chunk, rng)
      chunk = (chunk - chunk.mean()) / (chunk.std() + 1e-7)  # what the feature extractor does
      batch.append(chunk)
      targets.append(labels[key][first:first + window_frames])
    with torch.autocast("cuda", dtype=torch.float16, enabled=DEVICE == "cuda"):
      logits = model(torch.tensor(np.stack(batch), device=DEVICE)).logits
    n = min(logits.shape[1], window_frames)
    log_probs = F.log_softmax(logits[:, :n].float(), dim=-1)
    target = torch.tensor(np.stack(targets)[:, :n], device=DEVICE)
    loss = F.kl_div(log_probs, target, log_target=True, reduction="none").sum(-1).mean()
    optimizer.zero_grad()
    scaler.scale(loss).backward()
    scaler.unscale_(optimizer)
    torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
    scaler.step(optimizer)
    scaler.update()
    schedule.step()
    if step % args.log_every == 0:
      letters = target.argmax(-1) != 0  # frames where the teacher hears a letter, not blank
      agree = (log_probs.argmax(-1) == target.argmax(-1))[letters].float().mean().item()
      print(f"step {step} loss {loss.item():.3f} letter agreement {agree:.3f} "
            f"{(time.time() - started) / step:.1f}s/step", flush=True)
    if step % 500 == 0 or step == args.steps:
      model.save_pretrained(out)
      (out / "classes.json").write_text(json.dumps(classes, ensure_ascii=False))


def main():
  parser = argparse.ArgumentParser()
  sub = parser.add_subparsers(dest="command", required=True)
  sub.add_parser("label")
  t = sub.add_parser("train")
  t.add_argument("--out", required=True)
  t.add_argument("--layers", type=int, default=12)
  t.add_argument("--steps", type=int, default=3000)
  t.add_argument("--batch", type=int, default=8)
  t.add_argument("--window", type=float, default=4.0, help="seconds; context + hop + look-ahead")
  t.add_argument("--lr", type=float, default=5e-5)
  t.add_argument("--log-every", type=int, default=20)
  args = parser.parse_args()
  {"label": label, "train": train}[args.command](args)


if __name__ == "__main__":
  main()

"""Exports a distilled student to ONNX, quantizes it to int8 and measures it for phones.

  python export_onnx.py STUDENT_DIR OUT_DIR

Writes OUT_DIR/model.onnx (float32), OUT_DIR/model.int8.onnx and classes.json, then reports file
sizes, how closely int8 output matches float32, and single-thread speed per streaming window.
"""
import argparse
import shutil
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from transformers import Wav2Vec2ForCTC

from spike.acoustic import StreamingConfig
from spike.data import SAMPLE_RATE, sura_audio


class Logits(torch.nn.Module):
  def __init__(self, model):
    super().__init__()
    self.model = model

  def forward(self, audio):
    return self.model(audio).logits


def session(path: Path) -> ort.InferenceSession:
  options = ort.SessionOptions()
  options.intra_op_num_threads = 1  # a phone app should not take every core
  options.inter_op_num_threads = 1
  return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("student")
  parser.add_argument("out")
  args = parser.parse_args()
  out = Path(args.out)
  out.mkdir(parents=True, exist_ok=True)

  model = Wav2Vec2ForCTC.from_pretrained(args.student).float().eval()
  example = torch.randn(1, 4 * SAMPLE_RATE)
  fp32 = out / "model.onnx"
  torch.onnx.export(
    Logits(model), (example,), str(fp32), input_names=["audio"], output_names=["logits"],
    dynamic_axes={"audio": {1: "samples"}, "logits": {1: "frames"}}, opset_version=17, dynamo=False,
  )
  int8 = out / "model.int8.onnx"
  # Only the transformer's matrix multiplications: quantized convolutions (the waveform feature
  # encoder) run slower than float on many CPUs.
  quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
  shutil.copy(Path(args.student) / "classes.json", out / "classes.json")

  streaming = StreamingConfig()
  window = round((streaming.context_seconds + streaming.hop_seconds + streaming.lookahead_seconds)
                 * SAMPLE_RATE)
  audio = sura_audio(67)[60 * SAMPLE_RATE:60 * SAMPLE_RATE + window][None]  # real recitation
  audio = (audio - audio.mean()) / audio.std()
  results = {}
  for name, path in (("float32", fp32), ("int8", int8)):
    s = session(path)
    s.run(None, {"audio": audio})  # warm up
    started = time.perf_counter()
    runs = 5
    for _ in range(runs):
      logits = s.run(None, {"audio": audio})[0]
    seconds = (time.perf_counter() - started) / runs
    results[name] = logits
    print(f"{name:>7}: {path.stat().st_size / 1e6:6.1f} MB | one {window / SAMPLE_RATE:.1f}s window "
          f"on 1 thread: {seconds * 1000:5.0f} ms = {seconds / streaming.hop_seconds:.2f}x real time "
          f"at a {streaming.hop_seconds}s hop")
  agree = (results["float32"].argmax(-1) == results["int8"].argmax(-1)).mean()
  print(f"int8 vs float32 frame argmax agreement on a recitation window: {agree:.3f}")


if __name__ == "__main__":
  main()

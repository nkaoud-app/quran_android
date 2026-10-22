"""Runs a CTC acoustic model over audio the way a phone would: in small chunks, no look-ahead."""
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from transformers import AutoProcessor, Wav2Vec2ForCTC

from .data import DATA, SAMPLE_RATE

FRAME_SECONDS = 0.02  # wav2vec2 emits one frame per 20 ms
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"


@dataclass
class StreamingConfig:
  hop_seconds: float = 0.5  # new audio per model call (decision latency)
  context_seconds: float = 6.0  # past audio re-fed to the model for context
  lookahead_seconds: float = 1.0  # future audio the model sees; adds to decision latency


class AcousticModel:
  """A Hugging Face CTC model, a distilled student directory (with classes.json), or a student
  exported to ONNX (model*.onnx next to its classes.json)."""

  def __init__(self, model_id: str):
    self.model_id = model_id
    self.onnx = None
    if model_id.endswith(".onnx"):
      import onnxruntime

      self.onnx = onnxruntime.InferenceSession(model_id, providers=["CPUExecutionProvider"])
      classes = Path(model_id).parent / "classes.json"
    else:
      self.model = Wav2Vec2ForCTC.from_pretrained(model_id).float().eval().to(DEVICE)
      classes = Path(model_id) / "classes.json"
    if classes.exists():  # student: outputs skeleton classes directly
      letters = json.loads(classes.read_text())["letters"]
      self.vocab = {"<blank>": 0} | {letter: i + 1 for i, letter in enumerate(letters)}
      self.blank_id = 0
      self.processor = None
    else:
      self.processor = AutoProcessor.from_pretrained(model_id)
      self.vocab = self.processor.tokenizer.get_vocab()
      self.blank_id = self.processor.tokenizer.pad_token_id

  @torch.no_grad()
  def _log_probs(self, audio: np.ndarray) -> np.ndarray:
    if self.processor:
      inputs = self.processor(audio, sampling_rate=SAMPLE_RATE, return_tensors="pt").input_values
    else:
      inputs = torch.tensor((audio - audio.mean()) / (audio.std() + 1e-7))[None]
    if self.onnx:
      logits = torch.tensor(self.onnx.run(None, {"audio": inputs.numpy()})[0][0])
      return torch.log_softmax(logits, dim=-1).numpy()
    with torch.autocast("cuda", dtype=torch.float16, enabled=DEVICE == "cuda"):
      logits = self.model(inputs.to(DEVICE)).logits[0]
    return torch.log_softmax(logits.float(), dim=-1).cpu().numpy()

  def _version(self) -> str:
    """Changes when a local (student) checkpoint is retrained, so cached outputs are not reused."""
    weights = Path(self.model_id) if self.onnx else Path(self.model_id) / "model.safetensors"
    return str(weights.stat().st_mtime) if weights.exists() else ""

  def streaming_log_probs(self, audio: np.ndarray, config: StreamingConfig) -> np.ndarray:
    """Frame log-probabilities, each computed only from audio up to the end of its hop.

    Returns (frames, vocab); frame i becomes available `lookahead_seconds` after the end of the
    hop containing it.
    Results are cached on disk since this is the slow part of an evaluation.
    """
    key = hashlib.sha1(
      f"{self.model_id}|{self._version()}|aligned|{config}|{len(audio)}|{audio[::997].tobytes().hex()}".encode()
    ).hexdigest()[:16]
    cache = Path(DATA) / "cache" / f"{key}.npy"
    if cache.exists():
      return np.load(cache)

    hop = int(config.hop_seconds * SAMPLE_RATE)
    context = int(config.context_seconds * SAMPLE_RATE)
    lookahead = int(config.lookahead_seconds * SAMPLE_RATE)
    chunks = []
    for start in range(0, len(audio), hop):
      end = min(start + hop, len(audio))
      window_end = min(end + lookahead, len(audio))
      log_probs = self._log_probs(audio[max(0, start - context):window_end])
      new_frames = round((end - start) / SAMPLE_RATE / FRAME_SECONDS)
      if new_frames == 0:
        continue
      # Pick frames by position: the model emits one frame fewer than the window length suggests,
      # and the missing frame is at the end.
      first = round((start - max(0, start - context)) / SAMPLE_RATE / FRAME_SECONDS)
      chunks.append(log_probs[first:first + new_frames])
    result = np.concatenate(chunks)
    cache.parent.mkdir(parents=True, exist_ok=True)
    np.save(cache, result)
    return result

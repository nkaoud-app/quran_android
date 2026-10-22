"""Runs a CTC acoustic model over audio the way a phone would: in small chunks, no look-ahead."""
import hashlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from transformers import AutoProcessor, Wav2Vec2ForCTC

from .data import DATA, SAMPLE_RATE

FRAME_SECONDS = 0.02  # wav2vec2 emits one frame per 20 ms


@dataclass
class StreamingConfig:
  hop_seconds: float = 0.5  # new audio per model call (decision latency)
  context_seconds: float = 6.0  # past audio re-fed to the model for context
  lookahead_seconds: float = 1.0  # future audio the model sees; adds to decision latency


class AcousticModel:
  def __init__(self, model_id: str):
    self.model_id = model_id
    self.processor = AutoProcessor.from_pretrained(model_id)
    self.model = Wav2Vec2ForCTC.from_pretrained(model_id).eval()
    self.vocab = self.processor.tokenizer.get_vocab()
    self.blank_id = self.processor.tokenizer.pad_token_id

  @torch.no_grad()
  def _log_probs(self, audio: np.ndarray) -> np.ndarray:
    inputs = self.processor(audio, sampling_rate=SAMPLE_RATE, return_tensors="pt")
    logits = self.model(inputs.input_values).logits[0]
    return torch.log_softmax(logits.float(), dim=-1).numpy()

  def streaming_log_probs(self, audio: np.ndarray, config: StreamingConfig) -> np.ndarray:
    """Frame log-probabilities, each computed only from audio up to the end of its hop.

    Returns (frames, vocab); frame i becomes available `lookahead_seconds` after the end of the
    hop containing it.
    Results are cached on disk since this is the slow part of an evaluation.
    """
    key = hashlib.sha1(
      f"{self.model_id}|{config}|{len(audio)}|{audio[::997].tobytes().hex()}".encode()
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
      future_frames = round((window_end - end) / SAMPLE_RATE / FRAME_SECONDS)
      new_frames = round((end - start) / SAMPLE_RATE / FRAME_SECONDS)
      if new_frames == 0:
        continue  # a few trailing samples; [-0:] below would repeat the whole window
      kept = log_probs[: log_probs.shape[0] - future_frames] if future_frames else log_probs
      chunks.append(kept[-new_frames:])
    result = np.concatenate(chunks)
    cache.parent.mkdir(parents=True, exist_ok=True)
    np.save(cache, result)
    return result

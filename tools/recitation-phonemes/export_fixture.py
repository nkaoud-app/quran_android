"""Records what the recognizer hears of a recitation, for PhonemeFollowerTest.

  python export_fixture.py SPIKE_DIR MODEL_DIR OUT.txt --sura 67 [--ayahs 1 10] [--drop 2] [--repeat 2]

SPIKE_DIR is tools/recitation-spike, for Minshawi's recitation and its word timings. MODEL_DIR
holds Quran-Lab's zipformer_p_arabic_v3.int8.onnx and its tokens.txt, run with sherpa-onnx as the
app runs it (480 ms chunks after 480 ms of silence). --drop leaves an ayah out, --repeat recites
it twice.

Each output line is the recognizer's tokens after one chunk: how many of the previous line's
tokens it keeps, then its other tokens (space separated). The first line is "sura first_ayah".
"""
import argparse
import sys
import numpy as np
import sherpa_onnx

CHUNK = 7680


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("spike_dir")
  parser.add_argument("model_dir")
  parser.add_argument("out")
  parser.add_argument("--sura", type=int, required=True)
  parser.add_argument("--ayahs", type=int, nargs=2)
  parser.add_argument("--drop", type=int)
  parser.add_argument("--repeat", type=int)
  args = parser.parse_args()
  sys.path.insert(0, args.spike_dir)
  from spike.data import SAMPLE_RATE, sura_audio, sura_words

  audio = sura_audio(args.sura)
  words = sura_words(args.sura)
  ayahs = sorted({w.ayah for w in words})
  first, last = args.ayahs or (ayahs[0], ayahs[-1])

  def span(ayah):  # ayah audio from its first word's start to the next ayah's start
    start = min(w.start for w in words if w.ayah == ayah)
    later = [w.start for w in words if w.ayah == ayah + 1]
    end = later[0] if later else max(w.end for w in words if w.ayah == ayah) + 0.5
    return audio[int(start * SAMPLE_RATE):int(end * SAMPLE_RATE)]

  opening = audio[:int(min(w.start for w in words if w.ayah == first) * SAMPLE_RATE)] if first == 1 else np.zeros(0, np.float32)
  pieces = [opening]
  for ayah in range(first, last + 1):
    if ayah == args.drop:
      continue
    pieces.append(span(ayah))
    if ayah == args.repeat:
      pieces.append(span(ayah))
  pieces.append(np.zeros(SAMPLE_RATE, np.float32))
  recitation = np.concatenate(pieces).astype(np.float32)

  d = args.model_dir
  rec = sherpa_onnx.OnlineRecognizer.from_zipformer2_ctc(
    tokens=f"{d}/tokens.txt", model=f"{d}/zipformer_p_arabic_v3.int8.onnx", num_threads=2,
    sample_rate=SAMPLE_RATE, feature_dim=80, enable_endpoint_detection=False)
  stream = rec.create_stream()
  stream.accept_waveform(SAMPLE_RATE, np.zeros(CHUNK, np.float32))
  while rec.is_ready(stream):
    rec.decode_stream(stream)
  with open(args.out, "w", encoding="utf-8") as out:
    out.write(f"{args.sura} {first}\n")
    previous = []
    for s in range(0, len(recitation) - CHUNK + 1, CHUNK):
      stream.accept_waveform(SAMPLE_RATE, recitation[s:s + CHUNK])
      while rec.is_ready(stream):
        rec.decode_stream(stream)
      tokens = list(rec.get_result_all(stream).tokens)
      keep = 0
      while keep < min(len(previous), len(tokens)) and previous[keep] == tokens[keep]:
        keep += 1
      out.write(" ".join([str(keep)] + tokens[keep:]) + "\n")
      previous = tokens
  print(f"{len(recitation) / SAMPLE_RATE:.1f}s of audio -> {args.out}")


if __name__ == "__main__":
  main()

package com.quran.recitation.tracking

import java.io.File

/** A streaming phoneme recognizer (the Zipformer model run by sherpa-onnx, see [ZipformerModel]). */
interface PhonemeRecognizer : AutoCloseable {
  /** Takes 16 kHz mono samples in [-1, 1] and decodes what it can. */
  fun acceptWaveform(samples: FloatArray)

  /** All tokens recognized since creation or the last [reset]. */
  fun tokens(): List<String>

  /** Starts over, forgetting the audio heard so far. */
  fun reset()
}

fun interface PhonemeRecognizerFactory {
  fun create(model: File, tokens: File, threads: Int): PhonemeRecognizer
}

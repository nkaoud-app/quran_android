package com.quran.labs.androidquran.feature.reading.presenter.recitation

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.quran.data.di.AppScope
import com.quran.recitation.tracking.PhonemeRecognizer
import com.quran.recitation.tracking.PhonemeRecognizerFactory
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.File

/** The streaming Zipformer CTC phoneme model (see ZipformerModel) run by sherpa-onnx. */
@ContributesBinding(AppScope::class)
class SherpaPhonemeRecognizerFactory @Inject constructor() : PhonemeRecognizerFactory {
  override fun create(model: File, tokens: File, threads: Int): PhonemeRecognizer =
    SherpaPhonemeRecognizer(model, tokens, threads)
}

private class SherpaPhonemeRecognizer(model: File, tokens: File, threads: Int) : PhonemeRecognizer {
  private val recognizer = OnlineRecognizer(
    assetManager = null,
    config = OnlineRecognizerConfig(
      featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
      modelConfig = OnlineModelConfig(
        zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = model.path),
        tokens = tokens.path,
        numThreads = threads,
        modelType = "zipformer2_ctc",
      ),
      // tracking follows the whole recitation, so pauses need not end anything
      enableEndpoint = false,
    ),
  )
  private val stream: OnlineStream = recognizer.createStream()

  init {
    prime()
  }

  override fun acceptWaveform(samples: FloatArray) {
    stream.acceptWaveform(samples, SAMPLE_RATE)
    while (recognizer.isReady(stream)) recognizer.decode(stream)
  }

  override fun tokens(): List<String> = recognizer.getResult(stream).tokens.asList()

  override fun reset() {
    recognizer.reset(stream)
    prime()
  }

  /** A model chunk (480 ms) of silence first, so the first speech is not cut. */
  private fun prime() {
    acceptWaveform(FloatArray(PRIMING_SAMPLES))
  }

  override fun close() {
    stream.release()
    recognizer.release()
  }

  private companion object {
    const val SAMPLE_RATE = 16000
    const val PRIMING_SAMPLES = 7680
  }
}

package com.quran.recitation.tracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class StreamingFramesTest {
  /** Like wav2vec2: a frame per 320 samples with a 400 sample receptive field; class 1 = loudness. */
  private val clickDetector = FrameModel { window ->
    val frames = (window.size - 400) / 320 + 1
    Array(frames) { j ->
      FloatArray(RecitationSkeleton.CLASS_COUNT).also { logits ->
        logits[1] = (j * 320 until j * 320 + 400).maxOf { abs(window[it]) }
      }
    }
  }

  @Test
  fun `frames line up with the audio across hops and windows`() {
    val audio = FloatArray(10 * 16000)
    val click = (5.37 * 16000).toInt()
    audio[click] = 1f
    val streaming = StreamingFrames(clickDetector)

    val frames = ArrayList<FloatArray>()
    for (start in audio.indices step 1600) {
      frames += streaming.accept(audio.copyOfRange(start, start + 1600))
    }
    // With a 1 s hop and 1 s look-ahead, the last second still waits for its look-ahead.
    assertThat(frames.size).isEqualTo(9 * 50)
    frames += streaming.finish()
    // The final window ends with the audio, and the model emits one frame fewer than the window
    // length suggests, so the very last frame of the recording is not available.
    assertThat(frames.size).isEqualTo(10 * 50 - 1)

    val loudest = frames.indices.maxBy { frames[it][1] }
    assertThat(loudest).isEqualTo(click / 320)
  }

  @Test
  fun `frames are log-probabilities`() {
    val streaming = StreamingFrames(clickDetector)
    val frames = streaming.accept(FloatArray(3 * 16000) { (it % 97) / 97f })
    assertThat(frames).isNotEmpty()
    for (frame in frames) {
      assertThat(frame.sumOf { exp(it.toDouble()) }).isWithin(1e-4).of(1.0)
    }
  }

  @Test
  fun `old audio is dropped once it is out of context`() {
    val streaming = StreamingFrames(clickDetector)
    repeat(120) { streaming.accept(FloatArray(16000)) }
    assertThat(streaming.receivedSamples).isEqualTo(120L * 16000)
    assertThat(streaming.processedSamples).isEqualTo(119L * 16000)
  }
}

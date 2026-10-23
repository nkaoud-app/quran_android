package com.quran.recitation.tracking

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Turns a window of 16 kHz mono audio into per-frame (20 ms) class logits. */
fun interface FrameModel {
  /** Returns logits shaped frames x [RecitationSkeleton.CLASS_COUNT]. */
  fun logits(window: FloatArray): Array<FloatArray>
}

data class StreamingConfig(
  /** New audio per model call; decisions are made this often. */
  val hopSeconds: Double = 1.0,
  /** Past audio fed to the model again for context. */
  val contextSeconds: Double = 3.0,
  /** Future audio the model sees before frames are used; adds to the decision delay. */
  val lookaheadSeconds: Double = 1.0,
)

/**
 * Feeds microphone audio through a [FrameModel] the way the model was evaluated: one hop of new
 * audio at a time, with [StreamingConfig.contextSeconds] of past audio and
 * [StreamingConfig.lookaheadSeconds] of future audio around it, keeping only the hop's frames.
 *
 * Mirrors `streaming_log_probs` in tools/recitation-spike/spike/acoustic.py.
 */
class StreamingFrames(private val model: FrameModel, config: StreamingConfig = StreamingConfig()) {
  // Whole model frames, so every window starts on a frame boundary and frames line up exactly.
  private val hop = frameAligned(config.hopSeconds).coerceAtLeast(SAMPLES_PER_FRAME.toInt())
  private val context = frameAligned(config.contextSeconds)
  private val lookahead = frameAligned(config.lookaheadSeconds)

  // Audio from absolute sample `bufferStart` onwards; older audio is no longer needed.
  private var buffer = FloatArray(SAMPLE_RATE * 8)
  private var bufferStart = 0L
  private var bufferLength = 0
  private var nextHop = 0L

  /** Audio received so far, in samples. */
  val receivedSamples: Long get() = bufferStart + bufferLength

  /** Audio whose frames have been produced, in samples. */
  val processedSamples: Long get() = nextHop

  /** Adds audio and returns the log-probability frames of every hop that is now complete. */
  fun accept(samples: FloatArray, count: Int = samples.size): List<FloatArray> {
    append(samples, count)
    val frames = ArrayList<FloatArray>()
    while (receivedSamples >= nextHop + hop + lookahead) frames += processHop()
    return frames
  }

  /** Processes the remaining audio, with whatever look-ahead is left, at the end of a session. */
  fun finish(): List<FloatArray> {
    val frames = ArrayList<FloatArray>()
    while (nextHop < receivedSamples) frames += processHop()
    return frames
  }

  private fun processHop(): List<FloatArray> {
    val start = nextHop
    val end = minOf(start + hop, receivedSamples)
    val windowStart = max(0L, start - context)
    val windowEnd = minOf(end + lookahead, receivedSamples)
    nextHop = start + hop
    val newFrames = ((end - start) / SAMPLES_PER_FRAME).roundToInt()
    if (newFrames == 0) return emptyList()

    val window = normalized(windowStart, windowEnd)
    val logits = model.logits(window)
    // Frames are picked by position: the model emits one frame fewer than the window length
    // suggests, and the missing frame is at the end.
    val first = ((start - windowStart) / SAMPLES_PER_FRAME).roundToInt()
    val frames = (first until minOf(first + newFrames, logits.size)).map { logSoftmax(logits[it]) }
    discardBefore(nextHop - context)
    return frames
  }

  private fun append(samples: FloatArray, count: Int) {
    if (bufferLength + count > buffer.size) buffer = buffer.copyOf(max(buffer.size * 2, bufferLength + count))
    System.arraycopy(samples, 0, buffer, bufferLength, count)
    bufferLength += count
  }

  private fun discardBefore(sample: Long) {
    val drop = (sample - bufferStart).coerceIn(0L, bufferLength.toLong()).toInt()
    if (drop == 0) return
    System.arraycopy(buffer, drop, buffer, 0, bufferLength - drop)
    bufferStart += drop
    bufferLength -= drop
  }

  /** The window, normalized to zero mean and unit variance like the model's feature extractor. */
  private fun normalized(from: Long, to: Long): FloatArray {
    val offset = (from - bufferStart).toInt()
    val window = buffer.copyOfRange(offset, offset + (to - from).toInt())
    var mean = 0.0
    for (x in window) mean += x
    mean /= window.size
    var variance = 0.0
    for (x in window) variance += (x - mean) * (x - mean)
    val std = sqrt(variance / window.size) + 1e-7
    for (i in window.indices) window[i] = ((window[i] - mean) / std).toFloat()
    return window
  }

  private fun logSoftmax(logits: FloatArray): FloatArray {
    var best = Float.NEGATIVE_INFINITY
    for (x in logits) best = max(best, x)
    var sum = 0.0
    for (x in logits) sum += exp((x - best).toDouble())
    val logSum = best + ln(sum)
    return FloatArray(logits.size) { (logits[it] - logSum).toFloat() }
  }

  private fun frameAligned(seconds: Double): Int =
    (seconds * SAMPLE_RATE / SAMPLES_PER_FRAME).roundToInt().coerceAtLeast(0) * SAMPLES_PER_FRAME.toInt()

  companion object {
    const val SAMPLE_RATE = 16000
    private const val SAMPLES_PER_FRAME = 320.0
  }
}

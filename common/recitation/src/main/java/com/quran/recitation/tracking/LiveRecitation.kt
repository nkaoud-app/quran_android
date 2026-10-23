package com.quran.recitation.tracking

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Follows a reader live: microphone -> recitation model -> tracker and mistake checker. Used by
 * the recitation tracking screen and by recitation on the mushaf pages.
 */
class LiveRecitation(
  private val words: List<String>,
  private val openingPhrases: List<List<String>>,
  private val settings: RecitationTrackingSettings,
  private val modelFile: File,
) {

  /** The tracker's state after the latest audio. */
  data class Update(
    /** Words recited and confirmed, from the first. */
    val completed: Int,
    /** Word the reader is on (may be before [completed] when going back). */
    val currentWord: Int,
    /** Words (re)checked since the previous update and their latest check. */
    val checked: List<Pair<Int, WordCheck>>,
    /** Model time per second of audio, in milliseconds. */
    val modelMsPerSecond: Double,
    /** Audio heard but not yet processed, in seconds. */
    val waitingSeconds: Double,
  )

  /**
   * Listens until cancelled. [onListening] is called once the microphone is on and [onUpdate]
   * after each processed hop of audio, both on the main thread.
   */
  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  suspend fun run(onListening: () -> Unit, onUpdate: (Update) -> Unit): Unit = withContext(Dispatchers.IO) {
    val tracker = RecitationTracker(words, settings.tracker, openingPhrases)
    val checker = WordChecker(tracker, words)
    OnnxFrameModel(modelFile, settings.threads).use { model ->
      val streaming = StreamingFrames(model, settings.streaming)
      val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
        SAMPLE_RATE * 2 * 4, // 4 s of audio, so a slow model call does not drop audio
      )
      val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, bufferSize,
      )
      try {
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        recorder.startRecording()
        withContext(Dispatchers.Main) { onListening() }
        val pcm = ShortArray(SAMPLE_RATE / 10)
        val samples = FloatArray(pcm.size)
        var frames = 0
        var modelNanos = 0L
        var modelAudio = 0L
        while (true) {
          ensureActive()
          val read = recorder.read(pcm, 0, pcm.size)
          if (read <= 0) continue
          for (i in 0 until read) samples[i] = pcm[i] / 32768f
          val processedBefore = streaming.processedSamples
          val started = System.nanoTime()
          val newFrames = streaming.accept(samples, read)
          if (newFrames.isEmpty()) continue
          modelNanos += System.nanoTime() - started
          modelAudio += streaming.processedSamples - processedBefore
          val checked = ArrayList<Pair<Int, WordCheck>>()
          for (frame in newFrames) {
            tracker.push(frame)
            if (++frames % 500 == 0) tracker.renormalize()
            for (w in checker.push(frame)) checked += w to requireNotNull(checker.check(w))
          }
          val update = Update(
            completed = tracker.completed,
            currentWord = tracker.currentWord,
            checked = checked,
            modelMsPerSecond = modelNanos / 1e6 / (modelAudio / SAMPLE_RATE.toDouble()),
            waitingSeconds = (streaming.receivedSamples - streaming.processedSamples) / SAMPLE_RATE.toDouble(),
          )
          withContext(Dispatchers.Main) { onUpdate(update) }
        }
      } finally {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        recorder.release()
      }
    }
  }

  private companion object {
    const val SAMPLE_RATE = StreamingFrames.SAMPLE_RATE
  }
}

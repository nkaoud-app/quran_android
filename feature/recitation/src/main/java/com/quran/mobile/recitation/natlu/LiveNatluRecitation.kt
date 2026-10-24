package com.quran.mobile.recitation.natlu

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
 * Follows a reader live: microphone -> phoneme recognizer -> [NatluTracker], in 480 ms chunks
 * as recite_quran's AudioProcessor feeds its engine.
 */
class LiveNatluRecitation(
  private val recognizerFactory: PhonemeRecognizerFactory,
  private val model: File,
  private val tokens: File,
  private val words: List<String>,
  private val startWord: Int,
  private val config: NatluConfig,
) {

  /** What happened in the latest chunk of audio. */
  data class Update(
    /** Words recited ([Event.missed] false) or skipped or said wrong, in order. */
    val events: List<Event>,
    /** The next word expected. */
    val cursor: Int,
  )

  /** [word] was recited or missed, or tracking [moved] to it (words from it on are uncommitted). */
  data class Event(val word: Int, val missed: Boolean = false, val moved: Boolean = false)

  /**
   * Listens until cancelled. [onListening] is called once the microphone is on, [onUpdate] after
   * each chunk that changed something, both on the main thread.
   */
  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  suspend fun run(onListening: () -> Unit, onUpdate: (Update) -> Unit): Unit = withContext(Dispatchers.IO) {
    val events = ArrayList<Event>()
    val tracker = NatluTracker(words, startWord, config, object : DictationSequencer.Listener {
      override fun onWord(word: Int, missed: Boolean) {
        events += Event(word, missed = missed)
      }

      override fun onCursorMoved(word: Int) {
        events += Event(word, moved = true)
      }
    })
    recognizerFactory.create(model, tokens, THREADS).use { recognizer ->
      val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
        SAMPLE_RATE * 2 * 4, // 4 s of audio, so a slow decode does not drop audio
      )
      val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, bufferSize,
      )
      try {
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        recorder.startRecording()
        withContext(Dispatchers.Main) { onListening() }
        val pcm = ShortArray(CHUNK_SAMPLES)
        val samples = FloatArray(CHUNK_SAMPLES)
        var cursor = startWord
        while (true) {
          ensureActive()
          // A whole chunk, as the recognizer processes fixed 480 ms strides.
          var filled = 0
          while (filled < CHUNK_SAMPLES) {
            ensureActive()
            val read = recorder.read(pcm, filled, CHUNK_SAMPLES - filled)
            if (read > 0) filled += read
          }
          for (i in 0 until CHUNK_SAMPLES) samples[i] = pcm[i] / 32768f
          recognizer.acceptWaveform(samples)
          if (tracker.onTokens(recognizer.tokens())) {
            recognizer.reset()
            tracker.onRecognizerReset()
          }
          if (events.isNotEmpty() || tracker.cursor != cursor) {
            cursor = tracker.cursor
            val update = Update(events.toList(), cursor)
            events.clear()
            withContext(Dispatchers.Main) { onUpdate(update) }
          }
        }
      } finally {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        recorder.release()
      }
    }
  }

  private companion object {
    const val SAMPLE_RATE = 16000
    const val CHUNK_SAMPLES = SAMPLE_RATE * 480 / 1000
    const val THREADS = 2
  }
}

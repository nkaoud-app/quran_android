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
 * Follows a reader live: microphone -> [PhonemeRecognizer] -> [PhonemeFollower], in chunks of
 * 480 ms (one step of the streaming model).
 */
class LivePhonemeRecitation(
  private val recognizerFactory: PhonemeRecognizerFactory,
  private val model: File,
  private val tokens: File,
  private val words: List<String>,
  /** The ayah of each word. */
  private val ayahs: List<Int>,
  private val startWord: Int,
  private val openings: List<List<String>>,
  private val config: FollowerConfig = FollowerConfig(),
) {

  /** What happened in the latest chunk of audio, and where the reader is now (-1 before they start). */
  data class Update(val events: List<PhonemeFollower.Event>, val currentWord: Int)

  /**
   * Listens until cancelled. [onListening] is called once the microphone is on, [onUpdate] after
   * each chunk that changed something, both on the main thread.
   */
  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  suspend fun run(onListening: () -> Unit, onUpdate: (Update) -> Unit): Unit = withContext(Dispatchers.IO) {
    val follower = PhonemeFollower(words, ayahs, startWord, openings, config)
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
        var shownWord = -1
        while (true) {
          var filled = 0
          while (filled < CHUNK_SAMPLES) {
            ensureActive()
            val read = recorder.read(pcm, filled, CHUNK_SAMPLES - filled)
            if (read > 0) filled += read
          }
          for (i in 0 until CHUNK_SAMPLES) samples[i] = pcm[i] / 32768f
          recognizer.acceptWaveform(samples)
          val tokens = recognizer.tokens()
          val events = follower.accept(tokens)
          if (tokens.size > MAX_TOKENS) {
            // A very long recitation: start the recognizer over (the follower keeps its place).
            recognizer.reset()
            follower.onRecognizerReset()
          }
          if (events.isNotEmpty() || follower.currentWord != shownWord) {
            shownWord = follower.currentWord
            val update = Update(events, shownWord)
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
    const val MAX_TOKENS = 6000
  }
}

package com.quran.labs.androidquran.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.quran.labs.androidquran.R
import com.quran.recitation.common.ExpectedWord
import com.quran.recitation.common.RecognizedWord
import com.quran.recitation.common.RecitationAlignmentEngine

/** First end-to-end test surface for recitation alignment. */
class RecitationPracticeActivity : AppCompatActivity(), RecognitionListener {
  private val alignmentEngine = RecitationAlignmentEngine()
  private val expectedText = "الحمد لله رب العالمين"
  private val expectedWords = expectedText.split(" ").mapIndexed { index, text ->
    ExpectedWord(index, text)
  }

  private var speechRecognizer: SpeechRecognizer? = null
  private lateinit var status: TextView
  private lateinit var transcript: TextView
  private lateinit var feedback: TextView
  private lateinit var actionButton: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = getString(R.string.recitation_practice)
    setContentView(createContent())

    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
      actionButton.isEnabled = false
      status.text = "Arabic speech recognition is not available on this device."
    }
  }

  private fun createContent(): ScrollView {
    val padding = (24 * resources.displayMetrics.density).toInt()
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, padding, padding, padding)
    }

    content.addView(TextView(this).apply {
      text = "Milestone 1 test"
      textSize = 22f
      gravity = Gravity.CENTER_HORIZONTAL
    })
    content.addView(TextView(this).apply {
      text = "Recite the displayed ayah. This prototype uses the device Arabic speech recognizer."
      textSize = 16f
      setPadding(0, padding / 2, 0, padding / 2)
    })
    content.addView(TextView(this).apply {
      text = expectedText
      textSize = 28f
      gravity = Gravity.CENTER
      textDirection = TextView.TEXT_DIRECTION_RTL
      setPadding(0, padding, 0, padding)
    })

    actionButton = Button(this).apply {
      text = "Start listening"
      setOnClickListener { toggleListening() }
    }
    content.addView(actionButton)
    status = TextView(this).apply { setPadding(0, padding, 0, padding / 2) }
    content.addView(status)
    transcript = TextView(this).apply { textSize = 18f }
    content.addView(transcript)
    feedback = TextView(this).apply { textSize = 18f; setPadding(0, padding, 0, 0) }
    content.addView(feedback)

    return ScrollView(this).apply { addView(content) }
  }

  private fun toggleListening() {
    if (speechRecognizer != null) {
      stopListening()
      return
    }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
      return
    }
    startListening()
  }

  private fun startListening() {
    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
      it.setRecognitionListener(this)
      it.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      })
    }
    actionButton.text = "Stop listening"
    status.text = "Listening…"
    feedback.text = ""
  }

  private fun stopListening() {
    speechRecognizer?.stopListening()
    speechRecognizer?.destroy()
    speechRecognizer = null
    actionButton.text = "Start listening"
    status.text = "Stopped"
  }

  private fun updateAlignment(text: String) {
    val recognized = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
      .map { RecognizedWord(it) }
    val result = alignmentEngine.align(expectedWords, recognized)
    transcript.text = "Recognized: $text"
    feedback.text = when {
      result.substitutions.isNotEmpty() -> "Possible word difference: " +
        result.substitutions.joinToString { "${it.expected.text} → ${it.recognized.text}" }
      result.missingWords.isNotEmpty() -> "Please repeat: " + result.missingWords.joinToString { it.text }
      result.unexpectedWords.isNotEmpty() -> "Unexpected/repeated word: " +
        result.unexpectedWords.joinToString { it.text }
      result.matchedWords.size == expectedWords.size -> "Ayah matched."
      else -> "Keep going…"
    }
  }

  override fun onPartialResults(results: Bundle?) {
    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::updateAlignment)
  }

  override fun onResults(results: Bundle?) {
    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::updateAlignment)
    stopListening()
  }

  override fun onError(error: Int) {
    status.text = "Recognition error ($error). Tap Start and try again."
    stopListening()
  }

  override fun onReadyForSpeech(params: Bundle?) {}
  override fun onBeginningOfSpeech() {}
  override fun onRmsChanged(rmsdB: Float) {}
  override fun onBufferReceived(buffer: ByteArray?) {}
  override fun onEndOfSpeech() { status.text = "Processing…" }
  override fun onEvent(eventType: Int, params: Bundle?) {}

  override fun onRequestPermissionsResult(
    requestCode: Int, permissions: Array<String>, grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQUEST_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
      startListening()
    }
  }

  override fun onDestroy() {
    stopListening()
    super.onDestroy()
  }

  companion object {
    private const val REQUEST_AUDIO = 1001
  }
}

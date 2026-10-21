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
import com.quran.data.core.QuranInfo
import com.quran.data.model.SuraAyah
import com.quran.labs.androidquran.QuranApplication
import com.quran.labs.androidquran.R
import com.quran.labs.androidquran.model.translation.ArabicDatabaseUtils
import com.quran.recitation.common.ExpectedWord
import com.quran.recitation.common.RecognizedWord
import com.quran.recitation.common.RecitationAlignmentEngine
import dev.zacsweers.metro.Inject
import io.reactivex.rxjava3.disposables.CompositeDisposable

/** First end-to-end test surface for recitation alignment. */
class RecitationPracticeActivity : AppCompatActivity(), RecognitionListener {
  private val alignmentEngine = RecitationAlignmentEngine()
  @Inject lateinit var arabicDatabaseUtils: ArabicDatabaseUtils
  @Inject lateinit var quranInfo: QuranInfo

  private var currentSura = 1
  private var currentAyah = 1
  private var expectedText = ""
  private var expectedWords = emptyList<ExpectedWord>()
  private var autoStartNextAyah = false
  private var matchAdvanceScheduled = false
  private val disposables = CompositeDisposable()

  private var speechRecognizer: SpeechRecognizer? = null
  private lateinit var status: TextView
  private lateinit var transcript: TextView
  private lateinit var feedback: TextView
  private lateinit var actionButton: Button
  private lateinit var reference: TextView
  private lateinit var location: TextView
  private lateinit var previousButton: Button
  private lateinit var nextButton: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    (application as QuranApplication).applicationComponent.inject(this)
    currentSura = intent.getIntExtra(EXTRA_SURA, 1).coerceIn(1, 114)
    currentAyah = intent.getIntExtra(EXTRA_AYAH, 1)
      .coerceIn(1, quranInfo.getNumberOfAyahs(currentSura))
    title = getString(R.string.recitation_practice)
    setContentView(createContent())
    loadAyah()

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
    reference = TextView(this).apply {
      text = expectedText
      textSize = 28f
      gravity = Gravity.CENTER
      textDirection = TextView.TEXT_DIRECTION_RTL
      setPadding(0, padding, 0, padding)
    }
    content.addView(reference)

    location = TextView(this).apply {
      gravity = Gravity.CENTER
      setPadding(0, 0, 0, padding / 2)
    }
    content.addView(location)

    val navigation = LinearLayout(this).apply {
      gravity = Gravity.CENTER
    }
    previousButton = Button(this).apply {
      text = "Previous ayah"
      setOnClickListener { moveAyah(-1) }
    }
    nextButton = Button(this).apply {
      text = "Next ayah"
      setOnClickListener { moveAyah(1) }
    }
    navigation.addView(previousButton)
    navigation.addView(nextButton)
    content.addView(navigation)

    actionButton = Button(this).apply {
      text = "Start listening"
      isEnabled = false
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

  private fun loadAyah() {
    status.text = "Loading ayah…"
    actionButton.isEnabled = false
    val suraAyah = SuraAyah(currentSura, currentAyah)
    disposables.add(arabicDatabaseUtils.getVerses(suraAyah, suraAyah).subscribe({ verses ->
      val verse = verses.firstOrNull()
      if (verse == null) {
        runOnUiThread {
          status.text = "Arabic Quran data is not available. Download it from Quran data settings."
          actionButton.isEnabled = false
        }
        return@subscribe
      }
      expectedText = ArabicDatabaseUtils.getAyahWithoutBasmallah(
        currentSura, currentAyah, verse.text
      ).trim()
      expectedWords = expectedText.split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .mapIndexed { index, text -> ExpectedWord(index, text) }
      runOnUiThread {
        reference.text = expectedText
        location.text = "Surah $currentSura, ayah $currentAyah"
        status.text = "Ready"
        actionButton.isEnabled = true
        feedback.text = ""
        transcript.text = ""
        previousButton.isEnabled = currentSura != 1 || currentAyah != 1
        nextButton.isEnabled = currentSura != 114 || currentAyah != quranInfo.getNumberOfAyahs(114)
        if (autoStartNextAyah) {
          autoStartNextAyah = false
          if (!isFinishing && speechRecognizer == null) startListening()
        }
      }
    }, {
      runOnUiThread {
        status.text = "Unable to load this ayah. Download the Arabic Quran data and try again."
        actionButton.isEnabled = false
      }
    }))
  }

  private fun moveAyah(delta: Int, startListeningWhenReady: Boolean = false) {
    if (speechRecognizer != null) stopListening()
    val next = when {
      delta < 0 && currentAyah > 1 -> SuraAyah(currentSura, currentAyah - 1)
      delta < 0 && currentSura > 1 -> SuraAyah(currentSura - 1, quranInfo.getNumberOfAyahs(currentSura - 1))
      delta > 0 && currentAyah < quranInfo.getNumberOfAyahs(currentSura) -> SuraAyah(currentSura, currentAyah + 1)
      delta > 0 && currentSura < 114 -> SuraAyah(currentSura + 1, 1)
      else -> return
    }
    currentSura = next.sura
    currentAyah = next.ayah
    autoStartNextAyah = startListeningWhenReady
    loadAyah()
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
    val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
    speechRecognizer = recognizer
    recognizer.setRecognitionListener(SessionRecognitionListener(recognizer))
    recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    })
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
    if (expectedWords.isEmpty()) return
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
      result.matchedWords.size == expectedWords.size -> {
        scheduleNextAyah()
        "Ayah matched. Moving to the next ayah…"
      }
      else -> "Keep going…"
    }
  }

  private fun scheduleNextAyah() {
    if (matchAdvanceScheduled) return
    matchAdvanceScheduled = true
    stopListening()
    matchAdvanceScheduled = false
    moveAyah(1, startListeningWhenReady = true)
  }

  override fun onPartialResults(results: Bundle?) {
    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::updateAlignment)
  }

  override fun onResults(results: Bundle?) {
    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::updateAlignment)
    stopListening()
  }

  override fun onError(error: Int) {
    stopListening()
    status.text = "Recognition error ($error). Tap Start and try again."
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
    disposables.clear()
    super.onDestroy()
  }

  private inner class SessionRecognitionListener(
    private val recognizer: SpeechRecognizer
  ) : RecognitionListener {
    private inline fun ifCurrent(block: () -> Unit) {
      if (speechRecognizer === recognizer) block()
    }

    override fun onReadyForSpeech(params: Bundle?) = ifCurrent { this@RecitationPracticeActivity.onReadyForSpeech(params) }
    override fun onBeginningOfSpeech() = ifCurrent { this@RecitationPracticeActivity.onBeginningOfSpeech() }
    override fun onRmsChanged(rmsdB: Float) = ifCurrent { this@RecitationPracticeActivity.onRmsChanged(rmsdB) }
    override fun onBufferReceived(buffer: ByteArray?) = ifCurrent { this@RecitationPracticeActivity.onBufferReceived(buffer) }
    override fun onEndOfSpeech() = ifCurrent { this@RecitationPracticeActivity.onEndOfSpeech() }
    override fun onError(error: Int) = ifCurrent { this@RecitationPracticeActivity.onError(error) }
    override fun onResults(results: Bundle?) = ifCurrent { this@RecitationPracticeActivity.onResults(results) }
    override fun onPartialResults(results: Bundle?) = ifCurrent { this@RecitationPracticeActivity.onPartialResults(results) }
    override fun onEvent(eventType: Int, params: Bundle?) = ifCurrent { this@RecitationPracticeActivity.onEvent(eventType, params) }
  }

  companion object {
    private const val REQUEST_AUDIO = 1001
    const val EXTRA_SURA = "extra_sura"
    const val EXTRA_AYAH = "extra_ayah"
  }
}

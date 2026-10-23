package com.quran.labs.androidquran.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
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
import com.quran.recitation.tracking.OnnxFrameModel
import com.quran.recitation.tracking.RecitationTracker
import com.quran.recitation.tracking.StreamingFrames
import dev.zacsweers.metro.Inject
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Milestone 2 recitation tracking: an on-device Quran recitation model follows the reader through
 * the sura word by word (see tools/recitation-spike), instead of Android's speech recognizer.
 */
class RecitationTrackingActivity : AppCompatActivity() {
  @Inject lateinit var arabicDatabaseUtils: ArabicDatabaseUtils
  @Inject lateinit var quranInfo: QuranInfo

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val disposables = CompositeDisposable()
  private var session: Job? = null
  private var download: Job? = null

  private var sura = 1
  private var startAyah = 1
  private var words = emptyList<TrackedWord>()

  private lateinit var location: TextView
  private lateinit var reference: TextView
  private lateinit var status: TextView
  private lateinit var stats: TextView
  private lateinit var actionButton: Button
  private lateinit var previousButton: Button
  private lateinit var nextButton: Button
  private lateinit var previousSuraButton: Button
  private lateinit var nextSuraButton: Button
  private lateinit var resetSettingsButton: Button
  private val settingFields = LinkedHashMap<RecitationTrackingSetting, EditText>()

  private data class TrackedWord(val ayah: Int, val text: String)

  private val modelFile: File get() = File(filesDir, "recitation/$MODEL_NAME")

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    (application as QuranApplication).applicationComponent.inject(this)
    // Opens where the reader last practised (al-Mulk the first time) unless asked for a place.
    val last = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
    sura = (savedInstanceState?.getInt(EXTRA_SURA)
      ?: intent.getIntExtra(EXTRA_SURA, last.getInt(EXTRA_SURA, DEFAULT_SURA))).coerceIn(1, 114)
    startAyah = (savedInstanceState?.getInt(EXTRA_AYAH)
      ?: intent.getIntExtra(EXTRA_AYAH, if (sura == last.getInt(EXTRA_SURA, DEFAULT_SURA)) last.getInt(EXTRA_AYAH, 1) else 1))
      .coerceIn(1, quranInfo.getNumberOfAyahs(sura))
    title = getString(R.string.recitation_tracking)
    setContentView(createContent())
    loadText()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt(EXTRA_SURA, sura)
    outState.putInt(EXTRA_AYAH, startAyah)
  }

  private fun createContent(): ScrollView {
    val padding = (24 * resources.displayMetrics.density).toInt()
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, padding, padding, padding)
    }
    content.addView(TextView(this).apply {
      text = "Milestone 2 test: on-device Quran recitation model"
      textSize = 18f
      gravity = Gravity.CENTER_HORIZONTAL
    })
    location = TextView(this).apply {
      gravity = Gravity.CENTER
      setPadding(0, padding / 2, 0, 0)
    }
    content.addView(location)
    val suraNavigation = LinearLayout(this).apply { gravity = Gravity.CENTER }
    previousSuraButton = Button(this).apply {
      text = "Previous surah"
      setOnClickListener { moveSura(-1) }
    }
    nextSuraButton = Button(this).apply {
      text = "Next surah"
      setOnClickListener { moveSura(1) }
    }
    suraNavigation.addView(previousSuraButton)
    suraNavigation.addView(nextSuraButton)
    content.addView(suraNavigation)
    val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER }
    previousButton = Button(this).apply {
      text = "Previous ayah"
      setOnClickListener { moveStart(-1) }
    }
    nextButton = Button(this).apply {
      text = "Next ayah"
      setOnClickListener { moveStart(1) }
    }
    navigation.addView(previousButton)
    navigation.addView(nextButton)
    content.addView(navigation)
    reference = TextView(this).apply {
      textSize = 28f
      gravity = Gravity.CENTER
      textDirection = TextView.TEXT_DIRECTION_RTL
      setPadding(0, padding, 0, padding)
    }
    content.addView(reference)
    actionButton = Button(this).apply {
      text = "Start"
      isEnabled = false
      setOnClickListener { onAction() }
    }
    content.addView(actionButton)
    status = TextView(this).apply { setPadding(0, padding / 2, 0, 0) }
    content.addView(status)
    stats = TextView(this).apply {
      textSize = 13f
      setPadding(0, padding / 2, 0, 0)
    }
    content.addView(stats)
    addSettings(content, padding)
    return ScrollView(this).apply { addView(content) }
  }

  /** Every tracking setting, editable for testing; applied when Start is tapped. */
  private fun addSettings(content: LinearLayout, padding: Int) {
    content.addView(TextView(this).apply {
      text = "Settings (applied when you tap Start)"
      textSize = 18f
      setPadding(0, padding, 0, 0)
    })
    val saved = RecitationTrackingSettings.load(getSharedPreferences(PREFERENCES, MODE_PRIVATE))
    RecitationTrackingSetting.entries.forEach { setting ->
      content.addView(TextView(this).apply {
        text = "${setting.label}  (default ${setting.format(setting.default)}, " +
          "${setting.format(setting.min)}–${setting.format(setting.max)})"
        textSize = 15f
        setPadding(0, padding / 2, 0, 0)
      })
      content.addView(TextView(this).apply {
        text = setting.explanation
        textSize = 12f
      })
      val field = EditText(this).apply {
        inputType = if (setting.wholeNumber) InputType.TYPE_CLASS_NUMBER
        else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(setting.format(saved[setting]))
      }
      settingFields[setting] = field
      content.addView(field)
    }
    resetSettingsButton = Button(this).apply {
      text = "Reset settings to defaults"
      setOnClickListener {
        settingFields.forEach { (setting, field) -> field.setText(setting.format(setting.default)) }
      }
    }
    content.addView(resetSettingsButton)
  }

  /** Reads the settings fields, keeping each value within its range, and remembers them. */
  private fun readSettings(): RecitationTrackingSettings {
    val settings = RecitationTrackingSettings(
      settingFields.mapValues { (setting, field) ->
        setting.clamp(field.text.toString().trim().toDoubleOrNull() ?: setting.default)
      }
    )
    settingFields.forEach { (setting, field) -> field.setText(setting.format(settings[setting])) }
    settings.save(getSharedPreferences(PREFERENCES, MODE_PRIVATE))
    return settings
  }

  /** Loads the sura from the start ayah to its end; the tracker follows the whole stretch. */
  private fun loadText() {
    actionButton.isEnabled = false
    status.text = "Loading…"
    location.text = "Surah $sura, starting at ayah $startAyah"
    updateNavigation()
    getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
      .putInt(EXTRA_SURA, sura).putInt(EXTRA_AYAH, startAyah).apply()
    disposables.clear()
    disposables.add(
      arabicDatabaseUtils.getVerses(SuraAyah(sura, startAyah), SuraAyah(sura, quranInfo.getNumberOfAyahs(sura)))
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe({ verses ->
          words = verses.flatMap { verse ->
            ArabicDatabaseUtils.getAyahWithoutBasmallah(verse.sura, verse.ayah, verse.text)
              .split(" ").filter(String::isNotBlank).map { TrackedWord(verse.ayah, it) }
          }
          if (words.isEmpty()) {
            status.text = "Arabic Quran data is not available. Download it from Quran data settings."
            return@subscribe
          }
          render(completed = 0, current = -1)
          refreshReadiness()
        }, {
          status.text = "Unable to load the ayahs. Download the Arabic Quran data and try again."
        })
    )
  }

  private fun moveSura(delta: Int) {
    if (session != null) return
    sura = (sura + delta).coerceIn(1, 114)
    startAyah = 1
    loadText()
  }

  /** Choosing where to start is only possible while not listening. */
  private fun updateNavigation() {
    val idle = session == null
    previousSuraButton.isEnabled = idle && sura > 1
    nextSuraButton.isEnabled = idle && sura < 114
    previousButton.isEnabled = idle && startAyah > 1
    nextButton.isEnabled = idle && startAyah < quranInfo.getNumberOfAyahs(sura)
    settingFields.values.forEach { it.isEnabled = idle }
    resetSettingsButton.isEnabled = idle
  }

  private fun moveStart(delta: Int) {
    if (session != null) return
    startAyah = (startAyah + delta).coerceIn(1, quranInfo.getNumberOfAyahs(sura))
    loadText()
  }

  private fun refreshReadiness() {
    actionButton.isEnabled = words.isNotEmpty() && download == null
    when {
      session != null -> actionButton.text = "Stop"
      modelFile.exists() -> {
        actionButton.text = "Start"
        status.text = "Ready. Tap Start and recite from ayah $startAyah."
      }
      else -> {
        actionButton.text = "Download model (93 MB)"
        status.text = "The recitation model is not downloaded yet."
      }
    }
  }

  private fun onAction() {
    when {
      session != null -> stop()
      !modelFile.exists() -> downloadModel()
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED ->
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
      else -> start()
    }
  }

  private fun downloadModel() {
    actionButton.isEnabled = false
    download = scope.launch {
      var failure: String? = null
      try {
        withContext(Dispatchers.IO) {
          val partial = File(modelFile.path + ".part")
          partial.parentFile?.mkdirs()
          OkHttpClient().newCall(Request.Builder().url(MODEL_URL).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = requireNotNull(response.body)
            val total = body.contentLength()
            body.byteStream().use { input ->
              partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastShown = -1L
                while (true) {
                  ensureActive()
                  val read = input.read(buffer)
                  if (read < 0) break
                  output.write(buffer, 0, read)
                  done += read
                  val percent = if (total > 0) done * 100 / total else -1
                  if (percent != lastShown) {
                    lastShown = percent
                    withContext(Dispatchers.Main) {
                      status.text = if (percent >= 0) "Downloading model… $percent%" else "Downloading model… ${done / 1_000_000} MB"
                    }
                  }
                }
              }
            }
          }
          check(partial.renameTo(modelFile)) { "Could not save the model" }
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        failure = "Model download failed (${e.message}). Check the connection and try again."
      } finally {
        download = null
        refreshReadiness()
        failure?.let { status.text = it }
      }
    }
  }

  private fun start() {
    val trackedWords = words
    val phrases = RecitationTracker.openingPhrases(sura, startAyah)
    val settings = readSettings()
    status.text = "Loading model…"
    session = scope.launch {
      val error = withContext(Dispatchers.IO) {
        runCatching { listen(trackedWords.map { it.text }, phrases, settings) }.exceptionOrNull()
      }
      if (error != null && error !is CancellationException) {
        stop()
        status.text = "Recitation tracking stopped: ${error.message}"
      }
    }
    updateNavigation()
    refreshReadiness()
  }

  /** Follows the reader until cancelled: microphone -> model -> tracker -> screen. */
  @SuppressLint("MissingPermission") // checked in onAction
  private suspend fun listen(
    trackedWords: List<String>,
    phrases: List<List<String>>,
    settings: RecitationTrackingSettings,
  ) = coroutineScope {
    val tracker = RecitationTracker(trackedWords, settings.tracker, phrases)
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
        withContext(Dispatchers.Main) { status.text = "Listening… recite from ayah $startAyah." }
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
          for (frame in newFrames) {
            tracker.push(frame)
            if (++frames % 500 == 0) tracker.renormalize()
          }
          val msPerSecond = modelNanos / 1e6 / (modelAudio / SAMPLE_RATE.toDouble())
          val waiting = (streaming.receivedSamples - streaming.processedSamples) / SAMPLE_RATE.toDouble()
          val completed = tracker.completed
          val current = tracker.currentWord
          withContext(Dispatchers.Main) {
            render(completed, current)
            stats.text = "Model: %.0f ms per second of audio (%.2fx real time). Audio waiting: %.1f s."
              .format(msPerSecond, msPerSecond / 1000, waiting)
          }
        }
      } finally {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        recorder.release()
      }
    }
  }

  private fun stop() {
    session?.cancel()
    session = null
    updateNavigation()
    refreshReadiness()
    status.text = "Stopped."
  }

  /**
   * Shows the ayah of the first word not yet recited, so the display moves on by itself once an
   * ayah is finished. Recited words are green; the word being recited is highlighted.
   */
  private fun render(completed: Int, current: Int) {
    if (words.isEmpty()) return
    if (completed >= words.size) {
      reference.text = "Surah complete."
      return
    }
    val ayah = words[completed].ayah
    val text = SpannableStringBuilder()
    words.forEachIndexed { index, word ->
      if (word.ayah != ayah) return@forEachIndexed
      if (text.isNotEmpty()) text.append(' ')
      val start = text.length
      text.append(word.text)
      if (index < completed) {
        text.setSpan(ForegroundColorSpan(RECITED), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (index == current) {
        text.setSpan(BackgroundColorSpan(CURRENT), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    }
    reference.text = text
    location.text = "Surah $sura, ayah $ayah"
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != REQUEST_AUDIO) return
    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
      start()
    } else {
      status.text = "Microphone permission is needed to follow your recitation."
    }
  }

  override fun onStop() {
    super.onStop()
    if (session != null) stop()
  }

  override fun onDestroy() {
    scope.cancel()
    disposables.clear()
    super.onDestroy()
  }

  companion object {
    const val EXTRA_SURA = "extra_sura"
    const val EXTRA_AYAH = "extra_ayah"
    private const val PREFERENCES = "recitation_tracking"
    private const val DEFAULT_SURA = 67
    private const val REQUEST_AUDIO = 1002
    private const val SAMPLE_RATE = StreamingFrames.SAMPLE_RATE
    private const val MODEL_NAME = "recitation-s8-int8.onnx"
    private const val MODEL_URL =
      "https://github.com/nkaoud-app/quran_android/releases/download/recitation-model-v1/$MODEL_NAME"
    private val RECITED = Color.rgb(0x2E, 0x7D, 0x32)
    private val CURRENT = Color.argb(0x66, 0xFF, 0xC1, 0x07)
  }
}

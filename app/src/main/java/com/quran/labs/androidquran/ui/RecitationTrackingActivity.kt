package com.quran.labs.androidquran.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
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
import com.quran.recitation.tracking.LiveRecitation
import com.quran.recitation.tracking.RecitationModel
import com.quran.recitation.tracking.RecitationTracker
import com.quran.recitation.tracking.RecitationTrackingSetting
import com.quran.recitation.tracking.RecitationTrackingSettings
import com.quran.recitation.tracking.SpokenLetter
import com.quran.recitation.tracking.WordCheck
import com.quran.recitation.tracking.WordChecker
import dev.zacsweers.metro.Inject
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
  private var wordChecks = arrayOfNulls<WordCheck>(0)
  private var unclearThreshold = RecitationTrackingSetting.UNCLEAR.default

  private lateinit var location: TextView
  private lateinit var reference: TextView
  private lateinit var previous: TextView
  private lateinit var previousSummary: TextView
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    (application as QuranApplication).applicationComponent.inject(this)
    // Opens where the reader last practised (al-Mulk the first time) unless asked for a place.
    val last = getSharedPreferences(RecitationTrackingSettings.PREFERENCES, MODE_PRIVATE)
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
    previous = TextView(this).apply {
      textSize = 20f
      gravity = Gravity.CENTER
      textDirection = TextView.TEXT_DIRECTION_RTL
      setPadding(0, padding / 2, 0, 0)
    }
    content.addView(previous)
    previousSummary = TextView(this).apply {
      textSize = 13f
      gravity = Gravity.CENTER
    }
    content.addView(previousSummary)
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
    val saved = RecitationTrackingSettings.load(getSharedPreferences(RecitationTrackingSettings.PREFERENCES, MODE_PRIVATE))
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
        inputType = InputType.TYPE_CLASS_NUMBER or
          (if (setting.wholeNumber) 0 else InputType.TYPE_NUMBER_FLAG_DECIMAL) or
          (if (setting.min < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
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
    settings.save(getSharedPreferences(RecitationTrackingSettings.PREFERENCES, MODE_PRIVATE))
    return settings
  }

  /** Loads the sura from the start ayah to its end; the tracker follows the whole stretch. */
  private fun loadText() {
    actionButton.isEnabled = false
    status.text = "Loading…"
    location.text = "Surah $sura, starting at ayah $startAyah"
    updateNavigation()
    getSharedPreferences(RecitationTrackingSettings.PREFERENCES, MODE_PRIVATE).edit()
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
          wordChecks = arrayOfNulls(words.size)
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
      RecitationModel.isDownloaded(this) -> {
        actionButton.text = "Start"
        status.text = "Ready. Tap Start and recite from ayah $startAyah."
      }
      else -> {
        actionButton.text = "Download model (${RecitationModel.DOWNLOAD_SIZE})"
        status.text = "The recitation model is not downloaded yet."
      }
    }
  }

  private fun onAction() {
    when {
      session != null -> stop()
      !RecitationModel.isDownloaded(this) -> downloadModel()
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
        RecitationModel.download(this@RecitationTrackingActivity) { percent, bytes ->
          status.text = if (percent >= 0) "Downloading model… $percent%" else "Downloading model… ${bytes / 1_000_000} MB"
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
    unclearThreshold = settings.unclearThreshold
    wordChecks = arrayOfNulls(words.size)
    // Keep the screen on while listening; cleared again in stop().
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    status.text = "Loading model…"
    session = scope.launch {
      val error = runCatching { listen(trackedWords.map { it.text }, phrases, settings) }.exceptionOrNull()
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
  ) {
    LiveRecitation(trackedWords, phrases, settings, RecitationModel.file(this)).run(
      onListening = { status.text = "Listening… recite from ayah $startAyah." },
      onUpdate = { update ->
        for ((w, check) in update.checked) if (w < wordChecks.size) wordChecks[w] = check
        render(update.completed, update.currentWord)
        stats.text = "Model: %.0f ms per second of audio (%.2fx real time). Audio waiting: %.1f s."
          .format(update.modelMsPerSecond, update.modelMsPerSecond / 1000, update.waitingSeconds)
      },
    )
  }

  private fun stop() {
    session?.cancel()
    session = null
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    updateNavigation()
    refreshReadiness()
    status.text = "Stopped."
  }

  /**
   * Shows the ayah of the first word not yet recited, so the display moves on by itself once an
   * ayah is finished, with the ayah before it underneath it to see how it went. Recited words are
   * green, or amber when the mistake check found them unclear; the word being recited is
   * highlighted.
   */
  private fun render(completed: Int, current: Int) {
    if (words.isEmpty()) return
    val ayah = words.getOrNull(completed)?.ayah
    reference.text = if (ayah == null) "Surah complete." else ayahText(ayah, completed, current)
    val previousAyah = (ayah ?: (words.last().ayah + 1)) - 1
    val previousWords = words.indices.filter { words[it].ayah == previousAyah }
    if (previousWords.isEmpty()) {
      previous.text = ""
      previousSummary.text = ""
    } else {
      previous.text = ayahText(previousAyah, completed, current = -1)
      previousSummary.text = summary(previousAyah, previousWords)
    }
    if (ayah != null) location.text = "Surah $sura, ayah $ayah"
  }

  private fun isUnclear(word: Int): Boolean = (wordChecks.getOrNull(word)?.deficit ?: 0.0) < unclearThreshold

  private fun ayahText(ayah: Int, completed: Int, current: Int): CharSequence {
    val text = SpannableStringBuilder()
    words.forEachIndexed { index, word ->
      if (word.ayah != ayah) return@forEachIndexed
      if (text.isNotEmpty()) text.append(' ')
      val start = text.length
      text.append(word.text)
      if (index < completed) {
        val unclear = isUnclear(index)
        text.setSpan(ForegroundColorSpan(if (unclear) UNCLEAR else RECITED), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (unclear) {
          // The letters that most likely went wrong, with their vowel marks.
          for (letter in weakLetters(index)) {
            text.setSpan(ForegroundColorSpan(WEAK_LETTER), start + letter.start, start + letter.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          }
        }
      }
      if (index == current) {
        text.setSpan(BackgroundColorSpan(CURRENT), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    }
    return text
  }

  private fun weakLetters(word: Int): List<SpokenLetter> {
    val check = wordChecks.getOrNull(word) ?: return emptyList()
    val letters = WordChecker.spokenLetters(words[word].text)
    return check.weakLetters.mapNotNull { letters.getOrNull(it) }
  }

  /** e.g. "Ayah 5: all 12 words clear" or "Ayah 5: 1 unclear word: وَجَعَلۡنَٰهَا (و، ن)". */
  private fun summary(ayah: Int, ayahWords: List<Int>): String {
    val unclear = ayahWords.filter(::isUnclear)
    if (unclear.isEmpty()) return "Ayah $ayah: all ${ayahWords.size} words clear"
    val details = unclear.joinToString("، ") { w ->
      val letters = weakLetters(w)
      if (letters.isEmpty()) words[w].text else "${words[w].text} (${letters.joinToString("، ") { it.letter.toString() }})"
    }
    return "Ayah $ayah: ${unclear.size} unclear word${if (unclear.size > 1) "s" else ""}: $details"
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
    private const val DEFAULT_SURA = 67
    private const val REQUEST_AUDIO = 1002
    private val RECITED = Color.rgb(0x2E, 0x7D, 0x32)
    private val CURRENT = Color.argb(0x66, 0xFF, 0xC1, 0x07)
    private val UNCLEAR = Color.rgb(0xE6, 0x51, 0x00)
    private val WEAK_LETTER = Color.rgb(0xD5, 0x00, 0x00)
  }
}

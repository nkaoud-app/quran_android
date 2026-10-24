package com.quran.mobile.recitation.presenter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import com.quran.data.core.QuranInfo
import com.quran.data.di.QuranScope
import com.quran.data.model.AyahWord
import com.quran.data.model.SuraAyah
import com.quran.mobile.recitation.natlu.LiveNatluRecitation
import com.quran.mobile.recitation.natlu.MatchingStrictness
import com.quran.mobile.recitation.natlu.NatluConfig
import com.quran.mobile.recitation.natlu.NatluModel
import com.quran.mobile.recitation.natlu.NatluPhonemes
import com.quran.mobile.recitation.natlu.PhonemeRecognizerFactory
import com.quran.recitation.common.Recitation
import com.quran.recitation.common.RecitationSession
import com.quran.recitation.common.RecitedAyah
import com.quran.recitation.events.RecitationEventPresenter
import com.quran.recitation.presenter.RecitationPresenter
import com.quran.recitation.tracking.RecitationText
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Recitation on the mushaf pages, following the reader the way Natlu does
 * (https://github.com/Recite-Quran/Natlu, see NATLU_NOTICE.md): its on-device phoneme model
 * recognizes what is recited and its word-by-word matcher follows the sura. The pages show how
 * far the reader got ([RecitationHighlightsPresenterImpl]) and turn when they move on.
 *
 * The microphone button starts from the selected ayah or the top of the page and stops again; the
 * session and its highlights stay until it is closed. Starting again on the page the reader
 * stopped on carries on from their ayah. A long press chooses how strict matching is.
 */
@QuranScope
class RecitationPresenterImpl @Inject constructor(
  private val quranInfo: QuranInfo,
  private val recitationText: RecitationText,
  private val eventPresenter: RecitationEventPresenter,
  private val state: MushafRecitationState,
  private val recognizerFactory: PhonemeRecognizerFactory,
) : RecitationPresenter {
  private val enabled = MutableStateFlow(true)
  private var activity: Activity? = null
  private var scope: CoroutineScope? = null
  private var listening: Job? = null
  private var preparing: Job? = null
  private var downloading: Job? = null
  private var pendingStart: SuraAyah? = null
  private var dialog: AlertDialog? = null

  private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
    // The microphone is only heard while the app is in the foreground.
    override fun onActivityStopped(activity: Activity) {
      if (activity === this@RecitationPresenterImpl.activity) stopListening()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
  }

  override fun bind(what: Activity) {
    activity = what
    scope = MainScope()
    what.application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
  }

  override fun unbind(what: Activity) {
    if (activity !== what) return
    stopListening()
    dialog?.dismiss()
    dialog = null
    what.application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
    scope?.cancel()
    scope = null
    downloading = null
    activity = null
  }

  override fun isRecitationEnabled(): Boolean = true
  override fun isRecitationEnabledFlow(): StateFlow<Boolean> = enabled

  override fun startOrStopRecitation(startAyah: () -> SuraAyah, showModeSelection: Boolean) {
    val activity = activity ?: return
    if (showModeSelection) {
      chooseStrictness(activity)
      return
    }
    if (listening != null) {
      stopListening()
      return
    }
    if (preparing != null || downloading != null) return
    val ayah = startAyah()
    when {
      !NatluModel.isDownloaded(activity) -> offerDownload(activity, ayah)
      activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
        pendingStart = ayah
        activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
      }
      else -> start(activity, ayah)
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
    if (requestCode != REQUEST_AUDIO) return
    val ayah = pendingStart ?: return
    pendingStart = null
    val activity = activity ?: return
    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
      start(activity, ayah)
    } else {
      toast(activity, "Microphone permission is needed to follow your recitation.")
    }
  }

  override fun endSession() {
    stopListening()
    state.clear()
    eventPresenter.onRecitationSessionChange(null)
  }

  private fun chooseStrictness(activity: Activity) {
    val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val current = strictness(activity)
    val names = arrayOf(
      "Easy (beginners, noisy rooms)",
      "Normal (everyday practice)",
      "Strict (advanced, memorization tests)",
    )
    dialog = AlertDialog.Builder(activity)
      .setTitle("Matching strictness")
      .setSingleChoiceItems(names, current.ordinal) { chosen, which ->
        preferences.edit().putString(KEY_STRICTNESS, MatchingStrictness.entries[which].name).apply()
        chosen.dismiss()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun strictness(context: Context): MatchingStrictness {
    val name = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_STRICTNESS, null)
    return MatchingStrictness.entries.firstOrNull { it.name == name } ?: MatchingStrictness.NORMAL
  }

  private fun offerDownload(activity: Activity, ayah: SuraAyah) {
    dialog = AlertDialog.Builder(activity)
      .setTitle("Recitation tracking")
      .setMessage(
        "Following your recitation needs Natlu's recitation model (${NatluModel.DOWNLOAD_SIZE}), " +
          "which runs on this device. Automatic feedback can be wrong and does not replace " +
          "reciting to a qualified teacher. Download it now?"
      )
      .setPositiveButton("Download") { _, _ -> download(activity, ayah) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun download(activity: Activity, ayah: SuraAyah) {
    val scope = scope ?: return
    val progress = AlertDialog.Builder(activity)
      .setTitle("Downloading the recitation model")
      .setMessage("Starting…")
      .setCancelable(false)
      .setNegativeButton(android.R.string.cancel) { _, _ ->
        downloading?.cancel()
        downloading = null
      }
      .show()
    dialog = progress
    downloading = scope.launch {
      val failure = try {
        NatluModel.download(activity) { percent, bytes ->
          progress.setMessage(if (percent >= 0) "$percent%" else "${bytes / 1_000_000} MB")
        }
        null
      } catch (e: Exception) {
        e
      }
      // Cancelled from the dialog (which closes itself) or because the screen is closing.
      if (failure is CancellationException) throw failure
      progress.dismiss()
      downloading = null
      if (failure == null) {
        startOrStopRecitation({ ayah })
      } else {
        toast(activity, "Model download failed (${failure.message}). Check the connection and try again.")
      }
    }
  }

  private fun start(activity: Activity, requested: SuraAyah) {
    val scope = scope ?: return
    preparing = scope.launch {
      val progress = state.progress.value?.takeIf {
        eventPresenter.hasRecitationSession() && it.sura == requested.sura
      } ?: newSession(activity, requested) ?: run {
        toast(activity, "Arabic Quran data is not available. Download it from Quran data settings.")
        preparing = null
        return@launch
      }
      // Carry on from the ayah the reader stopped on when they start again on that page;
      // otherwise start at the requested ayah. Highlights from there on start over.
      val current = progress.currentAyah
      val from = if (current != null && requested <= current &&
        quranInfo.getPageFromSuraAyah(current.sura, current.ayah) ==
        quranInfo.getPageFromSuraAyah(requested.sura, requested.ayah)
      ) current else requested
      val startWord = progress.indexOf(from).coerceAtLeast(0)
      state.update { p ->
        p.copy(
          start = startWord,
          cursor = startWord,
          recited = p.recited.filterTo(HashSet()) { it < startWord },
          missed = p.missed.filterTo(HashSet()) { it < startWord },
        )
      }
      preparing = null
      listen(activity, scope, progress.words.map { it.phonemes }, startWord, from)
    }
  }

  /** Starts a session on [start]'s sura; null when its text is not available. */
  private suspend fun newSession(context: Context, start: SuraAyah): MushafRecitationProgress? {
    val phonemes = NatluPhonemes.sura(context, start.sura)
    // Mushaf word positions count the ۞ and ۩ marks between words, which are not recited.
    val positions = recitationText.words(start.sura, 1)
      .filter { word -> word.text.any { it in 'ء'..'ي' || it == 'ٱ' } }
      .groupBy({ it.word.ayah.ayah }, { it.word.wordPosition })
    val words = phonemes.flatMapIndexed { index, ayahPhonemes ->
      val ayah = SuraAyah(start.sura, index + 1)
      val ayahPositions = positions[ayah.ayah].orEmpty()
      ayahPhonemes.mapIndexed { i, phoneme ->
        val position = if (ayahPositions.size == ayahPhonemes.size) ayahPositions[i] else i + 1
        MushafWord(AyahWord(ayah, position), phoneme)
      }
    }
    if (words.isEmpty() || positions.isEmpty()) return null
    val pages = words.map { quranInfo.getPageFromSuraAyah(it.word.ayah.sura, it.word.ayah.ayah) }
    val first = words.indexOfFirst { it.word.ayah == start }.coerceAtLeast(0)
    state.start(MushafRecitationProgress(words, pages, start = first, cursor = first))
    eventPresenter.onRecitationSessionChange(MushafSession(start, state))
    return state.progress.value
  }

  @SuppressLint("MissingPermission") // checked in startOrStopRecitation
  private fun listen(activity: Activity, scope: CoroutineScope, phonemes: List<String>, startWord: Int, from: SuraAyah) {
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    eventPresenter.onListeningStateChange(true)
    eventPresenter.onRecitationChange(from)
    var shownAyah = from
    listening = scope.launch {
      try {
        val recitation = LiveNatluRecitation(
          recognizerFactory, NatluModel.file(activity), NatluModel.tokens(activity),
          phonemes, startWord, NatluConfig(strictness = strictness(activity)),
        )
        recitation.run(
          onListening = { toast(activity, "Listening… recite from ayah ${from.ayah}.") },
          onUpdate = { update ->
            state.update { progress ->
              val recited = progress.recited.toHashSet()
              val missed = progress.missed.toHashSet()
              for (event in update.events) {
                when {
                  event.moved -> {
                    recited.removeAll { it >= event.word }
                    missed.removeAll { it >= event.word }
                  }
                  event.missed -> {
                    missed += event.word
                    recited -= event.word
                  }
                  else -> {
                    recited += event.word
                    missed -= event.word
                  }
                }
              }
              progress.copy(cursor = update.cursor, recited = recited, missed = missed)
            }
            val progress = state.progress.value ?: return@run
            // Turn the page once the reader moves on to an ayah on the next one.
            val ayah = progress.currentAyah
            if (ayah != null && ayah != shownAyah) {
              shownAyah = ayah
              eventPresenter.onRecitationChange(ayah)
            }
            if (progress.cursor >= progress.words.size) {
              toast(activity, "Surah complete.")
              stopListening()
            }
          },
        )
      } catch (e: Exception) {
        if (e !is CancellationException) {
          toast(activity, "Recitation tracking stopped: ${e.message}")
          stopListening()
        }
      }
    }
  }

  private fun stopListening() {
    preparing?.cancel()
    preparing = null
    listening?.cancel()
    listening = null
    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    eventPresenter.onListeningStateChange(false)
  }

  private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }

  private class MushafSession(
    private val start: SuraAyah,
    private val state: MushafRecitationState,
  ) : RecitationSession {
    private val sessionId = UUID.randomUUID().toString()
    private val startedAt = System.currentTimeMillis()

    override fun id(): String = sessionId
    override fun startAyah(): SuraAyah = start
    override fun currentAyah(): SuraAyah = state.progress.value?.currentAyah ?: start
    override fun startedAt(): Long = startedAt
    override fun recitation(): Recitation = object : Recitation {
      override fun recitedAyahs(): List<RecitedAyah> = emptyList()
    }
  }

  private companion object {
    const val REQUEST_AUDIO = 4201
    const val PREFERENCES = "natlu_recitation"
    const val KEY_STRICTNESS = "strictness"
  }
}

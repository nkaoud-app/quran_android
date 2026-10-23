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
import com.quran.data.model.SuraAyah
import com.quran.recitation.common.Recitation
import com.quran.recitation.common.RecitationSession
import com.quran.recitation.common.RecitedAyah
import com.quran.recitation.events.RecitationEventPresenter
import com.quran.recitation.presenter.RecitationPresenter
import com.quran.recitation.tracking.LiveRecitation
import com.quran.recitation.tracking.RecitationModel
import com.quran.recitation.tracking.RecitationText
import com.quran.recitation.tracking.RecitationTracker
import com.quran.recitation.tracking.RecitationTrackingSettings
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
 * Recitation on the mushaf pages: the on-device recitation model follows the reader word by word
 * (see common/recitation's tracking package) and the pages show how far they got
 * ([RecitationHighlightsPresenterImpl]), turning when the reader moves on to the next page.
 *
 * The microphone button starts listening from the selected ayah or the top of the page, and stops
 * it again; the session (and its highlights) stays until it is closed. Starting again on the page
 * the reader stopped on carries on from the ayah they were on. The tracking settings are the ones
 * tuned on the recitation tracking screen.
 */
@QuranScope
class RecitationPresenterImpl @Inject constructor(
  private val quranInfo: QuranInfo,
  private val recitationText: RecitationText,
  private val eventPresenter: RecitationEventPresenter,
  private val state: MushafRecitationState,
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
    if (listening != null) {
      stopListening()
      return
    }
    if (preparing != null || downloading != null) return
    val ayah = startAyah()
    when {
      !RecitationModel.isDownloaded(activity) -> offerDownload(activity, ayah)
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

  private fun offerDownload(activity: Activity, ayah: SuraAyah) {
    dialog = AlertDialog.Builder(activity)
      .setTitle("Recitation tracking")
      .setMessage(
        "Following your recitation needs the recitation model (${RecitationModel.DOWNLOAD_SIZE}), " +
          "which runs on this device. Download it now?"
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
        RecitationModel.download(activity) { percent, bytes ->
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
      val progress = resumableProgress(requested) ?: newSession(requested) ?: run {
        toast(activity, "Arabic Quran data is not available. Download it from Quran data settings.")
        preparing = null
        return@launch
      }
      // Carry on from the ayah the reader stopped on when they start again on that page.
      val current = progress.currentAyah
      val from = if (current != null && requested <= current &&
        quranInfo.getPageFromSuraAyah(current.sura, current.ayah) == quranInfo.getPageFromSuraAyah(requested.sura, requested.ayah)
      ) current else requested
      val offset = progress.indexOf(from)
      state.update { it.copy(completed = offset, currentWord = -1, unclear = it.unclear.filterTo(HashSet()) { w -> w < offset }) }
      preparing = null
      listen(activity, scope, progress.words.drop(offset).map { it.text }, offset, from)
    }
  }

  /** The current session's progress when [requested] is within its words. */
  private fun resumableProgress(requested: SuraAyah): MushafRecitationProgress? {
    val progress = state.progress.value ?: return null
    return progress.takeIf { eventPresenter.hasRecitationSession() && it.indexOf(requested) >= 0 }
  }

  private suspend fun newSession(start: SuraAyah): MushafRecitationProgress? {
    val words = recitationText.words(start.sura, start.ayah)
    if (words.isEmpty()) return null
    val pages = words.map { quranInfo.getPageFromSuraAyah(it.word.ayah.sura, it.word.ayah.ayah) }
    state.start(MushafRecitationProgress(words, pages))
    eventPresenter.onRecitationSessionChange(MushafSession(start, state))
    return state.progress.value
  }

  @SuppressLint("MissingPermission") // checked in startOrStopRecitation
  private fun listen(activity: Activity, scope: CoroutineScope, words: List<String>, offset: Int, from: SuraAyah) {
    val settings = RecitationTrackingSettings.load(
      activity.getSharedPreferences(RecitationTrackingSettings.PREFERENCES, Context.MODE_PRIVATE)
    )
    val recitation = LiveRecitation(
      words, RecitationTracker.openingPhrases(from.sura, from.ayah), settings, RecitationModel.file(activity),
    )
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    eventPresenter.onListeningStateChange(true)
    eventPresenter.onRecitationChange(from)
    var shownAyah = from
    listening = scope.launch {
      try {
        recitation.run(
          onListening = { toast(activity, "Listening… recite from ayah ${from.ayah}.") },
          onUpdate = { update ->
            state.update { progress ->
              val unclear = progress.unclear.toHashSet()
              for ((w, check) in update.checked) {
                if (check.deficit < settings.unclearThreshold) unclear += offset + w else unclear -= offset + w
              }
              progress.copy(
                completed = offset + update.completed,
                currentWord = offset + update.currentWord,
                unclear = unclear,
              )
            }
            // Turn the page once the reader moves on to an ayah on the next one.
            val ayah = state.progress.value?.currentAyah
            if (ayah != null && ayah != shownAyah) {
              shownAyah = ayah
              eventPresenter.onRecitationChange(ayah)
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
  }
}

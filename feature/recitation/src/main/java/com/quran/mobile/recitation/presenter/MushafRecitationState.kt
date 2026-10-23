package com.quran.mobile.recitation.presenter

import com.quran.data.di.AppScope
import com.quran.data.model.SuraAyah
import com.quran.recitation.tracking.RecitationWord
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How far the reader got in the words of a recitation session on the mushaf. */
data class MushafRecitationProgress(
  /** The session's words, from where it started to the end of the sura. */
  val words: List<RecitationWord>,
  /** Mushaf page of each word. */
  val pages: List<Int>,
  /** Words recited and confirmed, from the first. */
  val completed: Int = 0,
  /** Word the reader is on, -1 before they start (may be before [completed] when going back). */
  val currentWord: Int = -1,
  /** Recited words the mistake check found unclear. */
  val unclear: Set<Int> = emptySet(),
  /** Whether words not yet recited are hidden (reciting from memory). */
  val hideUnrecited: Boolean = false,
) {
  /** The ayah of the first word not yet recited (the last ayah once all are recited). */
  val currentAyah: SuraAyah? get() = (words.getOrNull(completed) ?: words.lastOrNull())?.word?.ayah

  fun indexOf(ayah: SuraAyah): Int = words.indexOfFirst { it.word.ayah == ayah }
}

/** The progress of the current recitation session, shared by the reading screen and its pages. */
@SingleIn(AppScope::class)
class MushafRecitationState @Inject constructor() {
  private val _progress = MutableStateFlow<MushafRecitationProgress?>(null)
  val progress: StateFlow<MushafRecitationProgress?> = _progress.asStateFlow()

  /** Keeps whether unrecited words are hidden from one session to the next. */
  private var hideUnrecited = false

  fun start(progress: MushafRecitationProgress) {
    _progress.value = progress.copy(hideUnrecited = hideUnrecited)
  }

  fun update(transform: (MushafRecitationProgress) -> MushafRecitationProgress) {
    _progress.value = _progress.value?.let(transform)
  }

  fun toggleHideUnrecited() {
    hideUnrecited = !hideUnrecited
    update { it.copy(hideUnrecited = hideUnrecited) }
  }

  fun clear() {
    _progress.value = null
  }
}

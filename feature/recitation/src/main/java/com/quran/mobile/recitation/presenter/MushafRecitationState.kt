package com.quran.mobile.recitation.presenter

import com.quran.data.di.AppScope
import com.quran.data.model.AyahWord
import com.quran.data.model.SuraAyah
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How far the reader got in the words of a sura recited on the mushaf. */
data class MushafRecitationProgress(
  /** The sura's words: where each is on the mushaf, and its phonemes. */
  val words: List<MushafWord>,
  /** Mushaf page of each word. */
  val pages: List<Int>,
  /** Where this recitation started; words before it are left as they are. */
  val start: Int,
  /** The next word expected. */
  val cursor: Int,
  /** Words recited. */
  val recited: Set<Int> = emptySet(),
  /** Words skipped or said wrong (a later word matched first). */
  val missed: Set<Int> = emptySet(),
  /** Whether words not yet recited are hidden (reciting from memory). */
  val hideUnrecited: Boolean = false,
) {
  /** The ayah of the next word expected (the last ayah once all are recited). */
  val currentAyah: SuraAyah? get() = (words.getOrNull(cursor) ?: words.lastOrNull())?.word?.ayah

  val sura: Int get() = words.first().word.ayah.sura

  fun indexOf(ayah: SuraAyah): Int = words.indexOfFirst { it.word.ayah == ayah }
}

/** A word of the sura: where it is on the mushaf and how it sounds. */
data class MushafWord(val word: AyahWord, val phonemes: String)

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

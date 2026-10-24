package com.quran.mobile.recitation.presenter

import com.quran.data.di.QuranPageScope
import com.quran.data.model.SuraAyah
import com.quran.data.model.highlight.HighlightInfo
import com.quran.data.model.highlight.HighlightType
import com.quran.data.model.highlight.HighlightType.Mode
import com.quran.mobile.recitation.R
import com.quran.recitation.presenter.RecitationHighlightsPresenter
import com.quran.recitation.presenter.RecitationHighlightsPresenter.HighlightAction
import com.quran.recitation.presenter.RecitationHighlightsPresenter.RecitationPage
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Shows the recitation on a mushaf page: recited words turn green, words skipped or said wrong
 * red, the next word expected is highlighted, and words not yet recited can be hidden (keeping
 * the ayah numbers).
 *
 * Word highlights need the page's word coordinates (see AyahInfoDatabaseHandler).
 */
@QuranPageScope
class RecitationHighlightsPresenterImpl @Inject constructor(
  private val state: MushafRecitationState,
) : RecitationHighlightsPresenter {
  private var page: RecitationPage? = null
  private var scope: CoroutineScope? = null
  private var shown: List<HighlightInfo> = emptyList()

  override fun bind(what: RecitationPage) {
    page = what
    shown = emptyList()
    scope = MainScope().also { scope ->
      state.progress.onEach { show(it, force = false) }.launchIn(scope)
    }
  }

  override fun unbind(what: RecitationPage) {
    scope?.cancel()
    scope = null
    page = null
  }

  /** Called once the page's coordinates are loaded, so highlights can be drawn. */
  override fun refresh() {
    show(state.progress.value, force = true)
  }

  private fun show(progress: MushafRecitationProgress?, force: Boolean) {
    val page = page ?: return
    val highlights = progress?.let { highlightsFor(it, page.pageNumbers) }.orEmpty()
    // Most updates change nothing on a given page (the reader is on another one).
    if (!force && highlights.sameAs(shown)) return
    shown = highlights
    page.applyHighlights(
      buildList<HighlightAction> {
        TYPES.forEach { add(HighlightAction.UnhighlightAll(it)) }
        highlights.forEach { add(HighlightAction.Highlight(it)) }
      }
    )
  }

  private fun highlightsFor(progress: MushafRecitationProgress, pages: Set<Int>): List<HighlightInfo> {
    val result = ArrayList<HighlightInfo>()
    progress.words.forEachIndexed { index, word ->
      if (progress.pages[index] !in pages) return@forEachIndexed
      val ayah = word.word.ayah
      val position = word.word.wordPosition
      when {
        index in progress.recited -> result += highlight(ayah, position, RECITED)
        index in progress.missed -> result += highlight(ayah, position, MISSED)
        progress.hideUnrecited && index >= progress.start -> result += highlight(ayah, position, HIDDEN)
        index == progress.cursor -> result += highlight(ayah, position, NEXT_WORD)
      }
    }
    return result
  }

  private fun highlight(ayah: SuraAyah, word: Int, type: HighlightType) =
    HighlightInfo(ayah.sura, ayah.ayah, word, type, scrollToAyah = false)

  private fun List<HighlightInfo>.sameAs(other: List<HighlightInfo>): Boolean =
    size == other.size && indices.all { i ->
      val a = this[i]
      val b = other[i]
      a.sura == b.sura && a.ayah == b.ayah && a.word == b.word && a.highlightType == b.highlightType
    }

  companion object {
    // Ids after the app's own highlight types (selection, audio, bookmarks, colors).
    val RECITED = HighlightType(41, R.color.recitation_recited, Mode.COLOR)
    val MISSED = HighlightType(42, R.color.recitation_missed, Mode.COLOR)
    val HIDDEN = HighlightType(43, R.color.recitation_hidden, Mode.HIDE)
    val NEXT_WORD = HighlightType(44, R.color.recitation_next_word, Mode.HIGHLIGHT)

    private val TYPES = listOf(RECITED, MISSED, HIDDEN, NEXT_WORD)
  }
}

package com.quran.labs.androidquran.presenter.quran.ayahtracker

import android.graphics.RectF
import com.quran.data.core.QuranInfo
import com.quran.data.model.AyahGlyph
import com.quran.data.model.AyahWord
import com.quran.data.model.highlight.HighlightType
import com.quran.data.model.selection.SelectionIndicator
import com.quran.data.model.selection.withYScroll
import com.quran.labs.androidquran.data.QuranDisplayData
import com.quran.labs.androidquran.ui.util.ImageAyahUtils
import com.quran.labs.androidquran.view.HighlightingImageView
import com.quran.labs.androidquran.view.QuranPageLayout
import com.quran.page.common.draw.ImageDrawHelper

class AyahScrollableImageTrackerItem(
  page: Int,
  private val screenHeight: Int,
  quranInfo: QuranInfo,
  quranDisplayData: QuranDisplayData,
  private val quranPageLayout: QuranPageLayout,
  imageDrawHelpers: Set<ImageDrawHelper>,
  highlightingImageView: HighlightingImageView
) : AyahImageTrackerItem(
  page = page,
  quranInfo = quranInfo,
  quranDisplayData = quranDisplayData,
  imageDrawHelpers = imageDrawHelpers,
  ayahView = highlightingImageView
) {

  override fun onHighlightAyah(
    page: Int,
    sura: Int,
    ayah: Int,
    word: Int,
    type: HighlightType,
    scrollToAyah: Boolean
  ): Boolean {
    val coordinates = coordinates
    if (this.page == page && scrollToAyah && word >= 0) {
      scrollToWord(sura, ayah, word)
    } else if (this.page == page && scrollToAyah && coordinates != null) {
      val highlightBounds = ImageAyahUtils.getYBoundsForHighlight(coordinates, sura, ayah)
      if (highlightBounds != null) {
        val matrix = ayahView.imageMatrix
        matrix.mapRect(highlightBounds)
        val currentScrollY = quranPageLayout.currentScrollY
        val topOnScreen = highlightBounds.top > currentScrollY &&
            highlightBounds.top < currentScrollY + screenHeight
        val bottomOnScreen = highlightBounds.bottom > currentScrollY &&
            highlightBounds.bottom < currentScrollY + screenHeight
        val encompassesScreen = highlightBounds.top < currentScrollY &&
            highlightBounds.bottom > currentScrollY + screenHeight
        val canEntireAyahBeVisible = highlightBounds.height() < screenHeight

        // scroll when:
        // 1. the entire ayah fits on the screen, but the top or bottom aren't on the screen
        // 2. the entire ayah won't fit on the screen and neither the top is on the screen,
        //    nor is the bottom on the screen, nor is the current ayah greater than the visible
        //    viewport of the ayah (i.e. you're not in the middle of the ayah right now).
        val scroll = canEntireAyahBeVisible && (!topOnScreen || !bottomOnScreen) ||
            !canEntireAyahBeVisible && !topOnScreen && !bottomOnScreen && !encompassesScreen
        if (scroll) {
          val y = highlightBounds.top.toInt() - (0.05 * screenHeight).toInt()
          quranPageLayout.smoothScrollLayoutTo(y)
        }
      }
    }
    return super.onHighlightAyah(page, sura, ayah, word, type, scrollToAyah)
  }

  /**
   * Keeps a word followed word by word (recitation) on the screen: once it is above the screen or
   * low on it, scrolls its line near the top, so the lines after it can be seen.
   */
  private fun scrollToWord(sura: Int, ayah: Int, word: Int) {
    val wordBounds = wordCoordinates["$sura:$ayah:$word"]?.takeIf { it.isNotEmpty() } ?: return
    val bounds = RectF(wordBounds.first().bounds)
    wordBounds.forEach { bounds.union(it.bounds) }
    ayahView.imageMatrix.mapRect(bounds)
    val currentScrollY = quranPageLayout.currentScrollY
    if (bounds.top < currentScrollY || bounds.bottom > currentScrollY + 0.75 * screenHeight) {
      quranPageLayout.smoothScrollLayoutTo(bounds.top.toInt() - (0.15 * screenHeight).toInt())
    }
  }

  override fun getToolBarPosition(page: Int, sura: Int, ayah: Int): SelectionIndicator {
    val position = super.getToolBarPosition(page, sura, ayah)
    return position.withYScroll(-quranPageLayout.currentScrollY.toFloat())
  }

  override fun getToolBarPosition(page: Int, word: AyahWord): SelectionIndicator {
    val position = super.getToolBarPosition(page, word)
    return position.withYScroll(-quranPageLayout.currentScrollY.toFloat())
  }

  override fun getToolBarPosition(page: Int, glyph: AyahGlyph): SelectionIndicator {
    val position = super.getToolBarPosition(page, glyph)
    return position.withYScroll(-quranPageLayout.currentScrollY.toFloat())
  }
}

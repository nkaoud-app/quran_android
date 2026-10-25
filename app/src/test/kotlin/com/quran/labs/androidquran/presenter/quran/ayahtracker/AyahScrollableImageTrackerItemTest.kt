package com.quran.labs.androidquran.presenter.quran.ayahtracker

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.quran.data.core.QuranInfo
import com.quran.data.model.highlight.HighlightType
import com.quran.labs.androidquran.data.QuranDisplayData
import com.quran.labs.androidquran.pages.data.madani.MadaniDataSource
import com.quran.labs.androidquran.view.QuranImagePageLayout
import com.quran.page.common.data.AyahBounds
import com.quran.page.common.data.AyahCoordinates
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A landscape page (taller than the screen, so it scrolls) following a word recited on it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h400dp-land-mdpi")
class AyahScrollableImageTrackerItemTest {

  private class Layout : QuranImagePageLayout(ApplicationProvider.getApplicationContext()) {
    val scrolledTo = ArrayList<Int>()

    override fun smoothScrollLayoutTo(y: Int) {
      scrolledTo += y
      super.smoothScrollLayoutTo(y)
    }
  }

  private lateinit var layout: Layout
  private lateinit var item: AyahScrollableImageTrackerItem
  private val quranInfo = QuranInfo(MadaniDataSource())

  @Before
  fun setUp() {
    layout = Layout()
    assertThat(layout.canScroll()).isTrue()
    val imageView = layout.getImageView()
    // a page image twice as tall as wide, shown 800 px wide on a 400 px high screen
    imageView.setImageDrawable(
      BitmapDrawable(imageView.resources, Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888))
    )
    layout.measure(
      MeasureSpec.makeMeasureSpec(800, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(SCREEN_HEIGHT, MeasureSpec.EXACTLY),
    )
    layout.layout(0, 0, 800, SCREEN_HEIGHT)
    item = AyahScrollableImageTrackerItem(
      PAGE, SCREEN_HEIGHT, quranInfo, QuranDisplayData(quranInfo), layout, emptySet(), imageView
    )
    item.onSetAyahCoordinates(
      AyahCoordinates(
        PAGE,
        mapOf("2:6" to listOf(AyahBounds(1, 1, RectF(100f, 100f, 900f, 1900f)))),
        null,
        wordCoordinates = mapOf(
          "2:6:1" to listOf(AyahBounds(1, 1, RectF(700f, 100f, 900f, 200f))),
          "2:6:2" to listOf(AyahBounds(8, 2, RectF(700f, 1500f, 900f, 1600f))),
        ),
      )
    )
  }

  @Test
  fun `scrolls to a word followed low on the page`() {
    item.onHighlightAyah(PAGE, 2, 6, 2, TYPE, true)
    assertThat(layout.scrolledTo).hasSize(1)
    // the word's line (image 1500..1600, shown at 0.8 scale) ends up near the top of the screen
    assertThat(layout.scrolledTo.single()).isIn(1100..1200)
  }

  @Test
  fun `does not scroll for a word already on the screen`() {
    item.onHighlightAyah(PAGE, 2, 6, 1, TYPE, true)
    assertThat(layout.scrolledTo).isEmpty()
  }

  private companion object {
    const val PAGE = 3
    const val SCREEN_HEIGHT = 400
    val TYPE = HighlightType(44, android.R.color.transparent, HighlightType.Mode.HIGHLIGHT)
  }
}

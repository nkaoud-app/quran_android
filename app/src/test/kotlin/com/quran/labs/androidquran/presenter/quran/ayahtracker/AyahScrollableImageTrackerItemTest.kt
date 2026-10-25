package com.quran.labs.androidquran.presenter.quran.ayahtracker

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.quran.data.core.QuranInfo
import com.quran.data.model.highlight.HighlightType
import com.quran.labs.androidquran.base.TestApplication
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
@Config(application = TestApplication::class, sdk = [33], qualifiers = "w800dp-h400dp-land-mdpi")
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
    // the screen's height as when the app started in portrait, before the screen was turned
    item = AyahScrollableImageTrackerItem(
      PAGE, PORTRAIT_SCREEN_HEIGHT, quranInfo, QuranDisplayData(quranInfo), layout, emptySet(), imageView
    )
    item.onSetAyahCoordinates(
      AyahCoordinates(
        PAGE,
        mapOf("2:6" to listOf(AyahBounds(1, 1, RectF(100f, 100f, 900f, 1900f)))),
        null,
        wordCoordinates = mapOf(
          "2:6:1" to listOf(AyahBounds(1, 1, RectF(700f, 100f, 900f, 200f))),
          "2:6:2" to listOf(AyahBounds(8, 2, RectF(700f, 1500f, 900f, 1600f))),
          "2:6:3" to listOf(AyahBounds(4, 3, RectF(700f, 600f, 900f, 700f))),
        ),
      )
    )
  }

  @Test
  fun `scrolls to a word followed low on the page`() {
    item.onHighlightAyah(PAGE, 2, 6, 2, TYPE, true)
    // the word (image 1500..1600, shown at 0.769 scale below 20 px of padding, so 1173..1250 on
    // the page) ends up near the top of the 400 px the page shows
    assertThat(layout.scrolledTo).containsExactly(1113)
  }

  @Test
  fun `scrolls to a word just below what the page shows`() {
    // 481..558 on the page: below its 400 px, though within the portrait screen's height
    item.onHighlightAyah(PAGE, 2, 6, 3, TYPE, true)
    assertThat(layout.scrolledTo).containsExactly(421)
  }

  @Test
  fun `does not scroll for a word already on the screen`() {
    item.onHighlightAyah(PAGE, 2, 6, 1, TYPE, true)
    assertThat(layout.scrolledTo).isEmpty()
  }

  private companion object {
    const val PAGE = 3
    const val SCREEN_HEIGHT = 400
    const val PORTRAIT_SCREEN_HEIGHT = 1600
    val TYPE = HighlightType(44, android.R.color.transparent, HighlightType.Mode.HIGHLIGHT)
  }
}

package com.quran.labs.androidquran.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.TypedValue
import android.util.TypedValue.COMPLEX_UNIT_DIP
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.quran.data.model.highlight.HighlightType
import com.quran.data.model.highlight.HighlightType.Mode.BACKGROUND
import com.quran.data.model.highlight.HighlightType.Mode.COLOR
import com.quran.data.model.highlight.HighlightType.Mode.HIDE
import com.quran.data.model.highlight.HighlightType.Mode.HIGHLIGHT
import com.quran.data.model.highlight.HighlightType.Mode.UNDERLAY
import com.quran.data.model.highlight.HighlightType.Mode.UNDERLINE
import com.quran.labs.androidquran.ui.helpers.AyahHighlight
import com.quran.labs.androidquran.ui.helpers.AyahHighlight.TransitionAyahHighlight
import com.quran.page.common.data.AyahBounds
import com.quran.page.common.data.AyahCoordinates
import com.quran.page.common.data.PageCoordinates
import com.quran.page.common.draw.ImageDrawHelper
import java.util.SortedMap

class HighlightsDrawer(
  private val ayahCoordinates: () -> AyahCoordinates?,
  private val highlightCoordinates: () -> Map<AyahHighlight, List<AyahBounds>>?,
  private val currentHighlights: () -> SortedMap<HighlightType, Set<AyahHighlight>>?,
  private val superOnDraw: (Canvas) -> Unit,
  private val isNightMode: () -> Boolean = { false },
  private vararg val highlightTypesFilter: HighlightType.Mode = HighlightType.Mode.entries.toTypedArray(),
) : ImageDrawHelper {

  // cached objects for onDraw
  private val scaledRect = RectF()
  private val colorPath = Path()
  private val alreadyHighlighted = mutableMapOf<HighlightType.Mode, MutableSet<AyahHighlight>>()

  // Singleton object so we can share the cache across all pages
  private object PaintCache {
    private val cache =
      mutableMapOf<Triple<HighlightType.Mode, /* @ColorRes */ Int, Boolean>, Paint>()

    fun getPaintForHighlightType(
      context: Context,
      type: HighlightType,
      isNightMode: Boolean
    ): Paint {
      val colorResId = if (isNightMode) type.nightColorResId else type.colorResId
      return cache.getOrPut(Triple(type.mode, colorResId, isNightMode)) {
        Paint().apply {
          val color = ContextCompat.getColor(context, colorResId)
          when (type.mode) {
            COLOR -> {
              this.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
              isFilterBitmap = true
            }
            UNDERLAY -> this.color = if (isNightMode) {
              ColorUtils.setAlphaComponent(color, NIGHT_UNDERLAY_ALPHA)
            } else {
              color
            }
            else -> this.color = color
          }
        }
      }
    }
  }

  private fun alreadyHighlightedContains(
    highlightType: HighlightType,
    highlight: AyahHighlight
  ): Boolean {
    val alreadyHighlighted = alreadyHighlighted.getOrElse(highlightType.mode) { emptySet() }
    if (highlight in alreadyHighlighted) {
      return true
    }
    if (highlight.isTransition()) {
      val transitionHighlight = highlight as TransitionAyahHighlight
      // if x -> y, either x or y is already highlighted, then we don't show the highlight
      return transitionHighlight.source in alreadyHighlighted
          || transitionHighlight.destination in alreadyHighlighted
    }
    return false
  }

  override fun draw(pageCoordinates: PageCoordinates, canvas: Canvas, image: ImageView) {
    val highlightCoordinates = highlightCoordinates() ?: return
    val glyphsCoords = ayahCoordinates()?.glyphCoordinates
    val ayahLineCoords = ayahCoordinates()?.ayahLineCoordinates
    val underlineRaiseRatio = ayahCoordinates()?.underlineRaiseRatio ?: 0f
    val currentHighlights = currentHighlights() ?: return

    alreadyHighlighted.clear()

    val filteredHighlights = currentHighlights.filterKeys { it.mode in highlightTypesFilter }

    for ((highlightType, highlights) in filteredHighlights) {
      val paint = PaintCache.getPaintForHighlightType(image.context, highlightType, isNightMode())
      colorPath.reset()

      for (highlight in highlights) {
        if (alreadyHighlightedContains(highlightType, highlight)) continue

        val isUnderline = highlightType.mode == UNDERLINE
        val rangesToDraw = glyphsCoords?.takeIf { !highlightType.isTransitionAnimated }
          ?.getBounds(highlight.key, !isUnderline, highlightType.mode == BACKGROUND)
          ?: ayahLineCoords?.get(highlight.key)?.takeIf { isUnderline }?.map { it.bounds }
          ?: highlightCoordinates[highlight]?.map { it.bounds }

        if (rangesToDraw != null && rangesToDraw.isNotEmpty()) {
          for (bounds in rangesToDraw) {
            if (highlightType.mode == UNDERLINE) {
              val underlineThickness = TypedValue.applyDimension(
                COMPLEX_UNIT_DIP, UNDERLINE_THICKNESS_DIPS, image.resources.displayMetrics)
              bounds.top = bounds.bottom - bounds.height() * underlineRaiseRatio
              bounds.bottom = bounds.top + underlineThickness
            }

            image.imageMatrix.mapRect(scaledRect, bounds)
            scaledRect.offset(image.paddingLeft.toFloat(), image.paddingTop.toFloat())

            when (highlightType.mode) {
              HIDE -> {
                // Clip out the bounds to be hidden so they don't get drawn
                clipOutRect(canvas, scaledRect)
              }

              COLOR -> {
                // colorized all at once below, after collecting all the bounds of this type
                colorPath.addRect(scaledRect, Path.Direction.CW)
              }

              UNDERLAY, HIGHLIGHT, BACKGROUND, UNDERLINE -> {
                // Draw a rectangle with the specified paint
                canvas.drawRect(scaledRect, paint)
              }
            }
          }

          alreadyHighlighted.getOrPut(highlightType.mode, { mutableSetOf() }).add(highlight)
        }
      }

      if (highlightType.mode == COLOR && !colorPath.isEmpty) {
        // draw the image once, clipped to all the bounds of this type, with the color we want,
        // then clip them out so they don't get drawn again
        canvas.save()
        canvas.clipPath(colorPath)
        drawColorized(canvas, image, paint)
        canvas.restore()
        clipOutPath(canvas, colorPath)
      }
    }
  }

  /**
   * Draws the page image with [paint]'s color filter. The bitmap is drawn directly as ImageView
   * would draw it: setting the view's color filter while drawing invalidates it, which would keep
   * redrawing the page for as long as anything is colorized.
   */
  private fun drawColorized(canvas: Canvas, image: ImageView, paint: Paint) {
    val drawable = image.drawable as? BitmapDrawable
    val bitmap = drawable?.bitmap
    if (bitmap == null) {
      val appliedFilter = image.colorFilter
      image.colorFilter = paint.colorFilter
      superOnDraw(canvas)
      image.colorFilter = appliedFilter
      return
    }
    canvas.save()
    canvas.translate(image.paddingLeft.toFloat(), image.paddingTop.toFloat())
    canvas.concat(image.imageMatrix)
    canvas.drawBitmap(bitmap, null, drawable.bounds, paint)
    canvas.restore()
  }

  private fun clipOutPath(canvas: Canvas, path: Path) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      canvas.clipOutPath(path)
    } else {
      canvas.clipPath(path, Region.Op.DIFFERENCE)
    }
  }

  private fun clipOutRect(canvas: Canvas, rect: RectF) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      canvas.clipOutRect(rect)
    } else {
      canvas.clipRect(rect, Region.Op.DIFFERENCE)
    }
  }

  companion object {
    private const val UNDERLINE_THICKNESS_DIPS = 3f // dp
    private const val NIGHT_UNDERLAY_ALPHA = 0x66
  }
}

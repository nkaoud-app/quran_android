package com.quran.page.common.data

import com.quran.page.common.data.coordinates.PageGlyphsCoords

data class AyahCoordinates @JvmOverloads constructor(
  val page: Int,
  val ayahCoordinates: Map<String, List<AyahBounds>>,
  val glyphCoordinates: PageGlyphsCoords?,
  // bounds of each word ("sura:ayah:word", words counted from 1) when the page has no glyph data
  val wordCoordinates: Map<String, List<AyahBounds>> = emptyMap()
)

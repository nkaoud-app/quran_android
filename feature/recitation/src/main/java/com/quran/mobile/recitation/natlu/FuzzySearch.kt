package com.quran.mobile.recitation.natlu

// Ported from recite_quran's lib/tracking/ayah_search/fuzzy_search.dart (see NATLU_NOTICE.md).

/** An approximate occurrence of a query in a text, from [start] until [end]. */
data class FuzzyMatch(val start: Int, val end: Int, val dist: Int)

/**
 * Finds the occurrences of [query] in [text] within Levenshtein distance [maxDist]. Uses Myers'
 * bit-parallel algorithm for queries of up to 64 characters, dynamic programming beyond.
 */
fun findNearMatches(query: String, text: String, maxDist: Int): List<FuzzyMatch> {
  if (query.isEmpty() || text.isEmpty() || maxDist < 0) return emptyList()
  return if (query.length <= 64) bitParallelSearch(query, text, maxDist) else dpSearch(query, text, maxDist)
}

private fun mask(bits: Int): Long = if (bits >= 64) -1L else (1L shl bits) - 1

private fun charMasks(query: CharSequence, reversed: Boolean): LongArray {
  val masks = LongArray(2048)
  val n = query.length
  for (i in 0 until n) {
    val code = query[if (reversed) n - 1 - i else i].code
    if (code < 2048) masks[code] = masks[code] or (1L shl i)
  }
  return masks
}

/** The exact start of a match ending at [endIdx], by a backward Myers search. */
private fun refineMatchStart(query: String, text: String, endIdx: Int, maxDist: Int): Int {
  val n = query.length
  val window = if (endIdx < n + maxDist + 4) endIdx else n + maxDist + 4
  val masks = charMasks(query, reversed = true)
  val fullMask = mask(n)
  val topMask = 1L shl (n - 1)
  var vp = fullMask
  var vn = 0L
  var currDist = n
  var bestDist = 999
  var bestLen = n
  for (k in 0 until window) {
    val code = text[endIdx - 1 - k].code
    val pm = if (code < 2048) masks[code] else 0L
    val x = pm or vn
    val d0 = (((pm and vp) + vp) xor vp) or x
    val hn = vp and d0
    var hp = vn or ((vp or d0).inv() and fullMask)
    if ((hp and topMask) != 0L) currDist++
    if ((hn and topMask) != 0L) currDist--
    hp = (hp shl 1) and fullMask
    val hnShifted = (hn shl 1) and fullMask
    vp = (hnShifted or ((d0 or hp).inv() and fullMask)) and fullMask
    vn = hp and d0
    if (currDist <= maxDist && currDist <= bestDist) {
      bestDist = currDist
      bestLen = k + 1
    }
  }
  return endIdx - bestLen
}

private fun bitParallelSearch(query: String, text: String, maxDist: Int): List<FuzzyMatch> {
  val n = query.length
  val masks = charMasks(query, reversed = false)
  val fullMask = mask(n)
  val topMask = 1L shl (n - 1)
  var vp = fullMask
  var vn = 0L
  var currDist = n
  val raw = ArrayList<FuzzyMatch>()
  for (j in text.indices) {
    val code = text[j].code
    val pm = if (code < 2048) masks[code] else 0L
    val x = pm or vn
    val d0 = (((pm and vp) + vp) xor vp) or x
    val hn = vp and d0
    var hp = vn or ((vp or d0).inv() and fullMask)
    if ((hp and topMask) != 0L) currDist++
    if ((hn and topMask) != 0L) currDist--
    // Substring search: the top boundary delta is 0.
    hp = (hp shl 1) and fullMask
    val hnShifted = (hn shl 1) and fullMask
    vp = (hnShifted or ((d0 or hp).inv() and fullMask)) and fullMask
    vn = hp and d0
    if (currDist <= maxDist) {
      val end = j + 1
      raw += FuzzyMatch(if (end - n > 0) end - n else 0, end, currDist)
    }
  }
  return filterOverlapping(raw).map { it.copy(start = refineMatchStart(query, text, it.end, it.dist)) }
}

private fun dpSearch(query: String, text: String, maxDist: Int): List<FuzzyMatch> {
  val n = query.length
  var prevDist = IntArray(n + 1) { it }
  var prevStart = IntArray(n + 1)
  var currDist = IntArray(n + 1)
  var currStart = IntArray(n + 1)
  val matches = ArrayList<FuzzyMatch>()
  for (j in 1..text.length) {
    currDist[0] = 0
    currStart[0] = j
    val textChar = text[j - 1]
    for (i in 1..n) {
      val cost = if (query[i - 1] == textChar) 0 else 1
      var minDist = prevDist[i - 1] + cost
      var bestStart = prevStart[i - 1]
      val deleteDist = prevDist[i] + 1
      if (deleteDist < minDist || (deleteDist == minDist && prevStart[i] > bestStart)) {
        minDist = deleteDist
        bestStart = prevStart[i]
      }
      val insertDist = currDist[i - 1] + 1
      if (insertDist < minDist || (insertDist == minDist && currStart[i - 1] > bestStart)) {
        minDist = insertDist
        bestStart = currStart[i - 1]
      }
      currDist[i] = minDist
      currStart[i] = bestStart
    }
    if (currDist[n] <= maxDist) matches += FuzzyMatch(currStart[n], j, currDist[n])
    prevDist = currDist.also { currDist = prevDist }
    prevStart = currStart.also { currStart = prevStart }
  }
  return filterOverlapping(matches)
}

private fun filterOverlapping(matches: MutableList<FuzzyMatch>): List<FuzzyMatch> {
  if (matches.isEmpty()) return emptyList()
  matches.sortWith(compareBy<FuzzyMatch> { it.start }.thenBy { it.end }.thenBy { it.dist })
  val filtered = ArrayList<FuzzyMatch>()
  var current = matches[0]
  for (i in 1 until matches.size) {
    val next = matches[i]
    if (next.start < current.end) {
      if (next.dist < current.dist ||
        (next.dist == current.dist && next.end - next.start < current.end - current.start)
      ) {
        current = next
      }
    } else {
      filtered += current
      current = next
    }
  }
  filtered += current
  return filtered
}

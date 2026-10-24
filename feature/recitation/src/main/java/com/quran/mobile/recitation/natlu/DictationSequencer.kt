package com.quran.mobile.recitation.natlu

import kotlin.math.abs
import kotlin.math.min

/**
 * Follows the reader word by word through a sura's phonemes, from the phonemes recognized so far.
 * Ported from recite_quran's lib/tracking/word/dictation_sequencer.dart (see NATLU_NOTICE.md),
 * without its Tajweed evaluation:
 *
 * 1. Match the next word against the recognized phonemes not consumed yet; when it matches,
 *    commit it, consume its phonemes and move on to the following word.
 * 2. If it does not match, try the next [NatluConfig.maxSkipWords] words; when one matches, the
 *    words before it are missed (skipped or said wrong).
 * 3. If nothing matches, wait for more phonemes; after [NatluConfig.reanchorStallThreshold]
 *    unmatched phonemes, search the whole sura for where the reader is (auto re-anchor).
 */
class DictationSequencer(private val listener: Listener) {

  interface Listener {
    /** [word] was recited ([missed] false) or skipped or said wrong ([missed] true). */
    fun onWord(word: Int, missed: Boolean)

    /** Tracking moved to [word] (a jump or re-anchor); words from it on are no longer committed. */
    fun onCursorMoved(word: Int)
  }

  var config = NatluConfig()

  private var wordBoundaries = IntArray(0)
  private var fullPhonemes = ""

  private var asrText = ""
  private var asrCharAnchor = 0
  private var trimmedOffset = 0
  private var pendingTail: String? = null
  private var lastReanchorAttemptOffset = -1
  private var lastReanchorAttemptLen = -1

  /** The next word expected. */
  var cursor = 0
    private set
  private val committedGreen = HashSet<Int>()
  private val committedRed = HashSet<Int>()
  private val matcher = DictationMatcher()

  private val wordCount get() = maxOf(0, wordBoundaries.size - 1)

  /** Sets the sura's words (phonemes) and the word to start from. */
  fun setReference(words: List<String>, startWord: Int) {
    val boundaries = IntArray(words.size + 1)
    var offset = 0
    words.forEachIndexed { i, w ->
      boundaries[i] = offset
      offset += w.length
    }
    boundaries[words.size] = offset
    wordBoundaries = boundaries
    fullPhonemes = words.joinToString("")
    committedGreen.clear()
    committedRed.clear()
    asrText = ""
    resetStream()
    cursor = startWord.coerceIn(0, wordCount)
  }

  /** Continues from [word], with the phonemes recognized so far forgotten. */
  fun jumpToWord(word: Int) {
    cursor = word.coerceIn(0, wordCount)
    asrText = ""
    resetStream()
    committedGreen.removeAll { it >= cursor }
    committedRed.removeAll { it >= cursor }
  }

  /**
   * Called with all phonemes recognized since the recognizer was last reset; [isNewSegment] when
   * this is the first text after a reset.
   */
  fun syncStream(text: String, isNewSegment: Boolean) {
    if (isNewSegment || text.length < trimmedOffset) {
      asrText = ""
      resetStream()
    }
    asrText = text.substring(trimmedOffset)
    processSequence()
  }

  private fun resetStream() {
    asrCharAnchor = 0
    trimmedOffset = 0
    pendingTail = null
    lastReanchorAttemptOffset = -1
    lastReanchorAttemptLen = -1
  }

  /**
   * Early matching: absorbs the rest of an early-committed word (prolonged vowels, trailing
   * letters) before the next word may match; lifts as soon as a different sound arrives.
   */
  private fun drainPendingTail() {
    val tail = pendingTail
    if (!config.enableEarlyMatching || tail == null) {
      pendingTail = null
      return
    }
    if (tail.isEmpty()) return
    var tailIdx = 0
    while (asrCharAnchor < asrText.length && tailIdx < tail.length) {
      val code = asrText[asrCharAnchor].code
      val expected = tail[tailIdx].code
      if (code == expected) {
        asrCharAnchor++
        tailIdx++
      } else if (PhoneticCostEngine.isMaddVowel(expected) && PhoneticCostEngine.isMaddVowel(code)) {
        asrCharAnchor++ // a prolonged vowel
      } else {
        pendingTail = null // the reader moved on to the next word
        return
      }
    }
    if (tailIdx >= tail.length) pendingTail = null
  }

  private fun processSequence() {
    while (asrCharAnchor < asrText.length && cursor < wordCount) {
      if (config.enableEarlyMatching && pendingTail != null) {
        drainPendingTail()
        if (pendingTail != null) break
      }

      val unconsumed = asrText.substring(asrCharAnchor)
      var matched = false
      var waitingForPartial = false

      // How many words to skip, and whether to match one word or two merged (wasl, idgham).
      var skip = 0
      while (skip <= config.maxSkipWords && cursor + skip < wordCount) {
        val startW = cursor + skip
        for (merge in 1..2) {
          val endW = startW + merge - 1
          if (endW >= wordCount) break
          val result = matcher.matchWord(unconsumed, fullPhonemes, wordBoundaries[startW], refEnd(endW), config)
            ?: continue
          if (result.isPartial) {
            if (skip == 0) {
              waitingForPartial = true
              break // wait for more phonemes
            }
            continue // a later word partly matches; ignore it for now
          }
          if (result.tokensConsumed <= 0) continue
          if (merge > 1 && !isValidMerge(result, startW, endW, unconsumed)) continue

          for (s in 0 until skip) commitRed(cursor + s)
          for (w in startW..endW) commitGreen(w)
          asrCharAnchor += result.tokensConsumed
          cursor = endW + 1
          matched = true
          lastReanchorAttemptOffset = -1
          lastReanchorAttemptLen = -1

          // Early matching: reserve the word's phonemes not heard yet, so the next word cannot
          // match against them.
          if (config.enableEarlyMatching) {
            val wordRefEnd = refEnd(endW)
            val lastMatchedRef = result.trace.lastOrNull { it.op != PhonemeAlignment.Op.DELETE }?.refIdx ?: -1
            if (lastMatchedRef != -1 && lastMatchedRef < wordRefEnd - 1) {
              pendingTail = fullPhonemes.substring(lastMatchedRef + 1, wordRefEnd)
              drainPendingTail()
            } else {
              pendingTail = null
            }
          }
          break
        }
        if (matched || waitingForPartial) break
        skip++
      }

      if (!matched) {
        if (config.enableAutoReanchor) {
          val oldCursor = cursor
          attemptAutoReanchor()
          if (cursor != oldCursor) continue // try the new word right away
        }
        break // wait for more phonemes
      }
    }

    // Keep about 50 consumed phonemes (7-9 words) and trim older ones.
    val keepCushion = 50
    if (asrCharAnchor > keepCushion + 50) {
      val trim = asrCharAnchor - keepCushion
      trimmedOffset += trim
      asrText = asrText.substring(trim)
      asrCharAnchor = keepCushion
    }
  }

  private fun refEnd(word: Int) = if (word + 1 < wordBoundaries.size) wordBoundaries[word + 1] else fullPhonemes.length

  private fun wordAtPhonemeOffset(offset: Int): Int {
    if (wordBoundaries.isEmpty()) return 0
    var low = 0
    var high = wordBoundaries.size - 1
    var found = 0
    while (low <= high) {
      val mid = (low + high) ushr 1
      if (wordBoundaries[mid] <= offset) {
        found = mid
        low = mid + 1
      } else {
        high = mid - 1
      }
    }
    return found.coerceIn(0, wordCount)
  }

  private class Probe(val word: Int, val dist: Int, val offset: Int)

  private fun probeQueryWindow(query: String, offset: Int): Probe? {
    if (query.length < min(18, config.reanchorStallThreshold)) return null
    val maxDist = (query.length * 0.22).toInt()
    val candidates = findNearMatches(query, fullPhonemes, maxDist)
      .map { Probe(wordAtPhonemeOffset(it.start), it.dist, offset) }
      .filter { it.word != cursor && it.word != cursor + 1 }
    if (candidates.isEmpty()) return null
    // Closest edit distance first; on ties, forward and nearest to the cursor.
    val sorted = candidates.sortedWith(
      compareBy<Probe> { it.dist }
        .thenBy { if (it.word > cursor) 0 else 1 }
        .thenBy { abs(it.word - cursor) }
    )
    val best = sorted[0]
    // Mutashabihat: a similar match elsewhere in the sura makes the jump ambiguous.
    for (i in 1 until sorted.size) {
      val c = sorted[i]
      if (abs(c.word - best.word) > 2 && c.dist - best.dist < 3) return null
    }
    return best
  }

  private fun attemptAutoReanchor() {
    if (wordBoundaries.isEmpty() || fullPhonemes.isEmpty()) return
    val unconsumedLen = asrText.length - asrCharAnchor
    if (unconsumedLen < config.reanchorStallThreshold) return
    if (lastReanchorAttemptOffset == asrCharAnchor && lastReanchorAttemptLen == asrText.length) return
    lastReanchorAttemptOffset = asrCharAnchor
    lastReanchorAttemptLen = asrText.length

    val query = asrText.substring(asrCharAnchor)
    // The whole unconsumed text (up to 64 phonemes) resolves repeated phrases best, then the
    // front of it, then the latest phonemes, where the reader is now.
    val fullLen = min(64, query.length)
    var result = probeQueryWindow(query.substring(0, fullLen), 0)
    if (result == null && fullLen > 36) result = probeQueryWindow(query.substring(0, 36), 0)
    if (result == null && query.length > 28) {
      val tailOffset = query.length - 28
      result = probeQueryWindow(query.substring(tailOffset), tailOffset)
    }
    if (result == null) return

    asrCharAnchor += result.offset
    cursor = result.word
    pendingTail = null
    committedGreen.removeAll { it >= cursor }
    committedRed.removeAll { it >= cursor }
    listener.onCursorMoved(cursor)
  }

  private fun commitGreen(w: Int) {
    if (!committedGreen.add(w)) return
    committedRed.remove(w)
    listener.onWord(w, missed = false)
  }

  private fun commitRed(w: Int) {
    if (w in committedRed || w in committedGreen) return
    committedRed += w
    listener.onWord(w, missed = true)
  }

  /** A merged match must match each word's core, away from the boundary between them. */
  private fun isValidMerge(result: WordMatchResult, startW: Int, endW: Int, asr: String): Boolean {
    if (startW == endW) return true
    for (w in startW..endW) {
      val refStart = wordBoundaries[w]
      val refEnd = refEnd(w)
      val wordLen = refEnd - refStart
      val forgiveStart = if (w > startW) min(2, wordLen / 3) else 0
      val forgiveEnd = if (w < endW) min(2, wordLen / 3) else 0
      val coreStart = refStart + forgiveStart
      val coreEnd = refEnd - forgiveEnd
      val coreLen = coreEnd - coreStart
      if (coreLen <= 0) continue
      var coreCost = 0.0
      for (step in result.trace) {
        if (step.refIdx !in coreStart until coreEnd) continue
        when (step.op) {
          PhonemeAlignment.Op.DELETE -> coreCost += config.standardDeletionCost
          PhonemeAlignment.Op.REPLACE ->
            coreCost += if (step.predIdx >= 0 && step.predIdx < asr.length) {
              PhoneticCostEngine.substitutionCost(asr[step.predIdx].code, fullPhonemes[step.refIdx].code)
            } else {
              config.standardInsertionCost
            }
          else -> Unit
        }
      }
      if (coreCost / coreLen > config.defaultMaxPathCost) return false
    }
    return true
  }
}

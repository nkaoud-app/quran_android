package com.quran.mobile.recitation.natlu

import kotlin.math.max
import kotlin.math.min

// Ported from recite_quran's lib/tracking/word/dictation_matcher.dart (see NATLU_NOTICE.md):
// a per-word semi-global alignment of recognized phonemes against one word's phonemes, with
// the model's phonetic cost matrix. Tajweed evaluation is left out.

/** One step of an alignment: a reference phoneme matched, replaced or deleted, or an inserted one. */
data class PhonemeAlignment(val op: Op, val refIdx: Int, val predIdx: Int) {
  enum class Op { MATCH, REPLACE, DELETE, INSERT }
}

/** Result of aligning recognized phonemes against a word's reference. */
class WordMatchResult(
  /** Total edit cost normalized by reference length. */
  val pathCost: Double,
  /** How many recognized phonemes the match consumed from the buffer. */
  val tokensConsumed: Int,
  /** The recognized phonemes aligned to the word. */
  val cleanAsr: String,
  val trace: List<PhonemeAlignment>,
  /** The word is still being spoken. */
  val isPartial: Boolean = false,
) {
  companion object {
    val PARTIAL = WordMatchResult(0.0, 0, "", emptyList(), isPartial = true)
  }
}

/** The model's phonetic cost matrix. */
object PhoneticCostEngine {
  /** Tajweed and auxiliary markers that cost nothing to miss or add. */
  fun isZeroCostMarker(code: Int): Boolean =
    code == 0x0686 || // ڇ qalqalah release burst
      code == 0x06DC || // ۜ sakt
      code == 0x0619 || // ؙ ishmam
      code == 0x06EA || // ۪ imalah
      code == 0x0640 // ـ tatweel

  /** Interchangeable Quranic glyphs (cost 0). */
  fun isEquivalentGlyph(asr: Int, ref: Int): Boolean {
    if (asr == ref) return true
    val a = min(asr, ref)
    val b = max(asr, ref)
    if (a == 0x0645 && b == 0x06FE) return true // م ۾ (iqlab)
    if (a == 0x0646 && b == 0x06BA) return true // ن ں (ikhfa)
    if (a == 0x0648 && b == 0x06E5) return true // و ۥ
    if (a == 0x064A && b == 0x06E6) return true // ي ۦ
    if (isHamzaVariant(a) && isHamzaVariant(b)) return true
    // Ta marbuta can sound like ha or ta, but ha and ta do not match each other.
    if (a == 0x0629 && b == 0x0647) return true // ة ه
    if (a == 0x062A && b == 0x0629) return true // ت ة
    return false
  }

  private fun isHamzaVariant(code: Int) =
    code == 0x0621 || code == 0x0622 || code == 0x0623 || code == 0x0625 || code == 0x0672

  /** Phonemes the model confuses (cost [NatluConfig.acousticConfusionCost]). */
  private fun isAcousticConfusion(asr: Int, ref: Int): Boolean {
    val a = min(asr, ref)
    val b = max(asr, ref)
    return when (a) {
      // long vowels and their short vowels
      0x0627 -> b == 0x064E // ا َ
      0x0648 -> b == 0x064F // و ُ
      0x064F -> b == 0x06E5 // ُ ۥ
      0x064A -> b == 0x0650 // ي ِ
      0x0650 -> b == 0x06E6 // ِ ۦ
      // consonants
      0x062A -> b == 0x0637 // ت ط
      0x062C -> b == 0x0632 // ج ز
      0x062E -> b == 0x063A // خ غ
      0x062F -> b == 0x0636 // د ض
      0x0630 -> b == 0x0632 || b == 0x0638 // ذ ز ظ
      0x0633 -> b == 0x0635 // س ص
      0x0642 -> b == 0x0643 // ق ك
      else -> false
    }
  }

  /** Fatha, damma, kasra. */
  fun isTashkeel(code: Int) = code == 0x064E || code == 0x064F || code == 0x0650

  /** Long vowels ا و ي ۥ ۦ. */
  fun isMaddVowel(code: Int) =
    code == 0x0627 || code == 0x0648 || code == 0x064A || code == 0x06E5 || code == 0x06E6

  fun substitutionCost(asr: Int, ref: Int, acousticConfusionCost: Double = 0.25): Double = when {
    asr == ref || isEquivalentGlyph(asr, ref) -> 0.0
    isAcousticConfusion(asr, ref) -> acousticConfusionCost
    else -> 1.0
  }

  /** Cost of the expected phoneme at [refIdx] missing from the recognized stream. */
  fun deletionCost(fullPhonemes: String, refIdx: Int, standardDeletionCost: Double, acousticConfusionCost: Double): Double {
    if (refIdx < 0 || refIdx >= fullPhonemes.length) return standardDeletionCost
    val code = fullPhonemes[refIdx].code
    if (isZeroCostMarker(code)) return 0.0
    if (isHamzaVariant(code)) return acousticConfusionCost
    // CTC often emits a repeated phoneme (a madd or shadda) once.
    if (refIdx > 0 && code == fullPhonemes[refIdx - 1].code) return acousticConfusionCost
    return standardDeletionCost
  }

  /** Cost of an extra recognized phoneme at [asrIdx]. */
  fun insertionCost(asrText: String, asrIdx: Int, standardInsertionCost: Double, acousticConfusionCost: Double): Double {
    if (asrIdx < 0 || asrIdx >= asrText.length) return standardInsertionCost
    val code = asrText[asrIdx].code
    if (isZeroCostMarker(code)) return 0.0
    if (asrIdx > 0 && code == asrText[asrIdx - 1].code && isMaddVowel(code)) return acousticConfusionCost
    return standardInsertionCost
  }
}

/**
 * Matches recognized phonemes against one word at a time. Leading recognized phonemes are free
 * (wasl, CTC jitter, or anything said before the word), and the match ends at the endpoint that
 * consumes the fewest phonemes within the cost threshold.
 */
class DictationMatcher {
  private var dp = DoubleArray(2048)
  private var bt = ByteArray(2048)

  /**
   * Aligns [asrText] against [fullPhonemes] from [refStart] until [refEnd]. Returns null when it
   * does not match, [WordMatchResult.PARTIAL] when the word looks like it is still being spoken.
   */
  fun matchWord(asrText: String, fullPhonemes: String, refStart: Int, refEnd: Int, config: NatluConfig): WordMatchResult? {
    val m = asrText.length
    val n = refEnd - refStart
    if (m == 0 || n <= 0) return null

    val stride = n + 1
    val cells = (m + 1) * stride
    if (dp.size < cells) {
      val size = max(cells, dp.size * 2)
      dp = DoubleArray(size)
      bt = ByteArray(size)
    }
    val dp = dp
    val bt = bt

    // Row 0: the word's phonemes with nothing recognized.
    dp[0] = 0.0
    bt[0] = OP_SUB
    for (j in 1..n) {
      dp[j] = dp[j - 1] + PhoneticCostEngine.deletionCost(
        fullPhonemes, refStart + j - 1, config.standardDeletionCost, config.acousticConfusionCost,
      )
      bt[j] = OP_DEL
    }
    // Column 0: recognized phonemes before the word are free.
    for (i in 1..m) {
      dp[i * stride] = 0.0
      bt[i * stride] = OP_INS
    }

    for (i in 1..m) {
      val aCode = asrText[i - 1].code
      val row = i * stride
      val prev = (i - 1) * stride
      val insCost = PhoneticCostEngine.insertionCost(asrText, i - 1, config.standardInsertionCost, config.acousticConfusionCost)
      for (j in 1..n) {
        val ref = refStart + j - 1
        val subCost = PhoneticCostEngine.substitutionCost(aCode, fullPhonemes[ref].code, config.acousticConfusionCost)
        val delCost = PhoneticCostEngine.deletionCost(fullPhonemes, ref, config.standardDeletionCost, config.acousticConfusionCost)
        val sub = dp[prev + j - 1] + subCost
        val del = dp[row + j - 1] + delCost
        val ins = dp[prev + j] + insCost
        // Ties go to deletions, so trailing omissions show as deletions at the end of the path.
        if (sub < del && sub <= ins) {
          dp[row + j] = sub
          bt[row + j] = OP_SUB
        } else if (del <= ins) {
          dp[row + j] = del
          bt[row + j] = OP_DEL
        } else {
          dp[row + j] = ins
          bt[row + j] = OP_INS
        }
      }
    }

    // Effective length: repeated madd vowels count once and zero-cost markers not at all.
    var effN = 0
    for (j in 0 until n) {
      val code = fullPhonemes[refStart + j].code
      if (PhoneticCostEngine.isZeroCostMarker(code)) continue
      if (j > 0 && code == fullPhonemes[refStart + j - 1].code && PhoneticCostEngine.isMaddVowel(code)) continue
      effN++
    }
    if (effN < 1) effN = 1

    val threshold = when {
      effN <= 3 -> min(config.defaultMaxPathCost, config.shortWordPathCost)
      effN <= 7 -> min(config.defaultMaxPathCost, config.mediumWordPathCost)
      else -> config.defaultMaxPathCost
    }

    // Early matching: words of 4+ phonemes commit on the first exact match.
    val allowEarlyBreak = config.enableEarlyMatching && effN >= 4
    var bestI = -1
    var bestCost = Double.POSITIVE_INFINITY
    for (i in 1..m) {
      val norm = dp[i * stride + n] / effN
      if (norm <= threshold && (if (allowEarlyBreak) norm < bestCost else norm <= bestCost)) {
        bestI = i
        bestCost = norm
        if (allowEarlyBreak && bestCost == 0.0) break
      }
    }

    if (bestI < 0) {
      // A prefix of the word matches at the end of the buffer: it is still being spoken.
      val minJ = if (n > 2) 2 else 1
      for (i in max(1, m - 2)..m) {
        for (j in minJ until n) {
          if (dp[i * stride + j] / j <= threshold) return WordMatchResult.PARTIAL
        }
      }
      return null
    }

    var ci = bestI
    var cj = n
    val trace = ArrayList<PhonemeAlignment>()
    while (cj > 0) {
      val ref = refStart + cj - 1
      if (ci == 0) {
        trace += PhonemeAlignment(PhonemeAlignment.Op.DELETE, ref, -1)
        cj--
        continue
      }
      when (bt[ci * stride + cj]) {
        OP_SUB -> {
          val exact = PhoneticCostEngine.substitutionCost(asrText[ci - 1].code, fullPhonemes[ref].code) == 0.0
          trace += PhonemeAlignment(if (exact) PhonemeAlignment.Op.MATCH else PhonemeAlignment.Op.REPLACE, ref, ci - 1)
          ci--
          cj--
        }
        OP_DEL -> {
          trace += PhonemeAlignment(PhonemeAlignment.Op.DELETE, ref, -1)
          cj--
        }
        else -> {
          trace += PhonemeAlignment(PhonemeAlignment.Op.INSERT, ref, ci - 1)
          ci--
        }
      }
    }
    trace.reverse()
    return WordMatchResult(bestCost, bestI, asrText.substring(0, bestI), trace)
  }

  private companion object {
    const val OP_SUB: Byte = 0
    const val OP_DEL: Byte = 1
    const val OP_INS: Byte = 2
  }
}

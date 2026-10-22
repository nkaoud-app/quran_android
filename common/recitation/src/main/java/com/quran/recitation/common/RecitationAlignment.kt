package com.quran.recitation.common

/** A token produced by the speech-recognition backend. */
data class RecognizedWord(
  val text: String,
  val startTimeMs: Long? = null,
  val endTimeMs: Long? = null,
)

/** A word from the Quran text that the reader is expected to recite. */
data class ExpectedWord(
  val index: Int,
  val text: String,
)

sealed interface AlignmentOperation {
  data class Match(
    val expected: ExpectedWord,
    val recognized: RecognizedWord,
  ) : AlignmentOperation

  data class Missing(val expected: ExpectedWord) : AlignmentOperation

  data class Unexpected(val recognized: RecognizedWord) : AlignmentOperation

  data class Substitution(
    val expected: ExpectedWord,
    val recognized: RecognizedWord,
  ) : AlignmentOperation
}

data class AlignmentResult(
  val operations: List<AlignmentOperation>,
) {
  val missingWords: List<ExpectedWord>
    get() = operations.filterIsInstance<AlignmentOperation.Missing>().map { it.expected }

  val unexpectedWords: List<RecognizedWord>
    get() = operations.filterIsInstance<AlignmentOperation.Unexpected>().map { it.recognized }

  val substitutions: List<AlignmentOperation.Substitution>
    get() = operations.filterIsInstance<AlignmentOperation.Substitution>()

  val matchedWords: List<AlignmentOperation.Match>
    get() = operations.filterIsInstance<AlignmentOperation.Match>()

  /**
   * True when the reader reached the last expected word and nearly every expected word was
   * recognized. Speech recognition misses the odd word in long ayahs, so one miss is tolerated
   * for every eight words.
   */
  val isComplete: Boolean
    get() {
      val expectedCount = matchedWords.size + substitutions.size + missingWords.size
      if (expectedCount == 0) return false
      val lastExpected = operations.lastOrNull { it !is AlignmentOperation.Unexpected }
      return lastExpected is AlignmentOperation.Match &&
        expectedCount - matchedWords.size <= expectedCount / 8
    }
}

/** Splits ayah text into the words a reader recites, dropping waqf signs and ayah markers. */
fun expectedWordsOf(ayahText: String): List<ExpectedWord> =
  ayahText.split(WHITESPACE)
    .filter { normalizeArabicWord(it).isNotEmpty() }
    .mapIndexed { index, text -> ExpectedWord(index, text) }

/** Splits a speech recognition transcript into words. */
fun recognizedWordsOf(transcript: String): List<RecognizedWord> =
  transcript.split(WHITESPACE)
    .filter { normalizeArabicWord(it).isNotEmpty() }
    .map { RecognizedWord(it) }

/**
 * Reduces a word to its consonantal skeleton so Uthmani script (as stored in the Quran database)
 * compares equal to the standard spelling speech recognizers produce, e.g. ٱلْعَـٰلَمِينَ and
 * العالمين both become لعلمين. Alif and hamza forms are dropped since the two scripts disagree on
 * them most often; diacritics, Quranic annotation marks, tatweel, digits and punctuation are
 * dropped as well.
 */
fun normalizeArabicWord(word: String): String = buildString {
  for (c in word) {
    when (c) {
      'ا', 'ٱ', 'أ', 'إ', 'آ', 'ء', '\u0670' -> Unit
      'ى', 'ئ', 'ی', '\u06E6', '\u06E7' -> append('ي')
      'ؤ', '\u06E5' -> append('و')
      'ة' -> append('ه')
      in '\u0621'..'\u063A', in '\u0641'..'\u064A' -> append(c)
      else -> Unit
    }
  }
}

private val WHITESPACE = Regex("\\s+")

/**
 * Aligns recognized Quran tokens against the expected ayah tokens.
 *
 * This is deliberately independent from audio or a particular ASR model. It allows us to
 * replace the model later without changing the feedback and highlighting layer.
 */
class RecitationAlignmentEngine {
  fun align(expected: List<ExpectedWord>, recognized: List<RecognizedWord>): AlignmentResult {
    val expectedText = expected.map { normalizeArabicWord(it.text) }
    val recognizedText = recognized.map { normalizeArabicWord(it.text) }
    val expectedSize = expected.size
    val recognizedSize = recognized.size
    val costs = Array(expectedSize + 1) { IntArray(recognizedSize + 1) }
    val moves = Array(expectedSize + 1) { IntArray(recognizedSize + 1) }

    for (i in 1..expectedSize) {
      costs[i][0] = i
      moves[i][0] = MISSING
    }
    for (j in 1..recognizedSize) {
      costs[0][j] = j
      moves[0][j] = UNEXPECTED
    }

    for (i in 1..expectedSize) {
      for (j in 1..recognizedSize) {
        // Prefer diagonal moves, then missing words, to keep feedback stable when costs tie.
        val same = similar(expectedText[i - 1], recognizedText[j - 1])
        var cost = costs[i - 1][j - 1] + if (same) 0 else 1
        var move = if (same) MATCH else SUBSTITUTION
        // Uthmani script joins some words the recognizer splits, e.g. يَـٰٓأَيُّهَا vs يا أيها.
        if (j > 1 && costs[i - 1][j - 2] < cost &&
          similar(expectedText[i - 1], recognizedText[j - 2] + recognizedText[j - 1])
        ) {
          cost = costs[i - 1][j - 2]
          move = JOINED_MATCH
        }
        if (costs[i - 1][j] + 1 < cost) {
          cost = costs[i - 1][j] + 1
          move = MISSING
        }
        if (costs[i][j - 1] + 1 < cost) {
          cost = costs[i][j - 1] + 1
          move = UNEXPECTED
        }
        costs[i][j] = cost
        moves[i][j] = move
      }
    }

    val reversed = mutableListOf<AlignmentOperation>()
    var i = expectedSize
    var j = recognizedSize
    while (i > 0 || j > 0) {
      when (moves[i][j]) {
        MATCH -> reversed += AlignmentOperation.Match(expected[i - 1], recognized[j - 1])
        SUBSTITUTION -> reversed += AlignmentOperation.Substitution(expected[i - 1], recognized[j - 1])
        JOINED_MATCH -> {
          val first = recognized[j - 2]
          val second = recognized[j - 1]
          reversed += AlignmentOperation.Match(
            expected[i - 1],
            RecognizedWord("${first.text} ${second.text}", first.startTimeMs, second.endTimeMs),
          )
          j--
        }
        MISSING -> reversed += AlignmentOperation.Missing(expected[i - 1])
        UNEXPECTED -> reversed += AlignmentOperation.Unexpected(recognized[j - 1])
      }
      when (moves[i][j]) {
        MISSING -> i--
        UNEXPECTED -> j--
        else -> {
          i--
          j--
        }
      }
    }

    return AlignmentResult(reversed.asReversed())
  }

  /** Exact skeleton match, or one letter apart for longer words (e.g. الصلاة vs ٱلصَّلَوٰةَ). */
  private fun similar(expected: String, recognized: String): Boolean =
    expected == recognized ||
      (minOf(expected.length, recognized.length) >= 4 && editDistanceAtMostOne(expected, recognized))

  private fun editDistanceAtMostOne(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
      if (a[i] == b[j]) {
        i++
        j++
      } else {
        if (++edits > 1) return false
        when {
          a.length > b.length -> i++
          a.length < b.length -> j++
          else -> {
            i++
            j++
          }
        }
      }
    }
    return edits + (a.length - i) + (b.length - j) <= 1
  }

  private companion object {
    const val MATCH = 0
    const val SUBSTITUTION = 1
    const val JOINED_MATCH = 2
    const val MISSING = 3
    const val UNEXPECTED = 4
  }
}

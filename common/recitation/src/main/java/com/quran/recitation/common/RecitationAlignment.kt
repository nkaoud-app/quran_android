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
}

/**
 * Aligns recognized Quran tokens against the expected ayah tokens.
 *
 * This is deliberately independent from audio or a particular ASR model. It allows us to
 * replace the model later without changing the feedback and highlighting layer.
 */
class RecitationAlignmentEngine {
  fun align(expected: List<ExpectedWord>, recognized: List<RecognizedWord>): AlignmentResult {
    val expectedSize = expected.size
    val recognizedSize = recognized.size
    val costs = Array(expectedSize + 1) { IntArray(recognizedSize + 1) }

    for (i in 1..expectedSize) costs[i][0] = i
    for (j in 1..recognizedSize) costs[0][j] = j

    for (i in 1..expectedSize) {
      for (j in 1..recognizedSize) {
        val substitutionCost = if (sameWord(expected[i - 1].text, recognized[j - 1].text)) 0 else 1
        costs[i][j] = minOf(
          costs[i - 1][j] + 1,
          costs[i][j - 1] + 1,
          costs[i - 1][j - 1] + substitutionCost,
        )
      }
    }

    val reversed = mutableListOf<AlignmentOperation>()
    var i = expectedSize
    var j = recognizedSize
    while (i > 0 || j > 0) {
      val expectedWord = expected.getOrNull(i - 1)
      val recognizedWord = recognized.getOrNull(j - 1)
      val diagonal = if (i > 0 && j > 0) costs[i - 1][j - 1] else Int.MAX_VALUE
      val deletion = if (i > 0) costs[i - 1][j] else Int.MAX_VALUE
      val insertion = if (j > 0) costs[i][j - 1] else Int.MAX_VALUE

      // Prefer diagonal moves, then missing words, to keep feedback stable when costs tie.
      if (expectedWord != null && recognizedWord != null &&
        costs[i][j] == diagonal + if (sameWord(expectedWord.text, recognizedWord.text)) 0 else 1
      ) {
        reversed += if (sameWord(expectedWord.text, recognizedWord.text)) {
          AlignmentOperation.Match(expectedWord, recognizedWord)
        } else {
          AlignmentOperation.Substitution(expectedWord, recognizedWord)
        }
        i--
        j--
      } else if (expectedWord != null && costs[i][j] == deletion + 1) {
        reversed += AlignmentOperation.Missing(expectedWord)
        i--
      } else if (recognizedWord != null && costs[i][j] == insertion + 1) {
        reversed += AlignmentOperation.Unexpected(recognizedWord)
        j--
      } else {
        error("Unable to backtrack recitation alignment at ($i, $j)")
      }
    }

    return AlignmentResult(reversed.asReversed())
  }

  private fun sameWord(expected: String, recognized: String): Boolean =
    normalize(expected) == normalize(recognized)

  private fun normalize(value: String): String = value
    .replace("ـ", "")
    .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
    .replace('أ', 'ا')
    .replace('إ', 'ا')
    .replace('آ', 'ا')
    .replace('ى', 'ي')
}

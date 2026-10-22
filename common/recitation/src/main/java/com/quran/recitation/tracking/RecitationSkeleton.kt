package com.quran.recitation.tracking

/**
 * Reduces Quran text to the consonant skeleton the recitation acoustic model predicts.
 *
 * Uthmani text and speech disagree on vowel marks, alif and hamza, so those are dropped and a few
 * letter variants are merged. The remaining consonants, in code point order, are the model's
 * output classes: class 0 is the CTC blank and class `i + 1` is [CLASSES]`[i]`.
 */
object RecitationSkeleton {
  private val FOLDED: Set<Char> = "اٱأإآءـۥۦ|".toSet() + ('ً'..'ٟ').toSet() + 'ٰ'
  private val EQUIVALENT = mapOf(
    'ى' to 'ي', 'ئ' to 'ي', 'ی' to 'ي', 'ؤ' to 'و', 'ة' to 'ه', 'ۦ' to 'ي', 'ۧ' to 'ي',
    'ۥ' to 'و',
  )
  private val LETTERS: Set<Char> = (('ء'..'غ') + ('ف'..'ي')).toSet()

  /** The model's letter classes; class ids are offset by one for the blank. */
  val CLASSES: List<Char> = LETTERS.mapNotNull { skeleton(it.toString()).firstOrNull() }.distinct().sorted()

  /** Number of model output classes, including the blank. */
  val CLASS_COUNT: Int = CLASSES.size + 1

  private val classOf: Map<Char, Int> = CLASSES.withIndex().associate { (i, c) -> c to i + 1 }

  fun skeleton(word: String): String = buildString {
    for (raw in word) {
      val c = EQUIVALENT[raw] ?: raw
      if (c in LETTERS && c !in FOLDED) append(c)
    }
  }

  /** The class id of every consonant in [word]. */
  fun tokens(word: String): IntArray = skeleton(word).map { classOf.getValue(it) }.toIntArray()
}

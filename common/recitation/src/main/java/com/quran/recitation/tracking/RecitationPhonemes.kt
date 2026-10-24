package com.quran.recitation.tracking

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Each ayah's words as the phonemes [ZipformerModel] recognizes (Hafs, murattal), and the phrases
 * a recitation may open with; exported by tools/recitation-phonemes/export_phonemes.py from the
 * Tanzil text with quran-transcript.
 */
object RecitationPhonemes {
  @Volatile private var lines: Map<String, List<String>>? = null

  /** The phoneme words of each ayah of [sura], in order. */
  suspend fun sura(context: Context, sura: Int): List<List<String>> {
    val all = load(context)
    return generateSequence(1) { it + 1 }.map { all["$sura:$it"] }.takeWhile { it != null }.filterNotNull().toList()
  }

  /** The phrases a recitation of [sura] may open with: isti'adha, and basmala where it is not an ayah. */
  suspend fun openings(context: Context, sura: Int): List<List<String>> {
    val all = load(context)
    return listOfNotNull(all["istiadha"], all["basmala"]?.takeIf { sura != 1 && sura != 9 })
  }

  private suspend fun load(context: Context): Map<String, List<String>> =
    lines ?: withContext(Dispatchers.IO) {
      context.assets.open("recitation/phonemes.txt").bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
          val (key, words) = line.split('\t', limit = 2)
          key to words.split(' ')
        }
      }
    }.also { lines = it }
}

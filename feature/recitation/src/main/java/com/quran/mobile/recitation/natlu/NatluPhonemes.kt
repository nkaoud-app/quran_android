package com.quran.mobile.recitation.natlu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Each ayah's words as the phonemes Natlu's model recognizes (recite_quran's
 * ordered_quran_phonemes.json, Hafs; exported by tools/natlu/export_phonemes.py).
 */
object NatluPhonemes {
  @Volatile private var ayahs: Map<String, List<String>>? = null

  /** The phoneme words of each ayah of [sura], in order. */
  suspend fun sura(context: Context, sura: Int): List<List<String>> = withContext(Dispatchers.IO) {
    val all = ayahs ?: load(context).also { ayahs = it }
    generateSequence(1) { it + 1 }.map { all["$sura:$it"] }.takeWhile { it != null }.filterNotNull().toList()
  }

  private fun load(context: Context): Map<String, List<String>> =
    context.assets.open("natlu/quran_phonemes.txt").bufferedReader().useLines { lines ->
      lines.filter { it.isNotBlank() }.associate { line ->
        val (key, words) = line.split('\t', limit = 2)
        key to words.split(' ')
      }
    }
}

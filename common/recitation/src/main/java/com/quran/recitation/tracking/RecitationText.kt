package com.quran.recitation.tracking

import com.quran.data.model.AyahWord

/** A word of the Quran text to recite and where it is in the mushaf. */
data class RecitationWord(val word: AyahWord, val text: String)

/** The Arabic text recitation is followed against. */
interface RecitationText {
  /**
   * The words of [sura] from [firstAyah] to its end, without the basmala; the word positions count
   * from 1 within each ayah, as on the mushaf pages. Empty when the Arabic text is not available.
   */
  suspend fun words(sura: Int, firstAyah: Int): List<RecitationWord>
}

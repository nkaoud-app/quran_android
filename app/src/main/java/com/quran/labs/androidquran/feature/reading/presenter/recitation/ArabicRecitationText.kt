package com.quran.labs.androidquran.feature.reading.presenter.recitation

import com.quran.data.core.QuranInfo
import com.quran.data.di.AppScope
import com.quran.data.model.AyahWord
import com.quran.data.model.SuraAyah
import com.quran.labs.androidquran.model.translation.ArabicDatabaseUtils
import com.quran.recitation.tracking.RecitationText
import com.quran.recitation.tracking.RecitationWord
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recitation text from the Arabic Quran database (the same text as the mushaf's words). */
@ContributesBinding(AppScope::class)
class ArabicRecitationText @Inject constructor(
  private val arabicDatabaseUtils: ArabicDatabaseUtils,
  private val quranInfo: QuranInfo,
) : RecitationText {

  override suspend fun words(sura: Int, firstAyah: Int): List<RecitationWord> = withContext(Dispatchers.IO) {
    val verses = runCatching {
      arabicDatabaseUtils.getVerses(SuraAyah(sura, firstAyah), SuraAyah(sura, quranInfo.getNumberOfAyahs(sura)))
        .blockingGet()
    }.getOrDefault(emptyList())
    verses.flatMap { verse ->
      val ayah = SuraAyah(verse.sura, verse.ayah)
      ArabicDatabaseUtils.getAyahWithoutBasmallah(verse.sura, verse.ayah, verse.text)
        .split(" ").filter(String::isNotBlank)
        .mapIndexed { index, text -> RecitationWord(AyahWord(ayah, index + 1), text) }
    }
  }
}

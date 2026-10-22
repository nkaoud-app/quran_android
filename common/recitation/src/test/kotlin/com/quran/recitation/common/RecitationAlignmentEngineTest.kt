package com.quran.recitation.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecitationAlignmentEngineTest {
  private val engine = RecitationAlignmentEngine()

  @Test
  fun `matches Arabic words while ignoring common diacritics`() {
    val result = engine.align(
      expected = listOf(ExpectedWord(0, "الرَّحْمَنِ")),
      recognized = listOf(RecognizedWord("الرحمن")),
    )

    assertThat(result.matchedWords).hasSize(1)
    assertThat(result.missingWords).isEmpty()
    assertThat(result.substitutions).isEmpty()
  }

  @Test
  fun `reports a skipped word`() {
    val result = engine.align(
      expected = listOf(ExpectedWord(0, "الحمد"), ExpectedWord(1, "لله")),
      recognized = listOf(RecognizedWord("الحمد")),
    )

    assertThat(result.missingWords).containsExactly(ExpectedWord(1, "لله"))
  }

  @Test
  fun `reports an extra repeated word`() {
    val result = engine.align(
      expected = listOf(ExpectedWord(0, "الحمد")),
      recognized = listOf(RecognizedWord("الحمد"), RecognizedWord("الحمد")),
    )

    assertThat(result.unexpectedWords).containsExactly(RecognizedWord("الحمد"))
  }

  @Test
  fun `reports a substituted word`() {
    val result = engine.align(
      expected = listOf(ExpectedWord(0, "يَوْمِ")),
      recognized = listOf(RecognizedWord("قوم")),
    )

    assertThat(result.substitutions).hasSize(1)
    assertThat(result.substitutions.single().expected.text).isEqualTo("يَوْمِ")
  }

  @Test
  fun `matches Uthmani script against standard spelling`() {
    val result = engine.align(
      expected = expectedWordsOf("ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَـٰلَمِينَ"),
      recognized = recognizedWordsOf("الحمد لله رب العالمين"),
    )

    assertThat(result.matchedWords).hasSize(4)
    assertThat(result.isComplete).isTrue()
  }

  @Test
  fun `matches Uthmani spellings that differ by a letter`() {
    val result = engine.align(
      expected = expectedWordsOf(
        "ٱلَّذِينَ يُؤْمِنُونَ بِٱلْغَيْبِ وَيُقِيمُونَ ٱلصَّلَوٰةَ وَمِمَّا رَزَقْنَـٰهُمْ يُنفِقُونَ"
      ),
      recognized = recognizedWordsOf("الذين يؤمنون بالغيب ويقيمون الصلاة ومما رزقناهم ينفقون"),
    )

    assertThat(result.matchedWords).hasSize(8)
    assertThat(result.isComplete).isTrue()
  }

  @Test
  fun `ignores waqf signs in the ayah text`() {
    val expected = expectedWordsOf("ذَٰلِكَ ٱلْكِتَـٰبُ لَا رَيْبَ ۛ فِيهِ ۛ هُدًى لِّلْمُتَّقِينَ")
    val result = engine.align(
      expected = expected,
      recognized = recognizedWordsOf("ذلك الكتاب لا ريب فيه هدى للمتقين"),
    )

    assertThat(expected).hasSize(7)
    assertThat(result.isComplete).isTrue()
  }

  @Test
  fun `matches a joined Uthmani word recognized as two words`() {
    val result = engine.align(
      expected = expectedWordsOf("يَـٰٓأَيُّهَا ٱلنَّاسُ"),
      recognized = recognizedWordsOf("يا أيها الناس"),
    )

    assertThat(result.matchedWords).hasSize(2)
    assertThat(result.unexpectedWords).isEmpty()
    assertThat(result.isComplete).isTrue()
  }

  @Test
  fun `is not complete before the last word is recited`() {
    val result = engine.align(
      expected = expectedWordsOf("ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَـٰلَمِينَ"),
      recognized = recognizedWordsOf("الحمد لله رب"),
    )

    assertThat(result.isComplete).isFalse()
  }

  @Test
  fun `tolerates one missed word in a long ayah`() {
    val result = engine.align(
      expected = expectedWordsOf(
        "ٱلَّذِينَ يُؤْمِنُونَ بِٱلْغَيْبِ وَيُقِيمُونَ ٱلصَّلَوٰةَ وَمِمَّا رَزَقْنَـٰهُمْ يُنفِقُونَ"
      ),
      recognized = recognizedWordsOf("الذين يؤمنون بالغيب الصلاة ومما رزقناهم ينفقون"),
    )

    assertThat(result.missingWords).hasSize(1)
    assertThat(result.isComplete).isTrue()
  }
}

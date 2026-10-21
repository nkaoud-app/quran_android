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
      expected = listOf(ExpectedWord(0, "مالك")),
      recognized = listOf(RecognizedWord("ملك")),
    )

    assertThat(result.substitutions).hasSize(1)
    assertThat(result.substitutions.single().expected.text).isEqualTo("مالك")
  }
}

package com.quran.mobile.recitation.natlu

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DictationMatcherTest {
  private val matcher = DictationMatcher()
  private val config = NatluConfig()

  private fun match(asr: String, vararg words: String, word: Int = 0): WordMatchResult? {
    val full = words.joinToString("")
    val start = words.take(word).sumOf { it.length }
    return matcher.matchWord(asr, full, start, start + words[word].length, config)
  }

  @Test
  fun `a word recited exactly consumes its phonemes`() {
    val result = requireNotNull(match("تَبَاارَكَللَذِۦۦ", "تَبَاارَكَ", "للَذِۦۦ"))
    assertThat(result.isPartial).isFalse()
    assertThat(result.pathCost).isEqualTo(0.0)
    assertThat(result.tokensConsumed).isEqualTo("تَبَاارَكَ".length)
  }

  @Test
  fun `phonemes before the word are free`() {
    val result = requireNotNull(match("ءَعُۥۥذُتَبَاارَكَ", "تَبَاارَكَ"))
    assertThat(result.tokensConsumed).isEqualTo("ءَعُۥۥذُتَبَاارَكَ".length)
    assertThat(result.pathCost).isEqualTo(0.0)
  }

  @Test
  fun `a word still being recited waits`() {
    assertThat(match("تَبَاا", "تَبَاارَكَ")?.isPartial).isTrue()
  }

  @Test
  fun `a different word does not match`() {
    assertThat(match("قُلهُوَ", "تَبَاارَكَ")).isNull()
  }
}

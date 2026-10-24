package com.quran.mobile.recitation.natlu

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FuzzySearchTest {
  @Test
  fun `finds exact and near occurrences`() {
    val text = "abcdefghij-abXdefghij"
    val matches = findNearMatches("bcdefg", text, 1)
    assertThat(matches.map { it.start to it.dist }).containsExactly(1 to 0, 12 to 1).inOrder()
  }

  @Test
  fun `handles 64 character queries`() {
    val query = (0 until 64).joinToString("") { ('a' + it % 26).toString() }
    val text = "xyz" + query + "xyz"
    assertThat(findNearMatches(query, text, 2).map { it.start to it.dist }).containsExactly(3 to 0)
  }

  @Test
  fun `long queries use dynamic programming`() {
    val query = (0 until 80).joinToString("") { ('a' + it % 26).toString() }
    val text = "xyz" + query.replaceRange(40, 41, "#") + "xyz"
    assertThat(findNearMatches(query, text, 2).map { it.dist }).containsExactly(1)
  }
}

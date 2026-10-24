package com.quran.mobile.recitation.natlu

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Replays what Natlu's recognizer heard of Minshawi's recitation (tools/natlu/export_fixture.py)
 * through the tracker.
 */
class NatluTrackerTest {

  private val phonemes: Map<String, List<String>> =
    File("src/main/assets/natlu/quran_phonemes.txt").readLines().filter { it.isNotBlank() }.associate { line ->
      val (key, words) = line.split('\t', limit = 2)
      key to words.split(' ')
    }

  private class Replay(val ayahOf: List<Int>, val cursor: Int) {
    val recited = sortedSetOf<Int>()
    val missed = sortedSetOf<Int>()
    val moves = ArrayList<Int>()

    fun ayahWords(ayah: Int) = ayahOf.indices.filter { ayahOf[it] == ayah }
  }

  private fun replay(fixture: String): Replay {
    val lines = requireNotNull(javaClass.classLoader?.getResourceAsStream("natlu/$fixture")) { fixture }
      .bufferedReader().readLines()
    val (sura, firstAyah) = lines[0].split(' ').map { it.toInt() }
    val ayahs = generateSequence(1) { it + 1 }.map { phonemes["$sura:$it"] }.takeWhile { it != null }.filterNotNull().toList()
    val ayahOf = ayahs.flatMapIndexed { i, words -> List(words.size) { i + 1 } }
    val recited = sortedSetOf<Int>()
    val missedWords = sortedSetOf<Int>()
    val moves = ArrayList<Int>()
    val tracker = NatluTracker(ayahs.flatten(), ayahOf.indexOf(firstAyah), NatluConfig(), object : DictationSequencer.Listener {
      override fun onWord(word: Int, missed: Boolean) {
        if (missed) {
          missedWords += word
          recited -= word
        } else {
          recited += word
          missedWords -= word
        }
      }

      override fun onCursorMoved(word: Int) {
        moves += word
        recited.removeAll { it >= word }
        missedWords.removeAll { it >= word }
      }
    })
    var tokens = listOf<String>()
    for (line in lines.drop(1)) {
      val parts = line.split(' ')
      tokens = tokens.take(parts[0].toInt()) + parts.drop(1).filter { it.isNotEmpty() }
      tracker.onTokens(tokens)
    }
    return Replay(ayahOf, tracker.cursor).also {
      it.recited += recited
      it.missed += missedWords
      it.moves += moves
    }
  }

  @Test
  fun `follows al-Fatiha to its end`() {
    val replay = replay("fatiha.txt")
    assertThat(replay.recited).containsExactlyElementsIn(replay.ayahOf.indices)
    assertThat(replay.missed).isEmpty()
    assertThat(replay.cursor).isEqualTo(replay.ayahOf.size)
  }

  @Test
  fun `follows al-Mulk word by word`() {
    val replay = replay("mulk.txt")
    val ayahs1To10 = (1..10).flatMap(replay::ayahWords)
    assertThat(replay.recited).containsExactlyElementsIn(ayahs1To10)
    assertThat(replay.missed).isEmpty()
    assertThat(replay.moves).isEmpty()
    assertThat(replay.cursor).isEqualTo(ayahs1To10.last() + 1)
  }

  @Test
  fun `finds the reader again after a skipped ayah`() {
    val replay = replay("mulk-skip.txt")
    // ayah 3 was not recited; tracking re-anchors and follows ayahs 4 to 10
    assertThat(replay.recited).containsAtLeastElementsIn((4..10).flatMap(replay::ayahWords))
    assertThat(replay.recited.intersect(replay.ayahWords(3).toSet())).isEmpty()
    assertThat(replay.moves).isNotEmpty()
  }

  @Test
  fun `follows the reader back when they repeat an ayah`() {
    val replay = replay("mulk-repeat.txt")
    // back into ayah 2; its first words also start ayah 3, so the match is unique a little later
    assertThat(replay.moves).hasSize(1)
    assertThat(replay.ayahWords(2)).contains(replay.moves.single())
    assertThat(replay.recited).containsExactlyElementsIn((1..10).flatMap(replay::ayahWords))
    assertThat(replay.missed).isEmpty()
  }
}

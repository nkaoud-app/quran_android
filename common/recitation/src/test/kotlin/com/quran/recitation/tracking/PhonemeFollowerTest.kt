package com.quran.recitation.tracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Replays what the recognizer heard of Minshawi's recitation (tools/recitation-phonemes/
 * export_fixture.py) through the follower.
 */
class PhonemeFollowerTest {

  private val phonemes: Map<String, List<String>> =
    File("src/main/assets/recitation/phonemes.txt").readLines()
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .associate { line ->
        val (key, words) = line.split('\t', limit = 2)
        key to words.split(' ')
      }

  private class Replay(val ayahOf: List<Int>) {
    val recited = sortedSetOf<Int>()
    val missed = sortedSetOf<Int>()
    val everMissed = sortedSetOf<Int>()
    val wrongVowels = sortedSetOf<Int>()
    val repeats = ArrayList<Int>()
    var currentWord = -1

    fun ayahWords(ayah: Int) = ayahOf.indices.filter { ayahOf[it] == ayah }
  }

  private fun replay(fixture: String): Replay {
    val lines = requireNotNull(javaClass.classLoader?.getResourceAsStream("zipformer/$fixture")) { fixture }
      .bufferedReader().readLines()
    val (sura, firstAyah) = lines[0].split(' ').map { it.toInt() }
    val ayahs = generateSequence(1) { it + 1 }.map { phonemes["$sura:$it"] }.takeWhile { it != null }.filterNotNull().toList()
    val replay = Replay(ayahs.flatMapIndexed { i, words -> List(words.size) { i + 1 } })
    val openings = listOfNotNull(phonemes["istiadha"], phonemes["basmala"]?.takeIf { sura != 1 && sura != 9 })
    val follower = PhonemeFollower(ayahs.flatten(), replay.ayahOf.indexOf(firstAyah), openings)
    var tokens = listOf<String>()
    for (line in lines.drop(1)) {
      val parts = line.split(' ')
      tokens = tokens.take(parts[0].toInt()) + parts.drop(1).filter { it.isNotEmpty() }
      for (event in follower.accept(tokens)) {
        when (event) {
          is PhonemeFollower.Event.Recited -> {
            replay.recited += event.word
            replay.missed -= event.word
            if (event.wrongVowels) replay.wrongVowels += event.word else replay.wrongVowels -= event.word
          }
          is PhonemeFollower.Event.Missed -> {
            replay.missed += event.word
            replay.everMissed += event.word
            replay.recited -= event.word
            replay.wrongVowels -= event.word
          }
          is PhonemeFollower.Event.Skipped -> replay.missed -= event.word
          is PhonemeFollower.Event.Repeat -> {
            replay.repeats += event.word
            replay.recited.removeAll { it >= event.word }
            replay.missed.removeAll { it >= event.word }
            replay.wrongVowels.removeAll { it >= event.word }
          }
        }
      }
    }
    replay.currentWord = follower.currentWord
    return replay
  }

  @Test
  fun `follows al-Fatiha to its end`() {
    val replay = replay("fatiha.txt")
    assertThat(replay.recited).containsExactlyElementsIn(replay.ayahOf.indices)
    assertThat(replay.missed).isEmpty()
    assertThat(replay.wrongVowels).isEmpty()
    assertThat(replay.currentWord).isEqualTo(replay.ayahOf.size)
  }

  @Test
  fun `follows al-Mulk word by word`() {
    val replay = replay("mulk.txt")
    val ayahs1To10 = (1..10).flatMap(replay::ayahWords)
    assertThat(replay.recited).containsExactlyElementsIn(ayahs1To10)
    assertThat(replay.missed).isEmpty()
    assertThat(replay.wrongVowels).isEmpty()
    assertThat(replay.repeats).isEmpty()
    assertThat(replay.currentWord).isEqualTo(ayahs1To10.last() + 1)
  }

  @Test
  fun `follows the reader past a skipped ayah`() {
    val replay = replay("mulk-skip.txt")
    assertThat(replay.recited).containsAtLeastElementsIn((1..2).flatMap(replay::ayahWords) + (4..10).flatMap(replay::ayahWords))
    // ayah 3 was not recited: its words are not marked recited, nor missed
    assertThat(replay.recited.intersect(replay.ayahWords(3).toSet())).isEmpty()
    assertThat(replay.missed).isEmpty()
  }

  @Test
  fun `follows the reader back when they repeat an ayah`() {
    val replay = replay("mulk-repeat.txt")
    assertThat(replay.repeats).hasSize(1)
    assertThat(replay.ayahWords(2)).contains(replay.repeats.single())
    assertThat(replay.recited).containsExactlyElementsIn((1..10).flatMap(replay::ayahWords))
    assertThat(replay.missed).isEmpty()
  }

  /**
   * Minshawi's al-Mulk 1-3, edited: ayah 2 with its third word replaced by another word's audio,
   * then ayah 2 again correctly, then ayah 3.
   */
  @Test
  fun `a word said wrong is missed, and recited once said again`() {
    val replay = replay("mulk-mistake.txt")
    val replaced = replay.ayahWords(2)[2] // ٱلۡمَوۡتَ
    assertThat(replay.recited).containsExactlyElementsIn((1..3).flatMap(replay::ayahWords))
    assertThat(replay.missed).isEmpty()
    assertThat(replay.repeats).hasSize(1)
    assertThat(replay.everMissed).contains(replaced) // the first time
    assertThat(replay.recited).contains(replaced) // the second time
  }

  /**
   * Minshawi's al-Mulk 1-2, edited: ٱلۡمُلۡكُ in ayah 1 replaced by his ٱلۡمَلِكُ (12:43), the same
   * letters with other vowels.
   */
  @Test
  fun `a word said with other vowels is recited with wrong vowels`() {
    val replay = replay("mulk-vowel.txt")
    val replaced = replay.ayahWords(1)[3] // ٱلۡمُلۡكُ
    assertThat(replay.recited).containsExactlyElementsIn((1..2).flatMap(replay::ayahWords))
    assertThat(replay.missed).isEmpty()
    assertThat(replay.wrongVowels).containsExactly(replaced)
  }
}

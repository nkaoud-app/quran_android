package com.quran.recitation.tracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecitationTrackerTest {

  private fun resource(name: String): ByteArray =
    requireNotNull(javaClass.classLoader?.getResourceAsStream("tracker-fixture/$name")) { name }
      .use { it.readBytes() }

  private fun lines(name: String) = resource(name).decodeToString().trim().lines()
  private fun ints(name: String) = resource(name).decodeToString().trim().split(" ").map(String::toInt)

  @Test
  fun `skeleton classes match the model's output classes`() {
    assertThat(RecitationSkeleton.CLASSES.joinToString(""))
      .isEqualTo("بتثجحخدذرزسشصضطظعغفقكلمنهوي")
    assertThat(RecitationSkeleton.CLASS_COUNT).isEqualTo(28)
  }

  @Test
  fun `skeleton drops vowels, alif and hamza and merges letter variants`() {
    assertThat(RecitationSkeleton.skeleton("ٱلۡعَٰلَمِينَ")).isEqualTo("لعلمين")
    assertThat(RecitationSkeleton.skeleton("العالمين")).isEqualTo("لعلمين")
    assertThat(RecitationSkeleton.skeleton("ٱلصَّلَوٰةَ")).isEqualTo("لصلوه")
    assertThat(RecitationSkeleton.skeleton("ۛ")).isEmpty()
  }

  /**
   * The fixture holds the distilled model's streaming output for Minshawi's al-Fatiha and the
   * Python tracker's state after every frame (tools/recitation-spike/export_tracker_fixture.py).
   */
  @Test
  fun `follows al-Fatiha exactly like the Python tracker`() {
    val words = lines("words.txt")
    val phrases = lines("phrases.txt").map { it.split(" ") }
    val expectedCompleted = ints("completed.txt")
    val expectedCurrent = ints("current_word.txt")
    val buffer = ByteBuffer.wrap(resource("frames.bin")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val classes = RecitationSkeleton.CLASS_COUNT
    assertThat(buffer.remaining()).isEqualTo(expectedCompleted.size * classes)

    val tracker = RecitationTracker(words, TrackerConfig(), phrases)
    val frame = FloatArray(classes)
    val completed = ArrayList<Int>()
    val current = ArrayList<Int>()
    for (i in expectedCompleted.indices) {
      buffer.get(frame)
      tracker.push(frame)
      if (i % 500 == 0) tracker.renormalize()
      completed += tracker.completed
      current += tracker.currentWord
    }

    assertThat(completed).isEqualTo(expectedCompleted)
    assertThat(current).isEqualTo(expectedCurrent)
    assertThat(tracker.completed).isEqualTo(words.size)
  }

  @Test
  fun `opening phrases include the basmala only at a sura start`() {
    assertThat(RecitationTracker.openingPhrases(67, 1))
      .containsExactly(RecitationTracker.ISTIADHA, RecitationTracker.BASMALA).inOrder()
    assertThat(RecitationTracker.openingPhrases(67, 5)).containsExactly(RecitationTracker.ISTIADHA)
    assertThat(RecitationTracker.openingPhrases(1, 1)).containsExactly(RecitationTracker.ISTIADHA)
    assertThat(RecitationTracker.openingPhrases(9, 1)).containsExactly(RecitationTracker.ISTIADHA)
  }
}

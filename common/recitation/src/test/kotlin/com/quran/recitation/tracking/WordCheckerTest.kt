package com.quran.recitation.tracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WordCheckerTest {

  private fun resource(fixture: String, name: String): ByteArray =
    requireNotNull(javaClass.classLoader?.getResourceAsStream("$fixture/$name")) { "$fixture/$name" }
      .use { it.readBytes() }

  private fun lines(fixture: String, name: String) = resource(fixture, name).decodeToString().trim().lines()

  private fun spoken(word: String) =
    WordChecker.spokenLetters(word).joinToString(" ") { if (it.optional) "(${it.letter})" else "${it.letter}" }

  @Test
  fun `spoken letters follow pronunciation, not spelling`() {
    assertThat(spoken("ٱلدُّنۡيَا")).isEqualTo("د ن ي") // sun-letter lam is silent
    assertThat(spoken("ٱلرَّحۡمَٰنِ")).isEqualTo("ر ح م ن")
    assertThat(spoken("ٱلۡمُلۡكُ")).isEqualTo("ل م ل ك") // moon-letter lam is pronounced
    assertThat(spoken("تَرَىٰ")).isEqualTo("ت ر (ي)") // alif maqsura is a long a
    assertThat(spoken("فِيهَا")).isEqualTo("ف (ي) ه") // lengthened vowel
    assertThat(spoken("عَلَيۡهِمۡ")).isEqualTo("ع ل ي ه م") // a pronounced ي stays
    assertThat(spoken("يُؤۡمِنُونَ")).isEqualTo("ي (و) م ن (و) ن") // hamza seat is optional
    assertThat(spoken("وَلِلَّذِينَ")).isEqualTo("و ل ل ذ (ي) ن")
  }

  @Test
  fun `spacing characters inside a word are ignored`() {
    assertThat(spoken("ذَ ٰ⁠لِكَ")).isEqualTo("ذ ل ك")
  }

  /** Word checks for Minshawi's al-Fatiha must match the Python spike (spike/mistakes.py). */
  @Test
  fun `checks al-Fatiha exactly like the Python checker`() {
    replayAndCompare("tracker-fixture")
  }

  /**
   * Minshawi's al-Mulk 1-3, edited: ayah 2 with its third word replaced by another word's audio,
   * then ayah 2 again correctly, then ayah 3. The word is unclear at first and clear after the
   * repeat, exactly as in the Python checker.
   */
  @Test
  fun `a word recited again correctly becomes clear`() {
    val checker = replayAndCompare("tracker-fixture-repeat")
    val words = lines("tracker-fixture-repeat", "words.txt")
    val replaced = words.indexOf("ٱلۡمَوۡتَ")
    assertThat(checker.check(replaced)!!.deficit).isWithin(1e-6).of(0.0)
  }

  private fun replayAndCompare(fixture: String): WordChecker {
    val words = lines(fixture, "words.txt")
    val phrases = lines(fixture, "phrases.txt").map { it.split(" ") }
    val expected = lines(fixture, "deficits.txt").map { line ->
      val (deficit, worst) = line.split(" ")
      deficit.toDouble() to worst.toInt()
    }
    val buffer = ByteBuffer.wrap(resource(fixture, "frames.bin")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val tracker = RecitationTracker(words, TrackerConfig(), phrases)
    val checker = WordChecker(tracker, words)

    var frameIndex = 0
    while (buffer.hasRemaining()) {
      val frame = FloatArray(RecitationSkeleton.CLASS_COUNT)
      buffer.get(frame)
      tracker.push(frame)
      if (frameIndex++ % 500 == 0) tracker.renormalize()
      checker.push(frame)
    }

    assertThat(expected).hasSize(words.size)
    expected.forEachIndexed { w, (deficit, worst) ->
      val check = requireNotNull(checker.check(w)) { "word $w not checked" }
      assertThat(check.deficit).isWithin(1e-6).of(deficit)
      if (deficit < -1e-9) assertThat(check.worstLetter).isEqualTo(worst)
    }
    return checker
  }
}

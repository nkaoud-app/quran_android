package com.quran.recitation.tracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WordCheckerTest {

  private fun resource(name: String): ByteArray =
    requireNotNull(javaClass.classLoader?.getResourceAsStream("tracker-fixture/$name")) { name }
      .use { it.readBytes() }

  private fun lines(name: String) = resource(name).decodeToString().trim().lines()

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
    val words = lines("words.txt")
    val phrases = lines("phrases.txt").map { it.split(" ") }
    val expected = lines("deficits.txt").map { line ->
      val (deficit, worst) = line.split(" ")
      deficit.toDouble() to worst.toInt()
    }
    val buffer = ByteBuffer.wrap(resource("frames.bin")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
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
  }
}

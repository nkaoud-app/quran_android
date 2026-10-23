package com.quran.recitation.tracking

/** A consonant of a word as pronounced; [optional] letters may go unheard (vowel letters, hamza). */
data class SpokenLetter(val letter: Char, val classId: Int, val optional: Boolean)

/** How well a recited word matched what was expected. */
data class WordCheck(
  /** 0 when the model heard exactly the expected letters; more negative the more it differed. */
  val deficit: Double,
  /** Index into the word's [SpokenLetter]s of the letter that matched worst. */
  val worstLetter: Int,
)

/**
 * Checks recited words for possible mistakes ("goodness of pronunciation").
 *
 * Once the tracker confirms a word, the frames from shortly before it reached the word until it
 * confirmed it are aligned twice: freely (the model's best class per frame) and to the word's
 * letters as pronounced. The difference is the word's deficit. The phone model sometimes mishears
 * a letter in correct recitation, so a low deficit means "unclear", not a certain mistake.
 *
 * When the reader goes back over checked words, each is checked again as the reader passes it and
 * keeps its better attempt, so a word said wrong and then recited again correctly ends up clear.
 *
 * This is a port of tools/recitation-spike/spike/mistakes.py and must stay in step with it.
 */
class WordChecker(
  private val tracker: RecitationTracker,
  words: List<String>,
  private val skipPenalty: Double = 4.0,
) {
  private val spoken: List<List<SpokenLetter>> = words.map(::spokenLetters)
  private val reached = IntArray(words.size)
  private var reachedCount = 0
  private val checks = arrayOfNulls<WordCheck>(words.size)
  private var checkedCount = 0
  // Checked word -> frame the reader went back to (or before) it; -1 when not being recited again.
  private val revisitStart = IntArray(words.size) { -1 }
  private var revisiting = 0

  // Frames from absolute index `firstFrame` onwards; older ones are no longer needed.
  private val frames = ArrayDeque<FloatArray>()
  private var firstFrame = 0
  private var frameCount = 0

  fun check(word: Int): WordCheck? = checks.getOrNull(word)

  fun spokenLetters(word: Int): List<SpokenLetter> = spoken[word]

  /** Call with each frame right after the tracker consumed it; returns the words (re)checked now. */
  fun push(frame: FloatArray): List<Int> {
    val i = frameCount++
    frames.addLast(frame)
    val reachedUpTo = minOf(tracker.currentWord, spoken.size - 1)
    while (reachedCount <= reachedUpTo) reached[reachedCount++] = i

    val checked = ArrayList<Int>()
    while (checkedCount < tracker.completed) {
      val w = checkedCount++
      if (reachedCount <= w) reached[reachedCount++] = i
      checks[w] = score(w, reached[w], i)
      checked += w
    }

    // Going back: checked words at or after the reader's position are being recited again.
    val current = tracker.currentWord
    for (w in current until checkedCount) {
      if (revisitStart[w] < 0) {
        revisitStart[w] = i
        revisiting++
      }
    }
    var oldestRevisit = Int.MAX_VALUE
    if (revisiting > 0) {
      for (w in 0 until checkedCount) {
        val start = revisitStart[w]
        if (start < 0) continue
        if (current > w) { // passed it again
          revisitStart[w] = -1
          revisiting--
          val again = score(w, start, i)
          if (again.deficit > checks[w]!!.deficit) checks[w] = again
          checked += w
        } else {
          oldestRevisit = minOf(oldestRevisit, start)
        }
      }
    }

    // Keep only what the words still to be (re)checked may need.
    val nextStart = if (checkedCount < reachedCount) reached[checkedCount] else i
    val keepFrom = minOf(nextStart, oldestRevisit) - MARGIN
    while (firstFrame < keepFrom && frames.isNotEmpty()) {
      frames.removeFirst()
      firstFrame++
    }
    return checked
  }

  private fun score(word: Int, start: Int, end: Int): WordCheck {
    val letters = spoken[word]
    if (letters.isEmpty()) return WordCheck(0.0, 0)
    val span = (maxOf(0, start - MARGIN)..end).map { frames[it - firstFrame] }
    return wordDeficit(span, letters, skipPenalty)
  }

  companion object {
    /** Frames before the tracker reached a word that still count as the word's audio. */
    private const val MARGIN = 30

    private val MARKS: Set<Char> =
      ('ً'..'ٟ').toSet() + 'ٰ' + ('ۖ'..'ۭ').toSet()
    private const val SHADDA = 'ّ'

    /** Spacing characters inside some words of the Quran text. */
    private val INVISIBLE = setOf(' ', '​', '‌', '‍', '⁠')

    /**
     * The word's consonant skeleton as pronounced. Tajweed differs from spelling: a sun-letter lam
     * (ٱل before a doubled letter) is silent; ى is a long a; ؤ and ئ carry a hamza; a و or ي with
     * no vowel mark of its own is a lengthened vowel. Silent letters are dropped, the others are
     * optional because the model may not emit them as letters.
     */
    fun spokenLetters(word: String): List<SpokenLetter> {
      val units = ArrayList<Pair<Char, String>>() // base character and its marks
      for (c in word) {
        if (c in INVISIBLE) continue
        if (c in MARKS && units.isNotEmpty()) {
          units[units.size - 1] = units.last().let { it.first to it.second + c }
        } else {
          units += c to ""
        }
      }
      val letters = ArrayList<SpokenLetter>()
      units.forEachIndexed { i, (base, marks) ->
        val tokens = RecitationSkeleton.tokens(base.toString())
        if (tokens.isEmpty()) return@forEachIndexed
        val previous = units.getOrNull(i - 1)?.first
        val followingMarks = units.getOrNull(i + 1)?.second.orEmpty()
        if (base == 'ل' && previous == 'ٱ' && marks.isEmpty() && SHADDA in followingMarks) {
          return@forEachIndexed // sun-letter lam: silent
        }
        val optional = base in "ىؤئ" || (base in "وي" && marks.isEmpty())
        letters += SpokenLetter(RecitationSkeleton.skeleton(base.toString())[0], tokens[0], optional)
      }
      return letters
    }

    /**
     * Local CTC alignment of [frames] (log-probabilities): free frames before, the word's letters
     * in order (a blank between repeated letters, a letter skipped at [skipPenalty] unless
     * optional), free frames after; compared with aligning every frame freely.
     */
    fun wordDeficit(frames: List<FloatArray>, letters: List<SpokenLetter>, skipPenalty: Double = 4.0): WordCheck {
      val tokens = letters.size
      val n = 2 * tokens + 1
      val emission = IntArray(n).also { e -> letters.forEachIndexed { k, l -> e[2 * k + 1] = l.classId } }
      val optionalFirst = letters.first().optional
      val optionalLast = letters.last().optional && n >= 4
      // States: 0 = free before, 1..n = CTC states of the word, n + 1 = free after.
      val size = n + 2
      var delta = DoubleArray(size) { NEG }
      val back = Array(frames.size) { IntArray(size) }
      val free = DoubleArray(frames.size)

      for (t in frames.indices) {
        val frame = frames[t]
        var frameBest = Double.NEGATIVE_INFINITY
        for (x in frame) frameBest = maxOf(frameBest, x.toDouble())
        free[t] = frameBest
        val prev = delta
        val best = DoubleArray(size) { NEG }
        val arg = back[t]

        fun offer(target: Int, source: Int, penalty: Double = 0.0) {
          // Source -1 is "start of the span": only possible on the first frame.
          val value = (if (source == -1) (if (t == 0) 0.0 else NEG) else (if (t > 0) prev[source] else NEG)) - penalty
          if (value > best[target]) {
            best[target] = value
            arg[target] = source
          }
        }

        for (j in 0 until size) {
          when {
            j == 0 -> {
              offer(0, 0)
              offer(0, -1)
            }
            j == n + 1 -> {
              offer(j, j)
              offer(j, n)
              offer(j, n - 1)
              if (optionalLast) {
                offer(j, n - 2) // stop before an optional last letter
                offer(j, n - 3)
              }
            }
            else -> {
              offer(j, j)
              offer(j, j - 1)
              if (j == 1 || j == 2 || ((j == 3 || j == 4) && optionalFirst)) {
                offer(j, -1)
                offer(j, 0)
              }
              if (j % 2 == 0 && j >= 3 && emission[j - 1] != emission[j - 3]) offer(j, j - 2)
              if (j % 2 == 0 && j >= 5) {
                val skipped = j / 2 - 2
                val cost = if (letters[skipped].optional) 0.0 else skipPenalty
                offer(j, j - 4, cost)
                offer(j, j - 3, cost)
              }
            }
          }
        }
        for (j in 0 until size) {
          best[j] += if (j == 0 || j == n + 1) frameBest else frame[emission[j - 1]].toDouble()
        }
        delta = best
      }

      val ends = listOf(n + 1, n, n - 1) + if (optionalLast) listOf(n - 2, n - 3) else emptyList()
      var end = ends[0]
      for (candidate in ends) if (delta[candidate] > delta[end]) end = candidate
      val deficit = delta[end] - free.sum()

      // Per letter: the deficit of the frames aligned to it; letters skipped cost the skip penalty.
      val perLetter = DoubleArray(tokens)
      val visited = BooleanArray(tokens)
      var state = end
      for (t in frames.indices.reversed()) {
        if (state in 1..n && state % 2 == 0) {
          val k = state / 2 - 1
          perLetter[k] += frames[t][emission[state - 1]] - free[t]
          visited[k] = true
        }
        state = back[t][state]
        if (state == -1) break
      }
      for (k in 0 until tokens) if (!visited[k]) perLetter[k] -= skipPenalty
      var worst = 0
      for (k in 1 until tokens) if (perLetter[k] < perLetter[worst]) worst = k
      return WordCheck(deficit, worst)
    }

    private const val NEG = -1e18
  }
}

package com.quran.recitation.tracking

import kotlin.math.max

data class TrackerConfig(
  /** Per-frame score of the filler state (audio before the text), below the frame's best class. */
  val fillerPenalty: Double = 1.5,
  /** Frames (20 ms each) the best path must stay past a word before it counts as recited. */
  val stableFrames: Int = 5,
  /** Cost of jumping back to the start of an earlier word, e.g. when repeating a phrase. */
  val jumpBackPenalty: Double = 10.0,
  /** Cost of an expected letter the model never heard (weak letters such as a long ي). */
  val skipLetterPenalty: Double = 1.0,
)

/**
 * Follows a reader through a known sequence of [words], one acoustic model frame at a time.
 *
 * Every frame extends an online Viterbi forced alignment of the model's CTC frame scores against
 * the consonant skeleton of the words, so pauses and elongated vowels simply stay in the current
 * state however long they last. The reader may first recite any of [optionalPhrases] (e.g. the
 * ta'awwudh and basmala), may go back to an earlier word, and may have a letter go unheard.
 *
 * This is a port of tools/recitation-spike/spike/tracker.py and must stay in step with it.
 */
class RecitationTracker(
  words: List<String>,
  private val config: TrackerConfig = TrackerConfig(),
  optionalPhrases: List<List<String>> = emptyList(),
) {
  /** Number of words recited so far (the furthest the reader got). */
  var completed: Int = 0
    private set

  /** Index of the word the reader is currently on. */
  var currentWord: Int = 0
    private set

  private val stateCount: Int
  private val textStart: Int
  private val emission: IntArray
  private val canSkipBlank: BooleanArray
  private val lastStateOfWord: IntArray
  private val entries: IntArray
  private val jumpSources: IntArray
  private val jumpTargets: IntArray
  private val wordStarts: IntArray

  private var delta: DoubleArray
  private var next: DoubleArray
  private val later: DoubleArray
  private var filler = 0.0
  private var stable = 0

  init {
    // CTC states: blank, t1, blank, t2, ..., blank. Phrases and then the text are chained in order.
    val tokens = ArrayList<Int>()
    val segmentStarts = ArrayList<Int>()
    val phraseEnds = ArrayList<Int>()
    for (phrase in optionalPhrases) {
      segmentStarts += 2 * tokens.size
      phrase.forEach { tokens += RecitationSkeleton.tokens(it).toList() }
      phraseEnds += 2 * tokens.size
    }
    textStart = 2 * tokens.size
    segmentStarts += textStart
    lastStateOfWord = IntArray(words.size)
    words.forEachIndexed { w, word ->
      tokens += RecitationSkeleton.tokens(word).toList()
      lastStateOfWord[w] = 2 * tokens.size // the blank after the word's last letter
    }

    stateCount = 2 * tokens.size + 1
    emission = IntArray(stateCount)
    tokens.forEachIndexed { k, token -> emission[2 * k + 1] = token }
    // A letter may skip the blank before it unless it repeats the previous letter.
    canSkipBlank = BooleanArray(stateCount) { j -> j >= 3 && j % 2 == 1 && emission[j] != emission[j - 2] }
    entries = segmentStarts.flatMap { listOf(it, it + 1) }.filter { it < stateCount }.distinct().sorted().toIntArray()
    // The end of any phrase (its last letter or the blank after it) may go straight to the text.
    val jumps = phraseEnds.flatMap { end ->
      listOf(end - 1, end).flatMap { source -> listOf(source to textStart, source to textStart + 1) }
    }.filter { (source, target) -> source in 0 until stateCount && target < stateCount }
    jumpSources = jumps.map { it.first }.toIntArray()
    jumpTargets = jumps.map { it.second }.toIntArray()
    // Where reciting a word starts: the blank before its first letter, and that letter.
    wordStarts = words.indices.flatMap { w ->
      val start = if (w == 0) textStart else lastStateOfWord[w - 1]
      if (lastStateOfWord[w] > start) listOf(start, start + 1) else emptyList()
    }.toIntArray()

    delta = DoubleArray(stateCount) { Double.NEGATIVE_INFINITY }
    next = DoubleArray(stateCount)
    later = DoubleArray(stateCount)
  }

  /** Consumes one frame of class log-probabilities and returns the number of completed words. */
  fun push(frame: FloatArray): Int {
    val prev = delta
    val best = next
    val skipLetter = config.skipLetterPenalty
    for (j in 0 until stateCount) {
      var b = prev[j]
      if (j >= 1) b = max(b, prev[j - 1])
      if (canSkipBlank[j]) b = max(b, prev[j - 2])
      if (j >= 5 && j % 2 == 1) b = max(b, max(prev[j - 4], prev[j - 3]) - skipLetter)
      best[j] = b
    }
    for (i in jumpSources.indices) {
      best[jumpTargets[i]] = max(best[jumpTargets[i]], prev[jumpSources[i]])
    }
    for (entry in entries) best[entry] = max(best[entry], filler)
    if (wordStarts.isNotEmpty()) {
      // Best score of any later state, for jumping back to an earlier word's start.
      var running = Double.NEGATIVE_INFINITY
      for (j in stateCount - 1 downTo 0) {
        running = max(running, prev[j])
        later[j] = running
      }
      for (start in wordStarts) {
        if (start + 2 < stateCount) best[start] = max(best[start], later[start + 2] - config.jumpBackPenalty)
      }
    }
    var frameBest = Double.NEGATIVE_INFINITY
    for (score in frame) frameBest = max(frameBest, score.toDouble())
    var position = 0
    for (j in 0 until stateCount) {
      best[j] += frame[emission[j]].toDouble()
      if (best[j] > best[position]) position = j
    }
    delta = best
    next = prev
    filler += frameBest - config.fillerPenalty

    if (filler > delta[position] || position < textStart) return completed // still before the text
    currentWord = countAtOrBelow(lastStateOfWord, position)
    var target = completed
    while (target < lastStateOfWord.size && position >= lastStateOfWord[target]) target++
    if (target > completed) {
      if (++stable >= config.stableFrames) {
        completed = target
        stable = 0
      }
    } else {
      stable = 0
    }
    return completed
  }

  /** Keeps scores in a numerically comfortable range on long recitations. */
  fun renormalize() {
    var shift = filler
    for (score in delta) shift = max(shift, score)
    for (j in delta.indices) delta[j] -= shift
    filler -= shift
  }

  private fun countAtOrBelow(sorted: IntArray, value: Int): Int {
    var low = 0
    var high = sorted.size
    while (low < high) {
      val mid = (low + high) ushr 1
      if (sorted[mid] <= value) low = mid + 1 else high = mid
    }
    return low
  }

  companion object {
    val ISTIADHA = "أَعُوذُ بِٱللَّهِ مِنَ ٱلشَّيۡطَٰنِ ٱلرَّجِيمِ".split(" ")
    val BASMALA = "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ".split(" ")

    /** Phrases a reader may say before the ayah text: ta'awwudh, then basmala at a sura start. */
    fun openingPhrases(sura: Int, firstAyah: Int): List<List<String>> =
      if (firstAyah == 1 && sura != 1 && sura != 9) listOf(ISTIADHA, BASMALA) else listOf(ISTIADHA)
  }
}

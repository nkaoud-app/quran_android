package com.quran.recitation.tracking

import kotlin.math.min

/** Costs and thresholds of [PhonemeFollower]. Costs are per phoneme, 1 being a plain mistake. */
data class FollowerConfig(
  /** Per phoneme heard before the reader starts (isti'adha, basmala, noise). */
  val noiseCost: Double = 0.6,
  /** An extra phoneme heard. */
  val insertionCost: Double = 1.0,
  /** The same phoneme heard again (a prolonged sound). */
  val repeatInsertionCost: Double = 0.3,
  /** An expected phoneme not heard. */
  val deletionCost: Double = 1.0,
  /** An expected phoneme repeating the one before it not heard (madd, shadda, ghunna lengths). */
  val repeatDeletionCost: Double = 0.3,
  /** A phoneme heard as a different one. */
  val substitutionCost: Double = 1.0,
  /** A phoneme heard as one that sounds alike (a long and short vowel, emphatic pairs). */
  val similarCost: Double = 0.5,
  /** Going back to the start of an earlier word (repeating). */
  val jumpBackCost: Double = 6.0,
  /** Going ahead to the start of a later word, past words not heard (skipping). */
  val jumpAheadCost: Double = 12.0,
  /** Words passed at once beyond which they count as skipped rather than missed. */
  val maxMissedRun: Int = 3,
  /** Words the reader must go back for it to count as reciting them again. */
  val minRepeat: Int = 2,
  /** Average cost per phoneme up to which a word counts as recited. */
  val maxWordCost: Double = 0.35,
)

/**
 * Follows a reader through a sura word by word from the phonemes a streaming recognizer hears
 * (Quran-Lab's zipformer_p-arabic-v3 run by sherpa-onnx; the phonemes of quran-transcript).
 *
 * Every phoneme heard updates an edit-distance alignment of everything heard so far against the
 * sura's phonemes: one cost per position in the sura (the phonemes recited up to there). The
 * reader is at the cheapest position. Before they start, phonemes are noise or one of the
 * [openings] (isti'adha, basmala), which they may say or skip; from any position
 * they may go back to the start of an earlier word or ahead to the start of a later one, at a
 * fixed cost, so repeats and skips are followed.
 *
 * When the reader moves past a word, it is checked against what was heard while they were on it:
 * close enough and it is [Event.Recited], otherwise [Event.Missed]. Words passed in a jump ahead
 * are left alone (skipped), apart from the last few before it, which the reader may already have
 * said; words thought missed just before the jump were skipped too. Going back over words clears
 * them so they are checked again (the few just before where the reader went back too, against
 * what was just heard).
 */
class PhonemeFollower(
  private val words: List<String>,
  private val startWord: Int,
  openings: List<List<String>> = emptyList(),
  private val config: FollowerConfig = FollowerConfig(),
) {

  sealed class Event {
    /** The reader recited [word]. */
    data class Recited(val word: Int) : Event()

    /** The reader passed [word] without saying it right. */
    data class Missed(val word: Int) : Event()

    /** The reader went back to [word]; words from it on are being recited again. */
    data class Repeat(val word: Int) : Event()

    /** [word], first thought missed, was skipped (the reader jumped ahead): no longer marked. */
    data class Skipped(val word: Int) : Event()
  }

  // The openings, then the sura; positions in the openings are before the reader starts.
  private val openingPhrases = openings.map { it.joinToString("") }.filter { it.isNotEmpty() }
  private val suraOffset = openingPhrases.sumOf { it.length }
  private val reference: String = openingPhrases.joinToString("") + words.joinToString("")
  private val wordStarts = IntArray(words.size + 1).also { starts ->
    var offset = suraOffset
    words.forEachIndexed { i, w ->
      starts[i] = offset
      offset += w.length
    }
    starts[words.size] = offset
  }
  private val positions = reference.length + 1

  private var cost = DoubleArray(positions) { INFINITY }
  private var next = DoubleArray(positions)
  private var noise = 0.0
  private val startPosition = wordStarts[startWord.coerceIn(0, words.size)]

  // Where an opening phrase starts and ends; after one, the reader may say a later one or start.
  private val phraseStarts = IntArray(openingPhrases.size)
  private val phraseEnds = IntArray(openingPhrases.size)

  init {
    var offset = 0
    openingPhrases.forEachIndexed { i, phrase ->
      phraseStarts[i] = offset
      offset += phrase.length
      phraseEnds[i] = offset
    }
  }

  // What was heard, from absolute index `heardOffset`.
  private val heard = StringBuilder()
  private var heardOffset = 0
  private var heardCount = 0
  private var lastHeard = 0.toChar()

  // The recognizer's tokens already taken in.
  private var tokensTaken = 0

  /** Where the reader is: the word whose next phoneme is expected (-1 before they start). */
  var currentWord = -1
    private set

  /** When the reader came to each word they are on or passed in this attempt (heard index). */
  private val reachedAt = HashMap<Int, Int>()

  private val events = ArrayList<Event>()

  // Since the last word recited: when that was (heard index), and the words thought missed.
  private var lastRecitedAt = 0
  private val missedSince = ArrayList<Int>()

  /**
   * Takes all tokens the recognizer produced since it was last reset; returns what happened since
   * the previous call.
   */
  fun accept(tokens: List<String>): List<Event> {
    if (tokens.size < tokensTaken) tokensTaken = 0 // the recognizer started over
    for (i in tokensTaken until tokens.size) {
      val token = tokens[i]
      if (token.startsWith("<")) continue // blank and other special tokens
      for (c in token) hear(c)
    }
    tokensTaken = tokens.size
    update()
    return events.toList().also { events.clear() }
  }

  /** After the recognizer was reset: its tokens start over. */
  fun onRecognizerReset() {
    tokensTaken = 0
  }

  private fun hear(c: Char) {
    val insertion = if (c == lastHeard) config.repeatInsertionCost else config.insertionCost
    lastHeard = c
    heard.append(c)
    heardCount++

    // Before the reader starts, they may say an opening or start at the start word at any time.
    cost[startPosition] = min(cost[startPosition], noise)
    for (p in phraseStarts) cost[p] = min(cost[p], noise)
    noise += config.noiseCost

    val prev = cost
    val now = next
    now[0] = prev[0] + insertion
    for (j in 1 until positions) {
      val inserted = prev[j] + insertion
      val matched = prev[j - 1] + substitution(c, reference[j - 1])
      now[j] = if (matched <= inserted) matched else inserted
    }
    jumps(now)
    for (j in 1 until positions) {
      val deleted = now[j - 1] + deletion(j - 1)
      if (deleted < now[j]) now[j] = deleted
    }
    // After an opening: a later opening or the start word.
    for (i in phraseEnds.indices) {
      val done = now[phraseEnds[i]]
      for (k in i + 1 until phraseStarts.size) if (done < now[phraseStarts[k]]) now[phraseStarts[k]] = done
      if (done < now[startPosition]) now[startPosition] = done
    }
    // Keep costs small; only differences matter.
    var lowest = noise
    for (value in now) if (value < lowest) lowest = value
    for (j in now.indices) now[j] -= lowest
    noise -= lowest
    cost = now
    next = prev
  }

  /** Going back to an earlier word's start, or ahead to a later one's (in the sura). */
  private fun jumps(now: DoubleArray) {
    // ahead: the cheapest position before each word start
    var before = INFINITY
    var w = 0
    for (j in suraOffset until positions) {
      if (w < words.size && j == wordStarts[w]) {
        val jumped = before + config.jumpAheadCost
        if (jumped < now[j]) now[j] = jumped
        w++
      }
      if (now[j] < before) before = now[j]
    }
    // back: the cheapest position after each word start
    var after = INFINITY
    w = words.size - 1
    for (j in positions - 1 downTo suraOffset) {
      if (w >= 0 && j == wordStarts[w]) {
        val jumped = after + config.jumpBackCost
        if (jumped < now[j]) now[j] = jumped
        w--
      }
      if (now[j] < after) after = now[j]
    }
  }

  private fun update() {
    var best = 0
    for (j in 1 until positions) if (cost[j] <= cost[best]) best = j // ties: further on
    if (noise < cost[best] || best < suraOffset) return // not started yet (or saying an opening)
    val word = wordAt(best)
    // On starting, the words from the start word on were recited since the beginning.
    val previous = if (currentWord < 0) startWord.also { reachedAt[it] = heardOffset } else currentWord
    currentWord = word
    when {
      word > previous && word - previous <= config.maxMissedRun -> {
        for (w in previous until word) {
          if (check(w, reachedAt[w])) recited(w) else missed(w)
          reachedAt.remove(w)
        }
        reachedAt[word] = heardCount
      }
      word > previous -> {
        // A jump ahead: the words passed are skipped, but the reader may already have said the
        // last few before the jump was clear.
        for (w in missedSince) events += Event.Skipped(w)
        missedSince.clear()
        // Only what was heard after the last word recited (which may sound like these).
        val since = lastRecitedAt
        for (w in maxOf(previous, word - JUMP_LOOKBACK) until word) {
          if (check(w, since, margin = 0)) recited(w)
        }
        reachedAt.clear()
        reachedAt[word] = heardCount
      }
      word <= previous - config.minRepeat -> {
        events += Event.Repeat(word)
        missedSince.clear()
        // The jump back is clear only some way into the repeat (repeated passages often start
        // alike): the last few words before it may already have been said again.
        val recent = (maxOf(0, word - JUMP_LOOKBACK) until word).sumOf { words[it].length } * 2 + MARGIN
        for (w in maxOf(0, word - JUMP_LOOKBACK) until word) {
          if (check(w, heardCount - recent, margin = 0)) recited(w)
        }
        reachedAt.clear()
        reachedAt[word] = heardCount
      }
      else -> reachedAt.putIfAbsent(word, heardCount)
    }
    trimHeard()
  }

  private fun recited(word: Int) {
    events += Event.Recited(word)
    lastRecitedAt = heardCount
    missedSince.clear()
  }

  private fun missed(word: Int) {
    events += Event.Missed(word)
    missedSince += word
  }

  /** The word whose next phoneme is expected at sura [position] (words.size after the last). */
  private fun wordAt(position: Int): Int {
    var low = 0
    var high = words.size
    while (low < high) {
      val mid = (low + high) ushr 1
      if (wordStarts[mid + 1] <= position) low = mid + 1 else high = mid
    }
    return low
  }

  /**
   * Whether what was heard since [reached] (when the reader came to [word], less [margin]
   * phonemes) contains it, close enough.
   */
  private fun check(word: Int, reached: Int?, margin: Int = MARGIN): Boolean {
    val expected = words[word]
    if (expected.isEmpty()) return true
    val from = maxOf(heardOffset, (reached ?: heardCount) - margin)
    val span = heard.substring(from - heardOffset)
    return alignedCost(span, word) / expected.length <= config.maxWordCost
  }

  /**
   * The cheapest alignment of [word]'s phonemes to part of [span] (anything before and after it
   * free), with the same costs as following.
   */
  private fun alignedCost(span: String, word: Int): Double {
    val start = wordStarts[word]
    val n = words[word].length
    var column = DoubleArray(n + 1)
    var nextColumn = DoubleArray(n + 1)
    for (k in 1..n) column[k] = column[k - 1] + deletion(start + k - 1)
    var best = column[n]
    var last = 0.toChar()
    for (c in span) {
      val insertion = if (c == last) config.repeatInsertionCost else config.insertionCost
      last = c
      nextColumn[0] = 0.0 // free start
      for (k in 1..n) {
        nextColumn[k] = min(
          min(column[k] + insertion, column[k - 1] + substitution(c, reference[start + k - 1])),
          nextColumn[k - 1] + deletion(start + k - 1),
        )
      }
      if (nextColumn[n] < best) best = nextColumn[n] // free end
      column = nextColumn.also { nextColumn = column }
    }
    return best
  }

  private fun trimHeard() {
    val keepFrom = (reachedAt.values.minOrNull() ?: heardCount) - MARGIN
    val trim = keepFrom - heardOffset
    if (trim > KEEP) {
      heard.delete(0, trim)
      heardOffset += trim
    }
  }

  private fun deletion(refIndex: Int): Double {
    val c = reference[refIndex]
    return when {
      c == QALQALA -> config.repeatDeletionCost
      refIndex > 0 && reference[refIndex - 1] == c -> config.repeatDeletionCost
      else -> config.deletionCost
    }
  }

  private fun substitution(heard: Char, expected: Char): Double = when {
    heard == expected || canonical(heard) == canonical(expected) -> 0.0
    similar(heard, expected) -> config.similarCost
    else -> config.substitutionCost
  }

  private companion object {
    const val INFINITY = 1e18
    /** Phonemes heard before the reader came to a word that may still be part of it. */
    const val MARGIN = 8
    /** Words before the reader's position checked after a jump ahead. */
    const val JUMP_LOOKBACK = 4
    /** Heard phonemes no longer needed are dropped in batches of this many. */
    const val KEEP = 256
    const val QALQALA = 'چ' // ڇ

    /** Glyphs of the phonetic script for the same sound. */
    fun canonical(c: Char): Char = when (c) {
      'ۦ' -> 'ي' // ۦ ي
      'ۥ' -> 'و' // ۥ و
      'ں' -> 'ن' // ں ن
      '۾' -> 'م' // ۾ م
      'أ', 'إ', 'آ', 'ٲ' -> 'ء' // hamza forms
      else -> c
    }

    private val SIMILAR = listOf(
      "َا", // fatha, alif
      "ُو", // damma, waw
      "ِي", // kasra, ya
      "سص", // س ص
      "تط", // ت ط
      "دض", // د ض
      "ذظز", // ذ ظ ز
      "قك", // ق ك
      "هح", // ه ح
      "ءع", // ء ع
    ).map { group -> group.map(::canonical).toSet() }

    fun similar(a: Char, b: Char): Boolean {
      val x = canonical(a)
      val y = canonical(b)
      return SIMILAR.any { x in it && y in it }
    }
  }
}

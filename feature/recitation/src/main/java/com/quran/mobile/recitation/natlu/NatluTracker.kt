package com.quran.mobile.recitation.natlu

import kotlin.math.min

/**
 * Feeds the recognizer's phoneme tokens to a [DictationSequencer], as recite_quran's
 * AsrTokenProcessor and HighlightingController do (see NATLU_NOTICE.md).
 *
 * [words] are the sura's words as phonemes; tracking starts at [startWord].
 */
class NatluTracker(
  words: List<String>,
  startWord: Int,
  config: NatluConfig,
  listener: DictationSequencer.Listener,
) {
  private val sequencer = DictationSequencer(listener).apply {
    this.config = config
    setReference(words, startWord)
  }

  private var lastRawTokens: List<String> = emptyList()
  private val text = StringBuilder()
  private var lastProcessedText = ""
  private var expectingNewSegment = true

  /** The next word expected. */
  val cursor: Int get() = sequencer.cursor

  /**
   * Takes all tokens the recognizer produced since it was last reset. Returns true when the
   * recognizer should be reset (its result got too long); call [onRecognizerReset] after.
   */
  fun onTokens(tokens: List<String>): Boolean {
    // Streaming CTC tokens only grow; if earlier ones changed, start over.
    var common = 0
    val shared = min(lastRawTokens.size, tokens.size)
    while (common < shared && lastRawTokens[common] == tokens[common]) common++
    if (common < lastRawTokens.size) {
      text.setLength(0)
      common = 0
    }
    for (i in common until tokens.size) {
      val token = tokens[i]
      if (token.isEmpty() || token in BLANKS) continue
      text.append(token)
    }
    lastRawTokens = tokens.toList()

    if (tokens.size > MAX_TOKENS) return true
    if (text.isEmpty()) {
      lastProcessedText = ""
      return false
    }
    val current = text.toString()
    val isNewSegment = expectingNewSegment
    expectingNewSegment = false
    if (!isNewSegment && current == lastProcessedText) return false
    lastProcessedText = current
    sequencer.syncStream(current, isNewSegment)
    return false
  }

  /** After the recognizer was reset: its tokens start over. */
  fun onRecognizerReset() {
    lastRawTokens = emptyList()
    text.setLength(0)
    lastProcessedText = ""
    expectingNewSegment = true
  }

  /** Continues from [word] with fresh recognizer output (reset the recognizer too). */
  fun jumpToWord(word: Int) {
    sequencer.jumpToWord(word)
    onRecognizerReset()
  }

  private companion object {
    val BLANKS = setOf("<blank>", "<blk>", "<eps>", "eps")
    const val MAX_TOKENS = 8000
  }
}

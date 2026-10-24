package com.quran.mobile.recitation.natlu

/**
 * How strictly recited phonemes must match the text (recite_quran's MatchingStrictness).
 * Ported from recite_quran's lib/tracking/tracker_config.dart; see NATLU_NOTICE.md.
 */
enum class MatchingStrictness(
  /** Base acceptance error threshold (edit cost per reference phoneme). */
  val defaultMaxPathCost: Double,
  /** Threshold for short words (<= 3 phonemes). */
  val shortWordPathCost: Double,
  /** Threshold for medium words (<= 7 phonemes). */
  val mediumWordPathCost: Double,
  /** Words that may be skipped (marked missed) when a later word matches. */
  val maxSkipWords: Int,
  /** Cost of acoustically confusable phonemes (e.g. ص and س). */
  val acousticConfusionCost: Double,
  /** Cost of an extra recognized phoneme. */
  val standardInsertionCost: Double,
  /** Cost of a missing expected phoneme. */
  val standardDeletionCost: Double,
) {
  /** For beginners, children, noisy rooms or built-in microphones. */
  EASY(0.40, 0.30, 0.35, 1, 0.15, 0.50, 0.80),

  /** For everyday practice. */
  NORMAL(0.30, 0.25, 0.28, 2, 0.25, 0.75, 1.00),

  /** For advanced reciters and memorization tests. */
  HARD(0.25, 0.20, 0.23, 3, 0.35, 1.00, 1.00),
}

/** Tracking settings (recite_quran's TrackerConfig, without its Tajweed settings). */
data class NatluConfig(
  val strictness: MatchingStrictness = MatchingStrictness.NORMAL,
  /** Commit long words as soon as they match, before trailing vowels finish. */
  val enableEarlyMatching: Boolean = true,
  /** Find the reader again when they jump elsewhere in the sura (Natlu enables it). */
  val enableAutoReanchor: Boolean = true,
  /** Unmatched phonemes after which the reader is searched for elsewhere. */
  val reanchorStallThreshold: Int = 24,
) {
  val defaultMaxPathCost get() = strictness.defaultMaxPathCost
  val shortWordPathCost get() = strictness.shortWordPathCost
  val mediumWordPathCost get() = strictness.mediumWordPathCost
  val maxSkipWords get() = strictness.maxSkipWords
  val acousticConfusionCost get() = strictness.acousticConfusionCost
  val standardInsertionCost get() = strictness.standardInsertionCost
  val standardDeletionCost get() = strictness.standardDeletionCost
}

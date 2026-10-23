package com.quran.labs.androidquran.ui

import android.content.SharedPreferences
import com.quran.recitation.tracking.StreamingConfig
import com.quran.recitation.tracking.TrackerConfig
import java.util.Locale

/** A tunable recitation tracking setting, shown on the tracking screen for testing. */
enum class RecitationTrackingSetting(
  val label: String,
  val explanation: String,
  val default: Double,
  val min: Double,
  val max: Double,
  val wholeNumber: Boolean = false,
) {
  LOOKAHEAD(
    "Look-ahead (seconds)",
    "Audio the model hears after a word before deciding. Lower is more responsive; too low and the model is unsure of the letters near the end of each chunk.",
    1.0, 0.0, 2.0,
  ),
  HOP(
    "Update interval (seconds)",
    "How often the model runs on new audio. Lower is more responsive but runs the model more often (watch the speed line).",
    1.0, 0.2, 2.0,
  ),
  CONTEXT(
    "Past context (seconds)",
    "Earlier audio the model hears again each time. More can help accuracy but costs speed.",
    3.0, 0.0, 6.0,
  ),
  STABLE_FRAMES(
    "Confirmation frames (20 ms each)",
    "How long the match must hold before a word counts as recited. Higher avoids jumping ahead; lower is faster.",
    5.0, 1.0, 50.0, wholeNumber = true,
  ),
  SKIP_LETTER(
    "Unheard letter cost",
    "Lower forgives letters the model did not hear (fewer stalls); higher waits for every letter.",
    1.0, 0.0, 20.0,
  ),
  JUMP_BACK(
    "Going back cost",
    "Lower follows you more readily when you repeat earlier words; higher ignores short repeats.",
    10.0, 0.0, 50.0,
  ),
  FILLER(
    "Start sensitivity",
    "Higher starts following sooner; lower ignores more sound before you begin reciting.",
    1.5, 0.0, 10.0,
  ),
  UNCLEAR(
    "Unclear word threshold",
    "Recited words scoring below this are marked unclear (possible mistake). Closer to 0 marks more words; in testing about 2% of correctly recited words scored below -8.",
    -8.0, -30.0, 0.0,
  ),
  THREADS(
    "Processor threads",
    "CPU cores the model may use at once.",
    2.0, 1.0, 8.0, wholeNumber = true,
  ),
  ;

  val key: String get() = "setting_${name.lowercase()}"

  fun clamp(value: Double): Double {
    val bounded = value.coerceIn(min, max)
    return if (wholeNumber) Math.round(bounded).toDouble() else bounded
  }

  fun format(value: Double): String =
    if (wholeNumber) value.toLong().toString()
    else String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
}

/** Values of every [RecitationTrackingSetting]. */
class RecitationTrackingSettings(private val values: Map<RecitationTrackingSetting, Double>) {
  operator fun get(setting: RecitationTrackingSetting): Double = values[setting] ?: setting.default

  val streaming: StreamingConfig
    get() = StreamingConfig(
      hopSeconds = this[RecitationTrackingSetting.HOP],
      contextSeconds = this[RecitationTrackingSetting.CONTEXT],
      lookaheadSeconds = this[RecitationTrackingSetting.LOOKAHEAD],
    )

  val tracker: TrackerConfig
    get() = TrackerConfig(
      fillerPenalty = this[RecitationTrackingSetting.FILLER],
      stableFrames = this[RecitationTrackingSetting.STABLE_FRAMES].toInt(),
      jumpBackPenalty = this[RecitationTrackingSetting.JUMP_BACK],
      skipLetterPenalty = this[RecitationTrackingSetting.SKIP_LETTER],
    )

  val threads: Int get() = this[RecitationTrackingSetting.THREADS].toInt()

  val unclearThreshold: Double get() = this[RecitationTrackingSetting.UNCLEAR]

  fun save(preferences: SharedPreferences) {
    preferences.edit().apply {
      RecitationTrackingSetting.entries.forEach { putFloat(it.key, this@RecitationTrackingSettings[it].toFloat()) }
    }.apply()
  }

  companion object {
    fun load(preferences: SharedPreferences) = RecitationTrackingSettings(
      RecitationTrackingSetting.entries.associateWith {
        it.clamp(preferences.getFloat(it.key, it.default.toFloat()).toDouble())
      }
    )

    val defaults = RecitationTrackingSettings(RecitationTrackingSetting.entries.associateWith { it.default })
  }
}

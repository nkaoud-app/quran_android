package com.quran.mobile.recitation.presenter

import com.quran.data.di.AppScope
import com.quran.recitation.presenter.RecitationSettings
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
class RecitationSettingsImpl @Inject constructor(
  private val state: MushafRecitationState,
) : RecitationSettings {
  override fun isRecitationEnabled() = true

  /** Hides (or shows again) the words not yet recited, for reciting from memory. */
  override fun toggleAyahVisibility() = state.toggleHideUnrecited()
}

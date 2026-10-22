package com.coverflow.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Always-on state shared between [MainActivity] and the UI. Two ways in:
 *
 *  - **Dim mode** (our own, works on every watch): while music plays the app keeps the screen
 *    on; after a few idle seconds [MainActivity] drops the brightness to a fifth of what it was,
 *    keeping the same screens. Any touch, bezel turn or button press brightens it again without
 *    triggering anything.
 *  - **System ambient** (Wear OS always-on): if the screen does time out (e.g. music paused for
 *    a long time), the watch keeps showing the same screens in its low-power mode, at a fifth of
 *    their brightness.
 */
class AmbientState {
    /** In the watch's own low-power always-on (not our dim mode). */
    private val _isSystemAmbient = MutableStateFlow(false)
    val isSystemAmbient: StateFlow<Boolean> = _isSystemAmbient.asStateFlow()

    /** Set by the activity: keep the screen on (and allow dim mode) or let it time out. */
    internal var keepOnListener: (Boolean) -> Unit = {}

    internal fun enter() {
        _isSystemAmbient.value = true
    }

    internal fun exit() {
        _isSystemAmbient.value = false
    }

    /** Keep the screen on while the app is open (normally: while music is playing). */
    fun setKeepScreenOn(on: Boolean) = keepOnListener(on)
}

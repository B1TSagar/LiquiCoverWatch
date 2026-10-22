package com.coverflow.wear

import androidx.compose.ui.graphics.Color

/** Knobs worth playing with, all in one place. */
object Tuning {
    /** Clockwise bezel = next cover (covers travel right to left). Set false to reverse. */
    const val CLOCKWISE_IS_NEXT = true

    /** Bezel detents per cover step. 1 = every click moves one cover. */
    const val DETENTS_PER_STEP = 1

    // ---- Back button --------------------------------------------------------------------------

    /**
     * How long to wait for another Back press before acting. Presses 1-3 only fire after this
     * window (a fourth press fires straight away), so shorter feels snappier but makes
     * multi-presses harder to land.
     */
    const val MULTI_PRESS_WINDOW_MS = 400L

    /**
     * Two-finger tap (on Cover Flow it switches between the player's covers and the recently
     * played; elsewhere it opens the lyrics): fingers must land this close together and lift within.
     */
    const val TWO_FINGER_TAP_MAX_GAP_MS = 250L
    const val TWO_FINGER_TAP_MAX_MS = 600L

    // ---- Haptics -----------------------------------------------------------------------------

    const val HAPTICS = true

    /** The tick felt each time a cover (or track row) passes the center. */
    const val TICK_MS = 20L
    const val TICK_STRENGTH = 200 // 1..255, used when the motor supports amplitude control

    /** Fast spins can pass several covers per frame; ticks closer together than this are merged. */
    const val MIN_TICK_GAP_MS = 35L

    // ---- Now Playing --------------------------------------------------------------------------

    /**
     * Volume follows the bezel: turning it the way the volume arc grows (counter-clockwise, up
     * the right edge) raises the volume. Set false for clockwise = louder.
     */
    const val VOLUME_FOLLOWS_ARC = true

    /** Scrub: how many bezel clicks cover a whole song (step is clamped to the two limits below). */
    const val SCRUB_CLICKS_PER_TRACK = 48
    const val SCRUB_MIN_STEP_MS = 2_000L
    const val SCRUB_MAX_STEP_MS = 15_000L
    const val SCRUB_STEP_UNKNOWN_LENGTH_MS = 5_000L

    // ---- Lyrics -------------------------------------------------------------------------------

    /** Highlight each line this much before its timestamp (feels more in sync). */
    const val LYRICS_LEAD_MS = 250L

    /** After you scroll lyrics by hand, wait this long before following the song again. */
    const val LYRICS_MANUAL_HOLD_MS = 4_000L

    /** Lines this close to the current one (before and after) stay sharp, just dimmer. */
    const val LYRICS_SHARP_NEIGHBORS = 1
    const val LYRICS_NEIGHBOR_ALPHA = 0.62f

    /** Lines further away: blurred and dimmer still. */
    const val LYRICS_DIM_ALPHA = 0.38f
    const val LYRICS_BLUR_DP = 2.5f

    // ---- Always-on display -------------------------------------------------------------------

    /**
     * Keep the screen on while Cover Flow is open and music is playing, dimming to the
     * always-on view when idle (works whether or not the watch's own Always On Display does).
     */
    const val KEEP_SCREEN_ON = true

    /** After music pauses, keep the screen on this long before letting it time out normally. */
    const val KEEP_ON_AFTER_PAUSE_MS = 2 * 60_000L

    /** Idle time before dimming (always-on: the same screens, dimmed). */
    const val DIM_AFTER_MS = 15_000L

    /** Always-on brightness, as a share of the brightness before it (0..1). */
    const val DIM_FRACTION = 0.2f

    // ---- Cover Flow ---------------------------------------------------------------------------

    /**
     * How quickly covers chase the bezel when it's turned slowly (spring frequency, rad/s).
     * Higher = tighter and snappier, lower = floatier. Covers never snap or overshoot.
     */
    const val FLOW_RESPONSIVENESS = 16f

    /**
     * Turn the bezel faster and the covers speed up with it, changing as fast as the song title
     * does: the spring stiffens so the covers never trail the title by more than this many
     * covers (under 0.5 means the centre cover is always the one the title names)...
     */
    const val FLOW_MAX_LAG = 0.25f

    /** ...up to this stiffness (rad/s), enough for about 50 bezel clicks a second. */
    const val FLOW_MAX_RESPONSIVENESS = 400f

    /** Bezel speed is measured over this window. Shorter reacts quicker but jumpier. */
    const val FLOW_SPEED_WINDOW_MS = 250L

    /** How quickly the covers ease back to the relaxed glide once the bezel slows (ms). */
    const val FLOW_RELAX_MS = 200L

    /**
     * Covers are kept in memory at this multiple of the size the center cover is drawn at, so
     * they're only ever scaled down (sharp). The phone sends them up to 400 px.
     */
    const val COVER_MEMORY_SCALE = 1.25f
    const val COVERS_IN_MEMORY = 140
    const val COVERS_ON_DISK = 800

    /** With the app out of sight, only this many covers (the last the screens showed) stay in memory. */
    const val COVERS_KEPT_HIDDEN = 16

    /** If you stop scrolling for this long, the covers jump back to the playing song when it changes. */
    const val FOLLOW_PLAYING_AFTER_IDLE_MS = 8_000L

    /**
     * Once the song has been paused this long (ms), Cover Flow shows the recently played songs
     * instead of the player's own covers (as when nothing is playing). A two-finger tap switches back.
     */
    const val RECENT_AFTER_PAUSE_MS = 150_000L

    /** Music stopping counts once it has stayed stopped this long (ms): players briefly clear the song between tracks. */
    const val STOPPED_AFTER_MS = 1_500L

    // Geometry (fractions of screen width / height)
    const val COVER_SIZE = 0.44f
    const val COVER_CENTER_Y = 0.385f
    const val SIDE_ANGLE = 62f        // degrees the side covers are turned
    const val SIDE_OFFSET = 0.86f     // first side cover distance, in cover widths (clears the glass pane)
    const val SIDE_SPACING = 0.24f    // spacing between stacked side covers, in cover widths
    const val SIDE_SCALE = 0.84f
    const val REFLECTION = 0.30f      // reflection height, in cover heights
    const val COVER_CASE_DP = 6f      // how far the centre glass pane reaches past the cover resting in it

    /** How bright the album-colour background gets (0..1). Lower = darker, easier on OLED. */
    const val BACKGROUND_BRIGHTNESS = 0.42f
    const val BACKGROUND_FADE_MS = 450

    // ---- Liquid glass -------------------------------------------------------------------------

    /** The domed crystal over the whole face: the picture stretches a little toward the rim (0 = off). */
    const val GLASS_SCREEN = 1f

    /** How strongly glass bends what's behind it at its rim (1 = default, 0 = flat glass). */
    const val GLASS_BEND = 1f

    /** How close (dp) two pieces of glass must come before they start to melt into one. */
    const val GLASS_MERGE_DP = 10f

    /**
     * The album's track list: tracks stay separate pieces of glass at rest and melt into each
     * other (and into the selection bar) with this reach (dp) while the list is moving.
     */
    const val TRACK_MERGE_DP = 12f

    /** Light on the glass rims: glow, hair-thin highlight, outline and volume shading (0 = off). */
    const val GLASS_LIGHT = 1f

    /** The soft shadow under each piece of glass (0 = off, 1 = strong). */
    const val GLASS_SHADOW = 0.25f

    /**
     * The spring all moving liquid glass follows, the Rate page droplet's: damping (1 = no
     * overshoot; a little under gives the slight liquid settle) and stiffness (higher = quicker).
     * Used for the meters, scrubbing, the button spill, the lyrics' scrolling and lens, the
     * Rate droplet, and glass growing in.
     */
    const val GLASS_SPRING_DAMPING = 0.8f
    const val GLASS_SPRING_STIFFNESS = 260f

    val Accent = Color(0xFF3FA9F5)
    val AccentDeep = Color(0xFF1A6FB0)
}

package com.coverflow.wear.face

/**
 * The LiquiWatch · Expedition face's seconds hand beats eight times a second, a third of the way
 * through each eighth (1/24 s, 4/24 s, 7/24 s ... into each second, on the watch's own clock, which
 * the face reads too), and everything on that face changes only as it beats: the hands, the song's
 * progress line, the compass card. What this app hands the faces is timed to reach them just after
 * a beat ([FaceUpdates]), so the watch draws it in a frame it draws anyway, rather than in one of
 * its own just before or after. (Other faces aren't the worse for it: an update waits an eighth
 * of a second at most.)
 *
 * Beats are numbered from 1970 (480 a minute); beat n comes at 125 n + 41.67 ms, which the face sees
 * from the next whole millisecond.
 */
object Beat {
    /** Between beats (ms). */
    const val MS = 125L

    /** The beat at or before [wallMs] (wall clock, ms). */
    fun at(wallMs: Long): Long = Math.floorDiv(wallMs * 3 - 125, 375L)

    /** When beat [n] comes (wall clock, ms): the face sees it from then. */
    fun time(n: Long): Long = n * MS + 42
}

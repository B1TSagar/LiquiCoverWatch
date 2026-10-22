package com.coverflow.mobile

import android.content.Context
import android.content.Intent

/** Names of player apps, and the list shown in the phone app's "Music apps" section. */
object Players {
    private val known = mapOf(
        "com.spotify.music" to "Spotify",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.google.android.youtube" to "YouTube",
        "com.sec.android.app.music" to "Samsung Music",
        "com.jio.media.jiobeats" to "JioSaavn",
        "com.bsbportal.music" to "Wynk Music",
        "com.gaana" to "Gaana",
        "com.apple.android.music" to "Apple Music",
        "com.amazon.mp3" to "Amazon Music",
        "deezer.android.app" to "Deezer",
        "com.soundcloud.android" to "SoundCloud",
    )

    fun label(ctx: Context, pkg: String): String = known[pkg] ?: try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /**
     * Apps worth listing: everything we've seen play media, plus installed apps that offer a
     * media library service (most music and podcast apps).
     */
    fun candidates(ctx: Context): List<String> {
        val installed = try {
            ctx.packageManager
                .queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
                .mapNotNull { it.serviceInfo?.packageName }
        } catch (e: Exception) {
            emptyList()
        }
        return (Prefs.seenPlayers(ctx) + installed)
            .filter { it != ctx.packageName }
            .distinct()
            .sortedBy { label(ctx, it).lowercase() }
    }
}

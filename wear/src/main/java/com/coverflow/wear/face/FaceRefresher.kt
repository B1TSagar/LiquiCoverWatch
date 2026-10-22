package com.coverflow.wear.face

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After Cover Display is updated, the faces showing its album art, album colour or song progress
 * are asked for them again, so they get what this version sends straight away (a face keeps the
 * last data it was given until it's sent more, which otherwise waits for the music to change); and
 * the LiquiWatch companion is told what's playing again.
 */
class FaceRefresher : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // (Whether or not this app thinks a face shows them: a face that does asks for them now,
        // and is noted as showing them. An ask no face takes is no work for the watch.)
        FaceArt.refreshFaces(context)
        // (The companion's date pill is its own: told whether or not a face shows the album art.)
        NowPlayingLink.update(context, force = true)
        // (Held until the asks have gone: see FaceArt.done.)
        FaceArt.finishWhenDone(goAsync())
    }
}

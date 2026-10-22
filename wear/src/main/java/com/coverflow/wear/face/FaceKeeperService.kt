package com.coverflow.wear.face

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * Bound by the LiquiWatch companion while its Expedition face is up (as long as its live compass
 * runs), so this app is never frozen while that face needs it.
 *
 * A Galaxy Watch freezes an app it isn't showing once the app has been idle for a few seconds, and
 * a frozen app can't be handed the phone's messages: so this app never heard that a song was
 * playing, and the face never got its cover or its colour, nor the companion the song for the date
 * pill. (Seen in a log of the watch: "freezePackage com.coverflow.app ... reason: UidIdle", then
 * Google Play services' "sent binder code 18 with flags 2 to frozen apps and got error -74" as the
 * phone's messages came.) An app bound by one running in the foreground isn't idle, so it isn't
 * frozen. It does nothing else: as it's bound it only catches up on what the phone said meanwhile
 * ([FaceArt.catchUp]).
 */
class FaceKeeperService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "Kept awake for the watch face")
        kept = true
        FaceArt.catchUp(this)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "No longer kept awake for the watch face")
        kept = false
        return false
    }

    companion object {
        private const val TAG = "CoverFlowFace"

        /** Bound now: this app can't be frozen, so nothing need hold it until its asks have gone. */
        @Volatile
        var kept = false
            private set
    }
}

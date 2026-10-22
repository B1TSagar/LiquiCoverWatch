package com.coverflow.wear.face

import com.coverflow.shared.Paths
import com.coverflow.shared.PlayerState
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Wakes up when the phone's player state changes, or the face's background (this song's, or the
 * next song's sent ahead) arrives, even with Cover Flow closed, so the watch face's background can
 * follow the music (see [FaceArt]). Runs on a background thread, briefly; it does nothing much
 * unless a face shows the album art.
 *
 * The phone tells it of a change twice: first in a small message ([Paths.FACE_STATE]), the
 * moment it happens, then in the synced state item, which comes a little later. Whichever comes
 * first moves the face; the other, the same or older, changes nothing (see [FaceArt.noteState]).
 */
class FaceSyncService : WearableListenerService() {

    // (At the usual priority: at background priority it was all but stopped while the face kept
    // the watch's two cores busy, and the album art then waited until Cover Flow was opened.)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        var latest: PlayerState? = null
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != Paths.STATE) continue
            val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("json") ?: continue
            PlayerState.fromJsonString(json)?.let { latest = it }
        }
        latest?.let { heard(it) }
        // (Held until what it asked of the faces and the companion has gone: see FaceArt.done.)
        hold()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        when {
            path == Paths.FACE_STATE ->
                PlayerState.fromJsonString(String(messageEvent.data, Charsets.UTF_8))?.let { heard(it) }
            path.startsWith(Paths.FACE_ART_PREFIX) ->
                FaceArt.gotFaceArt(this, path.removePrefix(Paths.FACE_ART_PREFIX), messageEvent.data)
            path.startsWith(Paths.FACE_NEXT_PREFIX) ->
                FaceArt.gotNextFaceArt(this, path.removePrefix(Paths.FACE_NEXT_PREFIX), messageEvent.data)
            path.startsWith(Paths.COVER_PREFIX) ->
                FaceArt.gotCover(this, path.removePrefix(Paths.COVER_PREFIX), messageEvent.data)
        }
        // (Held until what it asked of the faces and the companion has gone: the watch freezes an
        // app it isn't showing soon after it's done, and an ask still waiting for its beat would
        // wait until the app next woke. The next message waits meanwhile, so only a beat or two
        // ([HOLD_MS]): a song's cover usually comes a moment after the song itself. While the
        // Expedition face is up the companion keeps this app awake anyway: see FaceKeeperService.)
        hold()
    }

    /**
     * Not while the companion keeps this app awake: it can't be frozen then, and the hold only
     * held up the next message (on the watch, a skip's messages were taken up to half a second late).
     */
    private fun hold() {
        if (!FaceKeeperService.kept) FaceArt.holdUntilDone(HOLD_MS)
    }

    private fun heard(state: PlayerState) {
        // (One older than a state already taken, the synced item of a song gone by: left alone.)
        if (!FaceArt.noteState(this, state)) return
        // Playing a song the face has no background for yet: the phone makes it. It sends a new
        // song's as the song starts, without being asked, so it's only asked if that hasn't come
        // in a moment; and in the background, so this thread is free to take it as it comes (it
        // used to wait here on the trip to the phone, holding up the very message it waited for).
        val key = state.current?.artKey
        if (state.inPlay && !key.isNullOrBlank() && FaceArt.inUse(this)) FaceArt.fetchLater(this, key, ASK_AFTER_MS)
    }

    private companion object {
        /** How long a new song's background has to come by itself before the phone is asked (ms). */
        const val ASK_AFTER_MS = 700L

        /** The longest each message is held for its asks to go (ms): the next beat or two. */
        const val HOLD_MS = 400L
    }
}

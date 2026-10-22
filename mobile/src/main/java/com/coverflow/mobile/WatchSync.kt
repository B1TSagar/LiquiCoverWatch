package com.coverflow.mobile

import android.content.Context
import android.util.Log
import com.coverflow.shared.Paths
import com.coverflow.shared.PlayerState
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Phone -> watch traffic over the Wear Data Layer. */
object WatchSync {
    private const val TAG = "CoverFlowSync"

    fun publishState(ctx: Context, state: PlayerState) {
        val request = PutDataMapRequest.create(Paths.STATE).apply {
            dataMap.putString("json", state.toJsonString())
            dataMap.putLong("t", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(request)
            .addOnFailureListener { Log.w(TAG, "publishState failed", it) }
    }

    /** A cover ready to show ([com.coverflow.shared.CoverPacket] bytes). */
    suspend fun sendCover(ctx: Context, nodeId: String, key: String, packet: ByteArray) =
        send(ctx, nodeId, Paths.COVER_PREFIX + key, packet)

    /**
     * Push a cover to every connected watch (when the now-playing art arrives, or a sharper
     * copy is found): [jpeg] is the saved cover, made ready for the watch here.
     */
    suspend fun pushArt(ctx: Context, key: String, jpeg: ByteArray) {
        val nodes = try {
            Wearable.getNodeClient(ctx).connectedNodes.await()
        } catch (e: Exception) {
            return
        }
        if (nodes.isEmpty()) return
        // A watch face showing this song gets its background made again from this copy: first,
        // as it's what the face is waiting for when the song has only just started (and it's
        // small: Cover Flow's copy of the cover, which follows, is several times its size).
        WatchFaceArt.coverChanged(ctx, key, jpeg)
        val packet = WatchCovers.packet(key, jpeg, WatchCovers.size(ctx)) ?: return
        nodes.forEach { sendCover(ctx, it.id, key, packet) }
    }

    /** Tell every connected watch that its cover for [key] was wrong; it will ask again. */
    suspend fun dropArt(ctx: Context, key: String) = sendToAll(ctx, Paths.ART_DROP_PREFIX + key, ByteArray(0))

    /** Send a message to every connected watch. */
    suspend fun sendToAll(ctx: Context, path: String, payload: ByteArray) {
        val nodes = try {
            Wearable.getNodeClient(ctx).connectedNodes.await()
        } catch (e: Exception) {
            return
        }
        nodes.forEach { send(ctx, it.id, path, payload) }
    }

    /** The connected watch's name (as its Bluetooth name usually reads), or null. */
    suspend fun watchName(ctx: Context): String? = try {
        Wearable.getNodeClient(ctx).connectedNodes.await().firstOrNull { it.isNearby }?.displayName
    } catch (e: Exception) {
        null
    }

    suspend fun send(ctx: Context, nodeId: String, path: String, payload: ByteArray) {
        try {
            Wearable.getMessageClient(ctx).sendMessage(nodeId, path, payload).await()
        } catch (e: Exception) {
            Log.w(TAG, "send $path failed", e)
        }
    }
}

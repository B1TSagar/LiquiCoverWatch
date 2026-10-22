package com.coverflow.mobile

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference

/**
 * Android only lets apps with notification access read other apps' media sessions.
 * This service unlocks the media session API and keeps [MediaHub] alive while the system has us
 * bound. It also lets us find a player's media notification, whose buttons (e.g. a "Love" or
 * "Favourite" button) are sometimes the only way to like a song from outside the app, and
 * whose disappearing tells us the player app was closed.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = WeakReference(this)
        MediaHub.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null && isMedia(sbn)) MediaHub.mediaNotificationChanged()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && isMedia(sbn)) MediaHub.mediaNotificationChanged()
    }

    private fun isMedia(sbn: StatusBarNotification): Boolean = try {
        sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    } catch (e: Exception) {
        false
    }

    companion object {
        @Volatile
        private var instance: WeakReference<MediaListenerService>? = null

        /** Whether the system has us bound, so other apps' notifications can be seen. */
        val isConnected: Boolean get() = instance?.get() != null

        /** Media-style notifications currently posted by [pkg] (newest first). */
        fun mediaNotifications(pkg: String): List<Notification> = try {
            instance?.get()?.activeNotifications.orEmpty()
                .filter { it.packageName == pkg && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
                .sortedByDescending { it.postTime }
                .map { it.notification }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

package com.coverflow.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.util.Log
import java.util.regex.Pattern

/**
 * Links the watch to the phone app as its companion device, through Android's own "link a
 * device" dialog. Android lets an app linked to a companion device open other apps from the
 * background, in response to what you do on that device: here, picking a song on the watch
 * when the music app isn't running. (The alternative is "display over other apps".)
 */
object CompanionLink {
    private const val TAG = "CoverFlowLink"

    fun isLinked(ctx: Context): Boolean = linkedTo(ctx).isNotEmpty()

    /** The devices linked (their names as the phone shows them, or their addresses). */
    fun linkedTo(ctx: Context): List<String> {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return emptyList()
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations.map { (it.displayName ?: it.deviceMacAddress)?.toString() ?: "a device" }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Lets go of every linked device (one picked by mistake: then the watch can be linked instead). */
    fun unlink(ctx: Context) {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations.forEach { cdm.disassociate(it.id) }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.forEach { cdm.disassociate(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't unlink", e)
        }
    }

    /**
     * The watch's Bluetooth address among the devices the phone is paired with ("Nearby devices"
     * from Android 12): the one named [watchName] (as the Wear connection reports it), else the one
     * watch there is. Null if it can't be told.
     */
    @SuppressLint("MissingPermission")
    fun watchAddress(ctx: Context, watchName: String?): String? = try {
        val bonded = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        fun names(d: BluetoothDevice) = listOfNotNull(d.name, if (Build.VERSION.SDK_INT >= 30) d.alias else null)
        fun isWatch(d: BluetoothDevice) = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.WEARABLE ||
            names(d).any { it.contains("watch", ignoreCase = true) }
        (bonded.firstOrNull { d -> watchName != null && names(d).any { it.equals(watchName, ignoreCase = true) } }
            ?: bonded.filter(::isWatch).singleOrNull())?.address
    } catch (e: SecurityException) {
        null
    }

    /**
     * Shows the system's dialog for linking the watch: the one at [address] (see watchAddress),
     * else the one device with "watch" in its name or [watchName] (as the Wear connection reports
     * it), else (null) a choice of nearby devices. Only asked for as one device by its address does
     * the dialog look among the devices the phone is paired with, the only way it finds a watch
     * that's paired and connected: its search never lists one (seen on a Galaxy phone, the dialog
     * loading on and on), and a name matches only as the phone shows it, which is the watch's own
     * only until it's renamed in the phone's Bluetooth settings. [showDialog] launches the dialog Android hands back; [failed] gets the reason if it
     * can't be shown.
     */
    fun link(
        activity: Activity,
        watchName: String?,
        address: String?,
        showDialog: (IntentSender) -> Unit,
        linked: () -> Unit,
        failed: (String) -> Unit,
    ) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            failed("This phone can't link companion devices.")
            return
        }
        val filter = BluetoothDeviceFilter.Builder().apply {
            if (address != null) {
                setAddress(address)
            } else if (!watchName.isNullOrBlank()) {
                setNamePattern(Pattern.compile(".*(watch|" + Pattern.quote(watchName) + ").*", Pattern.CASE_INSENSITIVE))
            }
        }.build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(address != null || !watchName.isNullOrBlank())
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            // Android 8 to 12: the device picker is ready.
            @Deprecated("Replaced by onAssociationPending on Android 13")
            override fun onDeviceFound(intentSender: IntentSender) = showDialog(intentSender)

            // Android 13 and later.
            override fun onAssociationPending(intentSender: IntentSender) = showDialog(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) = linked()

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "Linking failed: $error")
                failed(error?.toString() ?: "Couldn't find the watch.")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.associate(request, activity.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                cdm.associate(request, callback, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start linking", e)
            failed(e.message ?: "Couldn't start linking.")
        }
    }
}

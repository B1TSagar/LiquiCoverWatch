package com.coverflow.mobile

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * The country the phone is in, for looking songs up in the right store and catalog: from the
 * mobile network, else the SIM, else the phone's language setting (which is often "US" for
 * English even elsewhere, and would find versions of songs blocked where the phone is).
 */
object Region {
    @Volatile
    var code: String = fromLocale()
        private set

    /** Work the country out (cheap; called as the phone app starts). */
    fun init(ctx: Context) {
        val tm = runCatching { ctx.getSystemService(TelephonyManager::class.java) }.getOrNull()
        val found = listOfNotNull(
            runCatching { tm?.networkCountryIso }.getOrNull(),
            runCatching { tm?.simCountryIso }.getOrNull(),
        ).map { it.trim().uppercase(Locale.ROOT) }.firstOrNull { it.length == 2 }
        code = found ?: fromLocale()
    }

    private fun fromLocale(): String =
        Locale.getDefault().country.uppercase(Locale.ROOT).takeIf { it.length == 2 } ?: "US"
}

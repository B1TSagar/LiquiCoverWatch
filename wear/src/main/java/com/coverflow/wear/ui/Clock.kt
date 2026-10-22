package com.coverflow.wear.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The clock at the top of the screen, on a pill of smoked liquid glass (the look of Now
 * Playing's volume label). [text] shows instead of the time when given (Now Playing's volume
 * and scrub labels), and the pill's width flows to fit the new text. [top] is how far down the
 * pill sits (default: where Now Playing's label sits); [onPlaced] is told where the pill lands.
 */
@Composable
fun BoxScope.GlassClock(
    scene: GlassScene,
    text: String? = null,
    look: GlassLook = ClockLook,
    letterSpacing: TextUnit = 0.sp,
    top: Dp = Dp.Unspecified,
    onPlaced: ((LayoutCoordinates) -> Unit)? = null,
) {
    val time = rememberClockText()
    val y = if (!top.value.isNaN()) top else (LocalConfiguration.current.screenHeightDp * 0.045f + 4f).dp
    Text(
        text = text ?: time,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = letterSpacing,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .offset(y = y)
            .glass(scene, look)
            .then(if (onPlaced != null) Modifier.onGloballyPositioned(onPlaced) else Modifier)
            .animateContentSize(glassSpring<IntSize>())
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The clock's glass: smoked, and never melting into the glass near it. */
val ClockLook = GlassLooks.Smoked.copy(merge = 0.dp)

/** The time as the watch shows it (12 or 24 hour, as set), updated on the minute. */
@Composable
private fun rememberClockText(): String {
    val context = LocalContext.current
    val text by produceState(clockNow(context), context) {
        while (true) {
            value = clockNow(context)
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }
    return text
}

private fun clockNow(context: Context): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
}

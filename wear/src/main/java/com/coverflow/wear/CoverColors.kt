package com.coverflow.wear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import android.graphics.Color as AndroidColor

/** Turns a cover's dominant colour (picked on the phone) into background tints. */
object CoverColors {
    /** The colour behind the covers: same hue, brightness pinned so white text stays readable. */
    fun backgroundTint(c: Color, brightness: Float = Tuning.BACKGROUND_BRIGHTNESS): Color {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(c.toArgb(), hsv)
        val vivid = hsv[1] >= 0.15f
        hsv[1] = if (vivid) min(max(hsv[1], 0.45f), 0.85f) else hsv[1]
        hsv[2] = if (vivid) brightness else brightness * 0.7f
        return Color(AndroidColor.HSVToColor(hsv))
    }

    /**
     * The colour of Now Playing's sliders: a tone of the cover's own colour that stands out on
     * the darkened, blurred cover behind them. Dark colours get lighter, very pale ones a little
     * darker, and the hue is kept, with enough saturation to read as that colour. Greys and
     * black-and-white covers get a light silver.
     */
    fun sliderTone(c: Color): Color {
        val r = c.red
        val g = c.green
        val b = c.blue
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val span = hi - lo
        val light = (hi + lo) / 2f
        val sat = if (span < 0.0001f) 0f else span / (1f - kotlin.math.abs(2f * light - 1f)).coerceAtLeast(0.0001f)
        val hue = when {
            span < 0.0001f -> 0f
            hi == r -> ((g - b) / span).mod(6f)
            hi == g -> (b - r) / span + 2f
            else -> (r - g) / span + 4f
        } * 60f
        if (sat < 0.12f) return hsl(hue, sat, 0.86f, c.alpha)
        val newLight = when {
            light > 0.80f -> 0.72f // very pale: a little darker
            light < 0.62f -> 0.64f // dark or deep: lighter
            else -> light
        }
        return hsl(hue, sat.coerceIn(0.55f, 0.90f), newLight, c.alpha)
    }

    private fun hsl(hue: Float, sat: Float, light: Float, alpha: Float): Color {
        val chroma = (1f - kotlin.math.abs(2f * light - 1f)) * sat
        val h = hue / 60f
        val x = chroma * (1f - kotlin.math.abs(h.mod(2f) - 1f))
        val (r, g, b) = when {
            h < 1f -> Triple(chroma, x, 0f)
            h < 2f -> Triple(x, chroma, 0f)
            h < 3f -> Triple(0f, chroma, x)
            h < 4f -> Triple(0f, x, chroma)
            h < 5f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val m = light - chroma / 2f
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f), alpha)
    }
}

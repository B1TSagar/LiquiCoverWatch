package com.coverflow.wear.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.coverflow.shared.Track
import com.coverflow.wear.Connection
import com.coverflow.wear.Tuning
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Album art, or a generated placeholder while it loads / when there is none. */
@Composable
fun CoverArt(track: Track, art: ImageBitmap?, modifier: Modifier = Modifier) {
    if (art != null) {
        Image(bitmap = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        CoverPlaceholder(track, modifier)
    }
}

/**
 * The blurred album cover filling the screen, darkened so text stays readable: the background of
 * every screen (Now Playing draws its own, as it darkens it further while scrubbing). Fades from
 * one cover to the next. Blur needs Wear OS 4 (Android 12) or later; older watches get the cover
 * dimmed further instead.
 */
@Composable
fun BlurredCover(track: Track?, art: ImageBitmap?, modifier: Modifier = Modifier) {
    val scrim = if (Build.VERSION.SDK_INT >= 31) 0.55f else 0.75f
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(
            targetState = track to art,
            animationSpec = tween(Tuning.BACKGROUND_FADE_MS),
            label = "blurredCover",
        ) { (t, a) ->
            if (t != null) CoverArt(t, a, Modifier.fillMaxSize().blur(20.dp))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
    }
}

private val placeholderPalette: List<Pair<Color, Color>> = listOf(
    Color(0xFF2B3A55) to Color(0xFF0F1626),
    Color(0xFF4A2B55) to Color(0xFF1A0F26),
    Color(0xFF2B5547) to Color(0xFF0F2620),
    Color(0xFF55402B) to Color(0xFF26190F),
    Color(0xFF552B33) to Color(0xFF260F14),
    Color(0xFF2B4D55) to Color(0xFF0F2126),
)

private fun placeholderColors(track: Track): Pair<Color, Color> =
    placeholderPalette[(track.artKey.hashCode() and 0x7fffffff) % placeholderPalette.size]

/** The colour a cover's background should take: its dominant colour, or the placeholder's. */
fun coverColor(track: Track, artColors: Map<String, Color>): Color =
    artColors[track.artKey] ?: placeholderColors(track).first

@Composable
fun CoverPlaceholder(track: Track, modifier: Modifier = Modifier) {
    val (top, bottom) = placeholderColors(track)
    Box(
        modifier.background(Brush.linearGradient(listOf(top, bottom), start = Offset.Zero, end = Offset.Infinite)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = (track.album.ifBlank { track.title }).take(1).uppercase().ifBlank { "♪" },
            fontSize = 34.sp,
            fontWeight = FontWeight.Light,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

/** Single-line text that scrolls when it doesn't fit. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        // Scrolling text redraws every frame: in a layer of its own, only it is redrawn.
        modifier = modifier.graphicsLayer {}.basicMarquee(iterations = Int.MAX_VALUE),
        style = TextStyle(
            fontSize = size,
            fontWeight = weight,
            color = color,
            textAlign = TextAlign.Center,
            shadow = Shadow(Color.Black, Offset(0f, 1f), 6f),
        ),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

enum class Glyph { Play, Pause, Next, Previous }

/** Tiny vector transport icons (avoids pulling in the large icon library). */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun triangle(left: Float, right: Float, pointRight: Boolean) = Path().apply {
            if (pointRight) {
                moveTo(left, 0f); lineTo(right, h / 2f); lineTo(left, h); close()
            } else {
                moveTo(right, 0f); lineTo(left, h / 2f); lineTo(right, h); close()
            }
        }
        when (glyph) {
            Glyph.Play -> drawPath(triangle(w * 0.18f, w * 0.92f, true), color)
            Glyph.Pause -> {
                drawRect(color, topLeft = Offset(w * 0.18f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.22f, h))
                drawRect(color, topLeft = Offset(w * 0.60f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.22f, h))
            }
            Glyph.Next -> {
                drawPath(triangle(0f, w * 0.78f, true), color)
                drawRect(color, topLeft = Offset(w * 0.80f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.16f, h))
            }
            Glyph.Previous -> {
                drawRect(color, topLeft = Offset(w * 0.04f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.16f, h))
                drawPath(triangle(w * 0.22f, w, false), color)
            }
        }
    }
}

/** Heart outline or filled. */
@Composable
fun HeartIcon(filled: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.9f)
            cubicTo(w * 0.08f, h * 0.62f, w * 0.0f, h * 0.2f, w * 0.27f, h * 0.12f)
            cubicTo(w * 0.40f, h * 0.08f, w * 0.48f, h * 0.18f, w * 0.5f, h * 0.27f)
            cubicTo(w * 0.52f, h * 0.18f, w * 0.60f, h * 0.08f, w * 0.73f, h * 0.12f)
            cubicTo(w * 1.0f, h * 0.2f, w * 0.92f, h * 0.62f, w * 0.5f, h * 0.9f)
            close()
        }
        if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(width = w * 0.09f))
    }
}

/** Five-point star outline or filled. */
@Composable
fun StarIcon(filled: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawStar(Offset(size.width / 2f, size.height * 0.54f), size.minDimension * 0.5f, color, filled)
    }
}

/** A five-pointed star of radius [outer] around [center]: solid, or as an outline. */
fun DrawScope.drawStar(center: Offset, outer: Float, color: Color, filled: Boolean) {
    val inner = outer * 0.42f
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = (-90.0 + i * 36.0) * PI / 180.0
        val x = center.x + (r * cos(a)).toFloat()
        val y = center.y + (r * sin(a)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(width = outer * 0.16f))
}

/**
 * Centered status text for empty / not-connected states, on a liquid glass card. Pass the
 * screen's [scene] if it already has a glass surface; otherwise the message brings its own.
 */
@Composable
fun StatusMessage(connection: Connection, hasState: Boolean, player: String, scene: GlassScene? = null) {
    val (title, body, busy) = when {
        connection == Connection.NoPhone -> Triple("Phone not connected", "Check Bluetooth and that your phone is nearby.", false)
        connection == Connection.NoPhoneApp -> Triple("Phone app needed", "Install Cover Display on your phone and allow access.", false)
        !hasState && connection == Connection.Searching -> Triple("Connecting…", "", true)
        !hasState -> Triple("Waiting for phone", "Open Cover Display on your phone and allow access.", true)
        else -> Triple("Nothing playing", if (player.isNotBlank()) "Start a song in $player." else "Start a song on your phone.", false)
    }
    InfoCard(title, body, busy, scene)
}

/** A card of glass in the middle of the screen with a title, a line or two, and a spinner while [busy]. */
@Composable
fun InfoCard(title: String, body: String, busy: Boolean, scene: GlassScene? = null) {
    val glassScene = scene ?: run {
        val own = rememberGlassScene()
        Box(Modifier.fillMaxSize().glassBackdrop(own).background(Color.Black))
        GlassSurface(own)
        own
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .glass(glassScene, GlassLooks.Card)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(28.dp), indicatorColor = Tuning.Accent)
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (body.isNotBlank()) {
                Text(
                    body,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * A short message from the phone over whatever page is up (e.g. why a picked song didn't
 * start). It fades in and out; a tap closes it early.
 */
@Composable
fun NoticeOverlay(text: String?, onDismiss: () -> Unit) {
    val last = remember { arrayOfNulls<String>(1) } // kept while it fades out
    if (text != null) last[0] = text
    AnimatedVisibility(visible = text != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(260))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = last[0].orEmpty(),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                color = Color.White,
                modifier = Modifier
                    .padding(horizontal = 26.dp)
                    .background(Color(0xF0222226), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

package com.coverflow.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** One-time setup screen on the phone: grant access, check the watch, see what's detected. */
class MainActivity : ComponentActivity() {
    private var hasAccess by mutableStateOf(false)
    private var watchLinked by mutableStateOf(false)
    private var linkedTo by mutableStateOf(emptyList<String>())
    private var overlayAllowed by mutableStateOf(false)
    private var unrestricted by mutableStateOf(false)
    private var linkError by mutableStateOf<String?>(null)

    /** Shows the system's "link a device" dialog when it's ready. */
    private val linkDialog = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    SetupScreen(
                        hasAccess = hasAccess,
                        onGrant = ::openAccessSettings,
                        watchLinked = watchLinked,
                        linkedTo = linkedTo,
                        overlayAllowed = overlayAllowed,
                        linkError = linkError,
                        onLinkWatch = ::linkWatch,
                        onRelink = ::relinkWatch,
                        onAllowOverlay = ::openOverlaySettings,
                        unrestricted = unrestricted,
                        onAllowBackground = ::allowBackground,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasAccess = MediaHub.hasAccess(this)
        refreshPermissions()
        if (hasAccess) MediaHub.start(this)
    }

    private fun refreshPermissions() {
        linkedTo = CompanionLink.linkedTo(this)
        watchLinked = linkedTo.isNotEmpty()
        overlayAllowed = Settings.canDrawOverlays(this)
        unrestricted = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        if (watchLinked) linkError = null
    }

    /**
     * Link the watch as this app's companion device, through Android's dialog: the watch the
     * Wear connection names first, or a choice of nearby devices if that doesn't find it.
     */
    private fun linkWatch() {
        // (To find the watch among the phone's paired devices, by its address: see CompanionLink.)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            nearbyAsk.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        linkFound()
    }

    /** Asked for "Nearby devices" first: linked with or without it (without, the dialog may not find the watch). */
    private val nearbyAsk = registerForActivityResult(ActivityResultContracts.RequestPermission()) { linkFound() }

    private fun linkFound() {
        linkError = null
        lifecycleScope.launch {
            val name = WatchSync.watchName(this@MainActivity)
            val address = CompanionLink.watchAddress(this@MainActivity, name)
            fun attempt(watchName: String?) {
                CompanionLink.link(
                    activity = this@MainActivity,
                    watchName = watchName,
                    address = if (watchName != null) address else null,
                    showDialog = { linkDialog.launch(IntentSenderRequest.Builder(it).build()) },
                    linked = ::refreshPermissions,
                    failed = { reason -> if (watchName != null) attempt(null) else { linkError = reason } },
                )
            }
            attempt(name)
        }
    }

    /** Android's own "let this app run in the background?" (its battery use "Unrestricted"). */
    private fun allowBackground() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Lets go of what's linked (a device picked by mistake) and links the watch instead. */
    private fun relinkWatch() {
        CompanionLink.unlink(this)
        refreshPermissions()
        linkWatch()
    }

    /** "Display over other apps": the only way Android lets a background app open another app. */
    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun openAccessSettings() {
        val component = MediaHub.listenerComponent(this).flattenToString()
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        } else null
        try {
            startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }
}

@Composable
private fun SetupScreen(
    hasAccess: Boolean,
    onGrant: () -> Unit,
    watchLinked: Boolean,
    linkedTo: List<String>,
    overlayAllowed: Boolean,
    linkError: String?,
    onLinkWatch: () -> Unit,
    onRelink: () -> Unit,
    onAllowOverlay: () -> Unit,
    unrestricted: Boolean,
    onAllowBackground: () -> Unit,
) {
    val ctx = LocalContext.current
    val state by MediaHub.state.collectAsStateWithLifecycle()
    var watches by remember { mutableStateOf<List<String>?>(null) }
    var online by remember { mutableStateOf(Prefs.onlineLookup(ctx)) }
    var players by remember { mutableStateOf(emptyList<String>()) }
    var excluded by remember { mutableStateOf(Prefs.excludedPlayers(ctx)) }
    var likeSetup by remember { mutableStateOf<MediaHub.LikeSetup?>(null) }
    val lastTry by Launcher.lastTry.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Launcher.restoreLastTry(ctx) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Only while the screen shows (a LaunchedEffect alone runs on after Home, for as long as the app lives).
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                watches = try {
                    Wearable.getNodeClient(ctx).connectedNodes.await().map { it.displayName }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                players = withContext(Dispatchers.IO) { Players.candidates(ctx) }
                likeSetup = MediaHub.likeSetup()
                delay(4_000)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Cover Display", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "This app sends what your phone is playing to the Cover Display app on your Galaxy Watch.",
            style = MaterialTheme.typography.bodyMedium,
        )

        StepCard(
            title = "1. Allow access to media",
            ok = hasAccess,
            body = if (hasAccess) {
                "Granted. Cover Display can see your music apps." +
                    if (!MediaListenerService.isConnected) {
                        "\n\nAndroid hasn't reconnected Cover Display to your notifications since it was updated, so it " +
                            "can't tell when a music app is closed (the watch then shows the recently played songs only a " +
                            "few minutes after music stops). Turn Cover Display's notification access off and on again, or " +
                            "restart the phone."
                    } else ""
            } else "Android only lets apps read what's playing through notification access. " +
                "Cover Display doesn't read your notifications.\n\nIf the switch is greyed out: App info → ⋮ → " +
                "Allow restricted settings, then try again.",
        ) {
            if (!hasAccess) Button(onClick = onGrant) { Text("Grant access") }
        }

        val connected = watches
        StepCard(
            title = "2. Watch",
            ok = !connected.isNullOrEmpty(),
            body = when {
                connected == null -> "Checking…"
                connected.isEmpty() -> "No watch connected. Make sure your Galaxy Watch is paired and nearby."
                else -> "Connected: " + connected.joinToString()
            },
        )

        StepCard(
            title = "3. Keep running in the background",
            ok = unrestricted,
            body = if (unrestricted) {
                "Allowed. Cover Display hears of every song as it starts, and the watch with it."
            } else "Allow Cover Display to run in the background (its battery use \"Unrestricted\"). Without it, the " +
                "phone freezes it while it isn't open, and later closes it: the watch then hears of no new song, and " +
                "its face's album art doesn't change, until Cover Display is opened on the watch. Uninstalling and " +
                "reinstalling Cover Display (rather than installing over it) takes this away: allow it again then.",
        ) {
            if (!unrestricted) Button(onClick = onAllowBackground) { Text("Allow") }
        }

        StepCard(
            title = "4. Now playing",
            ok = state?.current != null,
            body = state?.current?.let { cur ->
                buildString {
                    append(cur.title)
                    if (cur.artist.isNotBlank()) append(" — ").append(cur.artist)
                    append("\non ").append(state?.player.orEmpty())
                    val s = state
                    if (s != null) {
                        append("\n")
                        append(if (s.listIsQueue) "Queue shared: ${s.list.size} songs" else "Queue not shared by this app; showing ${s.list.size} recently played")
                    }
                }
            } ?: if (hasAccess) "Play something in any music app." else "Waiting for access.",
        )

        likeSetup?.let { setup ->
            LikeButtonCard(
                setup = setup,
                onChoose = { key ->
                    Prefs.setLikeButton(ctx, setup.pkg, key)
                    likeSetup = MediaHub.likeSetup()
                },
                onTry = { key ->
                    MediaHub.pressNow(setup.pkg, key)
                    scope.launch {
                        delay(1_500) // give the player a moment to update its buttons
                        likeSetup = MediaHub.likeSetup()
                    }
                },
            )
        }

        StepCard(
            title = "Play songs when nothing is playing",
            ok = watchLinked || overlayAllowed,
            body = "When nothing is playing, the watch shows your recently played songs, and picking one plays it " +
                "in the app it came from. Cover Display wakes the app in the background and checks the right song " +
                "starts. Some apps only start once they're opened, and Android only lets Cover Display open them if " +
                "your watch is linked to it (Android's companion device link) or it may \"display over other apps\"." +
                when {
                    watchLinked -> "\n\nLinked to " + linkedTo.joinToString() + "."
                    overlayAllowed -> "\n\nAllowed to display over other apps."
                    else -> ""
                } +
                (linkError?.let { "\n\nCouldn't link: $it" } ?: "") +
                (if (lastTry.isNotEmpty()) "\n\nLast song picked on the watch:\n" + lastTry.joinToString("\n") { "• $it" } else ""),
        ) {
            if (!watchLinked) {
                Button(onClick = onLinkWatch) { Text("Link watch") }
                if (!overlayAllowed) {
                    TextButton(onClick = onAllowOverlay) { Text("Or allow display over other apps") }
                }
            } else {
                // (Linked to something that isn't the watch, picked from the list of nearby devices.)
                TextButton(onClick = onRelink) { Text("Link a different device") }
            }
            if (lastTry.isNotEmpty()) {
                // For pasting into a message when a song won't start.
                TextButton(onClick = {
                    val text = "Cover Display, last song picked on the watch:\n" + lastTry.joinToString("\n")
                    ctx.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Cover Display steps", text))
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Steps copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy these steps") }
            }
        }

        YouTubeKeyCard()

        PlayersCard(
            players = players,
            excluded = excluded,
            playingPackage = state?.playerPackage.orEmpty(),
            onToggle = { pkg, include ->
                Prefs.setPlayerExcluded(ctx, pkg, !include)
                excluded = Prefs.excludedPlayers(ctx)
                MediaHub.refreshExclusions(ctx)
            },
            onClearHistory = { MediaHub.clearHistory(ctx) },
        )

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Look up covers, albums and lyrics online", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Uses Apple's iTunes Search for missing covers and album tracklists, and LRCLIB for synced lyrics.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = online, onCheckedChange = {
                    online = it
                    Prefs.setOnlineLookup(ctx, it)
                })
            }
        }
    }
}

/**
 * "Like button": which of the playing app's buttons Cover Flow presses to like a song when you
 * rate it 3 stars or more. Detected automatically; pick one here if detection gets it wrong.
 */
@Composable
private fun LikeButtonCard(setup: MediaHub.LikeSetup, onChoose: (String?) -> Unit, onTry: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Liking songs in ${setup.appName}", fontWeight = FontWeight.SemiBold)
            val active = setup.chosenKey?.takeIf { k -> setup.buttons.any { it.key == k } } ?: setup.detectedKey
            val activeLabel = setup.buttons.firstOrNull { it.key == active }?.label
            Text(
                when {
                    setup.ratingApi -> "${setup.appName} accepts likes directly. Rating a song 3★ or more on the watch likes it here."
                    setup.buttons.isEmpty() -> "${setup.appName} doesn't share any buttons with other apps, so likes can only be saved in Cover Display."
                    activeLabel != null && setup.chosenKey != null -> "Using \"$activeLabel\" (your choice) to like songs."
                    activeLabel != null -> "Found its like button: \"$activeLabel\"."
                    else -> "No like button recognised. If one of these is the like / love / favourite button, tap it:"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!setup.ratingApi && setup.buttons.isNotEmpty()) {
                Text(
                    "Buttons ${setup.appName} offers. Select the one Cover Display should use as the like button. " +
                        "Not sure which? Tap Try next to each while a song plays and watch ${setup.appName}: " +
                        "use the one that likes / favourites the song. (Tap Try again to undo it.)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                for (b in setup.buttons) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(b.key) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = b.key == active, onClick = { onChoose(b.key) })
                        Text(
                            b.label + (if (b.fromNotification) "  (notification)" else ""),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onTry(b.key) }) { Text("Try") }
                    }
                }
                if (setup.chosenKey != null) {
                    TextButton(onClick = { onChoose(null) }) { Text("Detect automatically") }
                }
            }
        }
    }
}

/** "Music apps": switch off players (YouTube, podcasts, a browser…) you don't want on the watch. */
@Composable
private fun PlayersCard(
    players: List<String>,
    excluded: Set<String>,
    playingPackage: String,
    onToggle: (pkg: String, include: Boolean) -> Unit,
    onClearHistory: () -> Unit,
) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Music apps", fontWeight = FontWeight.SemiBold)
            Text(
                "Switch off apps you don't want on the watch, like YouTube, podcasts or a browser. " +
                    "Their playback is ignored and the watch shows the next app that's playing.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (players.isEmpty()) {
                Text(
                    "Apps show up here once they've played something.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            for (pkg in players) key(pkg) {
                val label = remember(pkg) { Players.label(ctx, pkg) }
                val icon by produceState<ImageBitmap?>(null, pkg) {
                    value = withContext(Dispatchers.IO) {
                        try {
                            ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(32.dp)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        val note = when {
                            pkg in excluded -> "Ignored"
                            pkg == playingPackage -> "On the watch now"
                            else -> null
                        }
                        if (note != null) {
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Switch(checked = pkg !in excluded, onCheckedChange = { onToggle(pkg, it) })
                }
            }
            TextButton(onClick = onClearHistory) { Text("Clear recently played") }
        }
    }
}

/**
 * YouTube Music only starts a picked song from its link and shares no ids of its own, so each
 * song's video is looked up online; with the user's own YouTube API key it's searched for on
 * YouTube itself, which finds songs played as music videos too.
 */
@Composable
private fun YouTubeKeyCard() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.youtubeApiKey(ctx).orEmpty()) }
    var saved by remember { mutableStateOf(Prefs.youtubeApiKey(ctx) != null) }
    StepCard(
        title = "YouTube Music picks (optional)",
        ok = saved,
        body = "YouTube Music only starts a song you pick on the watch from the song's YouTube Music link, " +
            "and doesn't share its songs' IDs, so Cover Display searches YouTube for each song. Without a key it " +
            "uses YouTube Music's own web search (unofficial, so it could stop working if YouTube changes it), " +
            "then the iTunes and Deezer catalogs with Songlink. With your own free YouTube API key it uses " +
            "YouTube's official search instead:\n" +
            "1. In Google Cloud Console (console.cloud.google.com), create a project.\n" +
            "2. Enable \"YouTube Data API v3\" for it.\n" +
            "3. Credentials → Create credentials → API key, and paste it here.\n" +
            "It allows about 100 searches a day for free, and each song is only looked up once.",
    ) {
        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                saved = false
            },
            label = { Text("YouTube API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                Prefs.setYoutubeApiKey(ctx, key)
                saved = key.isNotBlank()
            }) { Text("Save") }
            if (saved) Text("Saved.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StepCard(title: String, ok: Boolean, body: String, action: @Composable () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (ok) "✓" else "•",
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            action()
        }
    }
}

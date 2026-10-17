package com.inferno.os

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF0B0B0B)
private val CardBg = Color(0xFFF3F3F3)
private val Mute = Color(0xFF8C8C8C)
private val RecRed = Color(0xFFE5392B)

private val RUNTIME_PERMS = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.BLUETOOTH_CONNECT,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.init(this)
        Recordings.refresh(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, background = Color.White, surface = Color.White)) {
                Root()
            }
        }
    }
}

private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private fun listenerOn(ctx: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

private fun missingSetup(ctx: Context): List<String> = buildList {
    if (!granted(ctx, Manifest.permission.RECORD_AUDIO)) add("microphone")
    if (!granted(ctx, Manifest.permission.READ_CONTACTS)) add("contacts")
    if (!granted(ctx, Manifest.permission.CALL_PHONE)) add("calls")
    if (!granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) add("notifications")
    if (!granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)) add("bluetooth")
    if (!listenerOn(ctx)) add("WhatsApp access")
    if (!Settings.canDrawOverlays(ctx)) add("display over apps")
}

private fun sendToService(ctx: Context, action: String? = null, text: String? = null) {
    val i = Intent(ctx, VoiceService::class.java)
    if (action != null) i.action = action
    if (text != null) i.putExtra(VoiceService.EXTRA_TEXT, text)
    ContextCompat.startForegroundService(ctx, i)
}

private fun fmtDur(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

private fun fmtDate(id: Long): String =
    SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(id))

/** First sentence(s) of the SUMMARY section, for the list preview. */
private fun previewOf(brief: String?): String {
    if (brief == null) return "No brief yet"
    val lines = brief.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val i = lines.indexOfFirst { it.uppercase().startsWith("SUMMARY") }
    val first = if (i >= 0) lines.drop(i + 1).firstOrNull() else lines.firstOrNull()
    return first ?: "No brief yet"
}

// ------------------------------------------------------------------ navigation

@Composable
private fun Root() {
    var screen by remember { mutableStateOf("home") }
    var openId by remember { mutableStateOf<Long?>(null) }

    BackHandler(enabled = screen != "home") {
        when {
            screen == "recordings" && openId != null -> openId = null
            screen == "voice" -> screen = "profile"
            else -> screen = "home"
        }
    }

    when (screen) {
        "profile" -> ProfileScreen(onBack = { screen = "home" }, onVoice = { screen = "voice" })
        "voice" -> VoiceScreen(onBack = { screen = "profile" })
        "recordings" -> RecordingsScreen(
            openId = openId,
            onOpen = { openId = it },
            onBack = { if (openId != null) openId = null else screen = "home" }
        )
        else -> Home(onProfile = { screen = "profile" }, onRecordings = { screen = "recordings" })
    }
}

// ------------------------------------------------------------------ shared pieces

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.White)
            .statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(CardBg).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Text("←", fontSize = 20.sp, color = Ink) }
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            content = content
        )
    }
}

@Composable
private fun Dot(active: Boolean, color: Color? = null) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color ?: if (active) Ink else Color(0xFFBDBDBD)))
}

@Composable
private fun Label(t: String) {
    Text(
        t.uppercase(), color = Mute, fontSize = 13.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 28.dp, bottom = 12.dp)
    )
}

@Composable
private fun Note(t: String) {
    Text(t, color = Mute, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Pill(text: String, dark: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (dark) Ink else CardBg)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)
    ) { Text(text, color = if (dark) Color.White else Ink, fontSize = 15.sp) }
}

@Composable
private fun PillField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        placeholder = { Text(hint, color = Mute) },
        singleLine = true,
        shape = RoundedCornerShape(50),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = CardBg,
            unfocusedContainerColor = CardBg,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = Ink,
        )
    )
}

@Composable
private fun Tile(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    dark: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .heightIn(min = 140.dp)
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(active, dotColor)
            Spacer(Modifier.width(8.dp))
            Text(label, color = Mute, fontSize = 13.sp, letterSpacing = 1.5.sp)
        }
        Text(
            value, fontSize = 24.sp, fontWeight = if (dark) FontWeight.Medium else FontWeight.Normal,
            color = if (active || dark) Ink else Mute
        )
    }
}

@Composable
private fun Avatar(initial: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) {
        Text(initial, color = Color.White, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun UsageBlock(onClick: (() -> Unit)? = null) {
    val u by Usage.today.collectAsStateWithLifecycle()
    val limit by Prefs.dailyLimit.collectAsStateWithLifecycle()
    var now by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now++
        }
    }
    val fresh = u.day == Usage.dayKey()
    val used = if (fresh) u.requests else 0
    val tokens = if (fresh) u.tokens else 0L
    val left = (limit - used).coerceAtLeast(0)
    val frac = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 1f
    val ms = remember(now, u) { Usage.msToReset() }
    val reset = "resets in ${ms / 3_600_000}h ${(ms / 60_000) % 60}m"

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(CardBg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(20.dp)
    ) {
        Text("$left left today", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Ink)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = if (frac > 0.9f) RecRed else Ink,
            trackColor = Color(0xFFDDDDDD),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "$used of $limit requests used · %,d tokens · $reset".format(tokens),
            color = Mute, fontSize = 13.sp, lineHeight = 18.sp
        )
    }
}

// ------------------------------------------------------------------ home

@Composable
private fun Home(onProfile: () -> Unit, onRecordings: () -> Unit) {
    val ctx = LocalContext.current
    val status by InfernoState.status.collectAsStateWithLifecycle()
    val on by InfernoState.serviceOn.collectAsStateWithLifecycle()
    val glasses by InfernoState.glasses.collectAsStateWithLifecycle()
    val listening by InfernoState.listening.collectAsStateWithLifecycle()
    val recording by InfernoState.recording.collectAsStateWithLifecycle()
    val busy by InfernoState.busy.collectAsStateWithLifecycle()
    val heard by InfernoState.lastHeard.collectAsStateWithLifecycle()
    val reply by InfernoState.lastReply.collectAsStateWithLifecycle()
    val recs by InfernoState.recordings.collectAsStateWithLifecycle()
    val hasKey by InfernoState.hasKey.collectAsStateWithLifecycle()
    val accountName by Prefs.accountName.collectAsStateWithLifecycle()

    var tick by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    val missing = remember(tick) { missingSetup(ctx) }
    val hasMic = remember(tick) { granted(ctx, Manifest.permission.RECORD_AUDIO) }

    LaunchedEffect(hasMic) {
        if (hasMic && !InfernoState.serviceOn.value) sendToService(ctx)
    }

    fun grant() {
        val denied = RUNTIME_PERMS.filter { !granted(ctx, it) }
        when {
            denied.isNotEmpty() -> launcher.launch(denied.toTypedArray())
            !listenerOn(ctx) -> ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            !Settings.canDrawOverlays(ctx) -> ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            )
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.White)
            .statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    buildAnnotatedString {
                        append("Inferno ")
                        withStyle(SpanStyle(color = Mute)) { append("OS") }
                    },
                    fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Ink
                )
                Box(Modifier.clip(CircleShape).clickable(onClick = onProfile)) {
                    Avatar(accountName.trim().take(1).uppercase().ifEmpty { "I" }, 48)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 8.dp)) { Dot(on) }
                Spacer(Modifier.width(12.dp))
                Text(
                    if (on) status else "Voice is off. Typing still works.",
                    fontSize = 18.sp, lineHeight = 26.sp, color = Color(0xFF3A3A3A)
                )
            }

            if (!hasKey) {
                Spacer(Modifier.height(16.dp))
                Pill("Sign in or add API key", onClick = onProfile)
            }
            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Pill("Grant access: ${missing.joinToString(", ")}") { grant() }
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tile(
                    label = "GLASSES",
                    value = if (glasses) "Connected" else "Not connected",
                    active = glasses,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {}
                Tile(
                    label = "INFERNO",
                    value = when {
                        !on -> "Off"
                        recording -> "Recording"
                        busy -> "Thinking"
                        listening -> "Listening"
                        else -> "On"
                    },
                    active = on,
                    dotColor = if (recording) RecRed else null,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    if (on) sendToService(ctx, VoiceService.ACTION_QUIT) else if (hasMic) sendToService(ctx) else grant()
                }
            }

            Label("Do things")
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tile(
                    label = "RECORD",
                    value = if (recording) "Stop & brief" else "Record",
                    active = recording,
                    dotColor = if (recording) RecRed else null,
                    dark = true,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    if (hasMic) sendToService(ctx, VoiceService.ACTION_REC_TOGGLE) else grant()
                }
                Tile(
                    label = "RECORDINGS",
                    value = if (recs.isEmpty()) "None yet" else "${recs.size} saved",
                    active = recs.isNotEmpty(),
                    dark = true,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = onRecordings
                )
            }

            Label("Usage")
            UsageBlock(onClick = onProfile)

            Label("Conversation")
            if (heard.isBlank() && reply.isBlank()) {
                Text("Say “Hey Inferno” or type below.", fontSize = 18.sp, lineHeight = 26.sp, color = Mute)
            } else {
                if (heard.isNotBlank()) {
                    Text("You", color = Mute, fontSize = 13.sp)
                    Text(heard, fontSize = 18.sp, lineHeight = 26.sp, color = Ink)
                    Spacer(Modifier.height(14.dp))
                }
                if (reply.isNotBlank()) {
                    Text("Inferno", color = Mute, fontSize = 13.sp)
                    Text(reply, fontSize = 18.sp, lineHeight = 26.sp, color = Ink)
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillField(input, { input = it }, "Type to Inferno", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Ink).clickable {
                    val t = input.trim()
                    if (t.isNotEmpty()) {
                        sendToService(ctx, VoiceService.ACTION_ASK, t)
                        input = ""
                    }
                },
                contentAlignment = Alignment.Center
            ) { Text("↑", color = Color.White, fontSize = 22.sp) }
        }
    }
}

// ------------------------------------------------------------------ profile

@Composable
private fun ProfileScreen(onBack: () -> Unit, onVoice: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val name by Prefs.accountName.collectAsStateWithLifecycle()
    val email by Prefs.accountEmail.collectAsStateWithLifecycle()
    val limit by Prefs.dailyLimit.collectAsStateWithLifecycle()
    val quiet by Prefs.quiet.collectAsStateWithLifecycle()
    val engine by Prefs.engine.collectAsStateWithLifecycle()
    val geminiVoice by Prefs.geminiVoice.collectAsStateWithLifecycle()
    val hasKey by InfernoState.hasKey.collectAsStateWithLifecycle()

    var keyInput by remember { mutableStateOf("") }
    var limitText by remember { mutableStateOf(limit.toString()) }
    var msg by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            try {
                val r = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data)
                GoogleAuth.complete(ctx, r)
                msg = ""
            } catch (e: Exception) {
                msg = GoogleAuth.explain(e)
            }
        }
    }

    fun signIn() {
        msg = "Opening Google…"
        scope.launch {
            try {
                val r = GoogleAuth.authorize(ctx)
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    GoogleAuth.complete(ctx, r)
                    msg = ""
                }
            } catch (e: Exception) {
                msg = GoogleAuth.explain(e)
            }
        }
    }

    Page("Profile", onBack) {
        Label("Google account")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(CardBg).padding(20.dp)) {
            if (email.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name.trim().take(1).uppercase().ifEmpty { "I" }, 48)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(name, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Ink)
                        Text(email, fontSize = 14.sp, color = Mute)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row { Pill("Sign out", dark = false) { GoogleAuth.signOut() } }
            } else {
                Text("Not signed in", fontSize = 18.sp, color = Ink)
                Spacer(Modifier.height(14.dp))
                Row { Pill("Sign in with Google") { signIn() } }
            }
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(msg, color = RecRed, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Note("Signed in, Gemini runs on your Google account's own project and quota. Needs a one-time Google Cloud setup (see README). The API key below works without it.")

        Label("API key")
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillField(
                keyInput, { keyInput = it },
                if (Keys.current.isNotBlank()) "Key saved · paste to replace" else "Paste Gemini API key",
                Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Pill("Save") {
                if (keyInput.isNotBlank()) {
                    Keys.save(ctx, keyInput)
                    keyInput = ""
                }
            }
        }
        Note(if (hasKey) "Ready. Signed-in account is used first, the key is the fallback." else "Nothing set yet.")

        Label("Usage")
        UsageBlock()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daily limit", color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
            PillField(
                limitText,
                { s ->
                    val digits = s.filter { it.isDigit() }.take(6)
                    limitText = digits
                    digits.toIntOrNull()?.let { Prefs.setLimit(it) }
                },
                "250", Modifier.width(130.dp), number = true
            )
        }
        Note("Google doesn't report remaining quota to apps. This counts requests made from this phone against the limit you set (check your tier in Google AI Studio). It resets at midnight Pacific time.")

        Label("Voice")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(CardBg)
                .clickable(onClick = onVoice).padding(20.dp)
        ) {
            Text("AI voice", color = Mute, fontSize = 13.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                if (engine == "gemini") "Gemini · $geminiVoice" else "Phone voice",
                fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink
            )
        }

        Label("Sounds")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(CardBg).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Silence listening beeps", fontSize = 16.sp, color = Ink)
                Text(
                    "Mutes media volume while Inferno listens, so the system beep can't be heard.",
                    fontSize = 13.sp, color = Mute, lineHeight = 18.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = quiet,
                onCheckedChange = { Prefs.setQuiet(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Ink)
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ------------------------------------------------------------------ voice

@Composable
private fun VoiceScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine by Prefs.engine.collectAsStateWithLifecycle()
    val phoneVoice by Prefs.phoneVoice.collectAsStateWithLifecycle()
    val geminiVoice by Prefs.geminiVoice.collectAsStateWithLifecycle()
    val rate by Prefs.rate.collectAsStateWithLifecycle()
    val hasKey by InfernoState.hasKey.collectAsStateWithLifecycle()

    var ready by remember { mutableStateOf(false) }
    val speaker = remember { Speaker(ctx, scope) {} }
    DisposableEffect(Unit) {
        speaker.start { ready = true }
        onDispose { speaker.shutdown() }
    }
    val phoneVoices = remember(ready) { if (ready) speaker.phoneVoices() else emptyList() }
    val sample = "Hey, I'm Inferno. I'm ready to help you."

    Page("AI voice", onBack) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Phone voices", dark = engine == "phone") { Prefs.setEngine("phone") }
            Pill("Gemini voices", dark = engine == "gemini") { Prefs.setEngine("gemini") }
        }

        if (engine == "phone") {
            Label("Choose a voice")
            if (phoneVoices.isEmpty()) {
                Text(
                    if (ready) "No extra voices installed. Add some in Settings > Text-to-speech." else "Loading voices…",
                    color = Mute, fontSize = 15.sp
                )
            }
            phoneVoices.forEach { (id, label) ->
                val selected = id == phoneVoice
                VoiceRow(label, selected) {
                    Prefs.setPhoneVoice(id)
                    speaker.speak(sample, glasses = false, notifyWhenDone = false)
                }
            }
            Label("Speed")
            Slider(
                value = rate,
                onValueChange = { Prefs.setRate(it) },
                onValueChangeFinished = { speaker.speak(sample, glasses = false, notifyWhenDone = false) },
                valueRange = 0.6f..1.6f,
                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink)
            )
            Note("Free, instant and works offline.")
        } else {
            Label("Choose a voice")
            Speaker.GEMINI_VOICES.forEach { (id, label) ->
                VoiceRow(label, id == geminiVoice) {
                    Prefs.setGeminiVoice(id)
                    if (hasKey) speaker.speak(sample, glasses = false, notifyWhenDone = false)
                }
            }
            Note(
                if (hasKey) "More natural, but each reply uses an extra Gemini request and takes a moment longer. If it fails, Inferno falls back to the phone voice."
                else "Sign in or add an API key to hear these."
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(22.dp))
            .background(if (selected) Ink else CardBg).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (selected) Color.White else Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = Color.White, fontSize = 16.sp)
    }
}

// ------------------------------------------------------------------ recordings

@Composable
private fun RecordingsScreen(openId: Long?, onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val recs by InfernoState.recordings.collectAsStateWithLifecycle()
    val rec = recs.firstOrNull { it.id == openId }
    if (rec != null) {
        RecordingDetail(rec, onBack)
        return
    }
    Page("Recordings", onBack) {
        Spacer(Modifier.height(8.dp))
        if (recs.isEmpty()) {
            Text(
                "Nothing yet. Tap Record, or say “Hey Inferno, start recording”. Each recording and its brief show up here.",
                fontSize = 17.sp, lineHeight = 24.sp, color = Mute
            )
        }
        recs.forEach { r ->
            Column(
                Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(24.dp))
                    .background(CardBg).clickable { onOpen(r.id) }.padding(18.dp)
            ) {
                Row {
                    Text(fmtDate(r.id), color = Mute, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(fmtDur(r.durationMs), color = Mute, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    previewOf(r.brief), fontSize = 16.sp, lineHeight = 22.sp,
                    color = if (r.brief == null) Mute else Ink, maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RecordingDetail(rec: Rec, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    DisposableEffect(rec.id) {
        onDispose {
            try { player?.release() } catch (e: Exception) { }
            player = null
        }
    }

    fun togglePlay() {
        val p = player
        try {
            if (p == null) {
                val np = MediaPlayer()
                np.setDataSource(rec.audio.absolutePath)
                np.prepare()
                np.setOnCompletionListener { playing = false }
                np.start()
                player = np
                playing = true
            } else if (p.isPlaying) {
                p.pause()
                playing = false
            } else {
                p.start()
                playing = true
            }
        } catch (e: Exception) {
            err = "Couldn't play this recording."
        }
    }

    Page(fmtDate(rec.id), onBack) {
        Text(fmtDur(rec.durationMs), color = Mute, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(if (playing) "Pause" else "Play") { togglePlay() }
            Pill(
                when {
                    busy -> "Writing…"
                    rec.brief == null -> "Make brief"
                    else -> "Redo brief"
                },
                dark = false
            ) {
                if (!busy) {
                    busy = true
                    err = ""
                    scope.launch {
                        try {
                            Recordings.brief(ctx, rec)
                        } catch (e: Exception) {
                            err = e.message ?: "Couldn't write the brief."
                        }
                        busy = false
                    }
                }
            }
        }
        if (err.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(err, color = RecRed, fontSize = 13.sp, lineHeight = 18.sp)
        }

        Label("Brief")
        if (rec.brief == null) {
            Text("No brief yet. Tap Make brief.", color = Mute, fontSize = 16.sp)
        } else {
            SelectionContainer {
                Text(rec.brief, fontSize = 16.sp, lineHeight = 24.sp, color = Ink)
            }
        }

        Spacer(Modifier.height(28.dp))
        Row {
            Pill(if (confirmDelete) "Tap again to delete" else "Delete", dark = false) {
                if (confirmDelete) {
                    try { player?.release() } catch (e: Exception) { }
                    player = null
                    Recordings.delete(ctx, rec.id)
                    onBack()
                } else {
                    confirmDelete = true
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

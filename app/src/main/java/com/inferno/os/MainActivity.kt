package com.inferno.os

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
        InfernoState.briefs.value = Briefs.load(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, background = Color.White, surface = Color.White)) {
                Home()
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

@Composable
private fun Home() {
    val ctx = LocalContext.current
    val status by InfernoState.status.collectAsStateWithLifecycle()
    val on by InfernoState.serviceOn.collectAsStateWithLifecycle()
    val glasses by InfernoState.glasses.collectAsStateWithLifecycle()
    val listening by InfernoState.listening.collectAsStateWithLifecycle()
    val recording by InfernoState.recording.collectAsStateWithLifecycle()
    val busy by InfernoState.busy.collectAsStateWithLifecycle()
    val heard by InfernoState.lastHeard.collectAsStateWithLifecycle()
    val reply by InfernoState.lastReply.collectAsStateWithLifecycle()
    val briefs by InfernoState.briefs.collectAsStateWithLifecycle()

    var tick by remember { mutableIntStateOf(0) }
    var showBriefs by remember { mutableStateOf(false) }
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
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        append("Inferno ")
                        withStyle(SpanStyle(color = Mute)) { append("OS") }
                    },
                    fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Ink
                )
                Box(Modifier.size(48.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) {
                    Text("I", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
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

            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Ink).clickable { grant() }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text("Grant access: ${missing.joinToString(", ")}", color = Color.White, fontSize = 14.sp)
                }
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
                    label = "BRIEFS",
                    value = if (briefs.isEmpty()) "None yet" else "${briefs.size} saved",
                    active = briefs.isNotEmpty(),
                    dark = true,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) { showBriefs = !showBriefs }
            }

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

            if (showBriefs) {
                Label("Briefs")
                if (briefs.isEmpty()) {
                    Text("Record a conversation and its brief lands here.", fontSize = 18.sp, lineHeight = 26.sp, color = Mute)
                }
                briefs.forEach {
                    BriefCard(it)
                    Spacer(Modifier.height(12.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type to Inferno", color = Mute) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CardBg,
                    unfocusedContainerColor = CardBg,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = Ink,
                )
            )
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
private fun BriefCard(b: Brief) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg)
            .clickable { open = !open }.padding(18.dp)
    ) {
        Text(b.name, color = Mute, fontSize = 12.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            b.text, fontSize = 15.sp, lineHeight = 21.sp, color = Ink,
            maxLines = if (open) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis
        )
    }
}

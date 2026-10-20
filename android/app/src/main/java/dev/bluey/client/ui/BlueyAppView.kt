package dev.bluey.client.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bluey.client.link.PcLink
import dev.bluey.client.realtime.LiveVoice
import dev.bluey.client.session.BlueySession
import dev.bluey.client.session.SessionStore
import dev.bluey.client.session.TranscriptEntry
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bluey's own app screen: start or end a session, see past sessions and their
 * transcripts, and settings. Opened from the faint button in the top-right corner of
 * his face. Port of <c>BlueyAppView.swift</c>.
 */
@Composable
fun BlueyAppView(
    store: SessionStore,
    live: LiveVoice,
    link: PcLink,
    onClose: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = selected != null) { selected = null }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Sidebar(store, live, link, onClose, Modifier.width(300.dp))
        if (selected == null) {
            SessionList(store, onOpen = { selected = it }, modifier = Modifier.weight(1f))
        } else {
            SessionDetail(store, selected!!) { selected = null }
        }
    }
}

// MARK: Left: who he is, start/end, settings

@Composable
private fun Sidebar(
    store: SessionStore,
    live: LiveVoice,
    link: PcLink,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected by link.connected.collectAsState()
    val pcName by link.pcName.collectAsState()
    val pcs by link.pcs.collectAsState()
    val currentPc by link.currentPc.collectAsState()
    var volume by remember { mutableStateOf(live.volume.toFloat()) }

    Column(
        modifier
            .fillMaxHeight()
            .background(Palette.panel.copy(alpha = 0.6f))
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniBluey(Modifier.size(width = 54.dp, height = 48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Bluey", color = Color.White, fontSize = 28.sp, fontFamily = BlueyFonts.fredoka)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (connected) Color(0xFF5BE49B)
                                else Palette.inkSoft.copy(alpha = 0.5f),
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (connected) (pcName ?: currentPc ?: "PC") else "Looking for your PC",
                        color = Palette.inkSoft,
                        fontSize = 12.sp,
                        fontFamily = BlueyFonts.plexSans,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        val starting = live.state == LiveVoice.State.Asleep
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .then(
                    if (starting) Modifier.background(BlobShape.gradientBrush(260f, 50f))
                    else Modifier.background(endRed),
                )
                .clickable(enabled = connected || !starting) {
                    if (starting) {
                        live.wake()
                        onClose() // back to his face when starting
                    } else {
                        live.sleep()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (starting) "▶" else "■",
                    color = Color.White,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (starting) "Start session" else "End session",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontFamily = BlueyFonts.fredoka,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            if (starting) {
                "He listens the whole session. Hold his face to ask something, let go and he answers."
            } else {
                "Session running. Hold his face to ask; everything you say is saved here."
            },
            color = Palette.inkSoft,
            fontSize = 12.sp,
            fontFamily = BlueyFonts.plexSans,
        )

        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        Spacer(Modifier.height(18.dp))

        Text(
            "CHIRP VOLUME",
            color = Palette.inkSoft,
            fontSize = 11.sp,
            fontFamily = BlueyFonts.plexMono,
        )
        Slider(
            value = volume,
            onValueChange = {
                volume = it
                live.volume = it.toDouble()
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Palette.berry2,
                activeTrackColor = Palette.berry2,
                inactiveTrackColor = Color.White.copy(alpha = 0.12f),
            ),
        )

        if (pcs.size > 1) {
            Spacer(Modifier.height(8.dp))
            Text("PC", color = Palette.inkSoft, fontSize = 11.sp, fontFamily = BlueyFonts.plexMono)
            for (name in pcs) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clickable { link.choose(name) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = BlueyFonts.plexSans,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (name == currentPc) {
                        Text(
                            if (connected) "✓" else "•••",
                            color = Palette.berry2,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Back to his face",
                color = Color.White,
                fontSize = 15.sp,
                fontFamily = BlueyFonts.plexSans,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private val endRed = Color(0xFFE5547A)

// MARK: Right: sessions

@Composable
private fun SessionList(store: SessionStore, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.padding(horizontal = 22.dp).padding(top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Sessions",
                color = Color.White,
                fontSize = 24.sp,
                fontFamily = BlueyFonts.fredoka,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${store.sessions.size}",
                color = Palette.inkSoft,
                fontSize = 13.sp,
                fontFamily = BlueyFonts.plexMono,
            )
        }

        if (store.sessions.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("💬", color = Palette.berry2, fontSize = 34.sp)
                Spacer(Modifier.height(10.dp))
                Text("No sessions yet", color = Color.White, fontSize = 18.sp, fontFamily = BlueyFonts.fredoka)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Start one and your transcript and his replies show up here.",
                    color = Palette.inkSoft,
                    fontSize = 13.sp,
                    fontFamily = BlueyFonts.plexSans,
                )
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 22.dp)) {
                items(store.sessions, key = { it.id }) { session ->
                    val dismiss = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                store.delete(session.id)
                                true
                            } else false
                        },
                    )
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 22.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(endRed),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Text("Delete", color = Color.White, modifier = Modifier.padding(end = 22.dp))
                            }
                        },
                    ) {
                        SessionRow(
                            session = session,
                            live = session.id == store.currentID,
                            modifier = Modifier.clickable { onOpen(session.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: BlueySession, live: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Palette.panel)
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SessionStore.formatDate(session.started),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontFamily = BlueyFonts.plexSans,
                    fontWeight = FontWeight.SemiBold,
                )
                if (live) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "LIVE",
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontFamily = BlueyFonts.plexMono,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF5BE49B))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                session.summary,
                color = Palette.inkSoft,
                fontSize = 13.sp,
                fontFamily = BlueyFonts.plexSans,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                SessionStore.formatDuration(session.duration / 1000),
                color = Palette.inkSoft,
                fontSize = 12.sp,
                fontFamily = BlueyFonts.plexMono,
            )
            Text(
                "${session.questionCount} asked",
                color = Palette.berry1,
                fontSize = 11.sp,
                fontFamily = BlueyFonts.plexMono,
            )
        }
    }
}

// MARK: Detail

@Composable
private fun SessionDetail(store: SessionStore, id: String, onBack: () -> Unit) {
    val session = store.sessions.firstOrNull { it.id == id }
    if (session == null) {
        onBack()
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2_000)
            copied = false
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = Color.White, fontSize = 22.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    SessionStore.formatDate(session.started),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontFamily = BlueyFonts.fredoka,
                )
                Text(
                    "${SessionStore.formatDuration(session.duration / 1000)} · ${session.questionCount} asked" +
                        if (session.transcribing > 0) {
                            " · transcribing ${session.transcribing} chunk${if (session.transcribing == 1) "" else "s"}…"
                        } else "",
                    color = Palette.inkSoft,
                    fontSize = 11.sp,
                    fontFamily = BlueyFonts.plexMono,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.berry3)
                    .clickable {
                        clipboard.setText(AnnotatedString(copyPrompt(session)))
                        copied = true
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    if (copied) "Copied" else "Copy prompt for agent",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = BlueyFonts.plexSans,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable {
                        val intent = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, session.exportText)
                        context.startActivity(Intent.createChooser(intent, "Share session"))
                    },
                contentAlignment = Alignment.Center,
            ) { Text("↗", color = Color.White, fontSize = 16.sp) }
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(session.displayEntries, key = { it.id }) { entry ->
                EntryView(entry)
            }
        }
    }
}

/** A prompt for an agent that points it at this session's notes files (D10: Mac→PC). */
private fun copyPrompt(session: BlueySession): String {
    val root = "~/Documents/Bluey Notes"
    val path = session.notesPath?.let { p ->
        val at = p.indexOf("\\Documents\\Bluey Notes")
        if (at >= 0) "~" + p.substring(at) else p
    } ?: "$root/ (the folder starting ${dateFolder(session.started)})"
    val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(session.started), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"))
    return """
        I recorded a meeting with my Bluey app on $date. The notes are on my PC in $path

        - notes.md: the full transcript with timestamps and speaker labels (A, B, … per five-minute chunk), plus my questions to Bluey, its replies and research reports. Start here.
        - session.json: the same data, structured.
        - audio/: the original recording in five-minute chunks.

        All my sessions are in $root/, one folder per session (see README.md there). Read the notes, then help me with:
    """.trimIndent()
}

private fun dateFolder(millis: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

@Composable
private fun EntryView(entry: TranscriptEntry) {
    when (entry.kind) {
        TranscriptEntry.Kind.transcript -> Row(verticalAlignment = Alignment.Top) {
            Text(
                "Speaker ${entry.speaker ?: ""}",
                color = speakerColor(entry.speaker),
                fontSize = 11.sp,
                fontFamily = BlueyFonts.plexMono,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(78.dp),
            )
            Text(
                entry.text,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 15.sp,
                fontFamily = BlueyFonts.plexSans,
                modifier = Modifier.weight(1f),
            )
            Text(
                SessionStore.formatTime(entry.time),
                color = Palette.inkSoft.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontFamily = BlueyFonts.plexMono,
            )
        }

        TranscriptEntry.Kind.heard -> Text(
            entry.text,
            color = Palette.inkSoft,
            fontSize = 14.sp,
            fontFamily = BlueyFonts.plexSans,
        )

        TranscriptEntry.Kind.asked -> Row {
            Spacer(Modifier.weight(1f).widthIn(min = 120.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "YOU ASKED",
                    color = Palette.berry1,
                    fontSize = 10.sp,
                    fontFamily = BlueyFonts.plexMono,
                )
                Text(
                    entry.text,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontFamily = BlueyFonts.plexSans,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(BlobShape.gradientBrush(400f, 80f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        TranscriptEntry.Kind.reply -> Row(verticalAlignment = Alignment.Bottom) {
            MiniBluey(Modifier.size(width = 28.dp, height = 25.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                entry.text,
                color = Palette.ink,
                fontSize = 16.sp,
                fontFamily = BlueyFonts.fredoka,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f).widthIn(min = 120.dp))
        }

        TranscriptEntry.Kind.report -> {
            var open by remember { mutableStateOf(false) }
            val paragraphs = entry.text.split("\n\n")
            Column(
                Modifier
                    .padding(start = 36.dp, end = 80.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFEEF0FF))
                    .padding(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { open = !open },
                ) {
                    Text("🔍", color = Palette.berry2, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        paragraphs.firstOrNull() ?: "Research",
                        color = Palette.ink,
                        fontSize = 16.sp,
                        fontFamily = BlueyFonts.fredoka,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Palette.berry2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (open) "−" else "+", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    }
                }
                Text(
                    paragraphs.drop(1).joinToString("\n\n"),
                    color = Palette.ink,
                    fontSize = 14.sp,
                    fontFamily = BlueyFonts.plexSans,
                    maxLines = if (open) Int.MAX_VALUE else 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun speakerColor(speaker: String?): Color {
    val colors = listOf(
        0x8FB3FF, 0x5BE49B, 0xFFB86B, 0xFF9AD0, 0xC79BFF, 0x6BE3E0,
    ).map { Color(it) }
    if (speaker == null || speaker == "?") return Palette.inkSoft
    val first = speaker.codePointAt(0)
    return colors[first % colors.size]
}

/** A tiny Bluey: the blob with two googly eyes. */
@Composable
fun MiniBluey(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawPath(BlobShape.path(w, h), BlobShape.gradientBrush(w, h))
        val eye = w * 0.26f
        val centers = listOf(
            Offset(w / 2 - w * 0.05f - eye / 2, h / 2 - w * 0.04f),
            Offset(w / 2 + w * 0.05f + eye / 2, h / 2 - w * 0.04f),
        )
        for (c in centers) {
            drawCircle(Color.White, radius = eye / 2, center = c)
            drawCircle(
                Palette.ink,
                radius = w * 0.06f,
                center = Offset(c.x + w * 0.02f, c.y + w * 0.03f),
            )
        }
    }
}

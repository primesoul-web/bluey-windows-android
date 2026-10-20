package dev.bluey.client.session

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.min

/** One line in a session: the proper transcript, the rough live transcript, a question
 * you asked by holding the screen, his replies and research reports. */
@Serializable
data class TranscriptEntry(
    var id: String = UUID.randomUUID().toString(),
    var kind: Kind = Kind.heard,
    var text: String = "",
    var time: Long = 0,
    /** The Realtime conversation item this came from, so its transcript can fill in when it arrives. */
    var itemID: String? = null,
    /** Who was talking, for proper transcript lines ("A", "B", … within each five-minute chunk). */
    var speaker: String? = null,
) {
    enum class Kind { heard, asked, reply, report, transcript }
}

/** A recorded chunk waiting to be transcribed by the PC (M5). */
@Serializable
data class PendingChunk(var index: Int, var started: Long)

/** Everything from one wake-to-sleep session. */
@Serializable
data class BlueySession(
    var id: String = UUID.randomUUID().toString(),
    var started: Long = 0,
    var ended: Long? = null,
    var entries: MutableList<TranscriptEntry> = mutableListOf(),
    /** Where the PC saved this session's meeting notes. */
    var notesPath: String? = null,
    var pending: MutableList<PendingChunk>? = null,
) {
    val questionCount: Int get() = entries.count { it.kind == TranscriptEntry.Kind.asked }

    /** A short line for the session list: the first thing you asked, or the first thing you said. */
    val summary: String
        get() {
            val first = entries.firstOrNull { it.kind == TranscriptEntry.Kind.asked && it.text.isNotEmpty() }
                ?: displayEntries.firstOrNull {
                    it.kind == TranscriptEntry.Kind.transcript || it.kind == TranscriptEntry.Kind.heard
                }
            return first?.text ?: "Nothing said yet"
        }

    val duration: Long get() = (ended ?: System.currentTimeMillis()) - started

    /** What to show: the proper transcript where it exists, the rough live lines after it, plus
     * questions, replies and reports, in time order. */
    val displayEntries: List<TranscriptEntry>
        get() {
            val properUntil = entries.filter { it.kind == TranscriptEntry.Kind.transcript }
                .maxOfOrNull { it.time } ?: Long.MIN_VALUE
            return entries
                .filter { it.text.isNotEmpty() && !(it.kind == TranscriptEntry.Kind.heard && it.time <= properUntil) }
                .sortedBy { it.time }
        }

    val transcribing: Int get() = pending?.size ?: 0

    companion object {
        fun formatDate(millis: Long): String =
            formatLocal(millis, "MMM d, h:mm a")

        fun formatTime(millis: Long): String =
            formatLocal(millis, "h:mm:ss a")

        fun formatDuration(seconds: Long): String {
            val s = seconds.toInt()
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
            else "%d:%02d".format(s / 60, s % 60)
        }

        private fun formatLocal(millis: Long, pattern: String): String =
            java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault(),
            ).format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    }

    /** Plain text, for sharing. */
    val exportText: String
        get() {
            val lines = mutableListOf("Bluey session, ${formatDate(started)}", "")
            for (entry in displayEntries) {
                val who = when (entry.kind) {
                    TranscriptEntry.Kind.transcript -> "Speaker ${entry.speaker ?: "?"}"
                    TranscriptEntry.Kind.heard -> "You"
                    TranscriptEntry.Kind.asked -> "You asked"
                    TranscriptEntry.Kind.reply -> "Bluey"
                    TranscriptEntry.Kind.report -> "Research"
                }
                lines += "[${formatTime(entry.time)}] $who: ${entry.text}"
            }
            return lines.joinToString("\n")
        }
}

/** Keeps every session on the phone, as one JSON file in the app's files folder.
 * Full port of <c>SessionStore.swift</c> (M5 adds the chunk bookkeeping consumers). */
class SessionStore(
    context: Context? = null,
    private val scope: CoroutineScope,
    private val file: File? = context?.filesDir?.resolve("sessions.json"),
) {
    private val json = Json { ignoreUnknownKeys = true }

    var sessions: List<BlueySession> = emptyList() // newest first
        private set
    var currentID: String? = null
        private set

    /** Called with sessions that changed, a few seconds after the changes settle (M5 wires the consumer). */
    var onSync: ((List<BlueySession>) -> Unit)? = null
    private val dirty = mutableSetOf<String>()
    private var syncJob: Job? = null
    private var saveJob: Job? = null

    val current: BlueySession? get() = sessions.firstOrNull { it.id == currentID }

    init {
        load()
    }

    fun start() {
        end()
        val session = BlueySession(started = System.currentTimeMillis())
        sessions = listOf(session) + sessions
        currentID = session.id
        save()
    }

    fun end() {
        val id = currentID ?: return
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        currentID = null
        val session = sessions[index]
        val now = System.currentTimeMillis()
        if (session.entries.all { it.text.isEmpty() } && session.transcribing == 0 &&
            now - session.started < 20_000
        ) {
            // A few seconds with nothing said: don't keep it.
            sessions = sessions.filterIndexed { i, _ -> i != index }
        } else {
            session.ended = now
            session.entries.removeAll { it.text.isEmpty() }
            dirty += id
            scheduleSync()
        }
        save()
    }

    /** Holds a spot for something you said, in the order you said it. The words arrive a moment later. */
    fun placeholder(itemID: String, asked: Boolean) {
        update { session ->
            if (session.entries.none { it.itemID == itemID }) {
                session.entries.add(
                    TranscriptEntry(
                        kind = if (asked) TranscriptEntry.Kind.asked else TranscriptEntry.Kind.heard,
                        text = "",
                        time = System.currentTimeMillis(),
                        itemID = itemID,
                    ),
                )
            }
        }
    }

    fun heard(itemID: String, text: String, asked: Boolean) {
        val trimmed = text.trim()
        update { session ->
            val existing = session.entries.indexOfFirst { it.itemID == itemID }
            if (existing >= 0) {
                session.entries[existing].text = trimmed
                if (asked) session.entries[existing].kind = TranscriptEntry.Kind.asked
            } else if (trimmed.isNotEmpty()) {
                session.entries.add(
                    TranscriptEntry(
                        kind = if (asked) TranscriptEntry.Kind.asked else TranscriptEntry.Kind.heard,
                        text = trimmed,
                        time = System.currentTimeMillis(),
                        itemID = itemID,
                    ),
                )
            }
        }
    }

    fun reply(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        update { session ->
            session.entries.add(TranscriptEntry(kind = TranscriptEntry.Kind.reply, text = trimmed, time = System.currentTimeMillis()))
        }
    }

    fun report(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        update { session ->
            session.entries.add(TranscriptEntry(kind = TranscriptEntry.Kind.report, text = trimmed, time = System.currentTimeMillis()))
        }
    }

    /** Recent words from this session, for a fresh connection to pick up where the last one left off. */
    fun recentText(limit: Int = 6000): String? {
        val current = current ?: return null
        val text = current.displayEntries
            .filter { it.kind != TranscriptEntry.Kind.report }
            .joinToString("\n") { (if (it.kind == TranscriptEntry.Kind.reply) "Bluey: " else "") + it.text }
        if (text.isEmpty()) return null
        return text.takeLast(min(limit, text.length))
    }

    /** Re-sends recent sessions (after the phone reconnects to the PC). */
    fun resyncRecent() {
        val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
        dirty += sessions.filter { it.started > cutoff }.map { it.id }
        scheduleSync()
    }

    fun delete(id: String) {
        if (id == currentID) currentID = null
        sessions = sessions.filter { it.id != id }
        save()
    }

    private fun update(change: (BlueySession) -> Unit) {
        val id = currentID ?: return
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        change(sessions[index])
        dirty += id
        scheduleSync()
        scheduleSave()
    }

    // The scope is the main dispatcher by construction (Main.immediate in the app),
    // which keeps the debounces on the test scheduler in unit tests.
    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(6_000)
            val changed = sessions.filter { it.id in dirty }
            dirty.clear()
            if (changed.isNotEmpty()) onSync?.invoke(changed)
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000) // debounced, like asyncAfter(deadline: .now() + 1)
            writeNow()
        }
    }

    private fun save() {
        saveJob?.cancel()
        writeNow()
    }

    private fun writeNow() {
        val target = file ?: return
        try {
            target.writeText(json.encodeToString(ListSerializer(BlueySession.serializer()), sessions))
        } catch (_: Exception) {
            // best effort, like the original
        }
    }

    private fun load() {
        val target = file ?: return
        val loaded = try {
            if (target.exists()) {
                json.decodeFromString(ListSerializer(BlueySession.serializer()), target.readText())
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        // A session left open by a crash or force-quit is closed at its last line.
        sessions = loaded.map { session ->
            if (session.ended == null) {
                session.copy(ended = session.entries.lastOrNull()?.time ?: session.started)
            } else session
        }
    }

    companion object {
        fun formatDate(millis: Long): String = BlueySession.formatDate(millis)
        fun formatTime(millis: Long): String = BlueySession.formatTime(millis)
        fun formatDuration(seconds: Long): String = BlueySession.formatDuration(seconds)
    }
}

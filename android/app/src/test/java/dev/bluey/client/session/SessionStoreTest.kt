package dev.bluey.client.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The session store: placeholders, replies, summaries, the 20-second rule and persistence. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStoreTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        file = File.createTempFile("sessions", ".json")
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
        Dispatchers.resetMain()
    }

    private fun store(scope: kotlinx.coroutines.CoroutineScope) =
        SessionStore(context = null, scope = scope, file = file)

    @Test
    fun `placeholder then words keeps the turn's place in order`() = runTest {
        val s = store(backgroundScope)
        s.start()
        s.placeholder("item_1", asked = true)
        s.placeholder("item_2", asked = false)
        s.heard("item_2", "first said", asked = false) // words arrive out of order
        s.heard("item_1", "what's this?", asked = true)

        val current = s.current!!
        assertEquals(2, current.entries.size)
        assertEquals(listOf("item_1", "item_2"), current.entries.map { it.itemID })
        assertEquals("what's this?", current.entries[0].text)
        assertEquals(TranscriptEntry.Kind.asked, current.entries[0].kind)
        assertEquals("first said", current.entries[1].text)
        assertEquals(1, current.questionCount)
    }

    @Test
    fun `replies and reports land in the transcript`() = runTest {
        val s = store(backgroundScope)
        s.start()
        s.reply("That's the prompt box.")
        s.report("Research\n\nParagraph")
        val current = s.current!!
        assertEquals(2, current.entries.size)
        assertEquals(TranscriptEntry.Kind.reply, current.entries[0].kind)
        assertEquals(TranscriptEntry.Kind.report, current.entries[1].kind)
    }

    @Test
    fun `recentText prefixes replies, skips reports and caps at 6000 chars`() = runTest {
        val s = store(backgroundScope)
        s.start()
        s.heard("i1", "you said something fairly long".repeat(50), asked = false)
        s.reply("short reply")
        s.report("EXCLUDE ME")
        val text = s.recentText()!!
        assertTrue(text.contains("Bluey: short reply"))
        assertTrue(!text.contains("EXCLUDE ME"))
        assertTrue(text.length <= 6000)
    }

    @Test
    fun `an empty short session is dropped on end`() = runTest {
        val s = store(backgroundScope)
        s.start()
        // Nothing said; pretend it just started (< 20 s).
        s.current!!.started = System.currentTimeMillis() - 5_000
        s.end()
        assertTrue(s.sessions.isEmpty())
        assertNull(s.current)
    }

    @Test
    fun `a session with content is kept and closed`() = runTest {
        val s = store(backgroundScope)
        s.start()
        s.reply("hello")
        s.end()
        assertEquals(1, s.sessions.size)
        assertNotNull(s.sessions[0].ended)
        assertNull(s.currentID)
    }

    @Test
    fun `a crash-orphaned session is closed at its last line on load`() = runTest {
        val s = store(backgroundScope)
        s.start()
        s.reply("last words")
        advanceTimeBy(1_100) // the debounced save lands

        assertTrue(file.exists())
        val reloaded = store(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()))
        assertEquals(1, reloaded.sessions.size)
        assertNotNull(reloaded.sessions[0].ended)
        assertEquals("last words", reloaded.sessions[0].entries.single().text)
    }

    @Test
    fun `the summary is the first question, else the first utterance`() = runTest {
        val s = store(backgroundScope)
        s.start()
        s.heard("i1", "background chatter", asked = false)
        s.heard("i2", "what's this?", asked = true)
        s.reply("answer")
        assertEquals("what's this?", s.current!!.summary)
    }

    @Test
    fun `displayEntries hides heard lines the proper transcript already covers`() = runTest {
        val s = store(backgroundScope)
        s.start()
        s.current!!.entries.apply {
            add(TranscriptEntry(kind = TranscriptEntry.Kind.heard, text = "rough live line", time = 1_000))
            add(TranscriptEntry(kind = TranscriptEntry.Kind.transcript, text = "proper line A", time = 2_000, speaker = "A"))
        }
        val shown = s.current!!.displayEntries
        assertEquals(1, shown.size)
        assertEquals("proper line A", shown[0].text)
    }

    @Test
    fun `changes sync a few seconds after they settle`() = runTest {
        val s = store(backgroundScope)
        var synced: List<BlueySession> = emptyList()
        s.onSync = { synced = it }
        s.start()
        s.reply("something")
        assertTrue(synced.isEmpty()) // not yet — debounce
        advanceTimeBy(6_100)
        assertEquals(1, synced.size)
        assertEquals("something", synced[0].entries.single().text)
    }

    @Test
    fun `resyncRecent marks the last three days`() = runTest {
        val s = store(backgroundScope)
        var synced = 0
        s.onSync = { synced = it.size }
        s.start()
        s.reply("kept around") // an empty session would be dropped on end
        s.end()
        s.resyncRecent()
        advanceTimeBy(6_100)
        assertEquals(1, synced)
    }

    @Test
    fun `exportText names the speakers`() = runTest {
        val s = store(backgroundScope)
        s.start()
        s.current!!.entries.apply {
            add(TranscriptEntry(kind = TranscriptEntry.Kind.transcript, text = "hello", time = 1_000, speaker = "A"))
            add(TranscriptEntry(kind = TranscriptEntry.Kind.asked, text = "what?", time = 2_000))
            add(TranscriptEntry(kind = TranscriptEntry.Kind.reply, text = "this", time = 3_000))
        }
        val text = s.current!!.exportText
        assertTrue(text.contains("] Speaker A: hello"))
        assertTrue(text.contains("] You asked: what?"))
        assertTrue(text.contains("] Bluey: this"))
    }

    @Test
    fun `delete removes the session`() = runTest {
        val s = store(backgroundScope)
        s.start()
        val id = s.current!!.id
        s.delete(id)
        assertTrue(s.sessions.isEmpty())
    }
}

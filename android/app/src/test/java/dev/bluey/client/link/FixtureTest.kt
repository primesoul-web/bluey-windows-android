package dev.bluey.client.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance against the fixtures in shared-protocol/fixtures — the same files the
 * .NET tests and the CI schema validator consume.
 */
class FixtureTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .readBytes().decodeToString()

    private fun validFiles(): List<String> = listOf(
        "01-hello.ndjson",
        "02-face.ndjson",
        "03-commands.ndjson",
        "04-requests.ndjson",
        "05-legacy.ndjson",
        "06-unknown-fields.ndjson",
    )

    private val knownFields =
        setOf("face", "hello", "volume", "command", "audio", "speech", "callID", "tool", "text", "image")
    private val knownFaceFields = setOf("gazeX", "gazeY", "mood", "talk")

    @Test
    fun `every valid line decodes`() {
        for (file in validFiles()) {
            for (line in fixture(file).lineSequence()) {
                if (line.isBlank()) continue
                assertNotNull("failed: $file: $line", PacketCodec.tryDecode(line))
            }
        }
    }

    @Test
    fun `every valid line round trips over the known fields`() {
        for (file in validFiles()) {
            for (line in fixture(file).lineSequence()) {
                if (line.isBlank()) continue
                val packet = PacketCodec.tryDecode(line)!!
                val encoded = PacketCodec.encode(packet).decodeToString().trimEnd('\n')
                val original = Json.parseToJsonElement(line) as JsonObject
                val roundTripped = Json.parseToJsonElement(encoded) as JsonObject

                assertTrue(
                    "unexpected keys: ${roundTripped.keys}",
                    roundTripped.keys.all { it in knownFields },
                )
                for ((key, value) in original) {
                    if (key !in knownFields) continue
                    val output = roundTripped[key]
                    assertNotNull("known field $key was lost", output)
                    if (key == "face") {
                        val faceIn = value as JsonObject
                        val faceOut = output as JsonObject
                        assertTrue(faceOut.keys.all { it in knownFaceFields })
                        for ((faceKey, faceValue) in faceIn) {
                            if (faceKey !in knownFaceFields) continue
                            assertJsonEquals(faceValue, faceOut[faceKey]!!, "$key.$faceKey")
                        }
                    } else {
                        assertJsonEquals(value, output!!, key)
                    }
                }
            }
        }
    }

    @Test
    fun `every invalid line fails to decode`() {
        for (line in fixture("07-invalid-lines.ndjson").lineSequence()) {
            if (line.isBlank()) continue
            assertNull("expected rejection: $line", PacketCodec.tryDecode(line))
        }
    }

    private fun assertJsonEquals(expected: JsonElement, actual: JsonElement, path: String) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                assertEquals("keys at $path", expected.keys.sorted(), actual.keys.sorted())
                for (key in expected.keys) {
                    assertJsonEquals(expected[key]!!, actual[key]!!, "$path.$key")
                }
            }
            expected is JsonArray && actual is JsonArray -> {
                assertEquals("size at $path", expected.size, actual.size)
                expected.forEachIndexed { i, e -> assertJsonEquals(e, actual[i], "$path[$i]") }
            }
            expected is JsonPrimitive && actual is JsonPrimitive -> {
                val expectedBool = expected.booleanOrNull
                val actualBool = actual.booleanOrNull
                val expectedNum = expected.doubleOrNull
                val actualNum = actual.doubleOrNull
                when {
                    expectedBool != null || actualBool != null ->
                        assertEquals("bool at $path", expected.content, actual.content)
                    expectedNum != null && actualNum != null ->
                        assertEquals("number at $path", expectedNum, actualNum, 0.0)
                    else -> assertEquals("value at $path", expected.content, actual.content)
                }
            }
            expected is JsonNull && actual is JsonNull -> {}
            else -> assertEquals("kind at $path", expected, actual)
        }
    }
}

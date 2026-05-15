package com.pwmgr.core.format

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalJsonTest {

    private fun encode(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        CanonicalJson.encode(buildJsonObject(block)).decodeToString()

    @Test
    fun object_keys_sorted_lexicographically() {
        val out = encode {
            put("zebra", 1)
            put("alpha", 2)
            put("middle", 3)
        }
        assertEquals("""{"alpha":2,"middle":3,"zebra":1}""", out)
    }

    @Test
    fun nested_object_keys_sorted_independently() {
        val out = CanonicalJson.encode(
            buildJsonObject {
                put(
                    "kdf",
                    buildJsonObject {
                        put("salt", "AAAA")
                        put("algo", "argon2id")
                        put("memKiB", 19456)
                        put("iter", 2)
                        put("par", 1)
                    },
                )
                put("version", 1)
            },
        ).decodeToString()
        assertEquals(
            """{"kdf":{"algo":"argon2id","iter":2,"memKiB":19456,"par":1,"salt":"AAAA"},"version":1}""",
            out,
        )
    }

    @Test
    fun arrays_preserve_insertion_order() {
        val out = CanonicalJson.encode(
            buildJsonArray {
                add(JsonPrimitive(3))
                add(JsonPrimitive(1))
                add(JsonPrimitive(2))
            },
        ).decodeToString()
        assertEquals("[3,1,2]", out)
    }

    @Test
    fun strings_escape_special_chars() {
        val out = encode {
            put("a", "line1\nline2")
            put("b", "tab\there")
            put("c", "quote\"inside")
            put("d", "back\\slash")
            put("e", "carriage\rreturn")
        }
        assertEquals(
            """{"a":"line1\nline2","b":"tab\there","c":"quote\"inside","d":"back\\slash","e":"carriage\rreturn"}""",
            out,
        )
    }

    @Test
    fun control_characters_escaped_as_unicode() {
        val input = ""
        val out = encode { put("x", input) }
        assertEquals("{\"x\":\"\\u0001\"}", out)
    }

    @Test
    fun form_feed_uses_short_escape() {
        val input = ""
        val out = encode { put("x", input) }
        assertEquals("""{"x":"\f"}""", out)
    }

    @Test
    fun backspace_uses_short_escape() {
        val input = "\b"
        val out = encode { put("x", input) }
        assertEquals("""{"x":"\b"}""", out)
    }

    @Test
    fun null_is_emitted_as_literal_null() {
        val out = CanonicalJson.encode(kotlinx.serialization.json.JsonNull).decodeToString()
        assertEquals("null", out)
    }

    @Test
    fun unicode_passes_through() {
        val out = encode { put("greeting", "héllo 世界") }
        assertEquals("""{"greeting":"héllo 世界"}""", out)
    }
}

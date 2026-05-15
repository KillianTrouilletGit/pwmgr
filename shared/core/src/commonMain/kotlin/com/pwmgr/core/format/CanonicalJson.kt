package com.pwmgr.core.format

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic JSON serializer used for AEAD associated-data computation. The output bytes are
 * stable across implementations because:
 *  - object keys are emitted in lexicographic (Unicode code-point) order
 *  - no insignificant whitespace
 *  - numeric primitives are emitted via their canonical [JsonPrimitive.content]
 *  - strings are escaped per RFC 8259 with backslash, doublequote, b, f, n, r, t and \u00XX
 *
 * Implementations on other languages/platforms MUST produce byte-identical output.
 * See docs/CRYPTO.md section 4.2 for the normative rules; CanonicalJsonTest has golden vectors.
 */
object CanonicalJson {

    private const val FORM_FEED: Char = ''

    fun encode(element: JsonElement): ByteArray = buildString { write(element) }.encodeToByteArray()

    private fun StringBuilder.write(element: JsonElement) {
        when (element) {
            is JsonNull -> append("null")
            is JsonObject -> writeObject(element)
            is JsonArray -> writeArray(element)
            is JsonPrimitive -> writePrimitive(element)
        }
    }

    private fun StringBuilder.writeObject(obj: JsonObject) {
        append('{')
        val sortedKeys = obj.keys.sorted()
        var first = true
        for (key in sortedKeys) {
            if (!first) append(',')
            first = false
            writeString(key)
            append(':')
            write(obj.getValue(key))
        }
        append('}')
    }

    private fun StringBuilder.writeArray(arr: JsonArray) {
        append('[')
        var first = true
        for (item in arr) {
            if (!first) append(',')
            first = false
            write(item)
        }
        append(']')
    }

    private fun StringBuilder.writePrimitive(p: JsonPrimitive) {
        if (p.isString) writeString(p.content) else append(p.content)
    }

    private fun StringBuilder.writeString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                FORM_FEED -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) {
                    append("\\u")
                    val hex = c.code.toString(16)
                    repeat(4 - hex.length) { append('0') }
                    append(hex)
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }
}

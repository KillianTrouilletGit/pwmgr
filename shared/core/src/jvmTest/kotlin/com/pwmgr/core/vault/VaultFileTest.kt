package com.pwmgr.core.vault

import com.pwmgr.core.model.EntryType
import com.pwmgr.core.model.VaultEntry
import com.pwmgr.core.model.VaultPayload
import com.pwmgr.crypto.Kdf
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests use the minimum-allowed Argon2 parameters (memKiB=19456, iter=2, par=1) to keep
 * test wall-time low. The production defaults are exercised once in the smoke test.
 */
@OptIn(ExperimentalEncodingApi::class)
class VaultFileTest {

    private val password = "correct horse battery staple".toCharArray()
    private val wrong = "wrong password 9000".toCharArray()
    private val now = "2026-05-14T12:00:00Z"
    private val testMem = Kdf.MIN_MEM_KIB
    private val testIter = Kdf.MIN_ITERATIONS
    private val testPar = 1
    private val json = Json { encodeDefaults = true; prettyPrint = false }

    private lateinit var createdBytes: ByteArray
    private lateinit var session: VaultSession

    @BeforeTest
    fun setup() {
        val res = VaultFile.create(
            password = password.copyOf(),
            deviceId = "11111111-1111-1111-1111-111111111111",
            memKiB = testMem,
            iterations = testIter,
            parallelism = testPar,
            nowIso = now,
        )
        createdBytes = res.fileBytes
        session = res.session
    }

    @AfterTest
    fun tearDown() {
        session.lock()
    }

    @Test
    fun create_then_unlock_with_correct_password_yields_empty_payload() {
        val result = VaultFile.unlock(createdBytes, password.copyOf())
        assertEquals(0, result.payload.entries.size)
        assertEquals(0L, result.session.meta.revision)
        result.session.lock()
    }

    @Test
    fun unlock_with_wrong_password_throws() {
        assertFailsWith<WrongPasswordException> {
            VaultFile.unlock(createdBytes, wrong.copyOf())
        }
    }

    @Test
    fun save_then_unlock_round_trips_entries() {
        val entry = VaultEntry(
            id = "abc-123",
            type = EntryType.LOGIN,
            title = "GitHub",
            username = "octocat",
            password = "hunter2",
            urls = listOf("https://github.com"),
            createdAt = Instant.parse(now),
            updatedAt = Instant.parse(now),
        )
        val payload = VaultPayload(entries = listOf(entry))
        val saved = VaultFile.save(session, payload, "2026-05-14T12:05:00Z")

        val unlocked = VaultFile.unlock(saved, password.copyOf())
        assertEquals(1, unlocked.payload.entries.size)
        assertEquals(entry, unlocked.payload.entries[0])
        assertEquals(1L, unlocked.session.meta.revision)
        unlocked.session.lock()
    }

    @Test
    fun flipping_any_payload_ct_byte_breaks_decryption() {
        val obj = parseObj(createdBytes)
        val payload = obj.getValue("payload").jsonObject
        val ctB64 = payload.getValue("ct").jsonPrimitive.content
        val ctBytes = Base64.decode(ctB64)
        // Test a few representative positions instead of all bytes (Argon2 is slow per try):
        val positions = listOf(0, ctBytes.size / 2, ctBytes.size - 1)
        for (i in positions) {
            val tampered = ctBytes.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val mutated = mutate(obj, "payload", "ct", Base64.encode(tampered))
            assertFailsWith<CorruptVaultException>("payload tamper at $i not detected") {
                VaultFile.unlock(mutated, password.copyOf())
            }
        }
    }

    @Test
    fun flipping_a_wrap_ct_byte_throws_wrong_password() {
        val obj = parseObj(createdBytes)
        val wrap = obj.getValue("wrap").jsonObject
        val ctBytes = Base64.decode(wrap.getValue("ct").jsonPrimitive.content)
        val tampered = ctBytes.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val mutated = mutate(obj, "wrap", "ct", Base64.encode(tampered))
        assertFailsWith<WrongPasswordException> {
            VaultFile.unlock(mutated, password.copyOf())
        }
    }

    @Test
    fun changing_memKiB_in_header_breaks_decryption() {
        val mutated = mutateTopLevelKdf(createdBytes) { it + ("memKiB" to JsonPrimitive(testMem + 1024)) }
        assertFailsWith<WrongPasswordException> {
            VaultFile.unlock(mutated, password.copyOf())
        }
    }

    @Test
    fun changing_iter_in_header_breaks_decryption() {
        val mutated = mutateTopLevelKdf(createdBytes) { it + ("iter" to JsonPrimitive(testIter + 1)) }
        assertFailsWith<WrongPasswordException> {
            VaultFile.unlock(mutated, password.copyOf())
        }
    }

    @Test
    fun changing_par_in_header_breaks_decryption() {
        val mutated = mutateTopLevelKdf(createdBytes) { it + ("par" to JsonPrimitive(testPar + 1)) }
        assertFailsWith<WrongPasswordException> {
            VaultFile.unlock(mutated, password.copyOf())
        }
    }

    @Test
    fun changing_salt_in_header_breaks_decryption() {
        val newSalt = ByteArray(Kdf.SALT_BYTES) { 0x7F }
        val mutated = mutateTopLevelKdf(createdBytes) { it + ("salt" to JsonPrimitive(Base64.encode(newSalt))) }
        assertFailsWith<WrongPasswordException> {
            VaultFile.unlock(mutated, password.copyOf())
        }
    }

    @Test
    fun unknown_version_is_rejected() {
        val obj = parseObj(createdBytes)
        val mutated = JsonObject(obj.toMap() + ("version" to JsonPrimitive(2))).toBytes()
        assertFailsWith<InvalidVaultFormatException> {
            VaultFile.unlock(mutated, password.copyOf())
        }
    }

    @Test
    fun memKiB_below_floor_is_rejected_at_parse() {
        val mutated = mutateTopLevelKdf(createdBytes) { it + ("memKiB" to JsonPrimitive(1024)) }
        assertFailsWith<InvalidVaultFormatException> {
            VaultFile.unlock(mutated, password.copyOf())
        }
    }

    @Test
    fun smoke_test_with_production_default_params() {
        // One round-trip at full strength to catch any default-parameter regression.
        val res = VaultFile.create(
            password = password.copyOf(),
            deviceId = "22222222-2222-2222-2222-222222222222",
            nowIso = now,
        )
        val unlocked = VaultFile.unlock(res.fileBytes, password.copyOf())
        assertTrue(unlocked.payload.entries.isEmpty())
        unlocked.session.lock()
        res.session.lock()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun parseObj(bytes: ByteArray): JsonObject =
        json.parseToJsonElement(bytes.decodeToString()) as JsonObject

    private fun JsonObject.toBytes(): ByteArray = json.encodeToString(JsonObject.serializer(), this).encodeToByteArray()

    private fun mutate(obj: JsonObject, key: String, subKey: String, newValue: String): ByteArray {
        val sub = obj.getValue(key).jsonObject
        val newSub = JsonObject(sub.toMap() + (subKey to JsonPrimitive(newValue)))
        val newObj = JsonObject(obj.toMap() + (key to newSub))
        return newObj.toBytes()
    }

    private fun mutateTopLevelKdf(bytes: ByteArray, block: (Map<String, kotlinx.serialization.json.JsonElement>) -> Map<String, kotlinx.serialization.json.JsonElement>): ByteArray {
        val obj = parseObj(bytes)
        val kdf = obj.getValue("kdf").jsonObject
        val newKdf = JsonObject(block(kdf.toMap()))
        val newObj = JsonObject(obj.toMap() + ("kdf" to newKdf))
        return newObj.toBytes()
    }
}

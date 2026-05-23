package com.pwmgr.desktop.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Tiny line-delimited JSON server on 127.0.0.1. Each accepted connection authenticates
 * itself with the handshake token (sent as the first frame `{ "op": "auth", "token": "..." }`),
 * then issues requests until the peer hangs up.
 *
 * Designed for the native-messaging relay: one short-lived TCP connection per browser
 * request. We don't bother with persistent connection optimization — the cost of opening
 * a localhost socket is negligible.
 *
 * Threading: accept loop + N connection handlers, all on Dispatchers.IO with a
 * SupervisorJob so a single bad connection doesn't tear the whole server down.
 */
class LocalIpcServer(
    private val handler: IpcCommandHandler,
    private val expectedToken: String,
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null

    /** Starts the server on a random free localhost port. Returns the bound port. */
    fun start(): Int {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        acceptJob = scope.launch { acceptLoop(socket) }
        return socket.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        scope.cancel()
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (scope.isActive && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return // socket closed → exit
            }
            scope.launch { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = CLIENT_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            val writer = OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)
            var authenticated = false
            while (true) {
                val line = readLineSafe(reader) ?: return
                if (line.isBlank()) continue
                val response = handleLine(line, authenticated) { authenticated = true }
                writeLine(writer, response)
                // After a failed auth we close the connection — the peer must reconnect.
                if (!authenticated && response.contains("\"bad_auth\"")) return
            }
        }
    }

    private fun handleLine(line: String, authenticated: Boolean, markAuthed: () -> Unit): String {
        val obj = parseObject(line)
            ?: return errResp(0L, IpcErrors.BAD_REQUEST, "not a JSON object")

        val op = obj.stringField("op")
            ?: return errResp(idOf(obj), IpcErrors.BAD_REQUEST, "missing 'op'")

        return when {
            op == "auth" -> handleAuth(obj, markAuthed)
            !authenticated -> errResp(idOf(obj), IpcErrors.BAD_AUTH, "auth required")
            else -> dispatch(op, obj)
        }
    }

    private fun handleAuth(obj: JsonObject, markAuthed: () -> Unit): String {
        val token = obj.stringField("token")
        if (token != expectedToken) {
            return errResp(0L, IpcErrors.BAD_AUTH, "invalid token")
        }
        markAuthed()
        return buildJsonObject {
            put("id", JsonPrimitive(0))
            put("op", JsonPrimitive("auth_ok"))
        }.toString()
    }

    private fun dispatch(op: String, obj: JsonObject): String {
        val id = idOf(obj)
        return when (op) {
            "ping" -> buildJsonObject {
                put("id", JsonPrimitive(id))
                put("op", JsonPrimitive("pong"))
            }.toString()

            "status" -> buildJsonObject {
                put("id", JsonPrimitive(id))
                put("op", JsonPrimitive("status_ok"))
                put("unlocked", JsonPrimitive(handler.isUnlocked()))
            }.toString()

            "match" -> {
                if (!handler.isUnlocked()) return errResp(id, IpcErrors.LOCKED, "vault is locked")
                val host = obj.stringField("host")
                    ?: return errResp(id, IpcErrors.BAD_REQUEST, "missing 'host'")
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("op", JsonPrimitive("match_ok"))
                    put(
                        "candidates",
                        buildJsonArray {
                            for (c in handler.match(host)) {
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(c.id))
                                        put("title", JsonPrimitive(c.title))
                                        put("username", c.username?.let { JsonPrimitive(it) } ?: JsonNull)
                                    },
                                )
                            }
                        },
                    )
                }.toString()
            }

            "reveal" -> {
                if (!handler.isUnlocked()) return errResp(id, IpcErrors.LOCKED, "vault is locked")
                val entryId = obj.stringField("entryId")
                    ?: return errResp(id, IpcErrors.BAD_REQUEST, "missing 'entryId'")
                val revealed = handler.reveal(entryId)
                    ?: return errResp(id, IpcErrors.UNKNOWN_ENTRY, "no entry with id=$entryId")
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("op", JsonPrimitive("reveal_ok"))
                    put("username", revealed.first?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("password", JsonPrimitive(revealed.second))
                }.toString()
            }

            else -> errResp(id, IpcErrors.BAD_REQUEST, "unknown op '$op'")
        }
    }

    /** Renamed away from `error` to avoid colliding with Kotlin's built-in `error(Any): Nothing`. */
    private fun errResp(id: Long, code: String, message: String): String =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("op", JsonPrimitive("error"))
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
        }.toString()

    private fun parseObject(line: String): JsonObject? = try {
        json.parseToJsonElement(line) as? JsonObject
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.stringField(name: String): String? {
        val element = this[name] as? JsonPrimitive ?: return null
        return if (element.isString) element.content else null
    }

    private fun idOf(obj: JsonObject): Long {
        val element = obj["id"] as? JsonPrimitive ?: return 0L
        return element.content.toLongOrNull() ?: 0L
    }

    private fun readLineSafe(reader: BufferedReader): String? = try {
        reader.readLine()
    } catch (_: IOException) {
        null
    }

    private fun writeLine(writer: OutputStreamWriter, s: String) {
        try {
            writer.write(s)
            writer.write("\n")
            writer.flush()
        } catch (_: IOException) {
            // Peer hung up; the outer loop will end on the next failed read.
        }
    }

    companion object {
        private const val CLIENT_TIMEOUT_MS = 30_000
        private val json = Json { ignoreUnknownKeys = true }
    }
}

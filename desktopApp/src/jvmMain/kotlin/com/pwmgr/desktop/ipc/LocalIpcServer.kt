package com.pwmgr.desktop.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                val line = try {
                    reader.readLine() ?: return
                } catch (_: IOException) {
                    return
                }
                if (line.isBlank()) continue
                val response = try {
                    val obj = json.parseToJsonElement(line) as? JsonObject
                        ?: return@try error(0, IpcErrors.BAD_REQUEST, "not a JSON object")
                    val op = obj["op"]?.jsonPrimitive?.contentOrNullSafe()
                        ?: return@try error(0, IpcErrors.BAD_REQUEST, "missing 'op'")
                    if (op == "auth") {
                        val token = obj["token"]?.jsonPrimitive?.contentOrNullSafe()
                        if (token != expectedToken) {
                            // Reply once, then close.
                            writer.writeLineFlushed(error(0, IpcErrors.BAD_AUTH, "invalid token"))
                            return
                        }
                        authenticated = true
                        buildJsonObject {
                            put("id", JsonPrimitive(0))
                            put("op", JsonPrimitive("auth_ok"))
                        }.toString()
                    } else if (!authenticated) {
                        error(idOf(obj), IpcErrors.BAD_AUTH, "auth required")
                    } else {
                        dispatch(op, obj)
                    }
                } catch (t: Throwable) {
                    error(0, IpcErrors.INTERNAL, t.message ?: t::class.simpleName.orEmpty())
                }
                writer.writeLineFlushed(response)
            }
        }
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
                if (!handler.isUnlocked()) return error(id, IpcErrors.LOCKED, "vault is locked")
                val host = obj["host"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: return error(id, IpcErrors.BAD_REQUEST, "missing 'host'")
                val candidates = handler.match(host)
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("op", JsonPrimitive("match_ok"))
                    put(
                        "candidates",
                        kotlinx.serialization.json.buildJsonArray {
                            for (c in candidates) {
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(c.id))
                                        put("title", JsonPrimitive(c.title))
                                        put("username", c.username?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                                    },
                                )
                            }
                        },
                    )
                }.toString()
            }

            "reveal" -> {
                if (!handler.isUnlocked()) return error(id, IpcErrors.LOCKED, "vault is locked")
                val entryId = obj["entryId"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: return error(id, IpcErrors.BAD_REQUEST, "missing 'entryId'")
                val revealed = handler.reveal(entryId)
                    ?: return error(id, IpcErrors.UNKNOWN_ENTRY, "no entry with id=$entryId")
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("op", JsonPrimitive("reveal_ok"))
                    put("username", revealed.first?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                    put("password", JsonPrimitive(revealed.second))
                }.toString()
            }

            else -> error(id, IpcErrors.BAD_REQUEST, "unknown op '$op'")
        }
    }

    private fun idOf(obj: JsonObject): Long =
        obj["id"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: 0L

    private fun error(id: Long, code: String, message: String): String =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("op", JsonPrimitive("error"))
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
        }.toString()

    private fun OutputStreamWriter.writeLineFlushed(s: String) {
        write(s)
        write("\n")
        flush()
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this.isString) this.content else this.contentOrNull()
    private fun JsonPrimitive.contentOrNull(): String? = try { content } catch (_: Throwable) { null }

    companion object {
        private const val CLIENT_TIMEOUT_MS = 30_000  // generous; one request rarely takes this long
        private val json = Json { ignoreUnknownKeys = true }
    }
}

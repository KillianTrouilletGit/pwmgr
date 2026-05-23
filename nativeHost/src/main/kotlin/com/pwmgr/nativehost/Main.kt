package com.pwmgr.nativehost

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Native messaging host for browser ↔ PwMgr.
 *
 * The browser launches this process. It speaks the Chrome/Edge native messaging framing
 * on stdin/stdout (4-byte little-endian length prefix + JSON UTF-8) and bridges to the
 * running PwMgr desktop app over a localhost TCP socket, where the protocol is line-
 * delimited JSON (one message per `\n`).
 *
 * Lifecycle: long-running. Each browser-side `port.postMessage(...)` becomes one stdio
 * frame which we forward to PwMgr; PwMgr's reply becomes one stdio frame back to the
 * browser. The browser closes stdin when the port is closed.
 *
 * Discovery: we read `%LOCALAPPDATA%\PwMgr\ipc.handshake` (or `~/PwMgr/...` elsewhere) to
 * learn the TCP port and shared-secret token. If PwMgr isn't running, we report an error
 * frame back to the browser and exit.
 */
fun main() {
    val handshake = readHandshake() ?: run {
        respondError("PwMgr is not running. Open the desktop app and try again.")
        exitProcess(0)
    }

    val socket = try {
        Socket(InetAddress.getByName("127.0.0.1"), handshake.port).also { it.soTimeout = 30_000 }
    } catch (e: IOException) {
        respondError("Cannot reach PwMgr on localhost:${handshake.port}: ${e.message}")
        exitProcess(0)
    }

    socket.use { sock ->
        val tcpReader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
        val tcpWriter = OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)

        // Authenticate first.
        tcpWriter.writeLineFlushed("""{"op":"auth","token":"${escape(handshake.token)}"}""")
        val authResponse = try {
            tcpReader.readLine()
        } catch (_: IOException) {
            null
        }
        if (authResponse == null || !authResponse.contains("\"auth_ok\"")) {
            respondError("PwMgr rejected the handshake token. Restart the app.")
            return
        }

        val stdin = DataInputStream(System.`in`)
        val stdout = DataOutputStream(System.out)

        // Forward messages: stdio in → TCP, TCP → stdio out.
        while (true) {
            val request = readStdioMessage(stdin) ?: return
            tcpWriter.writeLineFlushed(request)
            val reply = try {
                tcpReader.readLine()
            } catch (_: IOException) {
                writeStdioMessage(stdout, """{"op":"error","code":"transport","message":"connection lost"}""")
                return
            } ?: return
            writeStdioMessage(stdout, reply)
        }
    }
}

@Serializable
private data class Handshake(val version: Int = 1, val port: Int, val token: String)

private fun readHandshake(): Handshake? {
    val path = handshakePath()
    if (!Files.exists(path)) return null
    return try {
        val text = Files.readString(path, StandardCharsets.UTF_8)
        json.decodeFromString(Handshake.serializer(), text)
    } catch (_: Throwable) {
        null
    }
}

private fun handshakePath(): Path {
    val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
    return Paths.get(base, "PwMgr", "ipc.handshake")
}

/** Reads one length-prefixed UTF-8 JSON message from stdin. Returns null on EOF. */
private fun readStdioMessage(input: DataInputStream): String? {
    val lengthBytes = ByteArray(4)
    var read = 0
    while (read < 4) {
        val n = try {
            input.read(lengthBytes, read, 4 - read)
        } catch (_: IOException) {
            return null
        }
        if (n < 0) return null
        read += n
    }
    val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
    if (length <= 0 || length > MAX_MESSAGE_BYTES) return null
    val payload = ByteArray(length)
    var off = 0
    while (off < length) {
        val n = try {
            input.read(payload, off, length - off)
        } catch (_: IOException) {
            return null
        }
        if (n < 0) return null
        off += n
    }
    return String(payload, StandardCharsets.UTF_8)
}

/** Writes one length-prefixed UTF-8 JSON message to stdout. */
private fun writeStdioMessage(output: DataOutputStream, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    val lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array()
    output.write(lengthBuffer)
    output.write(bytes)
    output.flush()
}

/**
 * Writes a single error frame to stdout before exiting. Used when we can't reach PwMgr
 * at all — the browser sees the error message immediately.
 */
private fun respondError(message: String) {
    val frame = """{"op":"error","code":"unavailable","message":"${escape(message)}"}"""
    writeStdioMessage(DataOutputStream(System.out), frame)
}

private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

private fun OutputStreamWriter.writeLineFlushed(line: String) {
    write(line)
    write("\n")
    flush()
}

private val json = Json { ignoreUnknownKeys = true }

/** Native messaging spec says max 1 MiB per message; PwMgr sends much less. */
private const val MAX_MESSAGE_BYTES = 1 shl 20

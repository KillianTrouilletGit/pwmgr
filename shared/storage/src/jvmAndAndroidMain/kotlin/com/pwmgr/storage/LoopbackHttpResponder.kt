package com.pwmgr.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Tiny one-shot HTTP responder bound to 127.0.0.1. Used by both desktop and Android OAuth
 * to receive Google's PKCE redirect.
 *
 * Replaces `com.sun.net.httpserver.HttpServer` which is unavailable on Android — Android's
 * runtime ships only the standard `java.net.*` and `javax.net.ssl.*`. ServerSocket is on
 * both platforms; a ~50-line HTTP parser handles the single GET we expect.
 *
 * Usage:
 * ```
 * val server = LoopbackHttpResponder().apply { start() }
 * val redirectUri = "http://127.0.0.1:${server.port}/callback"
 * openBrowser(authUrl + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8"))
 * val params = server.awaitCallback(timeoutMs = 5 * 60 * 1000)
 * server.close()
 * params?.get("code")  // PKCE auth code, or null on timeout/error
 * ```
 *
 * The HTML body returned to the browser is configurable (success vs error pages).
 */
class LoopbackHttpResponder(
    private val successHtml: String = DEFAULT_SUCCESS_HTML,
    private val errorHtml: String = DEFAULT_ERROR_HTML,
) {
    private var serverSocket: ServerSocket? = null
    private val callback = CompletableDeferred<Map<String, String>>()

    val port: Int get() = serverSocket?.localPort ?: error("Responder not started")

    fun start() {
        if (serverSocket != null) error("Responder already started")
        // Bind to 127.0.0.1 explicitly (not 0.0.0.0) so the socket is unreachable from the LAN.
        serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    }

    /**
     * Blocks (suspending) until the browser hits the loopback endpoint OR until [timeoutMs]
     * elapses. Returns the parsed query params, or null on timeout. Idempotent on timeout —
     * closes the underlying socket to unblock the inner accept call.
     */
    suspend fun awaitCallback(timeoutMs: Long): Map<String, String>? = coroutineScope {
        val sock = serverSocket ?: error("Responder not started")
        val acceptJob = launch(Dispatchers.IO) {
            try {
                val client = sock.accept()
                client.use { c ->
                    val request = readRequestLine(c.getInputStream()) ?: return@use
                    val params = parseQuery(request)
                    val hasError = params.containsKey("error")
                    writeHttpResponse(
                        c.getOutputStream(),
                        status = if (hasError) 400 else 200,
                        body = if (hasError) errorHtml else successHtml,
                    )
                    callback.complete(params)
                }
            } catch (_: IOException) {
                // Socket was closed (timeout path) or read failed. The await below will return
                // null via the timeout — no further action needed here.
            }
        }
        val result = withTimeoutOrNull(timeoutMs) { callback.await() }
        if (result == null) {
            // Timed out — close the socket so the blocking accept above throws and the job exits.
            runCatching { sock.close() }
        }
        acceptJob.cancelAndJoin()
        result
    }

    fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun readRequestLine(input: java.io.InputStream): String? {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        return reader.readLine()
    }

    /** Extracts query params from the first HTTP request line, e.g. `GET /callback?code=...&state=... HTTP/1.1`. */
    private fun parseQuery(requestLine: String): Map<String, String> {
        val parts = requestLine.split(' ')
        if (parts.size < 2) return emptyMap()
        val target = parts[1]
        val qIdx = target.indexOf('?')
        if (qIdx < 0) return emptyMap()
        val query = target.substring(qIdx + 1)
        return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) URLDecoder.decode(pair, "UTF-8") to ""
            else URLDecoder.decode(pair.substring(0, eq), "UTF-8") to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        }
    }

    private fun writeHttpResponse(output: OutputStream, status: Int, body: String) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val statusText = if (status in 200..299) "OK" else "Error"
        val headers = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    companion object {
        const val DEFAULT_SUCCESS_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>PwMgr — signed in</title>
<style>body{font-family:system-ui,sans-serif;background:#1e1b4b;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}main{text-align:center}h1{font-weight:500;margin:0 0 12px}p{opacity:.8;margin:0}</style>
</head><body><main><h1>You can close this tab</h1><p>PwMgr is now linked to your Google Drive.</p></main></body></html>"""

        const val DEFAULT_ERROR_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>PwMgr — sign-in error</title>
<style>body{font-family:system-ui,sans-serif;background:#7f1d1d;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}main{text-align:center;max-width:520px}h1{font-weight:500;margin:0 0 12px}p{opacity:.85;margin:0}</style>
</head><body><main><h1>Sign-in failed</h1><p>Return to PwMgr to see details.</p></main></body></html>"""
    }
}

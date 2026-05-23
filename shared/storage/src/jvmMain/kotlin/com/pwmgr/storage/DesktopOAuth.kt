package com.pwmgr.storage

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * Desktop OAuth 2.0 flow with PKCE via a loopback redirect. We bring up a tiny HTTP server
 * on a free localhost port, open the system browser at Google's authorization URL, and wait
 * for the callback with the auth code. Token exchange happens server-to-server.
 *
 * The browser sees a static "you can close this tab" page after the callback.
 *
 * Reference: RFC 8252 — OAuth 2.0 for Native Apps, §7.3 (loopback IP redirect).
 */
class DesktopOAuth(private val config: DriveOAuthConfig) : OAuthProvider {

    override suspend fun authorize(): OAuthAccount? = withContext(Dispatchers.IO) {
        val verifier = generateCodeVerifier()
        val challenge = sha256UrlEncoded(verifier)
        val state = randomToken(16)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val redirectUri = "http://127.0.0.1:$port/callback"

        val callbackFuture = CompletableDeferred<CallbackResult>()
        server.createContext("/callback") { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val result = when {
                params["error"] != null -> CallbackResult.Error(params["error"]!!, params["error_description"])
                params["state"] != state -> CallbackResult.Error("state_mismatch", "CSRF state did not match")
                params["code"] != null -> CallbackResult.Success(params["code"]!!)
                else -> CallbackResult.Error("missing_code", "callback had neither error nor code")
            }
            respondHtml(exchange, result)
            callbackFuture.complete(result)
        }
        server.start()

        try {
            val authUrl = buildAuthUrl(redirectUri, challenge, state)
            openBrowser(authUrl)
            val callback = try {
                withTimeout(AUTH_TIMEOUT.toMillis()) { callbackFuture.await() }
            } catch (e: Throwable) {
                return@withContext null
            }
            when (callback) {
                is CallbackResult.Error -> null
                is CallbackResult.Success -> exchangeCodeForTokens(callback.code, verifier, redirectUri)
            }
        } finally {
            server.stop(0)
        }
    }

    override suspend fun refreshAccessToken(refreshToken: String): String = withContext(Dispatchers.IO) {
        val body = formEncode(
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token",
        )
        val response = postForm(TOKEN_URL, body)
        if (response.status !in 200..299) {
            throw UnauthorizedException("refresh failed: HTTP ${response.status} — ${response.body.decodeToString()}")
        }
        val parsed = json.decodeFromString(TokenResponse.serializer(), response.body.decodeToString())
        parsed.access_token
    }

    private fun buildAuthUrl(redirectUri: String, challenge: String, state: String): String =
        AUTH_URL + "?" + formEncode(
            "client_id" to config.clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "https://www.googleapis.com/auth/drive.appdata openid email",
            "access_type" to "offline",
            "prompt" to "consent",
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )

    private fun exchangeCodeForTokens(code: String, verifier: String, redirectUri: String): OAuthAccount? {
        val body = formEncode(
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "code" to code,
            "code_verifier" to verifier,
            "grant_type" to "authorization_code",
            "redirect_uri" to redirectUri,
        )
        val response = postForm(TOKEN_URL, body)
        if (response.status !in 200..299) return null
        val parsed = json.decodeFromString(TokenResponse.serializer(), response.body.decodeToString())
        val refreshToken = parsed.refresh_token ?: return null

        val email = parsed.id_token?.let { decodeEmailFromIdToken(it) }
        return OAuthAccount(
            refreshToken = refreshToken,
            email = email,
            obtainedAt = System.currentTimeMillis(),
        )
    }

    private fun postForm(url: String, body: String): HttpResp {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val bytes = body.encodeToByteArray()
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val responseBytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResp(status, responseBytes)
        } catch (e: IOException) {
            throw NetworkException("POST $url failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private data class HttpResp(val status: Int, val body: ByteArray)

    private fun openBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        } catch (_: Throwable) { /* fall through */ }
        // Fallback for systems without java.awt.Desktop support.
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            os.contains("mac") -> arrayOf("open", url)
            else -> arrayOf("xdg-open", url)
        }
        Runtime.getRuntime().exec(cmd)
    }

    private fun respondHtml(exchange: HttpExchange, result: CallbackResult) {
        val (status, html) = when (result) {
            is CallbackResult.Success -> 200 to SUCCESS_HTML
            is CallbackResult.Error -> 400 to errorHtml(result)
        }
        val bytes = html.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private sealed interface CallbackResult {
        data class Success(val code: String) : CallbackResult
        data class Error(val error: String, val description: String?) : CallbackResult
    }

    @Serializable
    private data class TokenResponse(
        val access_token: String,
        val expires_in: Int = 0,
        val refresh_token: String? = null,
        val id_token: String? = null,
        val token_type: String = "Bearer",
        val scope: String = "",
    )

    companion object {
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private val AUTH_TIMEOUT: Duration = Duration.ofMinutes(5)
        private val json = Json { ignoreUnknownKeys = true }
        private val rng = SecureRandom()

        private fun generateCodeVerifier(): String {
            // 64 bytes → 86 chars base64url, well within RFC 7636's 43-128 range.
            val bytes = ByteArray(64)
            rng.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun sha256UrlEncoded(input: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        }

        private fun randomToken(byteLen: Int): String {
            val bytes = ByteArray(byteLen)
            rng.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun formEncode(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }

        private fun parseQuery(raw: String): Map<String, String> =
            raw.split('&').filter { it.isNotEmpty() }.associate {
                val idx = it.indexOf('=')
                if (idx < 0) URLDecoder.decode(it, "UTF-8") to ""
                else URLDecoder.decode(it.substring(0, idx), "UTF-8") to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
            }

        /**
         * Decodes the `email` claim from a Google id_token (JWT) without verifying signature.
         * Safe because the token came directly from Google over TLS in our token exchange call.
         */
        private fun decodeEmailFromIdToken(idToken: String): String? {
            val parts = idToken.split('.')
            if (parts.size != 3) return null
            return try {
                val payload = Base64.getUrlDecoder().decode(parts[1]).decodeToString()
                Regex(""""email"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
            } catch (_: Throwable) {
                null
            }
        }

        private const val SUCCESS_HTML = """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>PwMgr — signed in</title>
<style>body{font-family:system-ui,sans-serif;background:#1e1b4b;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}main{text-align:center}h1{font-weight:500;margin:0 0 12px}p{opacity:.8;margin:0}</style>
</head><body><main><h1>You can close this tab</h1><p>PwMgr is now linked to your Google Drive.</p></main></body></html>
"""

        private fun errorHtml(err: CallbackResult.Error): String = """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>PwMgr — sign-in error</title>
<style>body{font-family:system-ui,sans-serif;background:#7f1d1d;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}main{text-align:center;max-width:520px}h1{font-weight:500;margin:0 0 12px}p{opacity:.85;margin:0}code{background:#000;padding:2px 6px;border-radius:4px}</style>
</head><body><main><h1>Sign-in failed</h1><p>${err.error}${err.description?.let { ": $it" } ?: ""}</p></main></body></html>
"""
    }
}

/**
 * OAuth client identifiers issued by Google Cloud Console for the user's GCP project.
 *
 * Despite the name, "client secret" is not actually secret for Desktop OAuth clients — it's
 * embedded in distributed desktop apps. PKCE provides the real security against authorization
 * code interception. We still send it because Google's token endpoint requires it for Desktop
 * client types.
 */
data class DriveOAuthConfig(val clientId: String, val clientSecret: String) {
    companion object {
        fun loadFromFile(path: java.nio.file.Path): DriveOAuthConfig? {
            if (!java.nio.file.Files.exists(path)) return null
            val text = java.nio.file.Files.readString(path)
            val parsed = configJson.decodeFromString(FileFormat.serializer(), text)
            return DriveOAuthConfig(parsed.clientId, parsed.clientSecret)
        }

        private val configJson = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class FileFormat(val clientId: String, val clientSecret: String)
    }
}

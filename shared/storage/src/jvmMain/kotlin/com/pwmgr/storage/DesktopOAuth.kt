package com.pwmgr.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * Desktop OAuth 2.0 flow with PKCE via a loopback redirect. We bring up a tiny HTTP server
 * on a free localhost port (via [LoopbackHttpResponder] — shared with Android), open the
 * system browser at Google's authorization URL, and wait for the callback with the auth code.
 * Token exchange happens server-to-server.
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

        val responder = LoopbackHttpResponder()
        responder.start()
        val redirectUri = "http://127.0.0.1:${responder.port}/callback"

        try {
            val authUrl = buildAuthUrl(config.clientId, redirectUri, challenge, state)
            openBrowser(authUrl)
            val params = responder.awaitCallback(AUTH_TIMEOUT.toMillis())
                ?: return@withContext null
            if (params["error"] != null || params["state"] != state) return@withContext null
            val code = params["code"] ?: return@withContext null
            exchangeCodeForTokens(code, verifier, redirectUri)
        } finally {
            responder.close()
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
        } catch (_: Throwable) { /* fall through to OS-specific exec */ }
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            os.contains("mac") -> arrayOf("open", url)
            else -> arrayOf("xdg-open", url)
        }
        Runtime.getRuntime().exec(cmd)
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
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private val AUTH_TIMEOUT: Duration = Duration.ofMinutes(5)
        private val json = Json { ignoreUnknownKeys = true }
        private val rng = SecureRandom()

        private fun generateCodeVerifier(): String {
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

        private fun buildAuthUrl(clientId: String, redirectUri: String, challenge: String, state: String): String =
            AUTH_URL + "?" + formEncode(
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to "https://www.googleapis.com/auth/drive.appdata openid email",
                "access_type" to "offline",
                "prompt" to "consent",
                "state" to state,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
            )

        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"

        private fun formEncode(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
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
    }
}

// DriveOAuthConfig moved to jvmAndAndroidMain so AndroidOAuth can use the same type.

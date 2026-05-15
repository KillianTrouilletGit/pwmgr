package com.pwmgr.storage

/**
 * Platform-specific OAuth flow. Desktop uses loopback PKCE with the system browser;
 * Android uses Credential Manager / AuthorizationClient. Both return an [OAuthAccount]
 * with a refresh token suitable for long-lived sync.
 */
interface OAuthProvider {
    /**
     * Walks the user through the Google sign-in + consent flow.
     * Suspends until the user finishes (or cancels). On cancel, returns null.
     */
    suspend fun authorize(): OAuthAccount?

    /** Exchanges a refresh token for an access token. Cheap; called once per sync session. */
    suspend fun refreshAccessToken(refreshToken: String): String
}

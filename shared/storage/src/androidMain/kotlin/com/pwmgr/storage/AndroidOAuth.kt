package com.pwmgr.storage

/**
 * Android OAuth flow lands in Phase 5b. It will use Credential Manager for the account
 * picker (Sign in with Google) and AuthorizationClient (Identity Services) to request the
 * drive.appdata scope. The result will be an [OAuthAccount] suitable for [FileTokenStore].
 *
 * Until then, the Android build of :shared:storage compiles and can run merge/crypto code,
 * but actually initiating sign-in throws.
 */
class AndroidOAuth : OAuthProvider {
    override suspend fun authorize(): OAuthAccount? =
        throw NotImplementedError("Android OAuth flow ships in Phase 5b — desktop only for now.")

    override suspend fun refreshAccessToken(refreshToken: String): String =
        throw NotImplementedError("Android OAuth flow ships in Phase 5b — desktop only for now.")
}

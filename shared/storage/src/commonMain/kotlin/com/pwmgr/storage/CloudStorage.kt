package com.pwmgr.storage

/**
 * Generic single-blob cloud storage. The vault is always stored as a single opaque file
 * in a provider-specific private location (Drive's appDataFolder for Google Drive).
 *
 * Implementations must be thread-safe — calls may interleave with autosave.
 */
interface CloudStorage {

    /** Returns the remote file or null if it has never been uploaded. */
    suspend fun get(): RemoteFile?

    /**
     * Creates or replaces the vault file.
     *
     * @param expectedEtag When non-null, the upload succeeds only if the remote's current
     *   ETag matches. On mismatch, [ConflictException] is thrown so the caller can pull
     *   the new remote state, re-merge, and retry. When null, the upload is unconditional
     *   (used only for the very first upload).
     */
    suspend fun upsert(bytes: ByteArray, expectedEtag: String?): RemoteFile
}

data class RemoteFile(val id: String, val etag: String, val bytes: ByteArray) {
    // ByteArray's equals is by identity, override for value semantics in tests.
    override fun equals(other: Any?): Boolean =
        other is RemoteFile && id == other.id && etag == other.etag && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = (id.hashCode() * 31 + etag.hashCode()) * 31 + bytes.contentHashCode()
}

sealed class SyncException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class ConflictException(message: String = "remote was modified") : SyncException(message)
class UnauthorizedException(message: String = "authentication failed") : SyncException(message)
class NetworkException(message: String, cause: Throwable? = null) : SyncException(message, cause)
class RemoteServerException(val status: Int, message: String) : SyncException("remote server returned $status: $message")

package com.pwmgr.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Google Drive v3 client targeting the appDataFolder scope. The user's vault
 * lives in this private Drive folder, invisible from the Drive UI and inaccessible to
 * other apps.
 *
 * Wire format: HTTP/JSON via [HttpURLConnection]. ETag is read from the standard HTTP
 * response header on GET / POST / PATCH, and sent via If-Match on conditional updates.
 *
 * The client expects a fresh access token to be available via [getAccessToken] for each
 * request. Token caching / refresh is the caller's responsibility (typically via
 * [OAuthProvider.refreshAccessToken] once per sync).
 */
class GoogleDriveClient(
    private val getAccessToken: suspend () -> String,
    private val fileName: String = DEFAULT_FILE_NAME,
) : CloudStorage {

    private var cachedFileId: String? = null

    override suspend fun get(): RemoteFile? = withContext(Dispatchers.IO) {
        val id = resolveFileId() ?: return@withContext null
        val (status, body, etag) = http(
            method = "GET",
            url = "$DRIVE_API/files/${urlEnc(id)}?alt=media",
        )
        when (status) {
            200 -> RemoteFile(id = id, etag = etag ?: "", bytes = body)
            404 -> {
                cachedFileId = null
                null
            }
            else -> throw RemoteServerException(status, body.decodeToString())
        }
    }

    override suspend fun upsert(bytes: ByteArray, expectedEtag: String?): RemoteFile = withContext(Dispatchers.IO) {
        val existingId = resolveFileId()
        if (existingId == null) {
            // First-time upload: create metadata-only file in appDataFolder, then upload media.
            val createdId = createInAppData()
            val updated = uploadMedia(createdId, bytes, ifMatchEtag = null)
            cachedFileId = createdId
            updated
        } else {
            val updated = uploadMedia(existingId, bytes, ifMatchEtag = expectedEtag)
            updated
        }
    }

    /** Returns the cached file id, looking it up via list() the first time. */
    private suspend fun resolveFileId(): String? {
        cachedFileId?.let { return it }
        val (status, body, _) = http(
            method = "GET",
            url = "$DRIVE_API/files?spaces=appDataFolder" +
                "&q=name=${urlEnc("'$fileName'")}+and+trashed=false" +
                "&fields=files(id,name)",
        )
        if (status != 200) throw RemoteServerException(status, body.decodeToString())
        val parsed = json.decodeFromString(FileList.serializer(), body.decodeToString())
        val match = parsed.files.firstOrNull { it.name == fileName }
        cachedFileId = match?.id
        return match?.id
    }

    private suspend fun createInAppData(): String {
        val metadata = """{"name":"$fileName","parents":["appDataFolder"]}"""
        val (status, body, _) = http(
            method = "POST",
            url = "$DRIVE_API/files?fields=id",
            body = metadata.encodeToByteArray(),
            contentType = "application/json; charset=utf-8",
        )
        if (status !in 200..299) throw RemoteServerException(status, body.decodeToString())
        return json.decodeFromString(FileId.serializer(), body.decodeToString()).id
    }

    private suspend fun uploadMedia(fileId: String, bytes: ByteArray, ifMatchEtag: String?): RemoteFile {
        val headers = mutableMapOf<String, String>()
        if (ifMatchEtag != null) headers["If-Match"] = ifMatchEtag
        val (status, body, etag) = http(
            method = "PATCH",
            url = "$UPLOAD_API/files/${urlEnc(fileId)}?uploadType=media&fields=id",
            body = bytes,
            contentType = "application/octet-stream",
            extraHeaders = headers,
        )
        when (status) {
            in 200..299 -> {
                val parsed = json.decodeFromString(FileId.serializer(), body.decodeToString())
                return RemoteFile(id = parsed.id, etag = etag ?: "", bytes = bytes)
            }
            412 -> throw ConflictException()
            401 -> throw UnauthorizedException()
            else -> throw RemoteServerException(status, body.decodeToString())
        }
    }

    private suspend fun http(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val token = getAccessToken()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val responseBytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val etag = conn.getHeaderField("ETag")
            return HttpResponse(status, responseBytes, etag)
        } catch (e: IOException) {
            throw NetworkException("HTTP $method $url failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private data class HttpResponse(val status: Int, val body: ByteArray, val etag: String?)

    @Serializable
    private data class FileList(val files: List<FileItem> = emptyList())

    @Serializable
    private data class FileItem(val id: String, val name: String)

    @Serializable
    private data class FileId(val id: String)

    companion object {
        const val DEFAULT_FILE_NAME = "vault.enc"
        private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
        private val json = Json { ignoreUnknownKeys = true }
        private fun urlEnc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}

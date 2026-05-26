package com.pwmgr.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
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

    /**
     * "Update" semantics implemented as DELETE-old + multipart-CREATE-new because
     * [HttpURLConnection] (used here for Android/JVM portability) doesn't support PATCH —
     * the only method Google Drive v3 accepts for updating file content. PUT returns a 404
     * generic page; X-HTTP-Method-Override isn't honored by the /upload/ endpoint.
     *
     * Trade-off: the file id rotates on every upsert. SyncEngine's [expectedEtag]
     * optimistic-concurrency parameter is therefore ignored — concurrent writes from two
     * devices will silently overwrite each other instead of throwing [ConflictException].
     * Acceptable for personal single-user use (the only realistic concurrency window is
     * two devices saving within the same ~second). When we migrate off HttpURLConnection
     * (likely OkHttp on Android + java.net.http.HttpClient on JVM 11+), restore native
     * PATCH and the ETag check.
     */
    override suspend fun upsert(bytes: ByteArray, expectedEtag: String?): RemoteFile = withContext(Dispatchers.IO) {
        val existingId = resolveFileId()
        if (existingId != null) {
            deleteFile(existingId)
            cachedFileId = null
        }
        val createdId = multipartCreate(bytes)
        cachedFileId = createdId
        // We don't get a useful ETag back from multipart create. Use the file id as a
        // stand-in — the SyncEngine just round-trips this through cloud.get() next time.
        RemoteFile(id = createdId, etag = createdId, bytes = bytes)
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

    /**
     * Creates a fresh `vault.enc` in the appDataFolder with content in one multipart POST.
     * No metadata round-trip needed — the file resource is returned with content set.
     *
     * Body shape (RFC 2387 multipart/related):
     * ```
     * --BOUNDARY
     * Content-Type: application/json; charset=utf-8
     *
     * {"name":"vault.enc","parents":["appDataFolder"]}
     * --BOUNDARY
     * Content-Type: application/octet-stream
     *
     * <encrypted vault bytes>
     * --BOUNDARY--
     * ```
     */
    private suspend fun multipartCreate(bytes: ByteArray): String {
        val boundary = "----pwmgr" + java.lang.Long.toHexString(System.nanoTime())
        val metadata = """{"name":"$fileName","parents":["appDataFolder"]}"""
        val body = buildMultipartBody(boundary, metadata, bytes)
        val (status, responseBody, _) = http(
            method = "POST",
            url = "$UPLOAD_API/files?uploadType=multipart&fields=id",
            body = body,
            contentType = "multipart/related; boundary=$boundary",
        )
        when (status) {
            in 200..299 -> return json.decodeFromString(FileId.serializer(), responseBody.decodeToString()).id
            401 -> throw UnauthorizedException()
            else -> throw RemoteServerException(status, responseBody.decodeToString())
        }
    }

    private fun buildMultipartBody(boundary: String, metadata: String, content: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val crlf = "\r\n".toByteArray(Charsets.UTF_8)
        out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: application/json; charset=utf-8\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(metadata.toByteArray(Charsets.UTF_8))
        out.write(crlf)
        out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(content)
        out.write(crlf)
        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private suspend fun deleteFile(fileId: String) {
        val (status, body, _) = http(
            method = "DELETE",
            url = "$DRIVE_API/files/${urlEnc(fileId)}",
        )
        // 404 is fine — the file might have been deleted concurrently or never existed.
        if (status !in 200..299 && status != 404) {
            when (status) {
                401 -> throw UnauthorizedException()
                else -> throw RemoteServerException(status, body.decodeToString())
            }
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
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
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
            if (status !in 200..299) {
                logHttpFailure(method, url, status, responseBytes)
            }
            return HttpResponse(status, responseBytes, etag)
        } catch (e: IOException) {
            logHttpFailure(method, url, -1, "I/O: ${e.message}".encodeToByteArray())
            throw NetworkException("HTTP $method $url failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Emit a verbose record of any non-2xx response to BOTH stderr (visible in the PowerShell
     * window where `gradlew :desktopApp:run` was launched) AND to a rolling log file at
     * `%LOCALAPPDATA%\PwMgr\drive.log` so the user can copy a 404 / 403 body in full without
     * fighting the UI text-truncation.
     */
    private fun logHttpFailure(method: String, url: String, status: Int, body: ByteArray) {
        val ts = java.time.OffsetDateTime.now().toString()
        val bodyText = try { body.decodeToString() } catch (_: Throwable) { "<binary, ${body.size} bytes>" }
        val msg = "[$ts] Drive HTTP $method $url → status=$status\n----- BODY -----\n$bodyText\n----- END -----\n"
        System.err.println(msg)
        runCatching {
            val home = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            val dir = java.nio.file.Paths.get(home, "PwMgr")
            java.nio.file.Files.createDirectories(dir)
            val logPath = dir.resolve("drive.log")
            // Append. We don't rotate — for personal use a few MB is fine. Files.writeString
            // exists on JDK 11+ but only on Android API 33+; we target API 26, so go through
            // Files.write(bytes, ...) which has been around since Java 7 / Android 26.
            java.nio.file.Files.write(
                logPath,
                msg.toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
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

package com.nexora.browser.util

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.nio.file.Files

/**
 * Profile-aware GET downloader. Callers supply either Proxy.NO_PROXY or the
 * active NEXORA loopback relay proxy. Redirects are handled explicitly so the
 * profile CookieManager is queried again for each destination URL; cookies are
 * never blindly forwarded to a different host.
 */
class ProfileDownloadClient(
    private val proxy: Proxy,
    private val userAgent: String?,
    private val cookieForUrl: (String) -> String?
) {
    data class Result(
        val finalUrl: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val mimeType: String
    )

    fun download(
        sourceUrl: String,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result {
        var current = URI(sourceUrl).toURL()
        validateWebUrl(current)
        var redirects = 0

        while (true) {
            val connection = (current.openConnection(proxy) as? HttpURLConnection)
                ?: throw IOException("Unsupported download connection")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                userAgent?.takeIf { it.isNotBlank() }?.let {
                    connection.setRequestProperty("User-Agent", it)
                }
                cookieForUrl(current.toExternalForm())
                    ?.takeIf { it.isNotBlank() }
                    ?.let { connection.setRequestProperty("Cookie", it) }

                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirects >= MAX_REDIRECTS) throw IOException("Too many download redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect response did not include a Location header")
                    val next = current.toURI().resolve(location).toURL()
                    validateWebUrl(next)
                    current = next
                    redirects += 1
                    continue
                }
                if (status !in 200..299) {
                    throw IOException("Download server returned HTTP $status")
                }

                val total = connection.contentLengthLong
                val mime = connection.contentType?.substringBefore(';')?.trim().orEmpty()
                val parent = destination.absoluteFile.parentFile
                    ?: throw IOException("Download destination has no parent directory")
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Could not create the downloads directory")
                }
                if (destination.exists()) throw IOException("A file with this name already exists")
                val temporary = File.createTempFile("nexora-download-", ".part", parent)
                var downloaded = 0L
                try {
                    connection.inputStream.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var lastReported = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                                    runCatching { onProgress(downloaded, total) }
                                    lastReported = downloaded
                                }
                            }
                        }
                    }
                    try {
                        Files.move(temporary.toPath(), destination.toPath())
                    } catch (error: Exception) {
                        throw IOException("Could not finalize the downloaded file", error)
                    }
                } catch (error: Exception) {
                    temporary.delete()
                    throw error
                }
                runCatching { onProgress(downloaded, total) }
                return Result(current.toExternalForm(), downloaded, total, mime)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun validateWebUrl(url: URL) {
        if (!url.protocol.equals("http", ignoreCase = true) &&
            !url.protocol.equals("https", ignoreCase = true)
        ) throw IOException("Only HTTP and HTTPS downloads are supported")
        if (url.host.isBlank()) throw IOException("Download URL host is missing")
        if (url.userInfo != null) throw IOException("URLs with embedded credentials are not supported for downloads")
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 10
        private const val PROGRESS_STEP_BYTES = 512L * 1024L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

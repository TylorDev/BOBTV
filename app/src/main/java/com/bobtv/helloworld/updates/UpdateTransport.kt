package com.bobtv.helloworld.updates

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class UpdateHttpException(val status: Int) : IOException("Update HTTP $status")
interface UpdateTransport {
    fun manifest(cancelled: () -> Boolean): String
    fun download(manifest: UpdateManifest, destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit)
}

class HttpsUpdateTransport : UpdateTransport {
    private fun connection(url: String, cancelled: () -> Boolean): HttpURLConnection {
        var target = URI(url)
        repeat(6) { redirect ->
            checkCancelled(cancelled)
            require(target.scheme == "https" && target.userInfo == null && target.port in listOf(-1, 443))
            require(target.host in listOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            val connection = target.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "BOBTV-Updater")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(redirect < 5) { "Too many redirects" }
                    target = target.resolve(connection.getHeaderField("Location") ?: throw IOException("Missing redirect"))
                    connection.disconnect()
                } else {
                    if (status != 200) throw UpdateHttpException(status)
                    return connection
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IOException("Too many redirects")
    }

    override fun manifest(cancelled: () -> Boolean): String {
        val connection = connection(UpdateManifest.MANIFEST_URL, cancelled)
        try {
            return connection.inputStream.use { input ->
                val bytes = readBounded(input, UpdateManifest.MAX_METADATA_BYTES)
                checkCancelled(cancelled)
                require(bytes.size <= UpdateManifest.MAX_METADATA_BYTES) { "Metadata too large" }
                bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    override fun download(manifest: UpdateManifest, destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val connection = connection(manifest.apkUrl, cancelled)
        val start = System.nanoTime()
        try {
            val length = connection.contentLengthLong
            require(length < 0 || length == manifest.sizeBytes) { "Unexpected content length" }
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    var lastPercent = -1
                    while (true) {
                        checkCancelled(cancelled)
                        if ((System.nanoTime() - start) / 1_000_000 > 5 * 60_000) throw IOException("Download deadline exceeded")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= manifest.sizeBytes) { "APK exceeds declared size" }
                        output.write(buffer, 0, count)
                        val percent = (total * 100 / manifest.sizeBytes).toInt()
                        if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                    }
                    require(total == manifest.sizeBytes) { "Truncated APK" }
                    output.fd.sync()
                }
            }
        } finally { connection.disconnect() }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Update cancelled")
    }
}

fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Read cancelled")
        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= limit) { "Response exceeds limit" }
    }
    return output.toByteArray()
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Verification cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

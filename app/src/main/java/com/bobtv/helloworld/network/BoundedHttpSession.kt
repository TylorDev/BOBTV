package com.bobtv.helloworld.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class HttpPayload(val url: String, val bytes: ByteArray)
class HttpStatusException(val status: Int, val resource: String) : IOException("HTTP $status at $resource")

interface ProbeTransport : AutoCloseable {
    fun fetch(url: String, maxBytes: Int, offset: Long = 0, length: Long? = null,
              complete: Boolean = true): HttpPayload
    fun checkDeadline()
    fun cancel()
    override fun close() = cancel()
}

/** One cancellable, bounded HTTP session shared by all requests for a single source. */
class BoundedHttpSession(
    private val deadlineMs: Long,
    private val log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) : ProbeTransport {
    private val start = nanoTime()
    private val cancelled = AtomicBoolean()
    private val active = AtomicReference<HttpURLConnection?>()

    override fun checkDeadline() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("Request cancelled")
        if (elapsedMs() >= deadlineMs) throw SocketTimeoutException("Source deadline exceeded: " + deadlineMs + " ms")
    }

    private fun elapsedMs() = (nanoTime() - start) / 1_000_000
    private fun timeout(): Int = minOf(3_000L, deadlineMs - elapsedMs()).coerceAtLeast(1).toInt()

    override fun fetch(url: String, maxBytes: Int, offset: Long, length: Long?, complete: Boolean): HttpPayload {
        require(maxBytes > 0 && offset >= 0 && (length == null || length > 0))
        var target = URI(url)
        repeat(6) { redirect ->
            checkDeadline()
            require(target.scheme.equals("http", true) || target.scheme.equals("https", true)) { "Unsupported URL protocol" }
            val connection = target.toURL().openConnection() as HttpURLConnection
            active.set(connection)
            try {
                checkDeadline()
                connection.connectTimeout = timeout()
                connection.readTimeout = timeout()
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "BOBTV/2.2")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Connection", "close")
                val requested = minOf(maxBytes.toLong(), length ?: maxBytes.toLong()).toInt()
                if (!complete) connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + requested - 1))
                val status = connection.responseCode
                log("GET " + target + " · HTTP " + status + " · " + elapsedMs() + " ms")
                if (status in listOf(301, 302, 303, 307, 308)) {
                    check(redirect < 5) { "Too many redirects" }
                    target = target.resolve(connection.getHeaderField("Location") ?: throw IOException("Redirect without Location"))
                    return@repeat
                }
                if (status !in 200..299) throw HttpStatusException(status, target.toString())
                if (!complete && offset > 0 && status != 206) throw IOException("Server ignored segment byte range")
                if (status == 206 && !complete) {
                    val range = connection.getHeaderField("Content-Range").orEmpty()
                    check(range.startsWith("bytes " + offset + "-")) { "Invalid segment Content-Range: " + range }
                }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkDeadline()
                        val remaining = (if (complete) maxBytes + 1 else requested) - output.size()
                        if (remaining <= 0) break
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        if (count > 0) output.write(buffer, 0, count)
                        if (complete && output.size() > maxBytes) throw IOException("Response exceeds " + maxBytes + " bytes")
                    }
                    checkDeadline()
                    output.toByteArray()
                }
                log("Read " + bytes.size + " bytes · " + target + " · " + elapsedMs() + " ms")
                return HttpPayload(target.toASCIIString(), bytes)
            } finally {
                active.compareAndSet(connection, null)
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    override fun cancel() {
        cancelled.set(true)
        active.getAndSet(null)?.disconnect()
    }
}

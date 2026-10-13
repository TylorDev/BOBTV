package com.bobtv.helloworld.data

import com.bobtv.helloworld.network.ProbeTransport
import com.bobtv.helloworld.network.HttpStatusException
import java.io.IOException
import java.net.URI

sealed class HlsManifest {
    data class Variant(val url: String, val audioUrl: String? = null)
    data class Resource(val url: String, val offset: Long = 0, val length: Long? = null)
    data class Segment(val resource: Resource, val initialization: Resource? = null, val keyUrl: String? = null)
    data class Master(val variants: List<Variant>, val parserContext: Any? = null) : HlsManifest()
    data class Media(val segments: List<Segment>, val ended: Boolean) : HlsManifest()
}

fun interface HlsManifestReader {
    fun parse(url: String, bytes: ByteArray, parent: HlsManifest.Master?): HlsManifest
}

interface StreamProbe {
    fun check(): ProbeResult
    fun cancel()
}

class HlsSourceProbe(
    private val url: String,
    private val reader: HlsManifestReader,
    private val transport: ProbeTransport,
    private val nanoTime: () -> Long = System::nanoTime,
) : StreamProbe {
    private class Unavailable(message: String, cause: Exception? = null) : IOException(message, cause)
    override fun check(): ProbeResult {
        val start = nanoTime()
        return try {
            verify(url, emptySet(), null)
            ProbeResult(true, "Live HLS playlist and media data available", (nanoTime() - start) / 1_000_000)
        } catch (error: Exception) {
            ProbeResult(false, error.message ?: error.javaClass.simpleName, (nanoTime() - start) / 1_000_000, error,
                if (error is Unavailable) ProbeVerdict.UNAVAILABLE else ProbeVerdict.INCONCLUSIVE)
        } finally {
            transport.close()
        }
    }

    private fun verify(url: String, visited: Set<String>, parent: HlsManifest.Master?, reload: Boolean = true) {
        transport.checkDeadline()
        check(url !in visited && visited.size < 4) { "Recursive or excessively nested HLS playlist" }
        val response = try { transport.fetch(url, MAX_MANIFEST_BYTES) } catch (error: HttpStatusException) {
            if (error.status == 404 || error.status == 410) throw Unavailable("Manifest unavailable: ${error.message}", error)
            throw error
        }
        val manifest = reader.parse(response.url, response.bytes, parent)
        transport.checkDeadline()
        when (manifest) {
            is HlsManifest.Master -> {
                check(manifest.variants.isNotEmpty()) { "Master playlist has no variants" }
                var lastError: Exception? = null
                val failures = mutableListOf<Exception>()
                for (variant in manifest.variants.take(2)) {
                    transport.checkDeadline()
                    try {
                        verify(resolve(response.url, variant.url), visited + url, manifest)
                        variant.audioUrl?.let {
                            try { verify(resolve(response.url, it), visited + url, manifest) }
                            catch (error: Exception) { throw IOException("Separate audio unavailable", error) }
                        }
                        return
                    } catch (error: Exception) {
                        lastError = error
                        failures.add(error)
                    }
                }
                if (manifest.variants.size <= 2 && failures.all { it is Unavailable })
                    throw Unavailable("All variant manifests unavailable", lastError)
                throw IOException("No verified HLS variant (inconclusive): ${lastError?.message}", lastError)
            }
            is HlsManifest.Media -> {
                if (manifest.ended) throw Unavailable("Live playlist ended (#EXT-X-ENDLIST)")
                val segment = manifest.segments.getOrNull(manifest.segments.size - 2)
                    ?: manifest.segments.lastOrNull() ?: throw IOException("Live playlist has no media segments")
                segment.keyUrl?.let {
                    val key = transport.fetch(resolve(response.url, it), 16)
                    check(key.bytes.size == 16) { "Invalid or inaccessible AES-128 key" }
                }
                segment.initialization?.let {
                    check(hasMediaBytes(fetchResource(response.url, it))) { "Invalid initialization segment" }
                }
                val sample = try { fetchResource(response.url, segment.resource) } catch (error: IOException) {
                    if (!reload) throw error
                    verify(url, visited, parent, reload = false)
                    return
                }
                check(sample.isNotEmpty()) { "Empty media segment" }
                if (segment.keyUrl == null) check(hasMediaBytes(sample)) { "Response is not HLS media data" }
                else check(sample.size >= 16) { "Incomplete encrypted media segment" }
            }
        }
    }

    private fun fetchResource(base: String, resource: HlsManifest.Resource): ByteArray =
        transport.fetch(resolve(base, resource.url), MAX_SAMPLE_BYTES, resource.offset, resource.length,
            complete = false).bytes

    private fun resolve(base: String, value: String): String = URI(base).resolve(value).toASCIIString()
    override fun cancel() = transport.cancel()

    companion object {
        const val DEADLINE_MS = 10_000L
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_SAMPLE_BYTES = 64 * 1024
        fun hasMediaBytes(bytes: ByteArray): Boolean {
            if (bytes.size < 8) return false
            if (bytes[0] == 0x47.toByte()) return true // MPEG-TS
            if (String(bytes, 0, 3, Charsets.US_ASCII) == "ID3") return true
            val box = String(bytes, 4, 4, Charsets.US_ASCII)
            if (box in setOf("ftyp", "styp", "moof", "emsg")) return true
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return (first == 0xff && second and 0xe0 == 0xe0) ||
                (first == 0x0b && second == 0x77)
        }
    }
}

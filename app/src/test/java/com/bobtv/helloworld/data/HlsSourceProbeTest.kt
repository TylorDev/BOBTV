package com.bobtv.helloworld.data

import com.bobtv.helloworld.network.HttpPayload
import com.bobtv.helloworld.network.ProbeTransport
import com.bobtv.helloworld.network.HttpStatusException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HlsSourceProbeTest {
    private val root = "https://example.com/master.m3u8"
    private val media = HlsManifest.Media(listOf(HlsManifest.Segment(HlsManifest.Resource("segment.ts"))), false)
    private val ts = ByteArray(188).apply { this[0] = 0x47 }

    private class Transport : ProbeTransport {
        val responses = mutableMapOf<String, HttpPayload>()
        val requests = mutableListOf<Pair<String, Boolean>>()
        val ranges = mutableListOf<Pair<Long, Long?>>()
        var closed = false
        override fun fetch(url: String, maxBytes: Int, offset: Long, length: Long?, complete: Boolean): HttpPayload {
            requests.add(url to complete)
            ranges.add(offset to length)
            return responses[url] ?: throw IOException("HTTP 404 at " + url)
        }
        override fun checkDeadline() {}
        override fun cancel() { closed = true }
    }

    private fun probe(transport: Transport, manifests: Map<String, HlsManifest>): ProbeResult =
        HlsSourceProbe(root, HlsManifestReader { url, _, _ ->
            manifests[url] ?: throw IOException("Invalid HLS manifest")
        }, transport).check()

    @Test fun resolvesVariantsSegmentsAndRedirectedUrlsAndUsesLimitedSamples() {
        val transport = Transport()
        val redirected = "https://cdn.example.com/live/master.m3u8"
        val variant = "https://cdn.example.com/live/low/media.m3u8"
        transport.responses[root] = HttpPayload(redirected, byteArrayOf())
        transport.responses[variant] = HttpPayload(variant, byteArrayOf())
        transport.responses["https://cdn.example.com/live/low/segment.ts"] =
            HttpPayload("https://cdn.example.com/live/low/segment.ts", ts)
        assertTrue(probe(transport, mapOf(
            redirected to HlsManifest.Master(listOf(HlsManifest.Variant("low/media.m3u8"))),
            variant to media)).online)
        assertEquals(listOf(root to true, variant to true, "https://cdn.example.com/live/low/segment.ts" to false), transport.requests)
        assertTrue(transport.closed)
    }

    @Test fun triesAnotherVariantWhenTheFirstOneFails() {
        val transport = Transport()
        val working = "https://example.com/working.m3u8"
        transport.responses[root] = HttpPayload(root, byteArrayOf())
        transport.responses[working] = HttpPayload(working, byteArrayOf())
        transport.responses["https://example.com/segment.ts"] = HttpPayload("https://example.com/segment.ts", ts)
        assertTrue(probe(transport, mapOf(
            root to HlsManifest.Master(listOf(HlsManifest.Variant("missing.m3u8"), HlsManifest.Variant("working.m3u8"))),
            working to media)).online)
        assertTrue(transport.requests.any { it.first.endsWith("missing.m3u8") })
    }

    @Test fun manifestsWithoutLiveDataAreOfflineEvenWithAnAccessibleManifest() {
        for (manifest in listOf(media.copy(ended = true), media.copy(segments = emptyList()),
            HlsManifest.Master(emptyList()))) {
            val transport = Transport()
            transport.responses[root] = HttpPayload(root, byteArrayOf())
            assertFalse(probe(transport, mapOf(root to manifest)).online)
            assertTrue(transport.closed)
        }
        val transport = Transport()
        transport.responses[root] = HttpPayload(root, byteArrayOf())
        val missingSegment = probe(transport, mapOf(root to media))
        assertFalse(missingSegment.online)
        assertTrue(missingSegment.detail.contains("HTTP 404"))
        assertNotNull(missingSegment.error)
    }

    @Test fun malformedPlaylistsAndHtmlSegmentsAreRejected() {
        val transport = Transport()
        transport.responses[root] = HttpPayload(root, "<html>offline</html>".toByteArray())
        assertFalse(probe(transport, emptyMap()).online)
        transport.responses["https://example.com/segment.ts"] =
            HttpPayload("https://example.com/segment.ts", "<html>offline</html>".toByteArray())
        assertFalse(probe(transport, mapOf(root to media)).online)
        transport.responses["https://example.com/segment.ts"] = HttpPayload("https://example.com/segment.ts", byteArrayOf())
        assertFalse(probe(transport, mapOf(root to media)).online)
    }

    @Test fun recursiveMastersAreRejected() {
        val transport = Transport()
        transport.responses[root] = HttpPayload(root, byteArrayOf())
        val result = probe(transport, mapOf(root to HlsManifest.Master(listOf(HlsManifest.Variant("master.m3u8")))))
        assertFalse(result.online)
        assertTrue(result.detail.contains("Recursive"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun verifiesAesKeysInitializationAndByteRanges() {
        val transport = Transport()
        transport.responses[root] = HttpPayload(root, byteArrayOf())
        transport.responses["https://example.com/key"] = HttpPayload("https://example.com/key", ByteArray(16))
        transport.responses["https://example.com/init"] = HttpPayload("https://example.com/init",
            byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray() + ByteArray(8))
        transport.responses["https://example.com/segments"] = HttpPayload("https://example.com/segments", ByteArray(32))
        val encrypted = HlsManifest.Media(listOf(HlsManifest.Segment(
            HlsManifest.Resource("segments", 188, 64),
            HlsManifest.Resource("init"), "key")), false)
        assertTrue(probe(transport, mapOf(root to encrypted)).online)
        assertEquals(188L to 64L, transport.ranges.last())
        transport.responses["https://example.com/key"] = HttpPayload("https://example.com/key", ByteArray(1))
        assertFalse(probe(transport, mapOf(root to encrypted)).online)
    }

    @Test fun separateAudioMustAlsoBeAccessible() {
        val transport = Transport()
        val variant = "https://example.com/video.m3u8"
        transport.responses[root] = HttpPayload(root, byteArrayOf())
        transport.responses[variant] = HttpPayload(variant, byteArrayOf())
        transport.responses["https://example.com/segment.ts"] = HttpPayload("https://example.com/segment.ts", ts)
        assertFalse(probe(transport, mapOf(
            root to HlsManifest.Master(listOf(HlsManifest.Variant("video.m3u8", "audio.m3u8"))),
            variant to media)).online)
    }
    @Test fun onlyManifestMissingIsConclusiveAndAccessDeniedRemainsInconclusive() {
        for (code in listOf(404, 410, 403, 503)) {
            val transport = object : ProbeTransport {
                override fun fetch(url: String, maxBytes: Int, offset: Long, length: Long?, complete: Boolean): HttpPayload =
                    throw HttpStatusException(code, url)
                override fun checkDeadline() {}
                override fun cancel() {}
            }
            val result = HlsSourceProbe(root, HlsManifestReader { _, _, _ -> media }, transport).check()
            assertEquals(if (code == 404 || code == 410) ProbeVerdict.UNAVAILABLE else ProbeVerdict.INCONCLUSIVE, result.verdict)
        }
    }
    @Test fun missingOldSegmentCanRecoverWithLatestSampleWithoutDeclaringOffline() {
        val transport = Transport()
        transport.responses[root] = HttpPayload(root, byteArrayOf())
        transport.responses["https://example.com/segment.ts"] = HttpPayload("https://example.com/segment.ts", ts)
        val manifest = HlsManifest.Media(listOf(HlsManifest.Segment(HlsManifest.Resource("old.ts"))) + media.segments, false)
        val result = probe(transport, mapOf(root to manifest))
        assertTrue(result.online)
        assertEquals(2, transport.requests.count { it.first == root })
    }
    @Test fun checkingOnlyTwoOfManyVariantsCannotProveAllOffline() {
        val transport = object : ProbeTransport {
            override fun fetch(url: String, maxBytes: Int, offset: Long, length: Long?, complete: Boolean): HttpPayload =
                if (url == root) HttpPayload(root, byteArrayOf()) else throw HttpStatusException(404, url)
            override fun checkDeadline() {}
            override fun cancel() {}
        }
        val result = HlsSourceProbe(root, HlsManifestReader { _, _, _ ->
            HlsManifest.Master((1..3).map { HlsManifest.Variant("$it.m3u8") })
        }, transport).check()
        assertEquals(ProbeVerdict.INCONCLUSIVE, result.verdict)
    }
}

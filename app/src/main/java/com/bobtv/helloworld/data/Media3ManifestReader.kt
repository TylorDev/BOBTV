package com.bobtv.helloworld.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.IOException

@androidx.annotation.OptIn(UnstableApi::class)
class Media3ManifestReader : HlsManifestReader {
    override fun parse(url: String, bytes: ByteArray, parent: HlsManifest.Master?): HlsManifest {
        val parentPlaylist = parent?.parserContext as? HlsMultivariantPlaylist
        val parser = if (parentPlaylist == null) HlsPlaylistParser() else HlsPlaylistParser(parentPlaylist, null)
        return when (val playlist = parser.parse(Uri.parse(url), bytes.inputStream())) {
            is HlsMultivariantPlaylist -> HlsManifest.Master(
                playlist.variants.sortedBy { it.format.bitrate }.map { variant ->
                    HlsManifest.Variant(variant.url.toString(),
                        playlist.audios.firstOrNull { it.groupId == variant.audioGroupId }?.url?.toString())
                }, playlist)
            is HlsMediaPlaylist -> HlsManifest.Media(
                playlist.segments.filter { !it.hasGapTag }.map { segment ->
                    HlsManifest.Segment(resource(segment), segment.initializationSegment?.let(::resource),
                        segment.fullSegmentEncryptionKeyUri)
                }, playlist.hasEndTag)
            else -> throw IOException("Unsupported HLS playlist")
        }
    }

    private fun resource(segment: HlsMediaPlaylist.Segment): HlsManifest.Resource =
        HlsManifest.Resource(segment.url, segment.byteRangeOffset,
            segment.byteRangeLength.takeIf { it != C.LENGTH_UNSET.toLong() && it > 0 })
}

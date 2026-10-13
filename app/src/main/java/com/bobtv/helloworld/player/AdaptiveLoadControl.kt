package com.bobtv.helloworld.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.util.concurrent.atomic.AtomicReference

@androidx.annotation.OptIn(UnstableApi::class)
class AdaptiveLoadControl : LoadControl {
    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val policy = AtomicReference(PlaybackPolicy())
    private var loading = false // Media3 playback thread only.
    val allocatedBytes: Int get() = allocator.totalBytesAllocated
    val unusedBytes: Int get() = allocator.unusedBytesAllocated
    fun update(value: PlaybackPolicy) { policy.set(value) }
    fun trim() { allocator.trim() }
    override fun getAllocator(playerId: PlayerId) = allocator
    override fun onPrepared(playerId: PlayerId) { loading = false }
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray,
                                  trackSelections: Array<out ExoTrackSelection?>) {
        allocator.setTargetBufferSize(policy.get().targetBytes)
    }
    override fun onStopped(playerId: PlayerId) { loading = false; allocator.reset() }
    override fun onReleased(playerId: PlayerId) { loading = false; allocator.reset() }
    override fun getBackBufferDurationUs(playerId: PlayerId) = 0L
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = false
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: androidx.media3.common.Timeline,
        mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, bufferedDurationUs: Long) = false
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val current = policy.get()
        allocator.setTargetBufferSize(current.targetBytes)
        val duration = (parameters.bufferedDurationUs / parameters.playbackSpeed / 1000).toLong()
        loading = current.continueLoading(duration, allocatedBytes, loading)
        return loading
    }
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val current = policy.get()
        val duration = (parameters.bufferedDurationUs / parameters.playbackSpeed / 1000).toLong()
        return current.startPlayback(duration, allocatedBytes, parameters.rebuffering)
    }
}

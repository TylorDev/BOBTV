package com.bobtv.helloworld.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.LivePlaybackSpeedControl
import java.util.concurrent.atomic.AtomicReference

/** Policy changes affect the speed controller, never replace the MediaItem or discard samples. */
@androidx.annotation.OptIn(UnstableApi::class)
class AdaptiveLiveController : LivePlaybackSpeedControl {
    private val requested = AtomicReference<Long?>(null)
    private val configuration = AtomicReference<MediaItem.LiveConfiguration?>(null)
    private val normal = DefaultLivePlaybackSpeedControl.Builder()
        .setFallbackMaxPlaybackSpeed(1.02f).build()
    @Volatile private var pressure = false
    val normalOffsetMs: Long get() = configuration.get()?.targetOffsetMs?.takeIf { it != C.TIME_UNSET } ?: 5_000
    fun update(targetMs: Long?, underPressure: Boolean) { requested.set(targetMs); pressure = underPressure }
    override fun setLiveConfiguration(liveConfiguration: MediaItem.LiveConfiguration) {
        configuration.set(liveConfiguration)
        normal.setLiveConfiguration(liveConfiguration)
    }
    override fun setTargetLiveOffsetOverrideUs(liveOffsetUs: Long) {
        // Seeks may set an override. A non-null source policy remains authoritative.
        normal.setTargetLiveOffsetOverrideUs(liveOffsetUs)
    }
    override fun notifyRebuffer() { if (requested.get() == null) normal.notifyRebuffer() }
    override fun getTargetLiveOffsetUs(): Long = requested.get()?.times(1000) ?: normal.targetLiveOffsetUs
    override fun getAdjustedPlaybackSpeed(liveOffsetUs: Long, bufferedDurationUs: Long): Float {
        val target = requested.get() ?: return if (pressure) 1f else normal.getAdjustedPlaybackSpeed(liveOffsetUs, bufferedDurationUs)
        val config = configuration.get()
        val min = maxOf(0.97f, config?.minPlaybackSpeed?.takeIf { it != C.RATE_UNSET } ?: 0.97f)
        val max = minOf(if (pressure) 1f else 1.02f, config?.maxPlaybackSpeed?.takeIf { it != C.RATE_UNSET } ?: 1.02f)
        if (min > max || liveOffsetUs == C.TIME_UNSET) return 1f
        val errorMs = liveOffsetUs / 1000 - target
        if (kotlin.math.abs(errorMs) < 1_000) return 1f.coerceIn(min, max)
        return (1f + errorMs / 100_000f).coerceIn(min, max)
    }
}

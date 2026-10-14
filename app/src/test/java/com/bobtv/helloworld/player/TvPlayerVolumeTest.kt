package com.bobtv.helloworld.player

import android.app.Activity
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.ui.PlayerView
import com.bobtv.helloworld.diagnostics.SessionLog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TvPlayerVolumeTest {
    @Test fun volumeIsAppliedBeforePlaybackAndRetainedThroughReleaseAndPlayerRecreation() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val view = PlayerView(activity)
        val log = SessionLog("Volume test")
        val tv = TvPlayer(activity, view, log) {}
        val ensure = TvPlayer::class.java.getDeclaredMethod("ensurePlayer").apply { isAccessible = true }
        try {
            tv.setVolume(0.25f)
            assertNull(view.player)
            ensure.invoke(tv)
            val first = view.player!!
            assertEquals(0.25f, first.volume, 0f)
            assertEquals(Player.STATE_IDLE, first.playbackState)
            assertFalse(first.playWhenReady)
            assertEquals(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                first.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode)
            tv.setVolume(0f)
            assertEquals(0f, first.volume, 0f)
            tv.release()
            assertNull(view.player)
            ensure.invoke(tv)
            val second = view.player!!
            assertNotSame(first, second)
            assertEquals(0f, second.volume, 0f)
            assertEquals(Player.STATE_IDLE, second.playbackState)
            tv.setVolume(0.0001f)
            assertEquals(0.0001f, second.volume, 0f)
            tv.release()
            ensure.invoke(tv)
            assertEquals(0.0001f, view.player!!.volume, 0f)
            assertTrue(log.export().contains("gain=0.0 before prepare/play"))
            assertTrue(log.export().contains("audio=PCM passthrough=false offload=false tunneling=false"))
            println(log.export())
        } finally {
            tv.release()
            activityController.pause().stop().destroy()
        }
    }

    @Test fun pcmSinkRejectsPassthroughEvenWhenDelegateAdvertisesItAndDisablesOffloadAndTunneling() {
        val calls = mutableListOf<String>()
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            calls.add("${method.name}:${args?.toList()}")
            when (method.name) {
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "supportsFormat" -> true
                else -> null
            }
        } as AudioSink
        val sink = PcmAudioSink(delegate)
        val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).build()
        assertTrue(sink.supportsFormat(pcm))
        for (mime in listOf(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_MPEG)) {
            val compressed = Format.Builder().setSampleMimeType(mime).build()
            assertFalse(sink.supportsFormat(compressed))
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(compressed))
            assertFalse(sink.getFormatOffloadSupport(compressed).isFormatSupported)
        }
        sink.setOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
        assertTrue(calls.any { it == "setOffloadMode:[${AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED}]" })
        sink.enableTunnelingV21()
        assertTrue(calls.any { it.startsWith("disableTunneling:") })
        assertFalse(calls.any { it.startsWith("enableTunnelingV21:") })
    }
}

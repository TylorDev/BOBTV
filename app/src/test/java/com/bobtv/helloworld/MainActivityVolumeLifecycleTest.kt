package com.bobtv.helloworld

import android.media.AudioManager
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.bobtv.helloworld.audio.StoredVolumePreferences
import com.bobtv.helloworld.audio.VolumeController
import com.bobtv.helloworld.audio.VolumeState
import com.bobtv.helloworld.diagnostics.SessionLog
import com.bobtv.helloworld.player.TvPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityVolumeLifecycleTest {
    private fun field(activity: MainActivity, name: String): Any = requireNotNull(MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity))

    // Exercise the real Activity lifecycle without contacting playlist servers in a volume test.
    private fun suspendCatalogueLoad(activity: MainActivity) {
        MainActivity::class.java.getDeclaredField("initialLoading").apply { isAccessible = true }.setBoolean(activity, true)
    }

    @Test fun foregroundAppliesSavedVolumeHomeRestoresAndReturnCapturesAgainBeforeNormalExit() {
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = lifecycle.get()
        val audio = activity.getSystemService(AudioManager::class.java)
        val preferences = activity.getSharedPreferences("bobtv", Context.MODE_PRIVATE)
        StoredVolumePreferences(preferences).write(VolumeState(40, 40))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        val originalIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val tv = field(activity, "tvPlayer") as TvPlayer
        val volume = field(activity, "volume") as VolumeController
        val log = field(activity, "log") as SessionLog
        val view = activity.findViewById<PlayerView>(R.id.player_view)
        val ensure = TvPlayer::class.java.getDeclaredMethod("ensurePlayer").apply { isAccessible = true }
        try {
            suspendCatalogueLoad(activity)
            lifecycle.start().resume()
            assertEquals(40, volume.state.level)
            assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
            ensure.invoke(tv)
            assertEquals(VolumeController.gain(40), view.player!!.volume, 0f)
            assertEquals(Player.STATE_IDLE, view.player!!.playbackState)
            volume.adjust(VolumeController.Action.MUTE)
            lifecycle.pause().stop() // Home uses this same lifecycle boundary.
            assertNull(view.player)
            assertEquals(originalIndex, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
            assertTrue(log.export().indexOf("Player released") < log.export().indexOf("Restore device"))

            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
            suspendCatalogueLoad(activity)
            lifecycle.start().resume()
            assertEquals(VolumeState(0, 40), volume.state)
            ensure.invoke(tv)
            assertEquals(0f, view.player!!.volume, 0f)
            volume.adjust(VolumeController.Action.MUTE)
            assertEquals(40, volume.state.level)
            activity.finish()
            lifecycle.pause().stop()
            assertNull(view.player)
            assertEquals(7, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
            assertEquals(VolumeState(40, 40), StoredVolumePreferences(preferences).read())
            println(log.export())
        } finally { lifecycle.destroy() }
    }
}

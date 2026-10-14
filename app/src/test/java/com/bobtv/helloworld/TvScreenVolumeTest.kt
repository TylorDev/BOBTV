package com.bobtv.helloworld

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.bobtv.helloworld.audio.*
import com.bobtv.helloworld.data.Channel
import com.bobtv.helloworld.player.TvPlayer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class TvScreenVolumeTest {
    class ScreenActivity : Activity() {
        lateinit var screen: TvScreen
        lateinit var volume: VolumeController
        val channels = (1..5).map { Channel("$it", "Channel $it", "", "https://example.com/$it.m3u8") }
        val selected = mutableListOf<Channel>()
        var retries = 0
        var acknowledgements = 0
        var exits = 0
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setTheme(R.style.Theme_BOBTV)
            setContentView(R.layout.activity_main)
            val device = object : DeviceAudio {
                var state = DeviceVolume(0, 15, 5, false, false)
                override fun read() = state
                override fun setIndex(index: Int) { state = state.copy(index = index) }
                override fun setMuted(muted: Boolean) { state = state.copy(muted = muted) }
                override fun volumeDb(index: Int): Float? = null
            }
            val preferences = StoredVolumePreferences(getSharedPreferences("screen-volume", MODE_PRIVATE))
            preferences.write(VolumeState(50, 50))
            volume = VolumeController(device, preferences, {}, {})
            volume.start()
            screen = TvScreen(this, selected::add, {}, { acknowledgements++ }, {}, { exits++ },
                { retries++ }, {}, { action -> screen.showVolume(volume.adjust(action)) })
            screen.setCatalogue(channels)
            screen.setCurrentChannel(channels.first())
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            screen.handleKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private lateinit var activityController: ActivityController<ScreenActivity>
    private lateinit var activity: ScreenActivity
    private val screen get() = activity.screen
    private fun view(id: Int): View = activity.findViewById(id)
    private fun press(key: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }
    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))

    @Before fun create() {
        activityController = Robolectric.buildActivity(ScreenActivity::class.java).setup().visible()
        activity = activityController.get()
        shadowOf(Looper.getMainLooper()).idle()
        view(R.id.root).requestFocus()
    }
    @After fun close() {
        screen.stop()
        activity.volume.stop()
        activityController.pause().stop().destroy()
    }

    @Test fun volumeKeysWorkInEveryScreenAndKeepFocus() {
        val states = listOf<() -> Unit>(
            { screen.setPlaybackStatus(TvPlayer.Status.PLAYING) },
            { screen.setPlaybackStatus(TvPlayer.Status.LOADING) },
            { screen.setPlaybackStatus(TvPlayer.Status.RETRYING) },
            { screen.setPlaybackStatus(TvPlayer.Status.WAITING_NETWORK) },
            {
                screen.setPlaybackStatus(TvPlayer.Status.PLAYING)
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                repeat(activity.channels.size) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
                assertTrue(view(R.id.refresh_channels).hasFocus())
            },
            { press(KeyEvent.KEYCODE_MENU) },
            { press(KeyEvent.KEYCODE_BACK); press(KeyEvent.KEYCODE_BACK); screen.setPlaybackStatus(TvPlayer.Status.FAILED) },
            { screen.setPlaybackStatus(TvPlayer.Status.PLAYING); press(KeyEvent.KEYCODE_BACK) },
        )
        states.forEachIndexed { index, enter ->
            enter()
            val focused = view(R.id.root).findFocus()
            // Playback has no interactive control requiring focus. Menus/dialogs must have one.
            if (index >= 4) assertNotNull("screen $index", focused)
            val before = activity.volume.state.level
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals(before + 1, activity.volume.state.level)
            assertSame("right changes focus in screen $index", focused, view(R.id.root).findFocus())
            press(KeyEvent.KEYCODE_VOLUME_DOWN)
            assertEquals(before, activity.volume.state.level)
            press(KeyEvent.KEYCODE_VOLUME_UP)
            assertEquals(before + 1, activity.volume.state.level)
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals(before, activity.volume.state.level)
            press(KeyEvent.KEYCODE_VOLUME_MUTE)
            assertEquals(0, activity.volume.state.level)
            press(KeyEvent.KEYCODE_VOLUME_MUTE)
            assertEquals(before, activity.volume.state.level)
            assertSame("volume changes focus in screen $index", focused, view(R.id.root).findFocus())
            assertEquals(View.VISIBLE, view(R.id.volume_notice).visibility)
            println("Volume screen=$index focus=${focused?.id} level=$before retained")
        }
        assertTrue(activity.selected.isEmpty())
        assertEquals(0, activity.exits)
        assertEquals(0, activity.retries)
        assertEquals(0, activity.acknowledgements)
    }

    @Test fun pendingChannelDigitsAndTheirOriginalTimerSurviveEveryVolumeKey() {
        for (inList in listOf(false, true)) {
            if (inList) press(KeyEvent.KEYCODE_DPAD_CENTER)
            press(KeyEvent.KEYCODE_0)
            press(KeyEvent.KEYCODE_5)
            advance(1000)
            val focused = view(R.id.root).findFocus()
            for (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE)) press(key)
            assertEquals("Canal: 05", (view(R.id.channel_input) as TextView).text.toString())
            assertSame(focused, view(R.id.root).findFocus())
            advance(400)
            assertEquals(if (inList) 1 else 0, activity.selected.size)
            advance(200)
            assertEquals(activity.channels.last(), activity.selected.last())
            if (inList) press(KeyEvent.KEYCODE_BACK)
        }
        assertEquals(2, activity.selected.size)
    }

    @Test fun heldPressChangesOnePerRepeatAndReleaseNeverAdjustsAgain() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_VOLUME_UP)) {
            val before = activity.volume.state.level
            for (repeat in 0..9) {
                assertTrue(activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, repeat)))
                assertEquals(before + repeat + 1, activity.volume.state.level)
            }
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
            assertEquals(before + 10, activity.volume.state.level)
        }
        press(KeyEvent.KEYCODE_VOLUME_MUTE)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, activity.volume.state.level)
    }

    @Test fun noticeIsInformationalInsideSafeAreaAndExpiresThreeSecondsAfterLastPress() {
        val notice = view(R.id.volume_notice)
        assertSame(view(R.id.safe_content), notice.parent)
        assertFalse(notice.isFocusable)
        assertFalse(view(R.id.volume_bar).isFocusable)
        assertFalse(view(R.id.volume_bar).isClickable)
        press(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(51, (view(R.id.volume_bar) as ProgressBar).progress)
        advance(2500)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        advance(2999)
        assertEquals(View.VISIBLE, notice.visibility)
        advance(1)
        assertEquals(View.GONE, notice.visibility)
        press(KeyEvent.KEYCODE_VOLUME_MUTE)
        assertTrue((view(R.id.volume_label) as TextView).text.startsWith("Silencio"))
        screen.stop()
        advance(3000)
        assertEquals(View.GONE, notice.visibility)
    }

    @Test fun exitStartsWithKeepWatchingAndUsesVerticalSelectionAndOk() {
        screen.setPlaybackStatus(TvPlayer.Status.PLAYING)
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(view(R.id.keep_watching).hasFocus())
        assertEquals(LinearLayout.VERTICAL, (view(R.id.keep_watching).parent as LinearLayout).orientation)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(view(R.id.keep_watching).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(view(R.id.exit_app).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(view(R.id.keep_watching).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(View.GONE, view(R.id.exit_panel).visibility)
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(view(R.id.keep_watching).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, activity.exits)
    }

    @Test fun failureButtonsUseVerticalNavigationAndOkCanRetryOrAcknowledge() {
        screen.setPlaybackStatus(TvPlayer.Status.FAILED)
        assertTrue(view(R.id.accept_change).hasFocus())
        assertEquals(LinearLayout.VERTICAL, (view(R.id.accept_change).parent as LinearLayout).orientation)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(view(R.id.retry_channel).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(view(R.id.retry_channel).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, activity.retries)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(view(R.id.accept_change).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, activity.acknowledgements)
        screen.setFailureMessage(unitel = true)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(View.VISIBLE, view(R.id.menu_panel).visibility)
        assertEquals(1, activity.acknowledgements)
    }
}

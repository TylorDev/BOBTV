package com.bobtv.helloworld

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import com.bobtv.helloworld.updates.UpdatePhase
import com.bobtv.helloworld.updates.UpdateStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1280dp-h720dp-land")
class TvScreenUpdatesTest {
    class ScreenActivity : Activity() {
        lateinit var screen: TvScreen
        var checks = 0
        var logs = 0
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setTheme(R.style.Theme_BOBTV)
            setContentView(R.layout.activity_main)
            screen = TvScreen(this, {}, {}, {}, { logs++ }, {}, onSaveLogs = { logs++ }, onCheckUpdates = { checks++ })
        }
        override fun dispatchKeyEvent(event: KeyEvent) = screen.handleKeyEvent(event) || super.dispatchKeyEvent(event)
    }
    private lateinit var controller: ActivityController<ScreenActivity>
    private lateinit var activity: ScreenActivity
    private fun view(id: Int): View = activity.findViewById(id)
    private fun press(key: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }
    @Before fun setup() {
        controller = Robolectric.buildActivity(ScreenActivity::class.java).setup().visible()
        activity = controller.get()
        press(KeyEvent.KEYCODE_MENU)
    }
    @After fun close() { activity.screen.stop(); controller.pause().stop().destroy() }

    @Test fun allSettingsActionsAreReachableAndCurrentNotesWorkOffline() {
        assertTrue(view(R.id.save_logs).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(view(R.id.export_logs).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(view(R.id.version_notes).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(View.VISIBLE, view(R.id.notes_panel).visibility)
        assertTrue((view(R.id.notes_text) as TextView).text.contains("Búsqueda automática"))
        activity.screen.setPlaybackStatus(com.bobtv.helloworld.player.TvPlayer.Status.FAILED)
        assertEquals(View.VISIBLE, view(R.id.notes_panel).visibility)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_9)
        assertEquals(View.GONE, view(R.id.channel_input).visibility)
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(view(R.id.version_notes).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(view(R.id.check_updates).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, activity.checks)
        assertEquals(0, activity.logs)
    }

    @Test fun progressDisablesDuplicateManualActionAndNavigationStillWorks() {
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue(view(R.id.check_updates).hasFocus())
        activity.screen.setUpdateStatus(UpdateStatus(UpdatePhase.DOWNLOADING, "Descargando · 42%", 42))
        assertFalse(view(R.id.check_updates).isEnabled)
        assertTrue(view(R.id.version_notes).hasFocus())
        assertEquals(View.VISIBLE, view(R.id.update_progress).visibility)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(View.VISIBLE, view(R.id.notes_panel).visibility)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(view(R.id.version_notes).hasFocus())
        activity.screen.setUpdateStatus(UpdateStatus(UpdatePhase.READY, "Se instalará al próximo inicio"))
        assertTrue(view(R.id.check_updates).isEnabled)
        assertEquals(View.GONE, view(R.id.update_progress).visibility)
    }
}

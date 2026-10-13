package com.bobtv.helloworld.controls

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class RemoteDigitsTest {
    @Test fun readsEveryDigitFromRemoteOrKeyboardNumberKeys() {
        for (digit in 0..9) assertEquals(digit, RemoteDigits.fromKeyCode(KeyEvent.KEYCODE_0 + digit))
    }

    @Test fun readsEveryDigitFromTheNumericKeypad() {
        for (digit in 0..9) assertEquals(digit, RemoteDigits.fromKeyCode(KeyEvent.KEYCODE_NUMPAD_0 + digit))
    }

    @Test fun navigationConfirmationAndSystemKeysAreNotDigits() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_HOME)) {
            assertNull(RemoteDigits.fromKeyCode(key))
        }
    }
}

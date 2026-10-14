package com.bobtv.helloworld.controls

import com.bobtv.helloworld.audio.VolumeController.Action

/** Key codes are Android constants; kept here so repetition/release policy is JVM-testable. */
object RemoteVolume {
    fun action(keyCode: Int): Action? = when (keyCode) {
        21, 25 -> Action.LOWER // DPAD_LEFT, VOLUME_DOWN
        22, 24 -> Action.RAISE // DPAD_RIGHT, VOLUME_UP
        164 -> Action.MUTE // VOLUME_MUTE (MUTE=91 is the microphone mute key)
        else -> null
    }

    fun handle(keyCode: Int, eventAction: Int, onAdjust: (Action) -> Unit): Boolean {
        val action = action(keyCode) ?: return false
        if (eventAction == 0) onAdjust(action) // Every DOWN, including each repeat.
        return true // Consume UP too; never let Android adjust the system a second time.
    }
}

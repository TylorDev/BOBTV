package com.bobtv.helloworld.controls

import com.bobtv.helloworld.audio.VolumeController.Action
import org.junit.Assert.*
import org.junit.Test

class RemoteVolumeTest {
    @Test fun horizontalArrowsAndVolumeButtonsHaveIdenticalActions() {
        assertEquals(Action.LOWER, RemoteVolume.action(21))
        assertEquals(RemoteVolume.action(21), RemoteVolume.action(25))
        assertEquals(Action.RAISE, RemoteVolume.action(22))
        assertEquals(RemoteVolume.action(22), RemoteVolume.action(24))
        assertEquals(Action.MUTE, RemoteVolume.action(164))
        for (key in listOf(19, 20, 23, 7, 8, 4, 82, 91)) assertNull(RemoteVolume.action(key))
    }

    @Test fun heldButtonProcessesEveryDownAndConsumesReleaseWithoutAnotherAdjustment() {
        for (key in listOf(21, 22, 24, 25, 164)) {
            val actions = mutableListOf<Action>()
            repeat(12) { assertTrue(RemoteVolume.handle(key, 0, actions::add)) }
            assertEquals(12, actions.size)
            assertTrue(RemoteVolume.handle(key, 1, actions::add))
            assertEquals(12, actions.size)
            // A release arriving after resume is still consumed.
            assertTrue(RemoteVolume.handle(key, 1, actions::add))
            assertFalse(RemoteVolume.handle(19, 0, actions::add))
        }
    }
}

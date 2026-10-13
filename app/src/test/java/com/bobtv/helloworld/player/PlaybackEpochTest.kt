package com.bobtv.helloworld.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackEpochTest {
    @Test fun manualRetryOfSameChannelCancelsAnAlreadyScheduledAlternative() {
        val epoch = PlaybackEpoch()
        epoch.replace()
        val old = epoch.ticket()
        var unwantedSwitches = 0
        val delayedAlternative = { if (epoch.accepts(old)) unwantedSwitches++ }
        epoch.replace() // Manual retry, same channel and URL.
        delayedAlternative()
        assertEquals(0, unwantedSwitches)
        assertTrue(epoch.accepts(epoch.ticket()))
    }
    @Test fun nextSourceAttemptInvalidatesOldProgressWithoutChangingSelection() {
        val epoch = PlaybackEpoch()
        epoch.replace()
        val generation = epoch.generation
        val old = epoch.ticket()
        epoch.nextAttempt()
        assertEquals(generation, epoch.generation)
        assertFalse(epoch.accepts(old))
    }
    @Test fun stopAndReturnRejectCallbacksFromThePreviousForegroundSession() {
        val epoch = PlaybackEpoch()
        epoch.replace()
        val old = epoch.ticket()
        epoch.replace() // Release.
        epoch.replace() // Restore the same channel.
        assertFalse(epoch.accepts(old))
    }
}

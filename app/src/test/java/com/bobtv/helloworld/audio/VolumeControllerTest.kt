package com.bobtv.helloworld.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class VolumeControllerTest {
    private class MemoryPreferences(var saved: VolumeState? = null) : VolumePreferences {
        override fun read() = saved
        override fun write(state: VolumeState) { saved = state }
    }
    private class FakeDevice(var state: DeviceVolume, val events: MutableList<String>) : DeviceAudio {
        var reject = false
        var throwChanges = false
        var throwReads = false
        var ceiling = state.maximum
        var db: (Int) -> Float? = { null }
        override fun read(): DeviceVolume {
            if (throwReads) throw IllegalStateException("read rejected")
            return state
        }
        override fun setIndex(index: Int) {
            events.add("index=$index")
            if (throwChanges) throw SecurityException("rejected")
            if (!state.fixed && !reject) {
                val next = index.coerceIn(state.minimum, ceiling)
                state = state.copy(index = next, muted = next == 0)
            }
        }
        override fun setMuted(muted: Boolean) {
            events.add("mute=$muted")
            if (throwChanges) throw SecurityException("rejected")
            if (!state.fixed && !reject) state = state.copy(muted = muted)
        }
        override fun volumeDb(index: Int) = db(index)
    }
    private class Fixture(maximum: Int = 15, index: Int = 5, muted: Boolean = false,
                          fixed: Boolean = false, minimum: Int = 0, saved: VolumeState? = null) {
        val events = mutableListOf<String>()
        val device = FakeDevice(DeviceVolume(minimum, maximum, index, muted, fixed), events)
        val preferences = MemoryPreferences(saved)
        val gains = mutableListOf<Float>()
        val logs = mutableListOf<String>()
        val controller = VolumeController(device, preferences, { gains.add(it); events.add("gain=$it") }, logs::add)
    }

    @Test fun fifteenStepsUseAppModeAndAllOneHundredIncrementsAreDistinct() {
        val f = Fixture(saved = VolumeState(0, 45))
        f.controller.start()
        assertEquals(VolumeController.Mode.APP, f.controller.mode)
        assertEquals(15, f.device.state.index)
        val gains = mutableListOf(f.gains.last())
        repeat(100) { step ->
            assertEquals(step + 1, f.controller.adjust(VolumeController.Action.RAISE))
            gains.add(f.gains.last())
        }
        assertEquals(101, gains.toSet().size)
        assertTrue(gains.zipWithNext().all { (first, second) -> second > first })
        assertEquals(1f, gains.last(), 0f)
        repeat(10) { assertEquals(100, f.controller.adjust(VolumeController.Action.RAISE)) }
        repeat(100) { f.controller.adjust(VolumeController.Action.LOWER) }
        repeat(10) { assertEquals(0, f.controller.adjust(VolumeController.Action.LOWER)) }
        assertEquals(0f, f.gains.last(), 0f)
        assertEquals(15, f.device.state.index)
    }

    @Test fun nativeOneHundredStepsUseDistinctIndicesAndUnityPlayerGain() {
        for (maximum in listOf(100, 127, 200)) {
            val f = Fixture(maximum = maximum, saved = VolumeState(0, 40))
            f.controller.start()
            val indices = mutableListOf(f.device.state.index)
            assertEquals(VolumeController.Mode.NATIVE, f.controller.mode)
            repeat(100) {
                f.controller.adjust(VolumeController.Action.RAISE)
                indices.add(f.device.state.index)
                assertEquals(1f, f.gains.last(), 0f)
                assertFalse(f.device.state.muted)
            }
            assertEquals(101, indices.toSet().size)
            assertEquals(maximum, indices.last())
            f.controller.adjust(VolumeController.Action.MUTE)
            assertEquals(0, f.device.state.index)
            assertTrue(f.device.state.muted)
            assertEquals(1f, f.gains.last(), 0f)
        }
    }

    @Test fun nonzeroMinimumCannotUseNativeEvenWithOneHundredIncrements() {
        val f = Fixture(maximum = 101, minimum = 1)
        f.controller.start()
        assertEquals(VolumeController.Mode.APP, f.controller.mode)
    }

    @Test fun quadraticCurveHasExactSilenceAndClampsOutOfRangeValues() {
        assertEquals(0f, VolumeController.gain(-1), 0f)
        assertEquals(0.0001f, VolumeController.gain(1), 0.000001f)
        assertEquals(0.25f, VolumeController.gain(50), 0f)
        assertEquals(1f, VolumeController.gain(101), 0f)
        for (level in 1..100) assertTrue(VolumeController.gain(level) > VolumeController.gain(level - 1))
    }

    @Test fun muteRemembersLastAudibleAndRaiseFromSilenceIsOne() {
        val f = Fixture(saved = VolumeState(37, 37))
        f.controller.start()
        assertEquals(0, f.controller.adjust(VolumeController.Action.MUTE))
        assertEquals(VolumeState(0, 37), f.preferences.saved)
        assertEquals(37, f.controller.adjust(VolumeController.Action.MUTE))
        f.controller.adjust(VolumeController.Action.MUTE)
        assertEquals(1, f.controller.adjust(VolumeController.Action.RAISE))
        assertEquals(VolumeState(1, 1), f.preferences.saved)
    }

    @Test fun fixedVolumeAndRejectedOrClampedMaximumAreLoggedWithoutInventingSuccess() {
        val fixed = Fixture(index = 15, fixed = true)
        fixed.controller.start()
        assertEquals(VolumeController.Mode.APP, fixed.controller.mode)
        assertTrue(fixed.logs.any { "Limitation" in it })
        val rejected = Fixture()
        rejected.device.reject = true
        rejected.controller.start()
        assertEquals(5, rejected.device.state.index)
        assertEquals(100, rejected.controller.state.level) // Unchanged device gain needs no local attenuation.
        assertTrue(rejected.logs.any { "requested maximum" in it })
        val clamped = Fixture()
        clamped.device.ceiling = 10
        clamped.device.db = { if (it == 5) -12f else if (it == 10) 0f else null }
        clamped.controller.start()
        assertEquals(10, clamped.device.state.index)
        assertEquals(50, clamped.controller.state.level) // Uses actual 10, not the rejected maximum 15.
    }

    @Test fun nativeRejectionAtStartFallsBackAndLaterRejectionKeepsTheSelectedLevel() {
        val first = Fixture(maximum = 100, index = 30, saved = VolumeState(40, 40))
        first.device.reject = true
        first.controller.start()
        assertEquals(VolumeController.Mode.APP, first.controller.mode)
        assertEquals(40, first.controller.state.level)
        val later = Fixture(maximum = 100, index = 30)
        later.controller.start()
        assertEquals(VolumeController.Mode.NATIVE, later.controller.mode)
        later.device.reject = true
        assertEquals(31, later.controller.adjust(VolumeController.Action.RAISE))
        assertEquals(VolumeController.Mode.APP, later.controller.mode)
        assertEquals(VolumeController.gain(31), later.gains.last(), 0f)
        assertTrue(later.logs.any { "native change rejected" in it })
    }

    @Test fun exceptionsInFirmwareFallBackAndAreLogged() {
        val f = Fixture(maximum = 100, index = 30, saved = VolumeState(40, 40))
        f.device.throwChanges = true
        f.controller.start()
        assertEquals(VolumeController.Mode.APP, f.controller.mode)
        assertEquals(VolumeController.gain(40), f.gains.last(), 0f)
        f.device.state = f.device.state.copy(index = 20)
        f.controller.stop()
        assertTrue(f.logs.any { "SecurityException" in it })
        assertTrue(f.logs.any { "restore requested" in it })
    }

    @Test fun captureFailureNeverChangesUnknownSystemState() {
        val f = Fixture(saved = VolumeState(25, 25))
        f.device.throwReads = true
        f.controller.start()
        f.controller.adjust(VolumeController.Action.RAISE)
        f.controller.stop()
        assertFalse(f.events.any { it.startsWith("index=") || it.startsWith("mute=") })
        assertEquals(26, f.preferences.saved?.level)
    }

    @Test fun firstStartEstimatesDbDifferenceAndNeverRoundsUp() {
        val f = Fixture()
        f.device.db = { if (it == 5) -12f else 0f }
        f.controller.start()
        assertEquals(50, f.controller.state.level)
        assertTrue(VolumeController.gain(50) <= 10.0.pow(-12.0 / 20).toFloat())
        assertEquals(57, VolumeController.estimateLevel(5, 15, null, null))
        assertEquals(100, VolumeController.estimateLevel(5, 5, -6f, -6f))
        assertEquals(100, VolumeController.estimateLevel(10, 5, 0f, -6f))
        assertEquals(0, VolumeController.estimateLevel(0, 15, null, null))
        assertEquals(0, VolumeController.estimateLevel(5, 15, Float.NEGATIVE_INFINITY, 0f))
        assertEquals(57, VolumeController.estimateLevel(5, 15, Float.NaN, 0f))
        assertEquals(57, VolumeController.estimateLevel(5, 15, -6f, Float.NEGATIVE_INFINITY))
        assertEquals(0, VolumeController.estimateLevel(5, 0, null, null))
    }

    @Test fun initialMuteAndZeroArePreservedInBothModes() {
        for (maximum in listOf(15, 100)) for ((index, muted) in listOf(5 to true, 0 to false)) {
            val f = Fixture(maximum = maximum, index = index, muted = muted)
            f.controller.start()
            assertEquals(0, f.controller.state.level)
            assertTrue(f.controller.state.lastAudible >= 1)
            assertEquals(if (maximum == 100) 1f else 0f, f.gains.last(), 0f)
            if (maximum == 100) assertTrue(f.device.state.muted)
        }
    }

    @Test fun gainIsZeroBeforeMaximumAndDeviceStateIsRestoredOnStop() {
        for (muted in listOf(false, true)) {
            val f = Fixture(muted = muted)
            f.controller.start()
            assertEquals("gain=0.0", f.events.first())
            assertTrue(f.events.indexOf("gain=0.0") < f.events.indexOf("index=15"))
            f.controller.adjust(VolumeController.Action.RAISE)
            f.controller.stop()
            assertEquals(5, f.device.state.index)
            assertEquals(muted, f.device.state.muted)
            assertEquals(0f, f.gains.last(), 0f)
            assertTrue(f.logs.any { "Restore" in it && "verified" in it })
        }
    }

    @Test fun nativeModeAlsoRestoresVolumeAndMute() {
        val f = Fixture(maximum = 100, index = 32, muted = true, saved = VolumeState(66, 66))
        f.controller.start()
        assertEquals(66, f.device.state.index)
        assertFalse(f.device.state.muted)
        f.controller.stop()
        assertEquals(32, f.device.state.index)
        assertTrue(f.device.state.muted)
    }

    @Test fun resumeCapturesNewDeviceStateButPreservesSavedBobtvVolume() {
        val f = Fixture(saved = VolumeState(40, 40))
        f.controller.start()
        f.controller.adjust(VolumeController.Action.MUTE)
        f.controller.stop()
        f.device.state = f.device.state.copy(index = 9, muted = true)
        f.controller.start()
        assertEquals(VolumeState(0, 40), f.controller.state)
        assertEquals(40, f.controller.adjust(VolumeController.Action.MUTE))
        f.controller.stop()
        assertEquals(9, f.device.state.index)
        assertTrue(f.device.state.muted)
    }

    @Test fun duplicateStartsAndStopsDoNotLoseOriginalCaptureAndRestartUsesPersistence() {
        val f = Fixture(saved = VolumeState(24, 24))
        f.controller.start()
        f.controller.start()
        f.controller.adjust(VolumeController.Action.MUTE)
        f.controller.stop()
        f.controller.stop()
        assertEquals(5, f.device.state.index)
        val next = VolumeController(f.device, f.preferences, f.gains::add, f.logs::add)
        next.start()
        assertEquals(VolumeState(0, 24), next.state)
        assertEquals(24, next.adjust(VolumeController.Action.MUTE))
        next.stop()
    }
}

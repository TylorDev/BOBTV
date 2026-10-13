package com.bobtv.helloworld.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ResourceGovernorTest {
    private val mib = 1024L * 1024
    @Test fun heapAndThermalLimitsActImmediatelyButRecoveryNeedsTwoMinutes() {
        val governor = ResourceGovernor()
        assertEquals(ResourceLevel.CRITICAL, governor.evaluate(0, 86 * mib, 100 * mib, false, false, -1, null, false))
        assertEquals(ResourceLevel.CRITICAL, governor.evaluate(1_000, 30 * mib, 100 * mib, false, false, -1, null, false))
        assertEquals(ResourceLevel.NORMAL, governor.evaluate(121_000, 30 * mib, 100 * mib, false, false, -1, null, false))
        assertEquals(ResourceLevel.STOP, governor.evaluate(122_000, 30 * mib, 100 * mib, false, false, 4, null, false))
    }
    @Test fun pssThresholdsNeedIndependentMeasurementsNotFastHeapTicks() {
        val governor = ResourceGovernor()
        repeat(2) { assertEquals(ResourceLevel.NORMAL, governor.evaluate(it * 60_000L, 20 * mib, 128 * mib, false, false, -1, 110 * 1024, false)) }
        repeat(5) { assertEquals(ResourceLevel.NORMAL, governor.evaluate(120_000, 20 * mib, 128 * mib, false, false, -1, null, false)) }
        assertEquals(ResourceLevel.LIMITED, governor.evaluate(120_000, 20 * mib, 128 * mib, false, false, -1, 110 * 1024, false))
    }
    @Test fun missingThermalReportingIsNotInventedAndSystemPressureStillActs() {
        val governor = ResourceGovernor()
        assertEquals(ResourceLevel.NORMAL, governor.evaluate(0, 20 * mib, 128 * mib, false, false, -1, null, false))
        assertEquals(ResourceLevel.CRITICAL, governor.evaluate(1, 20 * mib, 128 * mib, true, false, -1, null, false))
    }
}

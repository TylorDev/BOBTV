package com.bobtv.helloworld.audio

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

data class DeviceVolume(val minimum: Int, val maximum: Int, val index: Int, val muted: Boolean, val fixed: Boolean)
data class VolumeState(val level: Int, val lastAudible: Int)

interface DeviceAudio {
    fun read(): DeviceVolume
    fun setIndex(index: Int)
    fun setMuted(muted: Boolean)
    fun volumeDb(index: Int): Float?
}

interface VolumePreferences {
    fun read(): VolumeState?
    fun write(state: VolumeState)
}

/** All policy is independent of Android; only DeviceAudio and VolumePreferences touch the platform. */
class VolumeController(
    private val device: DeviceAudio,
    private val preferences: VolumePreferences,
    private val setGain: (Float) -> Unit,
    private val log: (String) -> Unit,
) {
    enum class Mode { NATIVE, APP }
    enum class Action { LOWER, RAISE, MUTE }
    var mode = Mode.APP
        private set
    var state = VolumeState(0, 1)
        private set
    private var original: DeviceVolume? = null
    private var active = false

    fun start() {
        if (active) return
        active = true
        // Suppress sound before changing the device index, including on first launch and resume.
        setGain(0f)
        original = readDevice()
        val saved = preferences.read()?.let { VolumeState(it.level.coerceIn(0, 100), it.lastAudible.coerceIn(1, 100)) }
        val captured = original
        log("Capture device=$captured saved=$saved")
        if (captured == null) {
            mode = Mode.APP
            state = saved ?: VolumeState(0, 1)
            log("Limitation: cannot capture device audio; leaving system settings untouched")
        } else if (captured.minimum == 0 && captured.maximum >= 100 && !captured.fixed) {
            mode = Mode.NATIVE
            val mapped = nativeLevel(captured.index, captured.maximum)
            state = saved ?: VolumeState(if (captured.muted || captured.index == 0) 0 else mapped, mapped.coerceAtLeast(1))
            if (!applyNative()) enterAppMode(captured, saved)
        } else enterAppMode(captured, saved)
        publish()
    }

    fun adjust(action: Action): Int {
        if (!active) return state.level
        val level = when (action) {
            Action.LOWER -> (state.level - 1).coerceAtLeast(0)
            Action.RAISE -> (state.level + 1).coerceAtMost(100)
            Action.MUTE -> if (state.level == 0) state.lastAudible else 0
        }
        state = VolumeState(level, if (level > 0) level else state.lastAudible)
        if (mode == Mode.NATIVE && !applyNative()) {
            // Keep the user's selected level when a later native change is rejected.
            original?.let { enterAppMode(it, state) } ?: run { mode = Mode.APP }
        }
        publish()
        return state.level
    }

    /** Caller stops playback first. Repeated stop calls must never overwrite the captured state. */
    fun stop() {
        if (!active) return
        active = false
        setGain(0f)
        original?.let { captured ->
            val restored = changeDevice(captured.index, captured.muted)
            log(if (restored != null && restored.index == captured.index && restored.muted == captured.muted)
                "Restore device index=${restored.index} muted=${restored.muted} verified"
            else "Limitation: restore requested index=${captured.index} muted=${captured.muted}; effective=$restored")
        }
        original = null
    }

    private fun enterAppMode(captured: DeviceVolume, saved: VolumeState?) {
        mode = Mode.APP
        setGain(0f)
        val effective = changeDevice(captured.maximum, false) ?: captured
        if (effective.index != captured.maximum || effective.muted || captured.fixed)
            log("Limitation: app mode requested maximum=${captured.maximum} unmuted; effective=$effective")
        if (saved != null) state = saved else {
            val originalDb = runCatching { device.volumeDb(captured.index) }.getOrNull()
            val effectiveDb = runCatching { device.volumeDb(effective.index) }.getOrNull()
            val estimate = estimateLevel(captured.index, effective.index, originalDb, effectiveDb)
            state = VolumeState(if (captured.muted || captured.index == 0) 0 else estimate, estimate.coerceAtLeast(1))
            log("Initial estimate original=${captured.index} effective=${effective.index} " +
                "originalDb=$originalDb effectiveDb=$effectiveDb level=${state.level} lastAudible=${state.lastAudible}")
        }
    }

    private fun applyNative(): Boolean {
        val maximum = original?.maximum ?: return false
        val index = nativeIndex(state.level, maximum)
        val effective = changeDevice(index, state.level == 0)
        val accepted = effective != null && effective.index == index && effective.muted == (state.level == 0)
        if (!accepted) log("Limitation: native change rejected level=${state.level} index=$index effective=$effective; switching to app")
        return accepted
    }

    private fun changeDevice(index: Int, muted: Boolean): DeviceVolume? {
        runCatching { device.setIndex(index) }.onFailure { log("Limitation: set index=$index failed: ${it.javaClass.simpleName}") }
        val afterIndex = readDevice()
        if (afterIndex?.muted != muted) {
            runCatching { device.setMuted(muted) }.onFailure { log("Limitation: set mute=$muted failed: ${it.javaClass.simpleName}") }
        }
        // Some firmwares restore their last index when unmuting. Verify and reapply the target.
        val afterMute = readDevice()
        if (!muted && afterMute != null && afterMute.index != index && !afterMute.muted) {
            runCatching { device.setIndex(index) }.onFailure { log("Limitation: reapply index=$index failed: ${it.javaClass.simpleName}") }
        }
        return readDevice()
    }

    private fun readDevice(): DeviceVolume? = runCatching { device.read() }
        .onFailure { log("Limitation: device read failed: ${it.javaClass.simpleName}") }.getOrNull()

    private fun publish() {
        val gain = if (mode == Mode.NATIVE) 1f else gain(state.level)
        setGain(gain)
        preferences.write(state)
        log("Applied mode=$mode level=${state.level} lastAudible=${state.lastAudible} gain=$gain")
    }

    companion object {
        fun gain(level: Int): Float = (level.coerceIn(0, 100) / 100f).let { it * it }
        fun nativeIndex(level: Int, maximum: Int): Int = (level.coerceIn(0, 100).toLong() * maximum / 100).toInt()
        fun nativeLevel(index: Int, maximum: Int): Int = if (maximum > 0)
            (index.toLong() * 100 / maximum).toInt().coerceIn(0, 100) else 0

        fun estimateLevel(originalIndex: Int, effectiveIndex: Int, originalDb: Float?, effectiveDb: Float?): Int {
            val amplitude = when {
                originalIndex <= 0 -> 0.0
                originalDb == Float.NEGATIVE_INFINITY && effectiveDb?.isFinite() == true -> 0.0
                originalDb?.isFinite() == true && effectiveDb?.isFinite() == true ->
                    10.0.pow((originalDb.toDouble() - effectiveDb) / 20.0)
                effectiveIndex > 0 -> originalIndex.toDouble() / effectiveIndex
                else -> 0.0
            }.coerceIn(0.0, 1.0)
            return floor(sqrt(amplitude) * 100.0).toInt().coerceIn(0, 100)
        }
    }
}

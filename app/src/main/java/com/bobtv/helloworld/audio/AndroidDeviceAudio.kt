package com.bobtv.helloworld.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class AndroidDeviceAudio(private val audio: AudioManager, private val log: (String) -> Unit = {}) : DeviceAudio {
    override fun read() = DeviceVolume(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC),
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        audio.isStreamMute(AudioManager.STREAM_MUSIC), audio.isVolumeFixed)

    override fun setIndex(index: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)

    override fun setMuted(muted: Boolean) = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
        if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)

    override fun volumeDb(index: Int): Float? {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val type = when {
            outputs.any { it.type == AudioDeviceInfo.TYPE_HDMI } -> AudioDeviceInfo.TYPE_HDMI
            outputs.any { it.type == AudioDeviceInfo.TYPE_HDMI_ARC } -> AudioDeviceInfo.TYPE_HDMI_ARC
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                outputs.any { it.type == AudioDeviceInfo.TYPE_HDMI_EARC } -> AudioDeviceInfo.TYPE_HDMI_EARC
            else -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        val db = runCatching { audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, type) }.getOrNull()
        log("STREAM_MUSIC dB query index=$index deviceType=$type hdmi=${type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER} db=$db")
        return db
    }
}

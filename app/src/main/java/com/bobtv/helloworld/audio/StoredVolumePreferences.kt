package com.bobtv.helloworld.audio

import android.content.SharedPreferences

class StoredVolumePreferences(private val preferences: SharedPreferences) : VolumePreferences {
    override fun read(): VolumeState? {
        if (!preferences.contains("volume_level")) return null
        return VolumeState(preferences.getInt("volume_level", 0), preferences.getInt("volume_last_audible", 1))
    }

    override fun write(state: VolumeState) {
        preferences.edit().putInt("volume_level", state.level)
            .putInt("volume_last_audible", state.lastAudible).apply()
    }
}

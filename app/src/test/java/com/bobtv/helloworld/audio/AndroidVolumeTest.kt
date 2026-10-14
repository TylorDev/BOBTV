package com.bobtv.helloworld.audio

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33])
class AndroidVolumeTest {
    @Test fun adapterControlsMusicStreamAndManifestDeclaresNormalPermission() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        try {
            val manager = activity.getSystemService(AudioManager::class.java)
            val adapter = AndroidDeviceAudio(manager)
            val initial = adapter.read()
            assertEquals(manager.getStreamMinVolume(AudioManager.STREAM_MUSIC), initial.minimum)
            assertEquals(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), initial.maximum)
            assertEquals(manager.isVolumeFixed, initial.fixed)
            adapter.setIndex(3)
            assertEquals(3, adapter.read().index)
            adapter.setMuted(true)
            assertTrue(adapter.read().muted)
            adapter.setMuted(false)
            assertFalse(adapter.read().muted)
            val requested = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
            assertTrue(requested?.contains("android.permission.MODIFY_AUDIO_SETTINGS") == true)
        } finally { activityController.pause().stop().destroy() }
    }

    @Test fun preferencesPersistLevelAndLastAudibleWithoutOverwritingExistingChannel() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val preferences = activityController.get().getSharedPreferences("bobtv", Context.MODE_PRIVATE)
        try {
            preferences.edit().clear().putString("last_channel", "unitel").commit()
            assertNull(StoredVolumePreferences(preferences).read())
            StoredVolumePreferences(preferences).write(VolumeState(0, 37))
            assertEquals(VolumeState(0, 37), StoredVolumePreferences(preferences).read())
            assertEquals("unitel", preferences.getString("last_channel", null))
            StoredVolumePreferences(preferences).write(VolumeState(1, 1))
            assertEquals(VolumeState(1, 1), StoredVolumePreferences(preferences).read())
        } finally { activityController.pause().stop().destroy() }
    }

    @Test fun dbEstimationQueriesHdmiWhenPresentAndBuiltInSpeakerOtherwise() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val manager = activityController.get().getSystemService(AudioManager::class.java)
        val logs = mutableListOf<String>()
        val adapter = AndroidDeviceAudio(manager, logs::add)
        try {
            shadowOf(manager).setOutputDevices(emptyList())
            adapter.volumeDb(3)
            assertTrue(logs.last().contains("deviceType=${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER} hdmi=false"))
            for (type in listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC)) {
                shadowOf(manager).setOutputDevices(listOf(
                    AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build(),
                    AudioDeviceInfoBuilder.newBuilder().setType(type).build()))
                adapter.volumeDb(3)
                assertTrue(logs.last().contains("deviceType=$type hdmi=true"))
            }
            println(logs.joinToString("\n"))
        } finally { activityController.pause().stop().destroy() }
    }
}

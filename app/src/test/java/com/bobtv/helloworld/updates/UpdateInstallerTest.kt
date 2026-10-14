package com.bobtv.helloworld.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33])
class UpdateInstallerTest {
    private lateinit var context: Context
    private lateinit var storage: UpdateStorage
    private lateinit var manifest: UpdateManifest
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        storage = UpdateStorage(context)
        storage.clearPending()
        storage.preferences.edit().clear().commit()
        manifest = UpdateManifest("2.5", 9, context.packageName, 29,
            "${UpdateManifest.REPOSITORY}/releases/download/v2.5/BOBTV-2.5-Android10.apk", 3, "0".repeat(64), "Prueba")
        storage.apk(manifest).writeText("apk")
        storage.savePending(manifest)
    }
    private fun callback(id: Int, status: Int) = Intent().putExtra("expected_session", id)
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, id).putExtra(PackageInstaller.EXTRA_STATUS, status)

    @Test fun sessionStagesSelfUpdateAndFailureKeepsPendingAndUserPreferences() {
        val preferences = context.getSharedPreferences("bobtv", Context.MODE_PRIVATE)
        preferences.edit().putString("last_channel", "unitel").putInt("volume_level", 42).commit()
        val installer = UpdateInstaller(context, storage)
        val id = installer.stage(storage.apk(manifest), manifest)
        assertEquals(context.packageName, context.packageManager.packageInstaller.getSessionInfo(id)!!.appPackageName)
        assertEquals(id, storage.sessionId)
        UpdateInstallReceiver().onReceive(context, callback(id, PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(-1, storage.sessionId)
        assertEquals(UpdatePhase.ERROR, storage.status().phase)
        assertEquals(manifest, storage.pending())
        assertEquals("unitel", preferences.getString("last_channel", null))
        assertEquals(42, preferences.getInt("volume_level", -1))
    }

    @Test fun pendingConfirmationSurvivesNewStorageAndRejectsUnrelatedCallback() {
        storage.session(15)
        val confirmation = Intent("test.android.CONFIRM").putExtra("session", 15)
        UpdateInstallReceiver().onReceive(context, callback(15, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, confirmation))
        assertEquals(UpdatePhase.CONFIRMATION, storage.status().phase)
        assertEquals("test.android.CONFIRM", Intent.parseUri(UpdateStorage(context).confirmation, Intent.URI_INTENT_SCHEME).action)
        UpdateInstallReceiver().onReceive(context, callback(16, PackageInstaller.STATUS_SUCCESS))
        assertEquals(15, storage.sessionId)
        assertNotNull(storage.pending())
    }

    @Test fun successfulInstallationClearsStagedFilesAndSession() {
        storage.session(12)
        UpdateInstallReceiver().onReceive(context, callback(12, PackageInstaller.STATUS_SUCCESS))
        assertNull(storage.pending())
        assertFalse(storage.apk(manifest).exists())
        assertEquals(-1, storage.sessionId)
        assertEquals(UpdatePhase.INSTALLED, storage.status().phase)
    }

    @Test fun processDeathBeforeCommitAbandonsUnsealedSessionButRetainsApk() {
        val installer = UpdateInstaller(context, storage)
        val id = installer.stage(storage.apk(manifest), manifest)
        UpdateInstaller(context, UpdateStorage(context)).reconcile()
        assertEquals(-1, storage.sessionId)
        assertNull(context.packageManager.packageInstaller.getSessionInfo(id))
        assertNotNull(storage.pending())
    }
}

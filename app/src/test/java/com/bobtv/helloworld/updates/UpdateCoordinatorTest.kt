package com.bobtv.helloworld.updates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateCoordinatorTest {
    class HostActivity : Activity()
    private lateinit var context: Context
    private lateinit var activity: HostActivity
    private lateinit var storage: UpdateStorage
    private lateinit var manifest: UpdateManifest
    private lateinit var coordinator: UpdateCoordinator
    private var pauses = 0
    private var resumes = 0

    @Suppress("DEPRECATION")
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        storage = UpdateStorage(context)
        storage.clearPending()
        storage.preferences.edit().clear().commit()
        val file = File(storage.directory, "fixture.apk").apply { writeText("apk") }
        manifest = UpdateManifest("2.5", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode + 1,
            context.packageName, 29, "${UpdateManifest.REPOSITORY}/releases/download/v2.5/BOBTV-2.5-Android10.apk",
            file.length(), sha256(file), "Parche")
        file.delete()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    private fun create(transport: UpdateTransport): UpdateRepository {
        val repository = UpdateRepository(context, storage, transport, ApkVerifier { _, _ -> }) { Long.MAX_VALUE }
        UpdateServices.setForTests(repository)
        coordinator = UpdateCoordinator(activity, repository, {}, { pauses++ }, { resumes++ })
        return repository
    }
    private fun transport() = object : UpdateTransport {
        override fun manifest(cancelled: () -> Boolean) = manifest.toJson()
        override fun download(manifest: UpdateManifest, destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
            destination.writeText("apk")
        }
    }

    @After fun cleanup() {
        if (::coordinator.isInitialized) coordinator.close()
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        UpdateServices.setForTests(null)
    }

    @Test fun pendingPatchBlocksPlaybackPermissionDenialResumesWithoutPromptLoop() {
        val repository = create(transport())
        repository.check(force = true)
        shadowOf(activity.packageManager).setCanRequestPackageInstalls(false)
        assertTrue(coordinator.start())
        assertEquals(1, pauses)
        assertEquals("android.settings.MANAGE_UNKNOWN_APP_SOURCES", shadowOf(activity).nextStartedActivityForResult.intent.action)
        coordinator.activityResult(UpdateCoordinator.REQUEST_SOURCES, Activity.RESULT_CANCELED)
        assertEquals(1, resumes)
        assertEquals(UpdatePhase.ERROR, storage.status().phase)
        assertFalse(coordinator.start())
        assertEquals(1, pauses)
        assertNotNull(storage.pending())
        coordinator.stop()
        assertTrue(coordinator.start())
        assertEquals(2, pauses)
    }

    @Test fun downloadFinishingInBackgroundIsRetainedWithoutStartingInstaller() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = object : UpdateTransport {
            override fun manifest(cancelled: () -> Boolean) = manifest.toJson()
            override fun download(manifest: UpdateManifest, destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                destination.writeText("apk")
            }
        }
        create(transport)
        coordinator.start()
        coordinator.resume()
        coordinator.checkNow()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        coordinator.stop()
        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (storage.pending() == null && System.nanoTime() < deadline) Thread.sleep(10)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        assertNotNull(storage.pending())
        assertEquals(0, pauses)
        assertEquals(-1, storage.sessionId)
        assertTrue(context.packageManager.packageInstaller.allSessions.isEmpty())
    }
}

package com.bobtv.helloworld.updates

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33])
class UpdateRepositoryTest {
    private lateinit var context: Context
    private lateinit var storage: UpdateStorage
    private val bytes = "signed APK fixture".toByteArray()
    private lateinit var release: UpdateManifest
    private var downloads = 0
    private var checks = 0
    private lateinit var transport: UpdateTransport

    @Suppress("DEPRECATION")
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        storage = UpdateStorage(context)
        storage.clearPending()
        storage.preferences.edit().clear().commit()
        release = UpdateManifest("2.5", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode + 1,
            context.packageName, 29, "${UpdateManifest.REPOSITORY}/releases/download/v2.5/BOBTV-2.5-Android10.apk",
            bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "# BOBTV 2.5\n\nParche de prueba")
        transport = object : UpdateTransport {
            override fun manifest(cancelled: () -> Boolean): String { checks++; return release.toJson() }
            override fun download(manifest: UpdateManifest, destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
                downloads++
                destination.writeBytes(bytes)
                progress(100)
            }
        }
    }
    private fun repository(transport: UpdateTransport = this.transport, space: Long = Long.MAX_VALUE,
                           verifier: ApkVerifier = ApkVerifier { _, _ -> }) =
        UpdateRepository(context, storage, transport, verifier) { space }

    @Test fun verifiedDownloadSurvivesNewRepositoryAndIsNotRepeated() {
        val repo = repository()
        assertTrue(repo.check(force = true) is UpdateResult.Ready)
        assertEquals(release, UpdateStorage(context).pending())
        assertArrayEquals(bytes, storage.apk(release).readBytes())
        assertTrue(repository().check(force = true) is UpdateResult.Ready)
        assertEquals(1, downloads)
        assertEquals(2, checks)
        assertEquals(UpdatePhase.READY, storage.status().phase)
    }

    @Test fun automaticCheckRespectsSixHoursAndManualCheckBypassesInterval() {
        assertTrue(repository().check() is UpdateResult.Ready)
        assertTrue(repository().check() is UpdateResult.Ready)
        assertEquals(1, checks)
        repository().check(force = true)
        assertEquals(2, checks)
        storage.checked(1000)
        assertFalse(storage.isDue(1000 + UpdateScheduler.INTERVAL_MS - 1))
        assertTrue(storage.isDue(1000 + UpdateScheduler.INTERVAL_MS))
        assertTrue(storage.isDue(999))
    }

    @Test fun insufficientSpaceDoesNotDownloadOrLoseAnExistingPendingApk() {
        repository().check(force = true)
        val previous = release
        release = release.copy(versionName = "2.6", versionCode = release.versionCode + 1,
            apkUrl = "${UpdateManifest.REPOSITORY}/releases/download/v2.6/BOBTV-2.6-Android10.apk")
        assertTrue(repository(space = 1).check(force = true) is UpdateResult.Failed)
        assertEquals(1, downloads)
        assertEquals(previous, storage.pending())
        assertEquals(UpdatePhase.ERROR, storage.status().phase)
    }

    @Test fun partialDownloadIsDiscardedAndTransientFailureIsRetryable() {
        val interrupted = object : UpdateTransport {
            override fun manifest(cancelled: () -> Boolean) = release.toJson()
            override fun download(manifest: UpdateManifest, destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
                destination.writeBytes(bytes.take(2).toByteArray())
                throw IOException("Network disconnected")
            }
        }
        assertEquals(UpdateResult.Failed(true), repository(interrupted).check(force = true))
        assertNull(storage.pending())
        assertFalse(File(storage.directory, "download.part.apk").exists())
        assertTrue(repository().check(force = true) is UpdateResult.Ready)
    }

    @Test fun truncatedOrCorruptBytesNeverBecomePending() {
        for (payload in listOf(bytes.dropLast(1).toByteArray(), ByteArray(bytes.size) { 7 })) {
            val corrupt = object : UpdateTransport {
                override fun manifest(cancelled: () -> Boolean) = release.toJson()
                override fun download(manifest: UpdateManifest, destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
                    destination.writeBytes(payload)
                }
            }
            assertEquals(UpdateResult.Failed(false), repository(corrupt).check(force = true))
            assertNull(storage.pending())
            assertFalse(File(storage.directory, "download.part.apk").exists())
        }
    }

    @Test fun signatureInspectionFailureDiscardsDownload() {
        assertEquals(UpdateResult.Failed(false), repository(verifier = ApkVerifier { _, _ ->
            throw IllegalArgumentException("Wrong certificate")
        }).check(force = true))
        assertNull(storage.pending())
    }

    @Test fun installedAndOlderVersionsAreNotDownloaded() {
        release = release.copy(versionCode = release.versionCode - 1)
        assertSame(UpdateResult.Current, repository().check(force = true))
        assertEquals(0, downloads)
        assertEquals(UpdatePhase.CURRENT, storage.status().phase)
    }

    @Test fun unsupportedAndroidDoesNotDownload() {
        release = release.copy(minSdk = 100)
        assertEquals(UpdateResult.Failed(false), repository().check(force = true))
        assertEquals(0, downloads)
        assertEquals(UpdatePhase.INCOMPATIBLE, storage.status().phase)
    }

    @Test fun stalePendingFileCannotBeInstalledAfterTampering() {
        repository().check(force = true)
        storage.apk(release).writeText("changed")
        val verifying = repository(verifier = ApkVerifier { file, manifest ->
            require(file.length() == manifest.sizeBytes && sha256(file) == manifest.sha256)
        })
        var staged = false
        assertThrows(IllegalArgumentException::class.java) { verifying.stagePending { _, _ -> staged = true; 1 } }
        assertFalse(staged)
        assertNull(storage.pending())
    }

    @Test fun overlappingManualAndPeriodicCheckUseOnlyOneDownload() {
        val entered = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val slow = object : UpdateTransport {
            override fun manifest(cancelled: () -> Boolean): String {
                entered.countDown()
                check(releaseLatch.await(5, TimeUnit.SECONDS))
                return transport.manifest(cancelled)
            }
            override fun download(manifest: UpdateManifest, destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit) =
                transport.download(manifest, destination, cancelled, progress)
        }
        val executor = Executors.newSingleThreadExecutor()
        val repo = repository(slow)
        try {
            val job = executor.submit<UpdateResult> { repo.check(force = true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertSame(UpdateResult.Busy, repo.check(force = true))
            releaseLatch.countDown()
            assertTrue(job.get(5, TimeUnit.SECONDS) is UpdateResult.Ready)
            assertEquals(1, downloads)
        } finally { releaseLatch.countDown(); executor.shutdownNow() }
    }

    @Test fun invalidMetadataAndMissingReleaseDoNotClaimCurrentVersion() {
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(release.copy(applicationId = "other.app").toJson()) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(release.copy(apkUrl = "http://example.com/app.apk").toJson()) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(release.copy(sizeBytes = UpdateManifest.MAX_APK_BYTES + 1).toJson()) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(release.copy(notes = "").toJson()) }
        val missing = object : UpdateTransport {
            override fun manifest(cancelled: () -> Boolean): String = throw UpdateHttpException(404)
            override fun download(manifest: UpdateManifest, destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit) = Unit
        }
        assertEquals(UpdateResult.Failed(false), repository(missing).check(force = true))
        assertEquals(UpdatePhase.ERROR, storage.status().phase)
    }
}

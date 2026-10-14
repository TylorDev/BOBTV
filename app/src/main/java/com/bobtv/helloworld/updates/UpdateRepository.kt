package com.bobtv.helloworld.updates

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import com.bobtv.helloworld.R
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class UpdateResult {
    data class Ready(val manifest: UpdateManifest) : UpdateResult()
    data object Current : UpdateResult()
    data object Busy : UpdateResult()
    data class Failed(val retryable: Boolean) : UpdateResult()
}

class UpdateRepository(
    private val context: Context,
    val storage: UpdateStorage = UpdateStorage(context),
    private val transport: UpdateTransport = HttpsUpdateTransport(),
    private val verifier: ApkVerifier = AndroidApkVerifier(context),
    private val freeSpace: () -> Long = { storage.directory.usableSpace },
) {
    private val lock = ReentrantLock()
    @Suppress("DEPRECATION")
    fun installedCode(): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    fun pending(): UpdateManifest? = storage.pending()?.takeIf { it.versionCode > installedCode() }

    fun check(force: Boolean = false, cancelled: () -> Boolean = { false }): UpdateResult {
        if (!lock.tryLock()) return UpdateResult.Busy
        try {
            if (storage.sessionId >= 0) return UpdateResult.Busy
            if (!force && !storage.isDue()) return pending()?.let(UpdateResult::Ready) ?: UpdateResult.Current
            storage.status(UpdateStatus(UpdatePhase.CHECKING, context.getString(R.string.update_checking)))
            Log.i("BOBTV.Update", "Checking public release")
            val manifest = UpdateManifest.parse(transport.manifest(cancelled))
            if (manifest.versionCode <= installedCode()) {
                storage.clearPending()
                storage.checked()
                storage.status(UpdateStatus(UpdatePhase.CURRENT, context.getString(R.string.update_current)))
                return UpdateResult.Current
            }
            if (manifest.minSdk > Build.VERSION.SDK_INT) {
                storage.checked()
                storage.status(UpdateStatus(UpdatePhase.INCOMPATIBLE, context.getString(R.string.update_incompatible)))
                return UpdateResult.Failed(false)
            }
            val existing = pending()
            if (existing == manifest && runCatching { verifier.verify(storage.apk(manifest), manifest) }.isSuccess) {
                ready(manifest)
                return UpdateResult.Ready(manifest)
            }
            if (freeSpace() < manifest.sizeBytes * 3 + 16L * 1024 * 1024) {
                storage.status(UpdateStatus(UpdatePhase.ERROR, context.getString(R.string.update_no_space)))
                return UpdateResult.Failed(false)
            }
            val temp = File(storage.directory, "download.part.apk")
            try {
                transport.download(manifest, temp, cancelled) { percent ->
                    storage.status(UpdateStatus(UpdatePhase.DOWNLOADING,
                        context.getString(R.string.update_downloading, manifest.versionName, percent), percent))
                }
                if (cancelled()) throw CancellationException("Update cancelled")
                // Independent of the transport: test/future transports cannot bypass the byte/hash limits.
                require(temp.length() == manifest.sizeBytes && sha256(temp) == manifest.sha256) { "APK integrity mismatch" }
                verifier.verify(temp, manifest)
                val destination = storage.apk(manifest)
                if (destination.exists()) kotlin.check(destination.delete()) { "Cannot replace invalid APK" }
                kotlin.check(temp.renameTo(destination)) { "Cannot persist APK" }
                storage.savePending(manifest)
                ready(manifest)
                return UpdateResult.Ready(manifest)
            } finally { temp.delete() }
        } catch (error: CancellationException) {
            storage.status(UpdateStatus(UpdatePhase.ERROR, context.getString(R.string.update_interrupted)))
            throw error
        } catch (error: Exception) {
            Log.w("BOBTV.Update", "Update check/download rejected", error)
            val noRelease = error is UpdateHttpException && error.status == 404
            storage.status(UpdateStatus(UpdatePhase.ERROR, context.getString(
                if (noRelease) R.string.update_no_release else R.string.update_failed)))
            return UpdateResult.Failed(error is IOException && !noRelease &&
                (error !is UpdateHttpException || error.status == 429 || error.status >= 500))
        } finally { lock.unlock() }
    }

    private fun ready(manifest: UpdateManifest) {
        storage.checked()
        storage.status(UpdateStatus(UpdatePhase.READY, context.getString(R.string.update_ready, manifest.versionName)))
        Log.i("BOBTV.Update", "Verified pending version=${manifest.versionName} code=${manifest.versionCode}")
    }

    fun stagePending(stage: (File, UpdateManifest) -> Int): Int = lock.withLock {
        val manifest = requireNotNull(pending()) { "No newer pending APK" }
        val apk = storage.apk(manifest)
        try { verifier.verify(apk, manifest) } catch (error: Exception) { storage.clearPending(); throw error }
        stage(apk, manifest)
    }

    fun reconcileInstalled() {
        // Called at Activity startup: never wait on a network download from the UI thread.
        if (!lock.tryLock()) return
        try {
            val pending = storage.pending()
            if (pending != null && pending.versionCode <= installedCode()) {
                storage.clearPending()
                storage.clearSession()
                storage.status(UpdateStatus(UpdatePhase.INSTALLED, context.getString(R.string.update_installed)))
            }
        } finally { lock.unlock() }
    }
}

// The singleton only receives applicationContext and never retains an Activity.
@SuppressLint("StaticFieldLeak")
object UpdateServices {
    @Volatile private var instance: UpdateRepository? = null
    fun get(context: Context): UpdateRepository = instance ?: synchronized(this) {
        instance ?: UpdateRepository(context.applicationContext).also { instance = it }
    }
    internal fun setForTests(repository: UpdateRepository?) { instance = repository }
}

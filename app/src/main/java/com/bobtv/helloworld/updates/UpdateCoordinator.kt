package com.bobtv.helloworld.updates

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.provider.Settings
import com.bobtv.helloworld.R
import java.util.concurrent.Executors

/** Activity-owned UI/install flow; the periodic worker only downloads and never interrupts TV. */
class UpdateCoordinator(
    private val activity: Activity,
    private val repository: UpdateRepository,
    private val onStatus: (UpdateStatus) -> Unit,
    private val pausePlayback: () -> Unit,
    private val resumePlayback: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val storage = repository.storage
    private val installer = UpdateInstaller(activity.applicationContext, storage)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val attempted = mutableSetOf<Long>()
    private var foreground = false
    private var resumed = false
    private var destroyed = false
    private var checking = false
    private var staging = false
    private var waitingPermission = false
    private var manualPending = false
    private var blocked = false
    private var confirmationLaunched = -1
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "phase" || key == "message" || key == "percent" || key == "confirmation")
            handler.post { if (!destroyed && foreground) deliver() }
    }

    fun start(): Boolean {
        foreground = true
        storage.preferences.registerOnSharedPreferenceChangeListener(listener)
        repository.reconcileInstalled()
        if (!staging) installer.reconcile()
        UpdateScheduler.schedule(activity.applicationContext, storage.isDue())
        onStatus(storage.status())
        if (waitingPermission || staging || (blocked && storage.sessionId >= 0)) return true
        if (storage.sessionId >= 0) {
            blocked = true
            return true
        }
        val pending = repository.pending()
        if (pending != null && pending.versionCode !in attempted) {
            requestInstall(pending, manual = false)
            return blocked
        }
        return false
    }

    fun resume() { resumed = true; if (foreground) deliver() }
    fun pause() { resumed = false }
    fun stop() {
        if (!blocked && !waitingPermission && !staging && storage.sessionId < 0) attempted.clear()
        foreground = false
        resumed = false
        manualPending = false
        storage.preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun checkNow() {
        if (checking || staging || storage.sessionId >= 0) return
        checking = true
        manualPending = true
        onStatus(UpdateStatus(UpdatePhase.CHECKING, activity.getString(R.string.update_checking)))
        executor.execute {
            val result = runCatching { repository.check(force = true, cancelled = { destroyed || Thread.currentThread().isInterrupted }) }
            handler.post {
                if (destroyed) return@post
                checking = false
                if (foreground) {
                    onStatus(storage.status())
                    if (result.getOrNull() is UpdateResult.Ready && manualPending)
                        repository.pending()?.let { requestInstall(it, manual = true) }
                    else if (result.getOrNull() is UpdateResult.Busy) deliver()
                    else manualPending = false
                }
            }
        }
    }

    private fun requestInstall(manifest: UpdateManifest, manual: Boolean) {
        if (!foreground || staging || storage.sessionId >= 0 || (!manual && manifest.versionCode in attempted)) return
        attempted.add(manifest.versionCode)
        manualPending = false
        blocked = true
        pausePlayback()
        log("Preparing install code=${manifest.versionCode}; playback stopped and device audio restored")
        if (!activity.packageManager.canRequestPackageInstalls()) {
            waitingPermission = true
            storage.status(UpdateStatus(UpdatePhase.CONFIRMATION, activity.getString(R.string.update_allow_sources)))
            runCatching {
                activity.startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")), REQUEST_SOURCES)
            }.onFailure { waitingPermission = false; fail(R.string.update_permission_denied) }
            return
        }
        stage()
    }

    private fun stage() {
        val stagedCode = repository.pending()?.versionCode
        staging = true
        storage.status(UpdateStatus(UpdatePhase.PREPARING, activity.getString(R.string.update_preparing)))
        executor.execute {
            val result = runCatching { repository.stagePending(installer::stage) }
            handler.post {
                staging = false
                val id = result.getOrNull()
                if (destroyed || !foreground) {
                    if (id != null) installer.abandon(id)
                    if (stagedCode != null) attempted.remove(stagedCode)
                    if (!destroyed) storage.status(UpdateStatus(UpdatePhase.READY, activity.getString(R.string.update_deferred)))
                    blocked = false
                    return@post
                }
                if (id == null) { log("APK staging failed: ${result.exceptionOrNull()?.javaClass?.simpleName}"); fail(R.string.update_install_failed) }
                else runCatching { installer.commit(id) }.onFailure {
                    installer.abandon(id)
                    fail(R.string.update_install_failed)
                }
            }
        }
    }

    private fun deliver() {
        val status = storage.status()
        onStatus(status)
        if (!checking && status.phase in listOf(UpdatePhase.ERROR, UpdatePhase.CURRENT, UpdatePhase.INCOMPATIBLE)) manualPending = false
        if (status.phase == UpdatePhase.READY && manualPending && !checking && !staging)
            repository.pending()?.let { requestInstall(it, manual = true) }
        if (status.phase == UpdatePhase.CONFIRMATION && !waitingPermission && resumed) {
            val id = storage.sessionId
            val confirmation = storage.confirmation
            if (id >= 0 && id != confirmationLaunched && confirmation != null) {
                confirmationLaunched = id
                runCatching {
                    activity.startActivityForResult(Intent.parseUri(confirmation, Intent.URI_INTENT_SCHEME), REQUEST_INSTALL)
                }.onFailure { installer.abandon(id); fail(R.string.update_install_failed) }
            }
        }
        if (blocked && !staging && !waitingPermission && storage.sessionId < 0 &&
            status.phase in listOf(UpdatePhase.ERROR, UpdatePhase.INSTALLED)) unblock()
    }

    fun activityResult(requestCode: Int, resultCode: Int): Boolean = when (requestCode) {
        REQUEST_SOURCES -> {
            waitingPermission = false
            if (activity.packageManager.canRequestPackageInstalls()) stage() else fail(R.string.update_permission_denied)
            true
        }
        REQUEST_INSTALL -> {
            if (resultCode == Activity.RESULT_CANCELED && storage.sessionId >= 0) {
                installer.abandon()
                fail(R.string.update_cancelled)
            }
            true
        }
        else -> false
    }

    private fun fail(message: Int) {
        storage.status(UpdateStatus(UpdatePhase.ERROR, activity.getString(message)))
        onStatus(storage.status())
        unblock()
    }
    private fun unblock() {
        if (!blocked) return
        blocked = false
        if (foreground && !destroyed) resumePlayback()
    }

    fun close() {
        destroyed = true
        stop()
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    fun saveState(bundle: Bundle) {
        bundle.putBoolean("update_permission", waitingPermission)
        bundle.putBoolean("update_blocked", blocked)
        bundle.putInt("update_confirmation_launched", confirmationLaunched)
        bundle.putLongArray("update_attempted", attempted.toLongArray())
    }
    fun restoreState(bundle: Bundle?) {
        if (bundle == null) return
        waitingPermission = bundle.getBoolean("update_permission")
        blocked = bundle.getBoolean("update_blocked")
        confirmationLaunched = bundle.getInt("update_confirmation_launched", -1)
        attempted.addAll(bundle.getLongArray("update_attempted")?.toList().orEmpty())
    }

    companion object {
        const val REQUEST_SOURCES = 2401
        const val REQUEST_INSTALL = 2402
    }
}

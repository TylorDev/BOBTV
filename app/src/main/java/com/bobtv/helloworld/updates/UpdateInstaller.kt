package com.bobtv.helloworld.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.bobtv.helloworld.R
import java.io.File
import java.util.concurrent.CancellationException

class UpdateInstaller(private val context: Context, private val storage: UpdateStorage) {
    private val installer get() = context.packageManager.packageInstaller

    fun stage(apk: File, manifest: UpdateManifest): Int {
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setAppLabel("BOBTV ${manifest.versionName}")
            setSize(manifest.sizeBytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(parameters)
        storage.session(id)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw CancellationException("Staging cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    session.fsync(output)
                }
            }
            return id
        } catch (error: Exception) { abandon(id); throw error }
    }

    fun commit(id: Int) {
        check(storage.sessionId == id)
        val intent = Intent(context, UpdateInstallReceiver::class.java).putExtra("expected_session", id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(context, id, intent, flags)
        storage.status(UpdateStatus(UpdatePhase.INSTALLING, context.getString(R.string.update_installing)))
        installer.openSession(id).use { it.commit(callback.intentSender) }
    }

    fun abandon(id: Int = storage.sessionId) {
        if (id >= 0) runCatching { installer.abandonSession(id) }
        if (storage.sessionId == id) storage.clearSession()
    }

    fun reconcile() {
        val id = storage.sessionId
        if (id < 0) return
        val session = installer.getSessionInfo(id)
        // Uncommitted sessions are leftovers from process death during staging, safe to rebuild.
        if (session == null || !session.isSealed) abandon(id)
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val storage = UpdateStorage(context)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || id != storage.sessionId || id != intent.getIntExtra("expected_session", -2)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i("BOBTV.Update", "Installer session=$id status=$status")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation != null) {
                    storage.confirmation(confirmation.toUri(Intent.URI_INTENT_SCHEME))
                    storage.status(UpdateStatus(UpdatePhase.CONFIRMATION, context.getString(R.string.update_confirmation)))
                } else fail(context, storage)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                storage.clearPending()
                storage.clearSession()
                storage.status(UpdateStatus(UpdatePhase.INSTALLED, context.getString(R.string.update_installed)))
            }
            else -> fail(context, storage)
        }
    }

    private fun fail(context: Context, storage: UpdateStorage) {
        storage.clearSession()
        storage.status(UpdateStatus(UpdatePhase.ERROR, context.getString(R.string.update_install_failed)))
    }
}

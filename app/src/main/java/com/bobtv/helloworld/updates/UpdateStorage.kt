package com.bobtv.helloworld.updates

import android.content.Context
import android.content.SharedPreferences
import android.annotation.SuppressLint
import android.util.AtomicFile
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, READY, CURRENT, INCOMPATIBLE, PREPARING, INSTALLING, CONFIRMATION, INSTALLED, ERROR }
data class UpdateStatus(val phase: UpdatePhase, val message: String, val percent: Int = -1)

// Session and confirmation writes must reach disk before Android can kill the self-updating process.
@SuppressLint("ApplySharedPref")
class UpdateStorage(context: Context) {
    val directory = File(context.filesDir, "updates").apply { mkdirs() }
    val preferences: SharedPreferences = context.getSharedPreferences("bobtv-updates", Context.MODE_PRIVATE)
    private val metadata = AtomicFile(File(directory, "pending.json"))

    fun apk(manifest: UpdateManifest) = File(directory, "${manifest.versionCode}.apk")
    fun pending(): UpdateManifest? = runCatching {
        val text = metadata.openRead().use { input ->
            val bytes = readBounded(input, UpdateManifest.MAX_METADATA_BYTES)
            require(bytes.size <= UpdateManifest.MAX_METADATA_BYTES)
            bytes.toString(Charsets.UTF_8)
        }
        UpdateManifest.parse(text).takeIf { apk(it).isFile }
    }.getOrNull()

    fun savePending(manifest: UpdateManifest) {
        val output = metadata.startWrite()
        try {
            output.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            metadata.finishWrite(output)
        } catch (error: Exception) {
            metadata.failWrite(output)
            throw error
        }
        directory.listFiles()?.filter { it.extension == "apk" && it != apk(manifest) }?.forEach { it.delete() }
    }

    fun clearPending() {
        metadata.delete()
        directory.listFiles()?.filter { it.extension in listOf("apk", "part") }?.forEach { it.delete() }
    }

    fun isDue(now: Long = System.currentTimeMillis()): Boolean {
        val last = preferences.getLong("last_check", 0)
        return last == 0L || now < last || now - last >= UpdateScheduler.INTERVAL_MS
    }
    fun checked(now: Long = System.currentTimeMillis()) { preferences.edit().putLong("last_check", now).commit() }
    fun status() = UpdateStatus(runCatching { UpdatePhase.valueOf(preferences.getString("phase", "IDLE")!!) }
        .getOrDefault(UpdatePhase.IDLE), preferences.getString("message", "").orEmpty(), preferences.getInt("percent", -1))
    fun status(value: UpdateStatus) {
        preferences.edit().putString("phase", value.phase.name).putString("message", value.message)
            .putInt("percent", value.percent).apply()
    }

    val sessionId: Int get() = preferences.getInt("session_id", -1)
    val confirmation: String? get() = preferences.getString("confirmation", null)
    fun session(id: Int) { preferences.edit().putInt("session_id", id).remove("confirmation").commit() }
    fun confirmation(value: String) { preferences.edit().putString("confirmation", value).commit() }
    fun clearSession() { preferences.edit().remove("session_id").remove("confirmation").commit() }
}

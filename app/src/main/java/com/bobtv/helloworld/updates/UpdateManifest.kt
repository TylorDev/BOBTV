package com.bobtv.helloworld.updates

import org.json.JSONObject

data class UpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val applicationId: String,
    val minSdk: Int,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val notes: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("versionName", versionName)
        put("versionCode", versionCode)
        put("applicationId", applicationId)
        put("minSdk", minSdk)
        put("apkUrl", apkUrl)
        put("sizeBytes", sizeBytes)
        put("sha256", sha256)
        put("notes", notes)
    }.toString()

    companion object {
        const val PACKAGE = "com.bobtv.helloworld"
        const val REPOSITORY = "https://github.com/TylorDev/BOBTV"
        const val MANIFEST_URL = "$REPOSITORY/releases/latest/download/update.json"
        const val MAX_METADATA_BYTES = 128 * 1024
        const val MAX_APK_BYTES = 64L * 1024 * 1024

        fun parse(text: String): UpdateManifest {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) { "Metadata too large" }
            val json = JSONObject(text)
            val version = json.getString("versionName")
            require(version.matches(Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?"))) { "Invalid stable version" }
            val result = UpdateManifest(version, json.getLong("versionCode"), json.getString("applicationId"),
                json.getInt("minSdk"), json.getString("apkUrl"), json.getLong("sizeBytes"),
                json.getString("sha256").lowercase(), json.getString("notes"))
            require(result.versionCode in 1..Int.MAX_VALUE.toLong()) { "Invalid version code" }
            require(result.applicationId == PACKAGE) { "Unexpected package" }
            require(result.minSdk in 29..100) { "Invalid minimum SDK" }
            require(result.apkUrl == "$REPOSITORY/releases/download/v$version/BOBTV-$version-Android10.apk") {
                "APK must be pinned to this repository and version"
            }
            require(result.sizeBytes in 1..MAX_APK_BYTES) { "Invalid APK size" }
            require(result.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid SHA-256" }
            require(result.notes.isNotBlank() && result.notes.toByteArray(Charsets.UTF_8).size <= 32 * 1024) {
                "Invalid patch notes"
            }
            return result
        }
    }
}

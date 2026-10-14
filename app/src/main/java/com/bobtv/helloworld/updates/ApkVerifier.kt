package com.bobtv.helloworld.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

fun interface ApkVerifier { fun verify(file: File, manifest: UpdateManifest) }

class AndroidApkVerifier(private val context: Context) : ApkVerifier {
    @Suppress("DEPRECATION")
    override fun verify(file: File, manifest: UpdateManifest) {
        require(file.length() == manifest.sizeBytes && sha256(file) == manifest.sha256) { "APK checksum mismatch" }
        val manager = context.packageManager
        val candidate = requireNotNull(manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)) {
            "Cannot inspect APK"
        }
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(candidate.packageName == context.packageName && candidate.packageName == manifest.applicationId) { "Wrong package" }
        require(candidate.longVersionCode == manifest.versionCode && candidate.versionName == manifest.versionName) { "Wrong version" }
        require(candidate.longVersionCode > installed.longVersionCode) { "Downgrades are forbidden" }
        require(candidate.applicationInfo?.minSdkVersion == manifest.minSdk && manifest.minSdk <= Build.VERSION.SDK_INT) {
            "Incompatible Android version"
        }
        val currentSigners = installed.signingInfo?.apkContentsSigners
        val candidateSigners = candidate.signingInfo?.apkContentsSigners
        require(!currentSigners.isNullOrEmpty() && !candidateSigners.isNullOrEmpty()) { "Missing signing certificates" }
        require(currentSigners.toSet() == candidateSigners.toSet()) { "APK certificate differs from installed app" }
    }
}

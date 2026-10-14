package com.bobtv.helloworld.updates

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33])
class AndroidApkVerifierTest {
    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var manifest: UpdateManifest
    private lateinit var candidate: PackageInfo
    private fun signing(value: String) = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(value))) }

    @Suppress("DEPRECATION")
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        file = File(context.cacheDir, "candidate.apk").apply { writeText("signed archive fixture") }
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        installed.signingInfo = signing("1234")
        shadowOf(context.packageManager).installPackage(installed)
        manifest = UpdateManifest("2.5", installed.longVersionCode + 1, context.packageName, 29,
            "${UpdateManifest.REPOSITORY}/releases/download/v2.5/BOBTV-2.5-Android10.apk", file.length(), sha256(file), "Parche")
        candidate = PackageInfo().apply {
            packageName = context.packageName
            versionName = manifest.versionName
            setLongVersionCode(manifest.versionCode)
            signingInfo = signing("1234")
            applicationInfo = ApplicationInfo().apply { minSdkVersion = 29 }
        }
        shadowOf(context.packageManager).setPackageArchiveInfo(file.path, candidate)
    }

    @Test fun matchingCertificatePackageVersionAndHashAreAccepted() { AndroidApkVerifier(context).verify(file, manifest) }

    @Test fun otherCertificateCannotReplaceInstalledApp() {
        candidate.signingInfo = signing("5678")
        assertThrows(IllegalArgumentException::class.java) { AndroidApkVerifier(context).verify(file, manifest) }
    }

    @Test fun wrongPackageOrVersionOrAndroidRequirementCannotPass() {
        candidate.packageName = "other.app"
        assertThrows(IllegalArgumentException::class.java) { AndroidApkVerifier(context).verify(file, manifest) }
        candidate.packageName = context.packageName
        candidate.setLongVersionCode(manifest.versionCode - 1)
        assertThrows(IllegalArgumentException::class.java) { AndroidApkVerifier(context).verify(file, manifest) }
        candidate.setLongVersionCode(manifest.versionCode)
        candidate.applicationInfo!!.minSdkVersion = 100
        assertThrows(IllegalArgumentException::class.java) { AndroidApkVerifier(context).verify(file, manifest) }
    }

    @Test fun changedBytesOrMissingSignatureCannotPass() {
        candidate.signingInfo = null
        assertThrows(IllegalArgumentException::class.java) { AndroidApkVerifier(context).verify(file, manifest) }
        candidate.signingInfo = signing("1234")
        file.writeText("tampered APK")
        assertThrows(IllegalArgumentException::class.java) { AndroidApkVerifier(context).verify(file, manifest) }
    }
}

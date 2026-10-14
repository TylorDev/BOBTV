plugins {
    id("com.android.application")
}

android {
    namespace = "com.bobtv.helloworld"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bobtv.helloworld"
        minSdk = 29
        targetSdk = 33
        versionCode = 8
        versionName = "2.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("distribution") {
            val signingPath = providers.environmentVariable("BOBTV_KEYSTORE_PATH").orNull
            if (signingPath != null) {
                storeFile = file(signingPath)
                storePassword = providers.environmentVariable("BOBTV_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("BOBTV_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("BOBTV_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes.getByName("release") {
        isMinifyEnabled = false
        signingConfig = signingConfigs.getByName("distribution")
    }

    sourceSets.getByName("test").resources.directories.add("src/main/assets")
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        // Keep Robolectric SDK downloads and its lock inside the workspace on restricted runners.
        val testHome = rootProject.file(".gradle-user-home/robolectric")
        it.systemProperty("user.home", testHome.absolutePath)
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        it.doFirst { testHome.mkdirs() }
    }
}

val patchAssets = layout.buildDirectory.dir("generated/updateAssets")
val preparePatchNotes by tasks.registering(Copy::class) {
    from(rootProject.file("releases/${android.defaultConfig.versionName}.md"))
    into(patchAssets)
    rename { "patch-notes.md" }
    doFirst { check(source.files.isNotEmpty()) { "Missing patch notes for this version" } }
}
android.sourceSets.getByName("main").assets.directories.add(patchAssets.get().asFile.absolutePath)
tasks.named("preBuild") { dependsOn(preparePatchNotes) }

dependencies {
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.work:work-testing:2.12.0")
}

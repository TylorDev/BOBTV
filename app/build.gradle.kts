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
        versionCode = 7
        versionName = "2.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}

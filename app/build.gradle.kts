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
        versionCode = 5
        versionName = "2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("test").resources.directories.add("src/main/assets")
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    testImplementation("junit:junit:4.13.2")
}

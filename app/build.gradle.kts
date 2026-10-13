plugins {
    id("com.android.application")
}

android {
    namespace = "com.bobtv.helloworld"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.bobtv.helloworld"
        minSdk = 29
        targetSdk = 33
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

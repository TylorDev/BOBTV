plugins {
    id("com.android.application")
}

android {
    namespace = "com.bobtv.helloworld"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.bobtv.helloworld"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

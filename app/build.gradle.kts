plugins {
    id("com.android.application")
}

android {
    namespace = "com.itachi.airmouse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itachi.airmouse"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

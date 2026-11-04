plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mocharealm.foundation.fabric.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    // The AIDL is packaged here so consumers no longer copy it by hand.
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // A single publishable release variant (AAR + sources). Wire a
    // `maven-publish` block to your own repository to publish it.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Runs as an independent client UID against the installed production Hub APK.
dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

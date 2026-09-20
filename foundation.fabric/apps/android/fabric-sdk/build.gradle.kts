plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mocharealm.foundation.fabric.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
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

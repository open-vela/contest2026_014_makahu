plugins { id("com.android.library") version "9.2.1" }
android {
    namespace = "com.mocharealm.foundation.fabric.ble"
    compileSdk = 37
    defaultConfig { minSdk = 28; consumerProguardFiles("consumer-rules.pro") }
}
android.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
val stageFabricNative by tasks.registering(Sync::class) {
    from(providers.gradleProperty("fabricJniLibs").orElse("build/native/jniLibs"))
    into(layout.projectDirectory.dir("src/main/jniLibs"))
}
tasks.named("preBuild") { dependsOn(stageFabricNative) }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val rustDir = rootProject.layout.projectDirectory.dir("../..")

android {
    namespace = "com.mocharealm.foundation.fabric"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.mocharealm.foundation.fabric"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

dependencies {
    implementation(project(":fabric-sdk"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
}

abstract class CopyJniLibsTask @Inject constructor(
    private val fs: FileSystemOperations,
) : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val targetDir: DirectoryProperty

    @TaskAction
    fun copyLibraries() {
        fs.delete { delete(targetDir) }
        fs.copy {
            from(sourceDir)
            into(targetDir)
            include("**/*.so")
        }
    }
}

val rustOutput = layout.buildDirectory.dir("rustJniLibs")
val androidSdkDirectory = androidComponents.sdkComponents.sdkDirectory
val compileRustAndroid = tasks.register<Exec>("compileRustAndroid") {
    workingDir(rustDir)
    commandLine(
        "cargo",
        "ndk",
        "--target",
        "arm64-v8a",
        "--target",
        "armeabi-v7a",
        "--target",
        "x86_64",
        "--output-dir",
        rustOutput.get().asFile.absolutePath,
        "build",
        "--package",
        "fabric-android-jni",
        "--release",
    )
    doFirst {
        val sdk = androidSdkDirectory.get().asFile.absolutePath
        environment("ANDROID_HOME", sdk)
        environment("ANDROID_SDK_ROOT", sdk)
        environment("CARGO_NDK_PLATFORM", "28")
    }
    inputs.files(fileTree(rustDir) {
        include("Cargo.toml", "Cargo.lock", "crates/**/*.toml", "crates/**/*.rs")
    })
    outputs.dir(rustOutput)
}

val buildRustAndroid = tasks.register<CopyJniLibsTask>("buildRustAndroid") {
    dependsOn(compileRustAndroid)
    sourceDir.set(rustOutput)
    targetDir.set(layout.buildDirectory.dir("generated/jniLibs"))
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRustAndroid) {
            it.targetDir
        }
    }
}

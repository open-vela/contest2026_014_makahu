#!/usr/bin/env bash
# Build framework-owned native libraries, portable AAR, SDK AAR and Hub APK.
set -euo pipefail
fabric_android=$(cd "$(dirname "$0")" && pwd)
fabric_repo=$(cd "$fabric_android/../.." && pwd)
fabric_native="${FABRIC_JNI_OUTPUT:-$fabric_android/build/native/jniLibs}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/29.0.14206865}"
export ANDROID_NDK_HOME
bash "$fabric_android/build-native.sh" "$fabric_native"
(
  cd "$fabric_android"
  bash "$fabric_repo/apps/android/gradlew" "-PfabricJniLibs=$fabric_native" assembleDebug assembleDebugAndroidTest --console=plain
)
(
  cd "$fabric_repo/apps/android"
  bash gradlew :fabric-sdk:assembleDebug :fabric-sdk:assembleDebugAndroidTest :hub-app:assembleDebug --console=plain
)

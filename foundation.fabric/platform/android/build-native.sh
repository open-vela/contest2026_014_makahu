#!/usr/bin/env bash
set -euo pipefail
fabric_root=$(cd "$(dirname "$0")/../.." && pwd)
fabric_ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed NDK}
fabric_out=${1:?Usage: build-native.sh /absolute/output/jniLibs}
case "$fabric_out" in /*) ;; *) echo 'Output must be absolute' >&2; exit 1;; esac
for fabric_spec in 'aarch64-linux-android aarch64-linux-android26 arm64-v8a' 'x86_64-linux-android x86_64-linux-android26 x86_64' 'armv7-linux-androideabi armv7a-linux-androideabi26 armeabi-v7a'; do
  read -r fabric_target fabric_cc fabric_abi <<< "$fabric_spec"
  cargo build --locked --manifest-path "$fabric_root/platform/portable/Cargo.toml" --release --no-default-features --target "$fabric_target"
  mkdir -p "$fabric_out/$fabric_abi"
  "$fabric_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/$fabric_cc-clang" \
    -std=c11 -Wall -Wextra -Werror -fPIC -shared -Wl,--no-undefined \
    -I"$fabric_root/platform/portable/include" \
    "$fabric_root/platform/android/jni/fabric_ble_jni.c" \
    "$fabric_root/platform/portable/c/fabric_ble_host.c" \
    "$fabric_root/platform/portable/c/fabric_pairing_host.c" \
    "$fabric_root/platform/portable/c/fabric_identity_posix.c" \
    "$fabric_root/platform/portable/target/$fabric_target/release/libfabric_portable.a" \
    -o "$fabric_out/$fabric_abi/libfabric_ble_android.so"
done

#!/usr/bin/env bash
set -euo pipefail
fabric_portable=$(cd "$(dirname "$0")" && pwd)
fabric_test_build=$(mktemp -d /tmp/fabric-portable-tests.XXXXXX)
trap 'rm -rf "$fabric_test_build"' EXIT
cargo test --locked --manifest-path "$fabric_portable/Cargo.toml"
cargo build --locked --release --manifest-path "$fabric_portable/Cargo.toml"
for fabric_test in host identity first_pair; do
  fabric_sources=()
  case "$fabric_test" in
    host) fabric_sources+=("$fabric_portable/c/fabric_ble_host.c");;
    identity) fabric_sources+=("$fabric_portable/c/fabric_identity_posix.c");;
    first_pair) fabric_sources+=("$fabric_portable/c/fabric_pairing_host.c" "$fabric_portable/c/fabric_identity_posix.c");;
  esac
  "${CC:-cc}" -std=c11 -Wall -Wextra -Werror \
    -I"$fabric_portable/include" "${fabric_sources[@]}" "$fabric_portable/tests/$fabric_test.c" \
    "$fabric_portable/target/release/libfabric_portable.a" -ldl -lpthread -lm -o "$fabric_test_build/$fabric_test"
  "$fabric_test_build/$fabric_test"
done

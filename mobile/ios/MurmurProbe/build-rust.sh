#!/usr/bin/env bash
# Builds murmur-probe (ffi/) for the iPhone or the simulator, as Xcode asks. Xcode runs it before
# compiling the Swift code; by hand: ./build-rust.sh (both).
set -euo pipefail
cd "$(dirname "$0")/ffi"
case "${PLATFORM_NAME:-}" in
  iphoneos) targets=(aarch64-apple-ios) ;;
  iphonesimulator) targets=(aarch64-apple-ios-sim) ;;
  *) targets=(aarch64-apple-ios aarch64-apple-ios-sim) ;;
esac
# Xcode's environment (SDKROOT, deployment targets, its own cc) confuses the C code cargo builds,
# both for the phone (opus, sqlite) and for the Mac (build scripts): cargo runs as in a terminal.
for target in "${targets[@]}"; do
  env -i HOME="$HOME" USER="${USER:-}" IPHONEOS_DEPLOYMENT_TARGET=18.0 PATH="$HOME/.cargo/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    nice -n 15 cargo build --release --target "$target"
done

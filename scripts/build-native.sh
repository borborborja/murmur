#!/usr/bin/env bash
# Build the actual shared speech engine and generate Kotlin's UniFFI bindings.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="${HOME}/.cargo/bin:/opt/homebrew/bin:${PATH}"
export ANDROID_HOME="${ANDROID_HOME:-${HOME}/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/29.0.14206865}"
case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *) echo "Build with Linux or macOS (or WSL)" >&2; exit 1 ;;
esac
toolchain="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host_tag}/bin"
test -x "${toolchain}/aarch64-linux-android29-clang" || { echo "Android NDK r29 is required" >&2; exit 1; }

cache="${repo}/mobile/android/.native-cache"
archive="${cache}/sherpa-onnx-v1.13.8-android.tar.bz2"
mkdir -p "${cache}"
if ! test -f "${archive}"; then
  curl --fail --location --retry 3 --output "${archive}.part" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
  mv "${archive}.part" "${archive}"
fi
python3 - "${archive}" <<'PY'
import hashlib, pathlib, sys
p = pathlib.Path(sys.argv[1])
h = hashlib.sha256()
with p.open("rb") as f:
    for chunk in iter(lambda: f.read(1024 * 1024), b""):
        h.update(chunk)
actual = h.hexdigest()
expected = "2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44"
if actual != expected:
    raise SystemExit("sherpa-onnx archive checksum mismatch; remove " + str(p))
PY
if ! test -d "${cache}/jniLibs/arm64-v8a"; then tar -xjf "${archive}" -C "${cache}"; fi
# sherpa-onnx-sys 1.13.8 shares an archive extraction cache across host and
# Android targets. Give both phases an explicit library directory: its cache
# lookup otherwise mistakes any cached Android jniLibs directory for host libs.
export SHERPA_ONNX_LIB_DIR="${cache}/jniLibs/arm64-v8a"
unset SHERPA_ONNX_ARCHIVE_DIR
rustup target add aarch64-linux-android

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${toolchain}/aarch64-linux-android29-clang"
export CC_aarch64_linux_android="${toolchain}/aarch64-linux-android29-clang"
export CXX_aarch64_linux_android="${toolchain}/aarch64-linux-android29-clang++"
export AR_aarch64_linux_android="${toolchain}/llvm-ar"
export ANDROID_PLATFORM=android-29
export ANDROID_ABI=arm64-v8a
export CMAKE_POLICY_VERSION_MINIMUM=3.5
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
cargo build --locked --release --lib --features punctuation --manifest-path "${repo}/mobile/ffi/Cargo.toml" --target aarch64-linux-android

jni="${repo}/mobile/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "${jni}"
cp "${repo}/mobile/ffi/target/aarch64-linux-android/release/libmurmur_mobile.so" "${jni}/"
native_dir="$(find "${cache}" -type d -path '*/jniLibs/arm64-v8a' | head -1)"
test -n "${native_dir}"
cp "${native_dir}"/*.so "${jni}/"
# Some sherpa archives rely on the application's NDK C++ runtime.
if ! test -f "${jni}/libc++_shared.so"; then
  cp "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host_tag}/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "${jni}/"
fi

notices="${repo}/mobile/android/app/src/main/assets/licenses/generated"
mkdir -p "${notices}"
cp "${ANDROID_NDK_HOME}/NOTICE" "${notices}/Android-NDK.txt"

bindings="${repo}/mobile/android/app/src/main/java"
# Keep host runtime libraries separate while retaining Cargo's compiled objects;
# a second CARGO_TARGET_DIR would unnecessarily duplicate the full Rust build.
host_lib_dir="$("${repo}/scripts/sherpa-host.sh")"
export LD_LIBRARY_PATH="${host_lib_dir}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export DYLD_LIBRARY_PATH="${host_lib_dir}${DYLD_LIBRARY_PATH:+:${DYLD_LIBRARY_PATH}}"
cd "${repo}/mobile/ffi"
SHERPA_ONNX_LIB_DIR="${host_lib_dir}" cargo run --locked --release --manifest-path "${repo}/mobile/ffi/Cargo.toml" --bin uniffi-bindgen -- \
  generate --library "${jni}/libmurmur_mobile.so" --language kotlin --no-format --out-dir "${bindings}"
echo "Native speech engine and Kotlin bindings ready: ${jni}"

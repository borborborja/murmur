#!/usr/bin/env bash
set -euo pipefail
android_root="$(cd "$(dirname "$0")/.." && pwd)"
android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
android_ndk="${ANDROID_NDK_HOME:-$android_sdk/ndk/29.0.14206865}"
case "$(uname -s)" in
    Darwin) host_tag=darwin-x86_64 ;;
    Linux) host_tag=linux-x86_64 ;;
    *) echo "Build with Linux or macOS (or WSL)" >&2; exit 1 ;;
esac
cmake_bin="${CMAKE:-$android_sdk/cmake/3.22.1/bin/cmake}"
if [[ ! -x "$cmake_bin" ]]; then cmake_bin="$(command -v cmake)"; fi
llama_ref=52975342ff6b85127ff31930d39e36725fd08809
whisper_ref=8302b819fc3096c03cedbc91ef1fe982cc6ebb77
notices="$android_root/app/src/main/assets/licenses/generated"
mkdir -p "$android_root/.inference-cache" "$android_root/app/src/main/jniLibs/arm64-v8a" "$notices"
for engine in llama whisper; do
    source_dir="$android_root/.inference-cache/$engine.cpp"
    if [[ "$engine" == llama ]]; then revision="$llama_ref"; project=llm; else revision="$whisper_ref"; project=whisper; fi
    if [[ ! -d "$source_dir/.git" ]]; then git init "$source_dir"; git -C "$source_dir" remote add origin "https://github.com/ggml-org/$engine.cpp.git"; fi
    if ! git -C "$source_dir" cat-file -e "$revision^{commit}" 2>/dev/null; then git -C "$source_dir" fetch --depth 1 origin "$revision"; fi
    git -C "$source_dir" checkout --detach "$revision"
    test "$(git -C "$source_dir" rev-parse HEAD)" = "$revision"
    cp "$source_dir/LICENSE" "$notices/$engine.cpp-LICENSE.txt"
    if [[ -f "$source_dir/ggml/LICENSE" ]]; then
        cp "$source_dir/ggml/LICENSE" "$notices/$engine.cpp-ggml-LICENSE.txt"
    fi
    "$cmake_bin" -S "$android_root/$project" -B "$android_root/.native-build/$project" \
      -DCMAKE_TOOLCHAIN_FILE="$android_ndk/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
      -DCMAKE_BUILD_TYPE=Release -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_C_FLAGS_RELEASE='-O3 -DNDEBUG' -DCMAKE_CXX_FLAGS_RELEASE='-O3 -DNDEBUG'
    "$cmake_bin" --build "$android_root/.native-build/$project" --target "murmur_$project" --parallel "${BUILD_JOBS:-4}"
    cp "$android_root/.native-build/$project/libmurmur_$project.so" "$android_root/app/src/main/jniLibs/arm64-v8a/"
done
cp "$android_ndk/toolchains/llvm/prebuilt/$host_tag/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$android_root/app/src/main/jniLibs/arm64-v8a/"

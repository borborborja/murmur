#!/usr/bin/env bash
# The Swift side of murmur-mobile (Generated/), made by UniFFI from the library built for this
# Mac, and the texts (Resources/*.xcstrings) from app/src/locales/*/mobile.json. Run it after
# changing mobile/ffi/src/lib.rs or the texts.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here/../../ffi"
nice -n 15 cargo build --release
cargo run -q --release --bin uniffi-bindgen -- generate --library target/release/libmurmur_mobile.dylib --language swift --out-dir "$here/Generated"
node "$here/../../../tools/mobile-strings.mjs"

#!/usr/bin/env bash
# Print the host-only sherpa library directory for bindgen and Rust tests.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="${HOME}/.cargo/bin:/opt/homebrew/bin:${PATH}"
host_triple="$(rustc -vV | awk '/^host: / {print $2}')"
case "${host_triple}" in
  aarch64-apple-darwin)
    host_package=osx-arm64-shared-lib
    digest=ae77050cdae565496059d96f5ab33d77b397a0864282a4e4a26e3b3b3effb948 ;;
  x86_64-apple-darwin)
    host_package=osx-x64-shared-lib
    digest=0f88371565a06372889c76266e253abd1b415fcc6f98232cabd968933efb0711 ;;
  x86_64-*-linux-gnu)
    host_package=linux-x64-shared-lib
    digest=3892d184be41027e18165e67f549cd4e4cdd8dcd73ac5579e97afd55e14e30b6 ;;
  aarch64-*-linux-gnu)
    host_package=linux-aarch64-shared-cpu-lib
    digest=fb98b80628a383909bf83626daca2e0d0ffc93bf1f7d222eaaa4282b388f072f ;;
  *) echo "No supported sherpa host runtime for ${host_triple}" >&2; exit 1 ;;
esac
host_stem="sherpa-onnx-v1.13.8-${host_package}"
host_cache="${repo}/mobile/android/.native-cache/host-${host_triple}"
host_lib_dir="${host_cache}/${host_stem}/lib"
if ! test -d "${host_lib_dir}"; then
  mkdir -p "${host_cache}"
  host_archive="${host_cache}/${host_stem}.tar.bz2"
  if ! test -f "${host_archive}"; then
    curl --fail --location --retry 3 --output "${host_archive}.part" \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/${host_stem}.tar.bz2"
    mv "${host_archive}.part" "${host_archive}"
  fi
  python3 - "${host_archive}" "${digest}" <<'PY'
import hashlib
import sys
from pathlib import Path

archive, expected = Path(sys.argv[1]), sys.argv[2]
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit(f"Host sherpa archive checksum mismatch: {archive}")
PY
  tar -xjf "${host_archive}" -C "${host_cache}"
fi
test -d "${host_lib_dir}" || { echo "Sherpa host runtime archive has no lib directory" >&2; exit 1; }
echo "${host_lib_dir}"

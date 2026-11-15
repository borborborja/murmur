#!/usr/bin/env bash
# Copies the live models (the Conformer in int8, the VAD, TitaNet) and the test recording from
# the repo's models/ and data/ into the app's resources. They don't go into git (.gitignore).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
out="$here/Resources/models"
mkdir -p "$out/conformer-ca-es-sherpa"
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
  cp "$repo/models/conformer-ca-es-sherpa/$f" "$out/conformer-ca-es-sherpa/$f"
done
cp "$repo/models/silero_vad.onnx" "$repo/models/nemo_en_titanet_small.onnx" "$out/"
# The meeting the computer is measured with, to compare the text.
cp "$repo/data/reunio-3min.wav" "$out/reunio-3min.wav"
du -sh "$out"

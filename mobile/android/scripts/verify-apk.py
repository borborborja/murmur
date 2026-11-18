#!/usr/bin/env python3
"""Check packaged ARM64 runtimes and Android's 16 KB ELF/ZIP alignment."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

PAGE = 16 * 1024
REQUIRED = {
    "libmurmur_mobile.so", "libmurmur_llm.so", "libmurmur_whisper.so",
    "libsherpa-onnx-c-api.so", "libonnxruntime.so", "libc++_shared.so",
}


def inspect(apk: Path) -> dict:
    report = []
    with zipfile.ZipFile(apk) as archive, apk.open("rb") as raw:
        entries = [entry for entry in archive.infolist() if entry.filename.endswith(".so")]
        names = {Path(entry.filename).name for entry in entries}
        if missing := REQUIRED - names:
            raise ValueError(f"Missing native runtimes: {sorted(missing)}")
        for entry in entries:
            if not entry.filename.startswith("lib/arm64-v8a/"):
                raise ValueError(f"Unexpected native ABI: {entry.filename}")
            binary = archive.read(entry)
            if binary[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", binary, 18)[0] != 183:
                raise ValueError(f"Not ARM64 little-endian ELF: {entry.filename}")
            offset = struct.unpack_from("<Q", binary, 32)[0]
            size, count = struct.unpack_from("<HH", binary, 54)
            loads = []
            for index in range(count):
                header = offset + index * size
                if struct.unpack_from("<I", binary, header)[0] != 1:
                    continue
                file_offset, virtual_address = struct.unpack_from("<QQ", binary, header + 8)
                alignment = struct.unpack_from("<Q", binary, header + 48)[0]
                if alignment < PAGE or virtual_address % PAGE != file_offset % PAGE:
                    raise ValueError(f"ELF segment is not 16 KB aligned: {entry.filename}")
                loads.append(alignment)
            if not loads:
                raise ValueError(f"No ELF LOAD segments: {entry.filename}")
            raw.seek(entry.header_offset + 26)
            filename_length, extra_length = struct.unpack("<HH", raw.read(4))
            data_offset = entry.header_offset + 30 + filename_length + extra_length
            if entry.compress_type != zipfile.ZIP_STORED or data_offset % PAGE:
                raise ValueError(f"APK library is not stored at a 16 KB ZIP boundary: {entry.filename}")
            report.append({"library": entry.filename, "elf_alignment": min(loads), "zip_offset": data_offset})
        assets = set(archive.namelist())
        required_assets = {
            "assets/notes/index.html",
            "assets/notes/notes.js",
            "assets/murmur-tokens.json",
            "assets/licenses/ONNX-Runtime-third-party.txt",
            "assets/licenses/sherpa-onnx.txt",
            "assets/licenses/space-grotesk-OFL.txt",
            "assets/licenses/generated/Android-NDK.txt",
            "assets/licenses/generated/Murmur.txt",
            "assets/licenses/generated/llama.cpp-LICENSE.txt",
            "assets/licenses/generated/whisper.cpp-LICENSE.txt",
            "assets/licenses/generated/notes.txt",
        }
        required_assets.update(f"assets/locales/{language}/android.json" for language in ("ca", "es", "en"))
        for name in sorted(required_assets):
            if name not in assets:
                raise ValueError(f"Missing offline UI asset: {name}")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"apk": str(apk), "bytes": apk.stat().st_size, "sha256": digest.hexdigest(), "native": report}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    print(json.dumps(inspect(args.apk), indent=2))

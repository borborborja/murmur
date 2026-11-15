// Builds murmur-whisper (app/whisper) and copies it where Tauri looks for the sidecar:
// src-tauri/binaries/murmur-whisper-<target triple>[.exe] (tauri.conf.json › externalBin).
// `npm run whisper`; MURMUR_TARGET picks another target (e.g. x86_64-apple-darwin).
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const app = join(dirname(fileURLToPath(import.meta.url)), "..");
const host = execFileSync("rustc", ["-vV"], { encoding: "utf8" }).match(/^host: (\S+)$/m)[1];
const target = process.env.MURMUR_TARGET || process.env.TAURI_ENV_TARGET_TRIPLE || host;
const exe = target.includes("windows") ? ".exe" : "";

// whisper.cpp would build for the CPU it's built on (GGML_NATIVE): on a CI runner, AVX-512 that
// most PCs don't have. The same AVX2 baseline as .cargo/config.toml instead.
const env = { ...process.env };
if (!target.includes("apple") && target.startsWith("x86_64")) {
  Object.assign(env, { GGML_NATIVE: "OFF", GGML_AVX: "ON", GGML_AVX2: "ON", GGML_FMA: "ON", GGML_F16C: "ON" });
}

// Windows on ARM: ggml can only target a given ARM CPU with clang (MSVC needs GGML_NATIVE, which on
// the CI runner would pick its SVE that no Snapdragon has). ARMv8.2 with dotprod and fp16, which
// every Windows ARM PC has (Snapdragon 8cx and X). With Ninja and clang-cl, as the CI sets for
// llama.cpp (.github/actions/preparar), in a Visual Studio ARM64 environment.
if (target === "aarch64-pc-windows-msvc") {
  Object.assign(env, { GGML_NATIVE: "OFF", GGML_CPU_ARM_ARCH: "armv8.2-a+dotprod+fp16" });
  env.CMAKE_GENERATOR ??= "Ninja";
  env.CMAKE_C_COMPILER ??= "clang-cl";
  env.CMAKE_CXX_COMPILER ??= "clang-cl";
}
// Vulkan on Windows: the shader generator's paths go past MAX_PATH, and MSBuild's file tracker
// fails on them (FTK1011). llama-cpp-sys does the same: no tracking, and /FS for the shared .pdb.
if (process.env.MURMUR_VULKAN === "1" && target.includes("windows")) {
  env.TrackFileAccess = "false";
  env.CFLAGS = `${env.CFLAGS ?? ""} /FS`.trim();
  env.CXXFLAGS = `${env.CXXFLAGS ?? ""} /FS`.trim();
}

const args = ["build", "--release"];
if (target !== host) args.push("--target", target);
// Whisper on the GPU with Vulkan (Windows x64 and Linux releases): MURMUR_VULKAN=1, with the
// Vulkan SDK installed. The app itself is built with `--features vulkan` at the same time.
if (process.env.MURMUR_VULKAN === "1") args.push("--features", "vulkan");
execFileSync("cargo", args, { cwd: join(app, "whisper"), stdio: "inherit", env });

const built = join(app, "whisper", "target", target !== host ? target : "", "release", `murmur-whisper${exe}`);
const dest = join(app, "src-tauri", "binaries", `murmur-whisper-${target}${exe}`);
mkdirSync(dirname(dest), { recursive: true });
copyFileSync(built, dest);
console.log(`murmur-whisper → ${dest}`);

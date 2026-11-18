#!/usr/bin/env python3
"""Build, sign and verify Murmur's installable Android APK without publishing it."""
from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

ANDROID = Path(__file__).resolve().parent.parent
REPO = ANDROID.parent.parent
SERVICE = "dev.murmur.android.signing"
ACCOUNT = "release"
ALIAS = "murmur"


class ReleaseError(RuntimeError):
    pass


def quiet(command: list[str], env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    """Subprocess arguments and environment may contain credentials; never log either."""
    return subprocess.run(command, env=env, text=True, capture_output=True, check=False)


def java_environment(env: dict[str, str]) -> dict[str, str]:
    result = env.copy()
    candidates: list[Path] = []
    if result.get("JAVA_HOME"):
        candidates.append(Path(result["JAVA_HOME"]))
    else:
        if sys.platform == "darwin":
            lookup = quiet(["/usr/libexec/java_home", "-v", "21"])
            if lookup.returncode == 0:
                candidates.append(Path(lookup.stdout.strip()))
            candidates.append(Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"))
        javac = shutil.which("javac")
        if javac:
            candidates.append(Path(javac).resolve().parent.parent)
        candidates.extend(Path("/usr/lib/jvm").glob("*21*"))
    for home in candidates:
        javac = home / "bin/javac"
        if javac.is_file() and re.match(r"javac 21(?:[.\s]|$)", quiet([str(javac), "-version"]).stdout.strip()):
            result["JAVA_HOME"] = str(home.resolve())
            result["PATH"] = str(home / "bin") + os.pathsep + result.get("PATH", "")
            return result
    raise ReleaseError("JDK 21 is required. Set JAVA_HOME to its installation directory.")


def keychain_password() -> str | None:
    result = quiet(["security", "find-generic-password", "-a", ACCOUNT, "-s", SERVICE, "-w", login_keychain()])
    if result.returncode == 0:
        return result.stdout.rstrip("\r\n")
    # security's item-not-found status. Locked or inaccessible keychains must not look empty.
    if result.returncode == 44:
        return None
    raise ReleaseError("The macOS login Keychain could not be read. Unlock it, or provide the signing environment variables.")


def login_keychain() -> str:
    directory = Path.home() / "Library/Keychains"
    modern = directory / "login.keychain-db"
    legacy = directory / "login.keychain"
    return str(legacy if not modern.exists() and legacy.exists() else modern)


def validate_keystore(env: dict[str, str]) -> None:
    keytool = Path(env["JAVA_HOME"]) / "bin/keytool"
    result = quiet([str(keytool), "-list", "-keystore", env["MURMUR_KEYSTORE"],
                    "-storepass:env", "MURMUR_STORE_PASSWORD", "-alias", env["MURMUR_KEY_ALIAS"]], env)
    if result.returncode != 0:
        raise ReleaseError("The release keystore or alias could not be opened with the supplied credentials. No key was replaced.")


def signing_environment(env: dict[str, str], platform: str | None = None,
                        home: Path | None = None) -> tuple[dict[str, str], str]:
    result = env.copy()
    if result.get("MURMUR_KEYSTORE"):
        key = Path(result["MURMUR_KEYSTORE"]).expanduser().resolve()
        if not key.is_file():
            raise ReleaseError("MURMUR_KEYSTORE must point to an existing release keystore.")
        if not result.get("MURMUR_STORE_PASSWORD"):
            raise ReleaseError("MURMUR_STORE_PASSWORD is required for the supplied keystore.")
        result["MURMUR_KEYSTORE"] = str(key)
        result.setdefault("MURMUR_KEY_ALIAS", ALIAS)
        result.setdefault("MURMUR_KEY_PASSWORD", result["MURMUR_STORE_PASSWORD"])
        validate_keystore(result)
        return result, "provided"
    if (platform or sys.platform) != "darwin":
        raise ReleaseError("Provide MURMUR_KEYSTORE, MURMUR_STORE_PASSWORD and, if needed, MURMUR_KEY_ALIAS/MURMUR_KEY_PASSWORD on this operating system.")
    if any(result.get(name) for name in ("MURMUR_STORE_PASSWORD", "MURMUR_KEY_ALIAS", "MURMUR_KEY_PASSWORD")):
        raise ReleaseError("Signing overrides require MURMUR_KEYSTORE. Refusing to combine partial overrides with the managed macOS key.")
    directory = (home or Path.home()) / ".local/share/murmur-android"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if directory.is_symlink():
        raise ReleaseError("The managed signing directory must not be a symbolic link.")
    directory.chmod(0o700)
    key = directory / "release.p12"
    lock = directory / ".signing.lock"
    try:
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError as error:
        raise ReleaseError("Another signing setup is running, or a previous setup left .signing.lock. Inspect it before retrying.") from error
    os.close(descriptor)
    temporary: Path | None = None
    credential_created = False
    try:
        password = keychain_password()
        if key.is_symlink():
            raise ReleaseError("Refusing a symbolic link at the managed keystore path.")
        if key.exists() and (not key.is_file() or password is None):
            raise ReleaseError("A release keystore already exists without its matching Keychain entry. No key was overwritten; provide its credentials explicitly.")
        if not key.exists() and password is not None:
            raise ReleaseError("The signing Keychain entry exists but the release keystore is missing. Restore the original key instead of replacing the app identity.")
        state = "reused"
        if not key.exists():
            state = "created"
            password = secrets.token_urlsafe(48)
            temporary = directory / f".release-{secrets.token_hex(12)}.p12"
            generated = result.copy()
            generated.update(MURMUR_STORE_PASSWORD=password, MURMUR_KEY_PASSWORD=password)
            command = [str(Path(result["JAVA_HOME"]) / "bin/keytool"), "-genkeypair", "-noprompt",
                       "-storetype", "PKCS12", "-keystore", str(temporary), "-alias", ALIAS,
                       "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA", "-validity", "10000",
                       "-dname", "CN=Murmur Android,O=Independent Android port",
                       "-storepass:env", "MURMUR_STORE_PASSWORD", "-keypass:env", "MURMUR_KEY_PASSWORD"]
            if quiet(command, generated).returncode != 0:
                raise ReleaseError("A new release key could not be generated. No existing key was changed.")
            temporary.chmod(0o600)
            # No -U: an existing Keychain item must never be silently replaced.
            if quiet(["security", "add-generic-password", "-a", ACCOUNT, "-s", SERVICE, "-w", password, login_keychain()]).returncode != 0:
                raise ReleaseError("The signing password could not be stored in the macOS login Keychain. The unpublished temporary key was removed.")
            credential_created = True
            # A hard-link publish is atomic and refuses any file that appeared meanwhile.
            os.link(temporary, key)
        key.chmod(0o600)
        result.update(MURMUR_KEYSTORE=str(key), MURMUR_STORE_PASSWORD=password or "",
                      MURMUR_KEY_ALIAS=ALIAS, MURMUR_KEY_PASSWORD=password or "")
        validate_keystore(result)
        return result, state
    except Exception:
        if credential_created and not key.exists():
            quiet(["security", "delete-generic-password", "-a", ACCOUNT, "-s", SERVICE, login_keychain()])
        raise
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        lock.unlink(missing_ok=True)


def run(command: list[str], env: dict[str, str], cwd: Path = REPO) -> None:
    result = subprocess.run(command, env=env, cwd=cwd, check=False)
    if result.returncode != 0:
        raise ReleaseError("A required build or verification step failed. No release was published.")


def needs_build(binary: Path, roots: list[Path], extensions: set[str]) -> bool:
    if not binary.is_file():
        return True
    built_at = binary.stat().st_mtime_ns
    for root in roots:
        files = root.rglob("*") if root.is_dir() else [root]
        if any(file.is_file() and (not extensions or file.suffix in extensions) and file.stat().st_mtime_ns > built_at for file in files):
            return True
    return False


def build(env: dict[str, str]) -> Path:
    sdk = Path(env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT") or Path.home() / "Library/Android/sdk").expanduser().resolve()
    if not sdk.is_dir():
        raise ReleaseError("ANDROID_HOME must point to an installed Android SDK.")
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_SDK_ROOT"] = str(sdk)
    env.setdefault("ANDROID_NDK_HOME", str(sdk / "ndk/29.0.14206865"))
    env["PATH"] = str(Path.home() / ".cargo/bin") + os.pathsep + env.get("PATH", "")
    run([sys.executable, str(ANDROID / "scripts/generate-resources.py")], env)
    if not (REPO / "app/node_modules/vite/package.json").is_file():
        run(["npm", "ci", "--prefix", str(REPO / "app")], env)
    run(["node", str(ANDROID / "scripts/build-notes.mjs")], env)
    jni = ANDROID / "app/src/main/jniLibs/arm64-v8a"
    speech_libs = ["libmurmur_mobile.so", "libsherpa-onnx-c-api.so", "libonnxruntime.so", "libc++_shared.so"]
    speech_sources = [REPO / "core/src", REPO / "core/Cargo.toml", REPO / "mobile/ffi/src",
                      REPO / "mobile/ffi/Cargo.toml", REPO / "mobile/ffi/Cargo.lock", REPO / "mobile/ffi/uniffi.toml",
                      REPO / "scripts/build-native.sh", REPO / "scripts/sherpa-host.sh"]
    if (not all((jni / name).is_file() for name in speech_libs)
            or not (ANDROID / "app/src/main/java/uniffi/murmur_mobile/murmur_mobile.kt").is_file()
            or needs_build(jni / "libmurmur_mobile.so", speech_sources, {".rs", ".toml", ".lock", ".sh"})):
        run([str(REPO / "scripts/build-native.sh")], env)
    if any(needs_build(jni / f"libmurmur_{name}.so", [ANDROID / name, ANDROID / "scripts/build-inference.sh"],
                       {".cpp", ".h", ".txt", ".sh"}) for name in ("llm", "whisper")):
        run([str(ANDROID / "scripts/build-inference.sh")], env)
    tools = [path for path in (sdk / "build-tools").glob("*") if (path / "apksigner").is_file()]
    if not tools:
        raise ReleaseError("Install Android SDK Build Tools with apksigner.")
    latest = max(tools, key=lambda path: tuple(int(part) for part in re.findall(r"\d+", path.name)))
    run([str(ANDROID / "gradlew"), ":app:testDebugUnitTest", ":app:assembleRelease", "--console=plain"], env, ANDROID)
    source = ANDROID / "app/build/outputs/apk/release/app-release.apk"
    if not source.is_file():
        raise ReleaseError("The signed release APK was not produced.")
    signature = quiet([str(latest / "apksigner"), "verify", "--verbose", "--print-certs", str(source)], env)
    if signature.returncode != 0:
        raise ReleaseError("Release APK signature verification failed.")
    if (latest / "zipalign").is_file():
        run([str(latest / "zipalign"), "-c", "-P", "16", "4", str(source)], env)
    verification = quiet([sys.executable, str(ANDROID / "scripts/verify-apk.py"), str(source)], env)
    if verification.returncode != 0:
        raise ReleaseError("Release APK native-runtime, offline-asset or 16 KB alignment verification failed.")
    report = json.loads(verification.stdout)
    gradle = (ANDROID / "app/build.gradle.kts").read_text()
    match = re.search(r'versionName\s*=\s*"([A-Za-z0-9._-]+)"', gradle)
    if not match:
        raise ReleaseError("The Android version name could not be read.")
    destination = REPO / "dist/android"
    destination.mkdir(parents=True, exist_ok=True)
    apk = destination / f"Murmur-{match.group(1)}-arm64.apk"
    with tempfile.NamedTemporaryFile(dir=destination, prefix=".release-", suffix=".apk", delete=False) as temporary:
        staged = Path(temporary.name)
    try:
        shutil.copyfile(source, staged)
        staged.chmod(0o644)
        os.replace(staged, apk)
    finally:
        staged.unlink(missing_ok=True)
    report["apk"] = str(apk)
    apk.with_suffix(".verification.json").write_text(json.dumps(report, indent=2) + "\n")
    apk.with_suffix(".signature.txt").write_text(signature.stdout + signature.stderr)
    apk.with_suffix(".apk.sha256").write_text(f'{report["sha256"]}  {apk.name}\n')
    print(f"Verified installable APK: {apk}")
    print(f'SHA-256: {report["sha256"]}')
    return apk


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-signing", action="store_true", help="Prepare/verify the local signing key without running Gradle or building an APK.")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        env = java_environment(dict(os.environ))
        env, state = signing_environment(env)
        print(f'Signing key {state}: {env["MURMUR_KEYSTORE"]}')
        if not args.prepare_signing:
            build(env)
        return 0
    except ReleaseError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        # A subprocess failure must never dump a credential-bearing argument list or environment.
        print(f"Release setup or verification failed ({type(error).__name__}). No release was published.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

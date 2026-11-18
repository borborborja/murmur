#!/usr/bin/env python3
"""Stage checksum-verified model data directly to a debug emulator without a second disk copy."""
import argparse
import hashlib
import json
import shlex
import subprocess
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("models", nargs="+")
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--package", default="dev.murmur.android.debug")
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]

    def shell(command, **kwargs):
        return subprocess.run(adb + ["shell", "-T", "run-as", args.package, "sh", "-c", shlex.quote(command)], check=True, **kwargs)

    catalog = json.loads(subprocess.check_output(adb + ["exec-out", "run-as", args.package, "cat", "files/test-model-catalog.json"]))
    models = {model["id"]: model for model in catalog["models"]}
    for model_id in args.models:
        for item in models[model_id]["files"]:
            path = item["path"]
            assert not path.startswith("/") and ".." not in path.split("/")
            target = "files/models/" + path
            existing = subprocess.run(adb + ["shell", "-T", "run-as", args.package, "sha256sum", target], capture_output=True, text=True)
            if existing.returncode == 0 and existing.stdout.split()[0] == item["sha256"]:
                print("Verified", path, flush=True)
                continue
            temporary = target + ".download"
            shell("mkdir -p " + shlex.quote(target.rsplit("/", 1)[0]))
            for attempt in range(6):
                size_reply = subprocess.run(adb + ["shell", "-T", "run-as", args.package, "stat", "-c", "%s", temporary], capture_output=True, text=True)
                offset = int(size_reply.stdout.strip()) if size_reply.returncode == 0 else 0
                if offset == item["bytes"]:
                    break
                if offset > item["bytes"]:
                    shell("rm -f " + shlex.quote(temporary)); offset = 0
                assert item["url"].startswith("https://")
                request = urllib.request.Request(item["url"], headers={"Range": f"bytes={offset}-"} if offset else {})
                print("Downloading", path, offset, "/", item["bytes"], flush=True)
                transfer = None
                try:
                    with urllib.request.urlopen(request, timeout=300) as response:
                        if response.status != 206:
                            offset = 0
                        elif offset:
                            assert response.headers.get("Content-Range", "").startswith(f"bytes {offset}-")
                        command = ("cat >> " if offset else "cat > ") + shlex.quote(temporary)
                        transfer = subprocess.Popen(adb + ["shell", "-T", "run-as", args.package, "sh", "-c", shlex.quote(command)], stdin=subprocess.PIPE)
                        size = offset
                        while chunk := response.read(256 * 1024):
                            size += len(chunk)
                            assert size <= item["bytes"]
                            transfer.stdin.write(chunk)
                        transfer.stdin.close()
                        assert transfer.wait() == 0
                        assert size == item["bytes"]
                    break
                except Exception:
                    if transfer:
                        try:
                            transfer.stdin.close()
                        except (BrokenPipeError, OSError):
                            pass
                        transfer.wait()
                    if attempt == 5:
                        raise
                    print("Retrying resumable transfer", path, flush=True)
            actual = subprocess.check_output(adb + ["shell", "-T", "run-as", args.package, "sha256sum", temporary], text=True).split()[0]
            if actual != item["sha256"]:
                shell("rm -f " + shlex.quote(temporary))
                raise AssertionError((path, "checksum"))
            shell("mv " + shlex.quote(temporary) + " " + shlex.quote(target))
            print("Verified", path, flush=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Push the generated files/ game data into the app's external data dir on a device.

Why not plain `adb push`?

`adb push` drives the adb sync service, and on Android 11+ writes under
/sdcard/Android/data/<pkg>/ go through the FUSE/MediaProvider layer, where the
first subdirectory creation fails with "remote secure_mkdirs failed: Operation
not permitted". The tree is ~240 files in several subdirectories, so push aborts
immediately.

`adb shell` itself is NOT restricted this way: uid 2000 (shell) is in
ext_data_rw and can mkdir/write under Android/data directly. So the trick is to
ship ONE archive through push and unpack it device-side with toybox tar.

(`run-as <pkg>` does not help here — the runas_app SELinux domain cannot even
stat /sdcard/Android, it fails with "Permission denied".)

    python tools/push_gamedata_to_device.py
    python tools/push_gamedata_to_device.py --src dist/gamedata/files --package com.sampmobile.launcher
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tarfile
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PKG = "com.sampmobile.launcher"
REMOTE_TAR = "/data/local/tmp/samp_gamedata.tar"


def log(msg: str = "") -> None:
    print(msg, flush=True)


def adb(adb_path: str, serial: str | None, *args: str, check: bool = False) -> subprocess.CompletedProcess:
    cmd = [adb_path] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", check=check)


def sh(adb_path: str, serial: str | None, command: str) -> str:
    return adb(adb_path, serial, "shell", command).stdout.strip()


def resolve_adb(explicit: str | None) -> str:
    if explicit:
        return explicit
    found = shutil.which("adb")
    if found:
        return found
    for c in (Path("C:/ProgramData/platform-tools/adb.exe"),
              Path(os.path.expandvars("%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe"))):
        if c.is_file():
            return str(c)
    raise SystemExit("adb not found; pass --adb")


def pick_serial(adb_path: str, wanted: str | None) -> str:
    out = adb(adb_path, None, "devices").stdout
    devs = [l.split()[0] for l in out.splitlines()[1:] if len(l.split()) >= 2 and l.split()[1] == "device"]
    if wanted:
        if wanted not in devs:
            raise SystemExit(f"device {wanted} not connected; have {devs}")
        return wanted
    if len(devs) == 1:
        return devs[0]
    raise SystemExit(f"need exactly one usable device, have {devs}; pass --serial")


def main() -> int:
    ap = argparse.ArgumentParser(description="Install files/ game data into the app's external dir via run-as.")
    ap.add_argument("--src", default=str(REPO_ROOT / "dist" / "gamedata" / "files"))
    ap.add_argument("--package", default=DEFAULT_PKG)
    ap.add_argument("--adb")
    ap.add_argument("--serial")
    ap.add_argument("--tar", default=str(REPO_ROOT / "dist" / "gamedata" / "samp_gamedata.tar"))
    ap.add_argument("--keep-tar", action="store_true")
    args = ap.parse_args()

    src = Path(args.src)
    if not src.is_dir():
        raise SystemExit(f"--src not a directory: {src}")

    adb_path = resolve_adb(args.adb)
    serial = pick_serial(adb_path, args.serial)
    log(f"device : {serial}")

    if not sh(adb_path, serial, f"pm path {args.package}"):
        raise SystemExit(f"{args.package} is not installed yet — install the APK first.")

    target = f"/sdcard/Android/data/{args.package}/files"
    sh(adb_path, serial, f"mkdir -p {target}")

    # 1) pack
    tar_path = Path(args.tar)
    if not tar_path.is_file():
        log(f"packing {src} -> {tar_path}")
        tar_path.parent.mkdir(parents=True, exist_ok=True)
        t0 = time.time()
        with tarfile.open(tar_path, "w") as tf:
            for p in sorted(src.rglob("*")):
                if p.is_file():
                    tf.add(p, arcname=p.relative_to(src).as_posix())
        log(f"packed {tar_path.stat().st_size / 1048576:.1f} MB in {time.time() - t0:.1f}s")
    else:
        log(f"reusing existing {tar_path} ({tar_path.stat().st_size / 1048576:.1f} MB)")

    # 2) push the single archive (plain files are fine for uid shell)
    log(f"pushing archive to {REMOTE_TAR} ...")
    res = subprocess.run([adb_path] + (["-s", serial] if serial else []) +
                         ["push", str(tar_path), REMOTE_TAR], check=False)
    if res.returncode != 0:
        raise SystemExit("adb push of the tar failed")

    # 3) let the app uid unpack into its own external dir
    log(f"unpacking into {target} as the app uid ...")
    cmd = (f"run-as {args.package} sh -c '"
           f"mkdir -p {target} && "
           f"tar -xf {REMOTE_TAR} -C {target} && "
           f"echo UNPACK_OK'")
    out = sh(adb_path, serial, cmd)
    log(out or "(no output)")
    if "UNPACK_OK" not in out:
        raise SystemExit("unpack failed; check the message above")

    sh(adb_path, serial, f"rm -f {REMOTE_TAR}")
    if not args.keep_tar:
        tar_path.unlink(missing_ok=True)

    # 4) verify a few landmarks
    log("verifying ...")
    check = sh(adb_path, serial, f"run-as {args.package} ls {target}")
    for want in ("texdb", "anim", "data", "models", "SAMP"):
        log(f"  {'OK ' if want in check.split() else 'MISS'} {want}")
    used = sh(adb_path, serial, f"du -sh {target} 2>/dev/null")
    log(f"  size: {used}")
    log("Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

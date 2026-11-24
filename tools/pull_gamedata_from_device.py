#!/usr/bin/env python3
"""
One-shot "harvest everything from the phone" helper.

Use this when you do NOT have a standalone GTA:SA 2.10 APK file, but the game
is already installed and playable on a real device.

    python tools/pull_gamedata_from_device.py --pc-samp "D:/GTASA"

Order of operations:
  1. Resolve adb + the connected device (USB or wireless debugging).
  2. Report the installed version (v2.10 is the supported target).
  3. Pull every APK / split of the game package and hand them to
     tools/prepare_gtasa.py --from-adb, which extracts libGTASA.so,
     libImmEmulatorJ.so and the light APK assets into app/src/main/.
  4. Discover the heavy (~2 GB) game data, in this priority:
       a. assets/texdb inside the pulled APKs   (2 GB+ asset-pack builds)
       b. /sdcard/Android/obb/<pkg>/*.obb       (classic OBB build)
       c. /sdcard/Android/data/<pkg>/files      (build that unpacks on first run)
     and pull whatever is found into dist/phone/.
  5. Run tools/build_gamedata.py with --gtasa <those sources> --pc-samp <pc>.

Everything lands in git-ignored paths, so nothing proprietary is committed.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import List, Optional

REPO_ROOT = Path(__file__).resolve().parent.parent
TOOLS_DIR = REPO_ROOT / "tools"
PHONE_DIR = REPO_ROOT / "dist" / "phone"

# Files build_gamedata.py refuses to run without.
REQUIRED_BASE = (
    "texdb/gta3.img",
    "texdb/gta3/gta3.txt",
    "anim/anim.img",
    "data/gta.dat",
)


def log(msg: str = "") -> None:
    print(msg, flush=True)


def step(n: int, msg: str) -> None:
    log("")
    log(f"=== [{n}] {msg} " + "=" * max(0, 58 - len(msg)))


def adb_run(adb: str, serial: Optional[str], *args: str, check: bool = False) -> subprocess.CompletedProcess:
    cmd: List[str] = [adb]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=check,
    )


def adb_shell(adb: str, serial: Optional[str], command: str) -> str:
    return adb_run(adb, serial, "shell", command).stdout.strip()


def resolve_adb(explicit: Optional[str]) -> str:
    if explicit:
        if not Path(explicit).is_file():
            raise FileNotFoundError(f"adb not found at: {explicit}")
        return explicit

    found = shutil.which("adb")
    if found:
        return found

    candidates: List[Path] = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_SDK"):
        val = os.environ.get(key)
        if val:
            candidates.append(Path(val))
    candidates += [
        Path("C:/ProgramData/platform-tools"),
        Path(os.path.expandvars("%LOCALAPPDATA%/Android/Sdk")),
        Path("D:/android-sdk"),
    ]
    local_props = REPO_ROOT / "local.properties"
    if local_props.is_file():
        for line in local_props.read_text(encoding="utf-8", errors="ignore").splitlines():
            m = re.match(r"^\s*sdk\.dir\s*=\s*(.+)$", line)
            if m:
                candidates.append(Path(m.group(1).strip().replace("\\\\", "\\")))

    exe = "adb.exe" if os.name == "nt" else "adb"
    for sdk in candidates:
        p = sdk / "platform-tools" / exe
        if p.is_file():
            return str(p)
    raise FileNotFoundError(
        "adb not found. Install Android platform-tools, add it to PATH, or pass --adb <path>."
    )


def pick_device(adb: str, serial: Optional[str]) -> str:
    out = adb_run(adb, None, "devices").stdout
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])

    if serial:
        if serial not in devices:
            raise RuntimeError(
                f"Device '{serial}' is not in 'device' state. Connected: {devices or 'none'}\n"
                f"Raw adb devices output:\n{out}"
            )
        return serial

    if not devices:
        raise RuntimeError(
            "No authorized device found.\n"
            "  1) Enable Developer options -> USB debugging on the phone.\n"
            "  2) Connect the USB cable; accept the 'Allow USB debugging' prompt.\n"
            "  3) Re-run:  python tools/pull_gamedata_from_device.py --pc-samp \"D:/GTASA\"\n"
            "Wireless debugging also works:  adb pair <ip:port>  then  adb connect <ip:port>"
        )
    if len(devices) > 1:
        raise RuntimeError(f"Multiple devices connected ({devices}); pick one with --serial.")
    return devices[0]


def device_model(adb: str, serial: str) -> str:
    brand = adb_shell(adb, serial, "getprop ro.product.brand")
    model = adb_shell(adb, serial, "getprop ro.product.model")
    abi = adb_shell(adb, serial, "getprop ro.product.cpu.abi")
    return f"{brand} {model} [{abi}]".strip()


def package_version(adb: str, serial: str, package: str) -> Optional[str]:
    out = adb_shell(adb, serial, f"dumpsys package {package}")
    if not out:
        return None
    m = re.search(r"versionName=([^\s]+)", out)
    return m.group(1) if m else None


def package_apk_paths(adb: str, serial: str, package: str) -> List[str]:
    out = adb_shell(adb, serial, f"pm path {package}")
    return [
        line.split(":", 1)[1].strip()
        for line in out.splitlines()
        if line.strip().startswith("package:")
    ]


def pull_file(adb: str, serial: str, remote: str, local: Path) -> bool:
    local.parent.mkdir(parents=True, exist_ok=True)
    if local.is_file() and local.stat().st_size > 0:
        log(f"  [skip] already local: {local.name} ({local.stat().st_size / 1048576:.1f} MB)")
        return True
    log(f"  [pull] {remote}  ->  {local}")
    res = subprocess.run(
        [adb] + (["-s", serial] if serial else []) + ["pull", remote, str(local)],
        check=False,
    )
    return res.returncode == 0 and local.is_file()


def pull_dir(adb: str, serial: str, remote: str, local_parent: Path) -> bool:
    local_parent.mkdir(parents=True, exist_ok=True)
    log(f"  [pull] {remote}/  ->  {local_parent}/")
    res = subprocess.run(
        [adb] + (["-s", serial] if serial else []) + ["pull", remote, str(local_parent)],
        check=False,
    )
    return res.returncode == 0


def remote_ls(adb: str, serial: str, remote: str) -> List[str]:
    out = adb_shell(adb, serial, f"ls -1 '{remote}' 2>/dev/null")
    if not out or "Permission denied" in out or "No such file" in out:
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def apk_has_heavy_assets(apk: Path) -> bool:
    """True if this APK itself carries the large game data (asset-pack style build)."""
    try:
        with zipfile.ZipFile(apk) as zf:
            for name in zf.namelist():
                low = name.lower()
                if low.endswith("assets/texdb/gta3.img") or low.endswith("assets/anim/anim.img"):
                    return True
    except zipfile.BadZipFile:
        return False
    return False


def scan_device_apks(adb: str, serial: str) -> List[str]:
    """Look for a leftover GTA:SA .apk in the usual download folders on the phone."""
    hits: List[str] = []
    for d in ("/sdcard/Download", "/sdcard/Downloads", "/sdcard", "/sdcard/Android/data"):
        for name in remote_ls(adb, serial, d):
            low = name.lower()
            if low.endswith(".apk") and ("gta" in low or "rockstar" in low):
                remote = f"{d}/{name}"
                if remote not in hits:
                    hits.append(remote)
    return hits


def apk_has_gtasa_lib(apk: Path) -> bool:
    try:
        with zipfile.ZipFile(apk) as zf:
            return any(
                n.startswith("lib/") and n.endswith("libGTASA.so") for n in zf.namelist()
            )
    except zipfile.BadZipFile:
        return False


def find_required(root: Path) -> List[str]:
    """Return which REQUIRED_BASE entries are missing under root (case-insensitive)."""
    missing = []
    if not root.is_dir():
        return list(REQUIRED_BASE)
    for rel in REQUIRED_BASE:
        needle = rel.lower().replace("/", "\\")
        hit = False
        for dirpath, _dirnames, filenames in os.walk(root):
            for fn in filenames:
                full = os.path.join(dirpath, fn)
                relpath = os.path.relpath(full, root).lower().replace("/", "\\")
                if relpath.endswith(needle):
                    hit = True
                    break
            if hit:
                break
        if not hit:
            missing.append(rel)
    return missing


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Pull installed GTA:SA binaries + game data from a phone and build files/."
    )
    ap.add_argument("--pc-samp", required=False, help='PC GTA:SA dir with SA-MP 0.3.7, e.g. "D:/GTASA"')
    ap.add_argument("--package", default="com.rockstargames.gtasa", help="Game package name")
    ap.add_argument("--adb", help="Explicit adb path")
    ap.add_argument("--serial", help="Target device serial")
    ap.add_argument("--out", default=str(REPO_ROOT / "dist" / "gamedata" / "files"), help="Output files/ dir")
    ap.add_argument("--cjk-font", help="Optional CJK .ttf installed as SAMP/fonts/chinese.ttf")
    ap.add_argument("--keep-audio", action="store_true", help="Also copy full audio streams (~+600 MB)")
    ap.add_argument("--skip-libs", action="store_true", help="Only pull game data, do not touch jniLibs/assets")
    ap.add_argument("--skip-data", action="store_true", help="Only pull binaries, skip the 2 GB data step")
    ap.add_argument("--skip-build", action="store_true", help="Harvest only; do not run build_gamedata.py")
    ap.add_argument("--extra-remote", action="append", default=[], help="Extra remote dir to try for game data")
    args = ap.parse_args()

    adb = resolve_adb(args.adb)
    serial = pick_device(adb, args.serial)
    log(f"adb      : {adb}")
    log(f"device   : {serial}  ({device_model(adb, serial)})")

    pkg = args.package
    version = package_version(adb, serial, pkg)
    if version is None:
        raise RuntimeError(
            f"Package '{pkg}' is not installed on {serial}.\n"
            "If your build uses a different package id, pass --package <id>.\n"
            f"Installed GTA-like packages: "
            + (adb_shell(adb, serial, "pm list packages | grep -i -E 'gta|rockstar|samp'") or "none found")
        )
    log(f"package  : {pkg}  version {version}")
    if not version.startswith("2.10"):
        log("")
        log(f"  !! WARNING: installed version is {version}, not 2.10.")
        log("     samp-mobile targets GTA:SA 2.10 native ABI. Other versions may build")
        log("     but can crash at runtime (missing/changed JNI exports). Continuing anyway.")

    apk_paths = package_apk_paths(adb, serial, pkg)
    log(f"apk parts: {len(apk_paths)}")
    for p in apk_paths:
        log(f"    {p}")

    apk_local_dir = PHONE_DIR / "apks"
    apk_local_dir.mkdir(parents=True, exist_ok=True)
    local_apks: List[Path] = []
    for idx, remote in enumerate(apk_paths):
        dest = apk_local_dir / f"{idx}_{Path(remote).name}"
        step(1, f"Pull APK part {idx + 1}/{len(apk_paths)}")
        if pull_file(adb, serial, remote, dest):
            local_apks.append(dest)

    # A leftover installer in /sdcard/Download is occasionally a complete APK and is
    # far easier than juggling splits — pull it only if it really carries libGTASA.so.
    for remote in scan_device_apks(adb, serial):
        log("")
        log(f"  found GTA-looking apk on phone storage: {remote}")
        dest = PHONE_DIR / "apks_extra" / Path(remote).name
        if pull_file(adb, serial, remote, dest) and apk_has_gtasa_lib(dest):
            log("    [ok] contains libGTASA.so -> using it as an extra native-lib source")
            local_apks.append(dest)

    # ---------------------------------------------------------------- binaries
    if not args.skip_libs:
        step(2, "Extract libGTASA.so / libImmEmulatorJ.so + light APK assets")
        py = sys.executable
        cmd = [
            py, str(TOOLS_DIR / "prepare_gtasa.py"),
            "--apk", *[str(p) for p in local_apks],
        ]
        log("  $ " + " ".join(cmd))
        res = subprocess.run(cmd, check=False)
        if res.returncode != 0:
            log("  !! prepare_gtasa.py reported a problem (missing ABI split?)")

    # ------------------------------------------------------------- game data
    gtasa_sources: List[str] = []
    if not args.skip_data:
        step(3, "Locate the ~2 GB game data")
        # a) asset-pack style APKs that carry assets/texdb themselves
        heavy_apks = [p for p in local_apks if apk_has_heavy_assets(p)]
        if heavy_apks:
            log(f"  found {len(heavy_apks)} APK part(s) that already contain assets/texdb")
            gtasa_sources += [str(p) for p in heavy_apks]

        # b) classic OBB
        obb_remote = f"/sdcard/Android/obb/{pkg}"
        obb_names = [n for n in remote_ls(adb, serial, obb_remote) if n.lower().endswith((".obb", ".zip"))]
        if obb_names:
            log(f"  found {len(obb_names)} OBB file(s) in {obb_remote}")
            for name in obb_names:
                dest = PHONE_DIR / "obb" / name
                if pull_file(adb, serial, f"{obb_remote}/{name}", dest):
                    gtasa_sources.append(str(dest))
        else:
            log(f"  no OBB in {obb_remote}")

        # c) unpacked data dir (builds that extract on first launch)
        for candidate in (
            f"/sdcard/Android/data/{pkg}/files",
            f"/sdcard/Android/data/{pkg}/files/gamedata",
        ) + tuple(args.extra_remote):
            children = remote_ls(adb, serial, candidate)
            if not children:
                continue
            low = {c.lower() for c in children}
            if low & {"texdb", "anim", "models", "assets", "data"}:
                log(f"  found unpacked data dir: {candidate} ({len(children)} entries)")
                local = PHONE_DIR / "files_from_device"
                if pull_dir(adb, serial, candidate, local):
                    gtasa_sources.append(str(local / Path(candidate).name))
            else:
                log(f"  dir exists but looks empty/irrelevant: {candidate}")

        if not gtasa_sources:
            log("")
            log("  !! No heavy game data found. The install may use Play Asset Delivery")
            log("     splits that adb cannot read, or the data lives elsewhere.")
            log("     Options:")
            log("       - Copy the OBB/Android/data folder to the PC manually and pass")
            log("         it to:  python tools/build_gamedata.py --gtasa <path> ...")
            log("       - Add --extra-remote /sdcard/<your/path> and retry.")
        else:
            missing = []
            for src in gtasa_sources:
                p = Path(src)
                if p.is_dir():
                    missing += find_required(p)
            if missing:
                log(f"  note: pulled data is missing {sorted(set(missing))} (will let build_gamedata.py judge)")

    # ------------------------------------------------------------------ build
    if not args.skip_build:
        if not args.pc_samp:
            raise SystemExit("--pc-samp is required unless --skip-build is passed.")
        if not gtasa_sources:
            raise SystemExit("Nothing to feed build_gamedata.py; pass --skip-build or fix the data step.")
        step(4, "Build clean files/ from pulled data + PC SA-MP")
        cmd = [
            sys.executable, str(TOOLS_DIR / "build_gamedata.py"),
            "--gtasa", *gtasa_sources,
            "--pc-samp", args.pc_samp,
            "--out", args.out,
        ]
        if args.keep_audio:
            cmd.append("--keep-audio")
        if args.cjk_font:
            cmd += ["--cjk-font", args.cjk_font]
        log("  $ " + " ".join(cmd))
        res = subprocess.run(cmd, check=False)
        if res.returncode != 0:
            log("  !! build_gamedata.py failed; see the message above.")
            return res.returncode

    log("")
    log("Done.")
    log(f"  binaries : {REPO_ROOT / 'app/src/main/jniLibs'}")
    log(f"  data     : {args.out}")
    log("  Next     : ./gradlew assembleDebug   (or the release task you normally use)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, FileNotFoundError) as exc:
        print(f"\nERROR: {exc}", file=sys.stderr)
        sys.exit(2)

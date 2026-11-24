#!/usr/bin/env python3
"""
Zero-device automated self-check for SA-MP Mobile.

Verifies license/compliance hygiene, build/manifest contracts, anti-regression
invariants, JNI native bindings, dual-ABI jniLibs ELF headers, asset integrity,
and (optionally) the built APK artifact. Uses Python 3 standard library only.
"""

from __future__ import annotations

import argparse
import os
import re
import struct
import sys
import zipfile
from pathlib import Path
from typing import List

REPO_ROOT = Path(__file__).resolve().parent.parent

# Always-bundled repository native dependencies (non-Rockstar)
REPO_JNILIBS = {
    "arm64-v8a": [
        "libAML.so",
        "libmonetloader.so",
        "libluajit-5.1.so",
        "libBASS.so",
        "libBASS_SSL.so",
        "libOpenAL64.so",
        "libc++_shared.so",
    ],
    "armeabi-v7a": [
        "libAML.so",
        "libmonetloader.so",
        "libluajit-5.1.so",
        "libBASS.so",
        "libbass_ssl.so",
        "libOpenAL32.so",
        "libc++_shared.so",
    ],
}

# User-supplied Rockstar GTA:SA v2.10 binaries (gitignored; populated by tools/prepare_gtasa.py)
PROPRIETARY_JNILIBS = {
    "arm64-v8a": ["libGTASA.so"],
    "armeabi-v7a": ["libGTASA.so", "libImmEmulatorJ.so"],
}

ELF_EXPECTED = {
    "arm64-v8a": (2, 183),    # ELFCLASS64, EM_AARCH64
    "armeabi-v7a": (1, 40),   # ELFCLASS32, EM_ARM
}

FORBIDDEN_COMMERCIAL_FONTS = [
    "gotham",
    "museo_sans",
    "helvetica",
    "bradleyhand",
    "chaletcomprime",
]

PERSONAL_PATTERNS = [
    (re.compile(r"[A-Za-z]:\\{1,2}Users\\{1,2}(?!<YourUser>)[A-Za-z0-9._-]+", re.IGNORECASE), "hardcoded Windows user path"),
    (re.compile(r"/Users/(?!<YourUser>)[A-Za-z0-9._-]+/"), "hardcoded macOS user path"),
    (re.compile(r"/home/(?!<YourUser>)[A-Za-z0-9._-]+/"), "hardcoded Linux user path"),
    (re.compile(r"\badb(?:\.exe)?\s+-s\s+[A-Z0-9]{12,}\b"), "hardcoded ADB device serial"),
]

SKIP_DIRS = {".git", ".gradle", "build", ".cxx", ".externalNativeBuild", ".idea", "dist"}
TEXT_EXTS = {
    ".java", ".cpp", ".cc", ".c", ".h", ".hpp", ".gradle", ".xml", ".toml",
    ".properties", ".md", ".txt", ".py", ".ps1", ".sh", ".bat", ".json", ".yml", ".yaml",
}

KNOWN_LEGACY_JAVA_UI_NATIVES = {
    # Legacy unused Java-UI callbacks superseded by native ImGui widgets.
    "com.sampmobile.game.ui.UI.sendButtonPanelKey",
    "com.sampmobile.game.ui.UI.voicePressed",
    "com.sampmobile.game.ui.UI.sendScoreboardClick",
    "com.sampmobile.game.ui.UI.chatSend",
}


class Runner:
    def __init__(self) -> None:
        self.passed = 0
        self.failed = 0
        self.warnings = 0

    def ok(self, name: str, detail: str = "") -> None:
        self.passed += 1
        suffix = f" ({detail})" if detail else ""
        print(f"[PASS] {name}{suffix}")

    def fail(self, name: str, detail: str) -> None:
        self.failed += 1
        print(f"[FAIL] {name}: {detail}")

    def warn(self, name: str, detail: str) -> None:
        self.warnings += 1
        print(f"[WARN] {name}: {detail}")


def strip_c_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.DOTALL)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def read_text(rel_path: str) -> str:
    return (REPO_ROOT / rel_path).read_text(encoding="utf-8", errors="replace")


def enabled_abis() -> List[str]:
    """ABIs actually built, read from android.defaultConfig.ndk.abiFilters.add("...")."""
    return re.findall(r'abiFilters\.add\("([^"]+)"\)', read_text("app/build.gradle"))


def check_compliance(r: Runner) -> None:
    for rel in ("LICENSE", "NOTICE", "third_party/licenses/THIRD_PARTY_NOTICES.md"):
        if (REPO_ROOT / rel).is_file():
            r.ok(f"Legal file present: {rel}")
        else:
            r.fail(f"Legal file present: {rel}", "missing required license/notice file")

    font_dir = REPO_ROOT / "app/src/main/res/font"
    bad_fonts = []
    if font_dir.is_dir():
        for p in sorted(font_dir.iterdir()):
            low = p.name.lower()
            if any(tok in low for tok in FORBIDDEN_COMMERCIAL_FONTS):
                bad_fonts.append(p.name)
    if not bad_fonts:
        r.ok("No commercial fonts in res/font/", "open-source fonts only")
    else:
        r.fail("No commercial fonts in res/font/", "found: " + ", ".join(bad_fonts))

    gitignore = read_text(".gitignore")
    required_ignores = [
        "app/src/main/jniLibs/*/libGTASA.so",
        "app/src/main/jniLibs/*/libImmEmulatorJ.so",
        "app/src/main/assets/*",
    ]
    missing_ignores = [pat for pat in required_ignores if pat not in gitignore]
    if not missing_ignores:
        r.ok(".gitignore Rockstar exclusion rules", "proprietary binaries & assets ignored")
    else:
        r.fail(".gitignore Rockstar exclusion rules", "missing: " + ", ".join(missing_ignores))


def check_build_and_manifest(r: Runner) -> None:
    gradle = read_text("app/build.gradle")
    manifest = read_text("app/src/main/AndroidManifest.xml")

    if "useLegacyPackaging = true" in gradle:
        r.ok("Gradle jniLibs legacy packaging", "useLegacyPackaging = true")
    else:
        r.fail("Gradle jniLibs legacy packaging", "missing useLegacyPackaging = true (required for AML/MonetLoader on Android 14+)")

    abis = enabled_abis()
    if "arm64-v8a" in abis and set(abis) <= {"arm64-v8a", "armeabi-v7a"}:
        r.ok("Gradle ABI filter", " + ".join(abis))
    else:
        r.fail("Gradle ABI filter", f"unexpected abiFilters {abis or 'none'} (arm64-v8a is mandatory)")

    if 'ndkVersion "25.1.8937393"' in gradle:
        r.ok("Gradle NDK version pinned", "25.1.8937393")
    else:
        r.fail("Gradle NDK version pinned", 'expected ndkVersion "25.1.8937393"')

    if 'file("google-services.json").exists()' in gradle:
        r.ok("Optional Firebase plugin guard", "conditional on google-services.json")
    else:
        r.fail("Optional Firebase plugin guard", "Firebase plugins should be conditional on google-services.json")

    if "123456" not in gradle:
        r.ok("No hardcoded keystore password in app/build.gradle")
    else:
        r.fail("No hardcoded keystore password in app/build.gradle", "found '123456' in app/build.gradle")

    if 'android:allowNativeHeapPointerTagging="false"' in manifest:
        r.ok("Manifest Android 14+ TBI guard", 'allowNativeHeapPointerTagging="false"')
    else:
        r.fail("Manifest Android 14+ TBI guard", 'missing android:allowNativeHeapPointerTagging="false"')

    if 'android:name="com.sampmobile.game.SAMP"' in manifest and 'android:process=":game"' in manifest:
        r.ok("Manifest isolated :game process")
    else:
        r.fail("Manifest isolated :game process", 'SAMP activity must run in android:process=":game"')

    if "com.android.vending.CHECK_LICENSE" not in manifest:
        r.ok("Manifest Play LVL cleanup", "no CHECK_LICENSE leftover")
    else:
        r.fail("Manifest Play LVL cleanup", "remove legacy com.android.vending.CHECK_LICENSE permission")

    gradlew_bytes = (REPO_ROOT / "gradlew").read_bytes()
    if b"\r\n" not in gradlew_bytes:
        r.ok("gradlew line endings", "LF-only")
    else:
        r.fail("gradlew line endings", "CRLF detected in gradlew (breaks Linux/macOS builds)")

    forbidden_secrets = ["app/keystore.jks", "app/google-services.json"]
    found_secrets = [p for p in forbidden_secrets if (REPO_ROOT / p).exists()]
    if not found_secrets:
        r.ok("No dev secrets in workspace", "keystore.jks / google-services.json absent")
    else:
        r.warn("Dev secret files present locally", ", ".join(found_secrets) + " (ensure not committed)")


def check_anti_regression(r: Runner) -> None:
    audio_cpp = strip_c_comments(read_text("app/src/main/cpp/SAMPMOBILE/Game/AudioStream.cpp"))
    if "BASS_Free(" not in audio_cpp:
        r.ok("AudioStream BASS safety", "no BASS_Free() call")
    else:
        r.fail("AudioStream BASS safety", "BASS_Free() in AudioStream destroys shared Voice channels")

    mod_mgr = read_text("app/src/main/java/com/sampmobile/launcher/utils/ModManager.java")
    if re.search(r"boolean\s+showModsOverlay\s*=\s*false\s*;", mod_mgr):
        r.ok("AML overlay occlusion guard", "showModsOverlay defaults to false")
    else:
        r.fail("AML overlay occlusion guard", "AmlCoreSettings.showModsOverlay must default to false")

    util_cpp = read_text("app/src/main/cpp/SAMPMOBILE/Game/Util.cpp")
    settings_cpp = read_text("app/src/main/cpp/SAMPMOBILE/Settings.cpp")
    if "0x850910" in util_cpp and "0x679474" in util_cpp:
        r.ok("Live GOT widget table guard", "GetTouchWidgets reads live GOT slot")
    else:
        r.fail("Live GOT widget table guard", "missing live GOT offsets (0x850910 / 0x679474) in Game/Util.cpp")

    if "0x851498" in settings_cpp and "0x679A3C" in settings_cpp:
        r.ok("Live GOT MobileSettings guard", "getLiveMobileSettings reads live GOT slot")
    else:
        r.fail("Live GOT MobileSettings guard", "missing live GOT offsets (0x851498 / 0x679A3C) in Settings.cpp")

    scoreboard_cpp = read_text("app/src/main/cpp/SAMPMOBILE/UI/SAMPWidgets/Scoreboard.cpp")
    push_cnt = len(re.findall(r"ImGui::PushID\s*\(", scoreboard_cpp))
    pop_cnt = len(re.findall(r"ImGui::PopID\s*\(", scoreboard_cpp))
    if push_cnt == pop_cnt:
        r.ok("Scoreboard ImGui PushID/PopID balance", f"{push_cnt} == {pop_cnt}")
    else:
        r.fail("Scoreboard ImGui PushID/PopID balance", f"PushID={push_cnt} != PopID={pop_cnt}")

    res_dir = REPO_ROOT / "app/src/main/res"
    empty_res_dirs = [
        str(p.relative_to(REPO_ROOT))
        for p in sorted(res_dir.iterdir())
        if p.is_dir() and not any(p.iterdir())
    ]
    if not empty_res_dirs:
        r.ok("No empty res/ subdirectories")
    else:
        r.fail("No empty res/ subdirectories", ", ".join(empty_res_dirs))


def check_assets(r: Runner, require_gtasa: bool) -> None:
    assets_dir = REPO_ROOT / "app/src/main/assets"
    monet_zip = assets_dir / "monetloader_lib.zip"
    if not monet_zip.is_file():
        r.fail("monetloader_lib.zip integrity", "file missing")
    else:
        try:
            with zipfile.ZipFile(monet_zip, "r") as zf:
                names = set(zf.namelist())
                if "lib/moonloader.lua" in names:
                    r.ok("monetloader_lib.zip integrity", f"{len(names)} entries, has lib/moonloader.lua")
                else:
                    r.fail("monetloader_lib.zip integrity", "missing lib/moonloader.lua")
        except zipfile.BadZipFile as exc:
            r.fail("monetloader_lib.zip integrity", f"corrupt zip: {exc}")

    assetfile = assets_dir / "assetfile.txt"
    if not assetfile.is_file():
        if require_gtasa:
            r.fail("User-extracted GTA:SA APK assets", "missing assetfile.txt (run tools/prepare_gtasa.py)")
        else:
            r.ok("GTA:SA proprietary assets stripped", "not present in clean repo; run tools/prepare_gtasa.py before packaging")
        return

    lines = [ln.strip() for ln in assetfile.read_text(encoding="utf-8").splitlines() if ln.strip()]
    try:
        declared = int(lines[0])
        actual = len(lines) - 1
        if declared == actual:
            r.ok("User-extracted assetfile.txt index", f"{actual} entries")
        else:
            r.fail("User-extracted assetfile.txt index", f"header={declared}, actual={actual}")
    except (ValueError, IndexError):
        r.fail("User-extracted assetfile.txt index", "invalid header count")

    core_assets = [
        "data/handling.cfg",
        "data/TouchDefaultPhoneWidescreen.cfg",
        "Text/american.gxt",
    ]
    missing_core = [c for c in core_assets if not (assets_dir / c).is_file()]
    if not (assets_dir / "data/script/mainV1.scm").is_file() and not (assets_dir / "data/mainV1.scm").is_file():
        missing_core.append("data/script/mainV1.scm")
    if not missing_core:
        r.ok("User-extracted core GTA:SA assets", f"{len(core_assets) + 1} checked")
    else:
        r.fail("User-extracted core GTA:SA assets", "missing: " + ", ".join(missing_core))


def check_jni_bindings(r: Runner) -> None:
    java_root = REPO_ROOT / "app/src/main/java/com/sampmobile"
    cpp_root = REPO_ROOT / "app/src/main/cpp/SAMPMOBILE"

    cpp_blob_parts: List[str] = []
    for p in cpp_root.rglob("*"):
        if p.is_file() and p.suffix in {".cpp", ".h", ".cc", ".hpp"}:
            cpp_blob_parts.append(p.read_text(encoding="utf-8", errors="ignore"))
    cpp_blob = "\n".join(cpp_blob_parts)

    native_decl_re = re.compile(r"\bnative\s+[\w<>\[\], ?]+\s+(\w+)\s*\(")
    pkg_re = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)

    checked = 0
    missing: List[str] = []
    for p in sorted(java_root.rglob("*.java")):
        text = p.read_text(encoding="utf-8", errors="ignore")
        pkg_m = pkg_re.search(text)
        if not pkg_m:
            continue
        pkg = pkg_m.group(1)
        cls = p.stem
        for m in native_decl_re.finditer(text):
            method = m.group(1)
            fq_method = f"{pkg}.{cls}.{method}"
            if fq_method in KNOWN_LEGACY_JAVA_UI_NATIVES:
                continue
            checked += 1
            jni_sym = (
                "Java_"
                + pkg.replace("_", "_1").replace(".", "_")
                + "_"
                + cls.replace("_", "_1")
                + "_"
                + method.replace("_", "_1")
            )
            if jni_sym not in cpp_blob and f'"{method}"' not in cpp_blob:
                missing.append(f"{fq_method} -> {jni_sym}")

    if not missing:
        r.ok("JNI native method bindings", f"{checked} Java native methods verified")
    else:
        r.fail("JNI native method bindings", "missing C++ exports: " + "; ".join(missing))


def check_jnilibs_elf(r: Runner, require_gtasa: bool) -> None:
    jni_root = REPO_ROOT / "app/src/main/jniLibs"
    errors: List[str] = []
    total_checked = 0
    prop_present = 0
    prop_missing: List[str] = []

    abis = enabled_abis()
    for abi, required in REPO_JNILIBS.items():
        if abi not in abis:
            continue
        abi_dir = jni_root / abi
        exp_class, exp_machine = ELF_EXPECTED[abi]
        for lib in required:
            if not (abi_dir / lib).is_file():
                errors.append(f"missing {abi}/{lib}")

        for plib in PROPRIETARY_JNILIBS.get(abi, []):
            if (abi_dir / plib).is_file():
                prop_present += 1
            else:
                prop_missing.append(f"{abi}/{plib}")

        if abi_dir.is_dir():
            for so in sorted(abi_dir.glob("*.so")):
                total_checked += 1
                hdr = so.read_bytes()[:20]
                if len(hdr) < 20 or hdr[:4] != b"\x7fELF":
                    errors.append(f"{abi}/{so.name}: not a valid ELF")
                    continue
                ei_class = hdr[4]
                e_machine = struct.unpack_from("<H", hdr, 18)[0]
                if (ei_class, e_machine) != (exp_class, exp_machine):
                    errors.append(
                        f"{abi}/{so.name}: wrong ELF arch (class={ei_class}, machine={e_machine})"
                    )

    label = "jniLibs ELF integrity (" + ", ".join(enabled_abis() or ["none"]) + ")"
    if not errors:
        r.ok(label, f"{total_checked} .so files verified")
    else:
        r.fail(label, "; ".join(errors))

    if prop_present == 0:
        if require_gtasa:
            r.fail("Rockstar proprietary binaries", "missing: " + ", ".join(prop_missing) + " (run tools/prepare_gtasa.py)")
        else:
            r.ok("Rockstar proprietary binaries stripped", "not present in clean repo")
    elif not prop_missing:
        r.ok("Rockstar proprietary binaries extracted locally", f"{prop_present} files verified")
    elif not require_gtasa and "arm64-v8a/libGTASA.so" not in prop_missing:
        r.warn("Rockstar proprietary binaries (64-bit only)", "missing 32-bit: " + ", ".join(prop_missing))
    else:
        r.fail("Rockstar proprietary binaries incomplete", "missing: " + ", ".join(prop_missing))


def check_hygiene(r: Runner) -> None:
    hits: List[str] = []
    for root, dirs, files in os.walk(REPO_ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for fname in files:
            p = Path(root) / fname
            rel = p.relative_to(REPO_ROOT).as_posix()
            if rel == "local.properties":
                continue
            if p.suffix.lower() not in TEXT_EXTS:
                continue
            try:
                text = p.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            for pat, label in PERSONAL_PATTERNS:
                if pat.search(text):
                    hits.append(f"{rel} ({label})")
                    break

    if not hits:
        r.ok("Repository path hygiene", "no personal machine paths or device serials")
    else:
        r.fail("Repository path hygiene", "; ".join(hits))


def check_apk(r: Runner, apk_path: Path, required: bool, require_gtasa: bool) -> None:
    if not apk_path.is_file():
        if required:
            r.fail("APK artifact check", f"APK not found: {apk_path}")
        else:
            r.warn("APK artifact check", f"skipped ({apk_path} not built yet)")
        return

    try:
        with zipfile.ZipFile(apk_path, "r") as zf:
            names = set(zf.namelist())
    except zipfile.BadZipFile as exc:
        r.fail("APK artifact check", f"corrupt APK: {exc}")
        return

    missing: List[str] = []
    base_entries = ["classes.dex", "AndroidManifest.xml", "assets/monetloader_lib.zip"]
    if require_gtasa:
        base_entries.append("assets/assetfile.txt")
    for base_entry in base_entries:
        if base_entry not in names:
            missing.append(base_entry)

    apk_abis = enabled_abis()
    for abi, libs in REPO_JNILIBS.items():
        if abi not in apk_abis:
            continue
        expected_libs = libs + ["libSAMPMOBILE.so", "libSCAnd.so"]
        if require_gtasa:
            expected_libs += PROPRIETARY_JNILIBS[abi]
        for lib in expected_libs:
            entry = f"lib/{abi}/{lib}"
            if entry not in names:
                missing.append(entry)

    size_mb = apk_path.stat().st_size / (1024 * 1024)
    if not missing:
        r.ok("APK artifact structure", f"{apk_path.name} ({size_mb:.2f} MB), {', '.join(apk_abis)} + assets complete")
    else:
        r.fail("APK artifact structure", "missing entries: " + ", ".join(missing))


def main() -> int:
    parser = argparse.ArgumentParser(description="Zero-device automated self-check for SA-MP Mobile.")
    parser.add_argument(
        "--apk",
        default="app/build/outputs/apk/debug/samp.apk",
        help="Path to APK to verify (default: app/build/outputs/apk/debug/samp.apk)",
    )
    parser.add_argument(
        "--require-apk",
        action="store_true",
        help="Fail if the target APK does not exist",
    )
    parser.add_argument(
        "--require-gtasa",
        action="store_true",
        help="Fail if user-supplied GTA:SA v2.10 binaries/assets are not extracted locally",
    )
    args = parser.parse_args()

    runner = Runner()
    check_compliance(runner)
    check_build_and_manifest(runner)
    check_anti_regression(runner)
    check_assets(runner, require_gtasa=args.require_gtasa)
    check_jni_bindings(runner)
    check_jnilibs_elf(runner, require_gtasa=args.require_gtasa)
    check_hygiene(runner)

    apk_path = Path(args.apk)
    if not apk_path.is_absolute():
        apk_path = REPO_ROOT / apk_path
    check_apk(runner, apk_path, required=args.require_apk, require_gtasa=args.require_gtasa)

    print(f"\nSummary: {runner.passed} passed, {runner.failed} failed, {runner.warnings} warnings.")
    return 0 if runner.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

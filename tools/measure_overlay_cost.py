#!/usr/bin/env python3
"""量化 WebView 覆盖层对游戏帧率/内存的真实开销，用来判断「WebView 到底行不行」。

不靠感觉，靠数字。原理：

  * 帧率 —— `dumpsys SurfaceFlinger --latency <游戏图层>` 拿到每帧的 present 时间戳，
    统计有效帧数 / 采样时长，并给出帧间隔中位数与 p95（卡顿感来自尾部延迟，不是平均值）。
  * 内存 —— 主进程 + WebView 渲染子进程（Android 8+ 是独立的 :sandboxed_processN）分别统计。
  * 面板开 / 关各采一轮，出对比表。

用法（设备已连、游戏已进世界）：

    python tools/measure_overlay_cost.py                    # 自动找设备
    python tools/measure_overlay_cost.py --serial 10ADAE... --seconds 8
    python tools/measure_overlay_cost.py --page bag.html    # 指定用哪个页面压测

需要 debug 包（靠 com.sampmobile.OVERLAY 广播开关面板），非 debug 包请手动开关面板并加 --manual。
"""

from __future__ import annotations

import argparse
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

PKG_DEFAULT = "com.sampmobile.launcher"
ACTION = "com.sampmobile.OVERLAY"

# SurfaceFlinger --latency 里表示「这帧还没落地」的哨兵值
PENDING = (0, 2 ** 63 - 1)


def find_adb() -> str:
    cands = [
        "adb",
        str(Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"),
        "C:/ProgramData/platform-tools/adb.exe",
        "D:/android-sdk/platform-tools/adb.exe",
    ]
    for c in cands:
        try:
            subprocess.run([c, "version"], capture_output=True, timeout=10, check=True)
            return c
        except Exception:
            continue
    sys.exit("找不到 adb，请把 adb 放进 PATH 或用 --adb 指定")


def sh(adb: str, serial: str | None, cmd: str, timeout: int = 60) -> str:
    argv = [adb] + (["-s", serial] if serial else []) + ["shell", cmd]
    try:
        p = subprocess.run(argv, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return ""
    return p.stdout.decode("utf-8", "replace")


def pick_serial(adb: str, want: str | None) -> str:
    out = subprocess.run([adb, "devices"], capture_output=True, timeout=20).stdout.decode()
    serials = [l.split("\t")[0] for l in out.splitlines()[1:] if "\tdevice" in l]
    if want:
        if want not in serials:
            sys.exit(f"设备 {want} 不在线，当前：{serials or '无'}")
        return want
    if not serials:
        sys.exit("没有在线设备。插 USB 并允许调试后再跑。")
    return serials[0]


def game_layer(adb: str, serial: str, pkg: str) -> str | None:
    """从 SurfaceFlinger 图层表里挑出游戏主画面那一层。"""
    out = sh(adb, serial, "dumpsys SurfaceFlinger --list")
    cands = [l.strip() for l in out.splitlines() if pkg in l and l.strip()]
    if not cands:
        return None
    # 优先 SurfaceView / BLAST 里带 #0 的那层，其次最长名字（最具体的那个）
    cands.sort(key=lambda s: ("SurfaceView" not in s, -len(s)))
    return cands[0]


def sample_fps(adb: str, serial: str, layer: str, seconds: float) -> dict:
    sh(adb, serial, f'dumpsys SurfaceFlinger --latency-clear "{layer}"')
    t0 = time.time()
    time.sleep(seconds)
    elapsed = time.time() - t0
    out = sh(adb, serial, f'dumpsys SurfaceFlinger --latency "{layer}"', timeout=90)

    presses: list[int] = []
    for line in out.splitlines()[1:]:          # 第一行是刷新周期
        parts = line.split()
        if len(parts) != 3:
            continue
        try:
            desired, actual, ready = (int(x) for x in parts)
        except ValueError:
            continue
        if actual in PENDING or ready in PENDING or desired == 0:
            continue
        presses.append(actual)

    if len(presses) < 2:
        return {"fps": 0.0, "frames": len(presses), "median_ms": None, "p95_ms": None, "elapsed": elapsed}

    presses.sort()
    span = (presses[-1] - presses[0]) / 1e9
    gaps = [(b - a) / 1e6 for a, b in zip(presses, presses[1:]) if b > a]
    return {
        "fps": (len(presses) - 1) / span if span > 0 else 0.0,
        "frames": len(presses),
        "median_ms": statistics.median(gaps) if gaps else None,
        "p95_ms": (statistics.quantiles(gaps, n=20)[-1] if len(gaps) >= 20 else (max(gaps) if gaps else None)),
        "elapsed": elapsed,
    }


def sample_mem(adb: str, serial: str, pkg: str) -> dict:
    """主进程 + 渲染子进程分开算（WebView 的实际内存大头在子进程）。"""
    out = sh(adb, serial, f"ps -A -o PID,NAME,RSS 2>/dev/null || ps -A")
    procs = []
    total_kb = 0
    for line in out.splitlines():
        if pkg not in line:
            continue
        m = re.match(r"\s*(\d+)\s+(\S+)\s+(\d+)", line)
        if not m:
            continue
        pid, name, rss = int(m.group(1)), m.group(2), int(m.group(3))
        procs.append((name, rss / 1024.0))
        total_kb += rss

    # 兜底：ps 不支持 -o 时用 meminfo 的 TOTAL PSS
    if not procs:
        info = sh(adb, serial, f"dumpsys meminfo {pkg} | grep -E 'TOTAL PSS|TOTAL:'")
        m = re.search(r"(\d[\d,]*)\s*K?B?", info)
        if m:
            total_kb = int(m.group(1).replace(",", ""))
    return {"procs": procs, "total_mb": total_kb / 1024.0}


def broadcast(adb: str, serial: str, extra: str) -> None:
    subprocess.run(
        [adb] + (["-s", serial] if serial else []) + ["shell", "am", "broadcast", "-a", ACTION, "-p", PKG] + extra.split(),
        capture_output=True, timeout=60,
    )


def open_panel(adb: str, serial: str, pkg: str, page: str, w_ratio=0.80, h_ratio=0.72) -> None:
    size = sh(adb, serial, "wm size")
    m = re.search(r"(\d+)x(\d+)", size)
    sw, sh_ = (int(m.group(1)), int(m.group(2))) if m else (2388, 1080)
    w, h = int(sw * w_ratio), int(sh_ * h_ratio)
    x, y = (sw - w) // 2, (sh_ - h) // 2
    # 先登记客户端（重装/重连后 PVar 会丢），再开页面
    broadcast(adb, serial, f"--es cmd /wvm")
    time.sleep(1.5)
    broadcast(adb, serial, f"--es url http://127.0.0.1:1/{page} --ei mode 1 --ei x {x} --ei y {y} --ei w {w} --ei h {h}")


def report(tag: str, fps: dict, mem: dict) -> None:
    med = f"{fps['median_ms']:.1f}" if fps["median_ms"] else "-"
    p95 = f"{fps['p95_ms']:.1f}" if fps["p95_ms"] else "-"
    print(f"  {tag:<8} fps={fps['fps']:6.1f}  中位={med:>6} ms  p95={p95:>6} ms  样本={fps['frames']:<4} 内存={mem['total_mb']:6.1f} MB")
    for name, mb in mem["procs"]:
        print(f"           └ {name:<45} {mb:7.1f} MB")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb")
    ap.add_argument("--serial")
    ap.add_argument("--package", default=PKG_DEFAULT)
    ap.add_argument("--page", default="index.html")
    ap.add_argument("--seconds", type=float, default=8.0)
    ap.add_argument("--manual", action="store_true", help="非 debug 包：手动开关面板，脚本只在两轮之间暂停")
    args = ap.parse_args()

    adb = args.adb or find_adb()
    serial = pick_serial(adb, args.serial)

    layer = game_layer(adb, serial, args.package)
    if not layer:
        sys.exit("找不到游戏画面图层，确认游戏已进入世界（不是启动器/加载页）")
    print(f"设备 {serial}\n图层 {layer}\n采样 {args.seconds:.0f}s × 2 轮\n")

    print("① 面板关闭 …")
    closed_fps = sample_fps(adb, serial, layer, args.seconds)
    closed_mem = sample_mem(adb, serial, args.package)
    report("关闭", closed_fps, closed_mem)

    if args.manual:
        input("\n请手动打开面板（保持游戏在跑），回车继续 …")
    else:
        print("\n② 打开面板 …")
        open_panel(adb, serial, args.package, args.page)
        time.sleep(3.0)

    open_fps = sample_fps(adb, serial, layer, args.seconds)
    open_mem = sample_mem(adb, serial, args.package)
    report("打开", open_fps, open_mem)

    if not args.manual:
        sh(adb, serial, "input keyevent 4")

    df = open_fps["fps"] - closed_fps["fps"]
    dm = open_mem["total_mb"] - closed_mem["total_mb"]
    print("\n=== 结论 ===")
    print(f"  帧率  {closed_fps['fps']:.1f} → {open_fps['fps']:.1f}  ({df:+.1f})")
    if closed_fps["p95_ms"] and open_fps["p95_ms"]:
        print(f"  p95   {closed_fps['p95_ms']:.1f} → {open_fps['p95_ms']:.1f} ms")
    print(f"  内存  {closed_mem['total_mb']:.1f} → {open_mem['total_mb']:.1f} MB  ({dm:+.1f})")
    if abs(df) < 2 and abs(dm) < 30:
        print("  → 开销可以忽略：问题不在 WebView 本身，去找页面/交互层的原因")
    elif df < -5:
        print("  → 确实在掉帧：优先缩面板尺寸、关掉 CSS 动画/滤镜，再考虑换渲染方案")
    else:
        print("  → 主要是内存/进程开销：面板用完即关，或改原生 View")


if __name__ == "__main__":
    main()

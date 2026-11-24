# 没有独立 APK 时，从手机已安装的整合包重建可运行客户端

适用场景：手里只有一台**已经装好并能玩的 SA-MP 手机端**（本文实测包名 `com.samp.game`，
Alyn / SA-MP Mobile 方案），找不到 `gtasa_2.10.apk`，手里只有 PC 版 GTA:SA。

实测结论：**不需要任何 APK 文件**。已安装游戏的 `base.apk` 在 `/data/app/.../` 下权限 644，
`adb pull` 可直读；官方游戏数据在 `/sdcard/Android/obb/<pkg>/*.obb`。

---

## 0. 前提

| 项 | 值（本文实测环境） |
|---|---|
| 设备 | vivo V2312A，arm64-v8a，Android 13 |
| 整合包 | `com.samp.game` v10.1.1，base.apk 112 MB |
| OBB | `main.8.com.samp.game.obb` 1.83 GB + `patch.8.com.samp.game.obb` 596 MB |
| PC 侧 | `D:/GTASA`（含 SA-MP 0.3.7 的 `SAMP.img` / `SAMP.ide` / `SAMPCOL.img`） |
| 工程 | `D:/samp-mobile`（ASCII 路径，AGP 8.8 不接受中文路径） |

关键校验：整合包里的 `lib/arm64-v8a/libGTASA.so` SHA-256 = `98726dd7...`，
与官方 2.10 一致 —— 说明它是基于官方库改包名的整合版，可直接用于本工程。

---

## 1. 取库 + 官方轻量资产

```bash
ADB="/c/ProgramData/platform-tools/adb -s <serial>"
export MSYS_NO_PATHCONV=1        # 否则 Git Bash 会把 /sdcard/... 改写成 Windows 路径

# 1.1 拉 base.apk
mkdir -p dist/phone/apks
$ADB pull "/data/app/~~xxx/com.samp.game-yyy==/base.apk" dist/phone/apks/
```

提取 `libGTASA.so` 与 APK 内 `assetfile.txt` 声明的官方资产：

```bash
python - <<'PY'
import zipfile, hashlib
from pathlib import Path
apk = Path("dist/phone/apks/xxx_base.apk")
with zipfile.ZipFile(apk) as z:
    names = set(z.namelist())
    dst = Path("app/src/main/jniLibs/arm64-v8a/libGTASA.so")
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(z.read("lib/arm64-v8a/libGTASA.so"))
    print("sha256", hashlib.sha256(dst.read_bytes()).hexdigest()[:16])   # 应为 98726dd7516f5a07

    lines = [l.strip() for l in z.read("assets/assetfile.txt").decode("utf-8","replace").splitlines() if l.strip()]
    entries = lines[1:]
    for rel in entries:
        src = f"assets/{rel}"
        if src not in names:
            print("absent from apk:", rel); continue
        out = Path("app/src/main/assets")/rel
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(z.read(src))
PY
```

**坑 1**：整合包的 `assetfile.txt` 头写 `125` 但实际列了 `126` 条。
`NvAPKFileHelper.GetAssetList()` 是 `new String[header]` 然后一直读到 EOF，
第 126 条会 `ArrayIndexOutOfBoundsException`（异常被 catch 吞掉，静默退化成慢速目录遍历）。
修正：把首行改成实际条目数。

**坑 2**：`data/handling.cfg` 不在 APK 里（被挪到外部）。从
`/sdcard/Android/data/<pkg>/files/SAMP/handling.cfg` 拉一份补上，否则车辆物理参数缺失。

---

## 2. 拉 OBB 并构建 files/

两个 OBB 内部**没有 `assets/` 前缀**，直接是 `texdb/`、`anim/`、`data/`、`audio/` ——
正好是 `build_gamedata.py` 期望的布局，无需改脚本。

```bash
$ADB pull /sdcard/Android/obb/com.samp.game/main.8.com.samp.game.obb  dist/phone/obb/
$ADB pull /sdcard/Android/obb/com.samp.game/patch.8.com.samp.game.obb dist/phone/obb/

python tools/build_gamedata.py \
  --gtasa dist/phone/obb/main.8.com.samp.game.obb dist/phone/obb/patch.8.com.samp.game.obb \
  --pc-samp "D:/GTASA" \
  --out dist/gamedata/files
# => 1,034.86 MB：anim 269M / texdb 736M / data 12.5M / audio 15.8M / SAMP 0.59M
```

---

## 3. 三处必须人工修正（否则跑不起来 / 没声音 / 没中文）

### 3.1 `main.scm` —— 不换必崩 SIGSEGV

`build_gamedata.py` 默认生成的是 **clean-room 空白 SCM**（4,325 字节），
客户端加载到 `Initiating scripting...` 后立刻在 `libGTASA.so+0x339a74` 崩：

```
F/SAMPMOBILE: fatal signal 11 (code 1), fault addr 0x0, pc 0x...74 (libGTASA.so+0x339a74), lastCmd 0x0446
```

必须换成真实 SA-MP 客户端的 `main.scm`（96,997 字节）+ `script.img`（184,320 字节）：

```bash
$ADB pull /sdcard/Android/data/com.samp.game/files/SAMP/. dist/phone/samp_ref/
cp -f dist/phone/samp_ref/* dist/gamedata/files/SAMP/
```

或者用官方 SA-MP 0.3.7 客户端的脚本目录：`build_gamedata.py --scm-dir <dir>`。

### 3.2 音频 —— 不补则全程无声

默认只带最小 `audio/CONFIG`。游戏会报：

```
E/SAMPMOBILE: OS_FileOpen failed (1): AUDIO\SFX\SPC_NA.osw
E/SAMPMOBILE: OS_FileOpen failed (1): AUDIO\STREAMS\AMBIENCE.osw
```

补全 `audio/`（1.13 GB，59 个文件）。可从 OBB 解压，或构建时加 `--keep-audio`：

```bash
python - <<'PY'
import zipfile
from pathlib import Path
obb = Path("dist/phone/obb/main.8.com.samp.game.obb"); dst = Path("dist/gamedata/files")
with zipfile.ZipFile(obb) as z:
    for i in z.infolist():
        if i.filename.startswith("audio/") and not i.is_dir():
            out = dst/i.filename; out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(z.read(i))
PY
```

### 3.3 中文字体 —— 不换中文变方块

`SAMP/fonts/chinese.ttf` 默认是 Roboto 顶替。换真中文字体（本项目自用取系统 simhei；
要合规分发请换 Noto Sans SC，OFL）：

```bash
cp /c/Windows/Fonts/simhei.ttf dist/gamedata/files/SAMP/fonts/chinese.ttf
```

---

## 4. ABI 只有 64 位

整合包只带 `arm64-v8a` 的 `libGTASA.so`。`app/build.gradle` 的 `abiFilters` 已收敛为单一
`arm64-v8a`（保留 32 位会打出一个缺库、dlopen 必崩的包）。
`verifyGtasaAssets` 同步改成按 `abiFilters` 实际值校验。

---

## 5. 把 2.2 GB 数据放进手机

`adb push` 到 `Android/data/<pkg>/` **会失败**：
`remote secure_mkdirs failed: Operation not permitted`。这是 Android 11+ 的 FUSE/MediaProvider
限制，不是权限没给对。

- `run-as <pkg>` 也不行：`runas_app` SELinux 域连 `/sdcard/Android` 都 stat 不了（Permission denied）。
- **可行**：`adb shell`（uid 2000，在 `ext_data_rw` 组）可以正常 mkdir/写入，
  所以打一个 tar 过去、在设备上解压即可。脚本：`tools/push_gamedata_to_device.py`

```bash
python tools/push_gamedata_to_device.py --serial <serial>
```

**坑 3（大小写）**：`/sdcard` 在 FUSE 层大小写不敏感。App 首启会自己建 `audio/sfx`、
`audio/streams`（小写、属主 app、权限 770），你的 tar 里是 `audio/SFX` → 命中同一目录 →
shell 写不进去，toybox tar 报 `bad symlink`。先删掉这两个空目录再解压，或直接先
`force-stop` 再 `rm -rf files/audio` 后整体解压。

**坑 4（属主）**：shell 解压出来的文件属主是 `shell`、组 `ext_data_rw`，而 App 进程
**不在 `ext_data_rw` 组**（`groups` 里没有 1078）。目录权限 `drwxrws---` 会让 App 拿到
`EACCES`（现象：`SAMP/settings.json.tmp: open failed: EACCES`）。解压后统一
`chmod -R 777 <target>` 即可（`files` 目录本身可能已被 App 接管，chmod 会失败，无害）。

---

## 6. 构建 + 安装

```bash
export JAVA_HOME="D:/服务器/DAYZSA/_build_env/jdk/jdk-21.0.2"
./gradlew.bat --no-daemon assembleDebug
# => app/build/outputs/apk/debug/samp.apk   37.04 MB (arm64-v8a)
```

vivo 机型 `adb install` 会报 `INSTALL_FAILED_ABORTED: User rejected permissions`，
需要在 `开发者选项 → USB 安装` 打开，或者直接把 APK 推到 `/sdcard/Download/` 手动点安装：

```bash
$ADB push app/build/outputs/apk/debug/samp.apk /sdcard/Download/
```

自检：`python tools/selfcheck.py --require-gtasa` → 29 passed / 0 failed。

---

## 7. 验证结果

启动 → 单机模式 → 进入世界（约 5 秒），日志应出现：

```
I/SAMPMOBILE: Initializing pools size...
I/SAMPMOBILE: Loading peds.ide..  /  Loading gta.dat..  /  Loading mainV1.scm..
I/SAMPMOBILE: hook fired: CGame::Process
I/SAMPMOBILE: Initiating scripting...
```

画面左上角提示 `GTA: SA v2.10 (arm64-v8a) started`。
连服（局域网 `192.168.0.117:7777`）后聊天区出现服务器消息、HUD 显示 `Hunger` / `Thirst`，
中文正常，管理员权限被正确识别。

---

## 8. 磁盘占用

| 位置 | 大小 |
|---|---|
| `dist/phone/`（拉下来的原始素材） | 3.6 GB（apk 112M + obb 2.46G + 参考 SAMP） |
| `dist/gamedata/files/`（构建产物） | 2.2 GB（含完整音频） |
| 手机 `Android/data/com.sampmobile.launcher/files/` | 2.2 GB |

不要的中间产物可直接删 `dist/phone/`（已在 `.gitignore`），需要时按第 1、2 步重拉。

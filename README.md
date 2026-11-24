# SA-MP Mobile

面向 **GTA: San Andreas Android v2.10** 的 Android 原生 SA-MP 联机客户端（Java 启动器 + C++ 原生库 `libSAMPMOBILE.so`），支持 `arm64-v8a` 与 `armeabi-v7a` 双架构构建。

> 版权、第三方组件与合规声明请参阅 [NOTICE](NOTICE)。

---

## 项目简介

本项目基于开源的 Alyn SA-MP Mobile 架构作为基础，重新实现 Android 端启动器、修复了大量底层崩溃与兼容性问题、改进、修复、完善了大量内容，优化和修复正确性、稳健性等问题，并提供mod加载支持（五合一模组生态），已经过一定的实机测试，如果你发现新的问题可以联系本人。

不定期维护更新，将一直保持开放、透明，给大家提供安全、稳定、好用的联机器，任何人都可以拿去构建、修改、fork，但衍生版本也须以同样条款开源，仓库不含任何游戏资源与商用字体，运行文件由用户本人的游戏中提取。没有任何隐藏、内部功能。

---

## 需要准备的资源总览

出于版权合规要求，本仓库**仅包含源码与开源依赖**，不分发任何 Rockstar 专有二进制、游戏数据包或商业字体。自行编译并运行完整客户端前，请先准备以下外部资源：

| 资源 | 用途 | 是否必需 | 使用方式 |
|---|---|---|---|
| **GTA:SA Android v2.10 安装包**（~26 MB 标准 APK、Split APK，或 2 GB+ 免 OBB 完整整合 APK） | 提取 `libGTASA.so`、`libImmEmulatorJ.so` 及原版 APK 内置轻量资产（传入 2 GB+ 完整 APK 时会自动跳过大资源，不会撑爆安装包） | 编译可运行 APK 必需 | 传给 `tools/prepare_gtasa.py --apk`（或用 `--from-adb` 从已安装单机版的设备拉取） |
| **GTA:SA Android v2.10 完整游戏数据**（配套 `main`/`patch` `.obb`、2 GB+ 含资源完整 `.apk`，或已解包 `files/` 目录） | 提取单机版 ~2.4 GB 的地图、模型、动画、配置与基础贴图库（普通 ~26 MB APK 不含这些资源，必须配 `.obb`；若已是 2 GB+ 免 OBB 完整 APK，则同一份 APK 即可直接复用于此步；`.xapk`/`.apks` 先解压即可） | 构建手机端 `files/` 数据包必需 | 传给 `tools/build_gamedata.py --gtasa`（支持同时传入多个 `.obb` / `.apk`） |

> **手里没有独立 APK 文件？** 只要游戏已安装且能在手机上跑通，插上 USB 开启调试后执行
> `python tools/pull_gamedata_from_device.py --pc-samp "D:/GTASA"`
> 即可自动完成「拉 APK → 提取 `libGTASA.so` → 找 `obb`/`Android/data` 数据 → 生成 `files/`」全流程，
> 不需要任何下载器或第三方整合包。详见 `tools/README.md`。
>
> 若手里的是**第三方整合包**（包名不是 `com.rockstargames.gtasa`），或构建后进游戏闪退/无声/中文方块，
> 直接看 [`PHONE_BOOTSTRAP.md`](PHONE_BOOTSTRAP.md) —— 那里记录了实测走通的完整命令，以及
> `main.scm` 必须替换（否则 SIGSEGV）、音频必须补全、`adb push` 到 `Android/data` 的绕行方案等四个坑。
| **PC 版 GTA:SA + 官方 SA-MP 0.3.7** 安装目录 | 提供 `SAMP/SAMP.img`、`SAMP.ide`、`SAMPCOL.img` 与 PC TextDraw 贴图 | 构建手机端 `files/` 数据包必需 | 传给 `tools/build_gamedata.py --pc-samp`（无需第三方手机整合包） |
| **开源中文字体 `.ttf`**（如 Noto Sans SC） | 生成 `SAMP/fonts/chinese.ttf` 以显示中文聊天/文本 | 可选（中文服建议准备） | 传给 `tools/build_gamedata.py --cjk-font`（省略时默认使用仓库自带的 Roboto） |

---

## 编译环境要求

| 工具 / 组件 | 要求版本 | 说明 |
|---|---|---|
| **JDK** | **17 – 21**（推荐 JDK 21 / Android Studio 内置 JBR） | Gradle 8.13 不支持 JDK 25+（会报 `Unsupported class file major version 69`） |
| **Android SDK** | `compileSdk 35` / `targetSdk 35` / `minSdk 24` | 需安装 Platform 35 与 Build-Tools 35 |
| **Android NDK** | **`25.1.8937393`** | 已在 `app/build.gradle` 中固定版本 |
| **CMake** | **`3.22.1`**（或兼容版本） | 可在 Android Studio SDK Manager 中安装 |
| **Gradle** | **8.13**（仓库自带 Wrapper） | 无需手动安装全局 Gradle |
| **Python 3** | 3.10+ | 运行 `tools/` 下脚本；其中 `build_gamedata.py` 需额外安装 `numpy` |

---

## 构建步骤

### 1. 克隆仓库并配置 `local.properties`

```bash
git clone https://github.com/XiaoNiaoa/samp-mobile.git
cd samp-mobile
cp local.properties.example local.properties
```

编辑 `local.properties`，填入本机 Android SDK 路径（用 Android Studio 打开项目也会自动生成）：

```properties
# Windows 示例
sdk.dir=C\:\\Users\\<YourUser>\\AppData\\Local\\Android\\Sdk

# macOS / Linux 示例
# sdk.dir=/Users/<YourUser>/Library/Android/sdk
```

### 2. 提取 GTA:SA Android v2.10 运行时文件

使用内置脚本提取 Rockstar 专有库与 APK 资产（提取目标路径均已在 `.gitignore` 中排除）：

```bash
# 方式 A：从本地 APK 文件或 Split-APK 目录提取
python tools/prepare_gtasa.py --apk /path/to/gtasa_2.10.apk

# 方式 B：从已安装 com.rockstargames.gtasa 的 ADB 设备/模拟器直接拉取
python tools/prepare_gtasa.py --from-adb

# 如需清理本地提取的专有文件
python tools/prepare_gtasa.py --clean
```

### 3. 编译 APK

**Debug 包**（无需任何签名配置或 `google-services.json`）：

```powershell
# Windows (PowerShell) — 若系统默认 JDK >= 25，先指定 JDK 17–21
# $env:JAVA_HOME = "C:\Path\To\jdk-21"
.\gradlew.bat assembleDebug
```

```bash
# macOS / Linux
./gradlew assembleDebug
```

输出路径：`app/build/outputs/apk/debug/samp.apk`。（若仅做源码/C++ 编译检查而不打包完整运行包，可加 `-PskipGtasaCheck=true`。）

**Release 包（可选）**：在 `local.properties` 或环境变量中提供签名信息（切勿提交密钥文件）：

```properties
SAMP_STORE_FILE=path/to/your-release.jks
SAMP_STORE_PASSWORD=your_store_password
SAMP_KEY_ALIAS=your_key_alias
SAMP_KEY_PASSWORD=your_key_password
```

然后执行 `./gradlew assembleRelease`。

**Firebase Crashlytics（可选）**：默认不启用。如需接入自己的 Firebase 项目，参考 `app/google-services.json.example` 放置 `app/google-services.json`，构建脚本会自动检测并启用相关插件。

### 4. 构建手机端精简游戏数据包

准备好 GTA:SA 2.10 完整数据与 PC 版 SA-MP 0.3.7 目录后，运行：

```bash
# 情况 A：标准拆分版（传入 main/patch .obb，若缺少 data/ 可一并传入小 .apk）
python tools/build_gamedata.py \
  --gtasa /path/to/main.obb /path/to/patch.obb \
  --pc-samp "/path/to/GTA San Andreas" \
  --cjk-font /path/to/NotoSansSC-Regular.ttf \
  --out dist/gamedata/files

# 情况 B：2 GB+ 免 OBB 完整单文件 APK（与步骤 2 共用同一份 .apk）
python tools/build_gamedata.py \
  --gtasa /path/to/gtasa_2.10_full.apk \
  --pc-samp "/path/to/GTA San Andreas" \
  --cjk-font /path/to/NotoSansSC-Regular.ttf \
  --out dist/gamedata/files
```

脚本会自动完成以下处理，输出可直接放入应用 `files/` 目录的精简数据包（默认约 1.05 GB，不含音频流）：
- 从官方 GTA:SA 2.10 提取单格式（ETC / PVR）基础贴图库、模型、动画与配置；
- 将 PC 版 `SAMP/SAMP.img` 自动拆分为移动版模型包 `texdb/samp.img` 与带完整 Mipmap 链、大小写别名的贴图库 `texdb/samp/`；
- 从 PC 版 `models/txd/` 等提取 TextDraw 贴图到 `texdb/samp_extra/`；
- 生成最小空白 `SAMP/main.scm`、空 `SAMP/script.img` 与开源字体（如需保留原版音频可加 `--keep-audio`，如需替换脚本可加 `--scm-dir`）。

---

## 零设备自动校验与单元测试

不连接真机或模拟器即可运行以下本地校验：

1. **双 ABI 编译期结构体校验**：`app/src/main/cpp/SAMPMOBILE/AbiGuards.cpp` 在编译 `arm64-v8a` / `armeabi-v7a` 时校验指针宽度、RakNet 类型、SA-MP 同步包结构体大小及 GTA:SA 核心引擎字段偏移。
2. **JUnit 单元测试**：

   ```bash
   ./gradlew testDebugUnitTest
   ```

3. **Python 静态与产物自检**（仅依赖 Python 3 标准库）：

   ```bash
   # 仅校验开源仓库状态（无需提前提取 GTA:SA 专有文件）
   python tools/selfcheck.py

   # 提取并编译后校验完整产物
   python tools/selfcheck.py --require-gtasa --require-apk
   ```

---

## 常见问题

- **报错 `Unsupported class file major version 69`**：当前 `JAVA_HOME` 指向了 JDK 25+，请切换到 JDK 17–21 后重试。
- **Android 14+ 加载 AML / MonetLoader 异常**：`app/build.gradle` 已配置 `packaging.jniLibs.useLegacyPackaging = true`，且 `AndroidManifest.xml` 已设置 `android:allowNativeHeapPointerTagging="false"`，请勿移除。
- **首次下载 Gradle 依赖较慢**：`settings.gradle` 已在官方仓库之后配置镜像回退。

---

## 许可证与免责声明

本项目原始及修改代码采用 **GNU General Public License v3.0** 开源发布，完整协议见 [LICENSE](LICENSE)，第三方组件许可证清单与原文见 [third_party/licenses/](third_party/licenses/THIRD_PARTY_NOTICES.md)。

本项目基于开源的 Alyn SA-MP Mobile 架构继续开发，保留原有文件归属声明。本项目与 Rockstar Games、Take-Two Interactive 或 SA-MP 官方团队无任何关联，不分发任何 Rockstar 专有二进制、游戏数据包或商业字体，详情请参阅 [NOTICE](NOTICE)。

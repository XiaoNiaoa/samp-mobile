# tools/

项目构建准备、数据包裁剪与自动自检脚本目录。

| 脚本 | 用途 |
|---|---|
| `prepare_gtasa.py` | 构建前从用户自备的 GTA:SA Android v2.10 APK（`--apk`）或已安装单机版的 ADB 设备/模拟器（`--from-adb`）提取 `libGTASA.so`、`libImmEmulatorJ.so` 与 GTA APK 资产到本地（已 `.gitignore`），支持 `--clean` 一键清除 |
| `push_gamedata_to_device.py` | 把 `dist/gamedata/files` 装进手机 `Android/data/<包名>/files`。`adb push` 在 Android 11+ 会因 FUSE 的 `secure_mkdirs` 限制失败，故改走「打 tar → push 单文件 → 设备端 toybox tar 解压」。支持 `--src` / `--package` / `--serial` / `--keep-tar` |
| `selfcheck.py` | 零设备跨平台自动自检脚本（Python 3 标准库），校验合规与许可证文件、构建/Manifest 底线、反回归红线、JNI 导出对齐、`abiFilters` 启用的 ABI 的 `jniLibs` ELF 头、资产完整性、路径卫生与 APK 产物结构（可选 `--require-gtasa` / `--require-apk`） |
| `build_gamedata.py` | 仅需官方 GTA:SA Android 2.10（`--gtasa`）与官方 PC GTA:SA + SA-MP 0.3.7（`--pc-samp`），全自动拆分转换并生成干净精简的移动端 `files/` 数据包（含 `texdb/samp/`、`samp_extra/`、开源字体与纯净空白 `main.scm`） |
| `pull_gamedata_from_device.py` | **没有 APK 文件时的首选**：从已安装游戏的手机一键取全部素材。拉全部 APK/split → 交 `prepare_gtasa.py` 提 `libGTASA.so` → 依次探测 APK 内置 `assets/texdb`、`/sdcard/Android/obb/<pkg>/*.obb`、`/sdcard/Android/data/<pkg>/files` → 自动串 `build_gamedata.py --pc-samp`。支持 `--skip-libs` / `--skip-data` / `--skip-build` / `--extra-remote` |

# WebView 面板联调包（2026-11-22）

手机端 `samp-mobile` 加了 WebView 覆盖层，服务端 `dayz` 加了对应的最小测试。
两侧都已编译通过。

---

## 一、代码落在哪

| 位置 | 说明 |
|---|---|
| `D:\samp-mobile` | 手机端工程（真正参与编译的副本） |
| `D:\服务器\DAYZSA\samp-mobile` | 指向 `D:\samp-mobile` 的目录联接，两个路径都能用 |
| `D:\服务器\DAYZSA\SAMP版-openmp` | 服务端工程 |

`D:\samp-mobile` 必须用纯 ASCII 路径：AGP 8.8 会直接拒绝含中文的工程路径
（`Your project path contains non-ASCII characters`），加 `android.overridePathCheck=true`
只是压掉报错，NDK/CMake 后面照样会出问题。

---

## 二、手机端改了哪些文件

| 文件 | 改动 |
|---|---|
| `SAMP.java` | 8 处补丁（字段/预热/返回键/onResume/onPause/onDestroy/按键隔离），脚本自动打的，备份 `SAMP.java.bak_webview` |
| `UI.java` | `showDialog()` 里拦 `#WV#` 标题，转给覆盖层，不弹原生对话框 |
| `ui/WebOverlay.java` | 覆盖层本体；新增 `handleServerDialog` / `dispatchServerEvent` / `greetServerIfNeeded`，以及 `prepareWebRoot` / `reload` / `webRootDir`（外部页面目录 + 本地服务） |
| `ui/WebRootServer.java` | **新增**：只绑 127.0.0.1 的极简静态服务器，把 `files/web/` 通过 http 提供给 WebView |
| `ui/WebBridge.java` | `window.samp` 的 JS 接口，未改动 |
| `assets/web/index.html` | 商品表改成和服务端一致的 4 件，新增 `onServerEvent` |
| `AndroidManifest.xml` | 加 `android:usesCleartextTraffic="true"`，备份 `.bak_wv` |
| `AbiGuards.cpp` | **上游缺一行 include，不加编不过**（见第五节） |

---

## 三、协议

### 服务端 → 客户端

借用 `ShowPlayerDialog` 当传输通道，标题固定 `#WV#`，消息体 `ACTION|PAYLOAD`：

| ACTION | PAYLOAD 示例 | 行为 |
|---|---|---|
| `OPEN` | `{"bal":1250,"p":[40,120,15,260]}` | 面板模式打开，下发余额与价格 |
| `SYNC` | `{"bal":1210}` | 只更新余额，不重载页面 |
| `TOAST` | `{"t":1}` | 轻提示，`t=1` 余额不足 / `t=2` 背包满 |
| `CLOSE` | — | 关闭面板 |
| `OPENURL` | `{"u":"bag.html","w":80,"h":72}` | 打开 `files/web/` 下任意页面，`w`/`h` 是屏幕百分比 |

PAYLOAD 必须是**纯 ASCII**：客户端拿到对话框文本会按 CP1251 → UTF-8 转码，
而服务端源码是 GBK，中文会变乱码。商品名一律写死在页面里，服务端只发 id 和价格。
（这也顺带保证了价格权威在服务端，改前端没用。）

### 客户端 → 服务端

`window.samp.command('/wvbuy 1')` → `UI._keyboardSend()` → `Chat::keyboardEvent()`
→ `pNetGame->SendChatCommand()`。不需要打开聊天框，服务端普通命令就能收到。

| 命令 | 作用 |
|---|---|
| `/wvm` | 客户端首次打开面板时自动发，登记"这是手机端"，服务端收到后立刻推一次 `OPEN` |
| `/wvbuy <1-4>` | 购买，价格/发货全在服务端算 |
| `/wvpage <页面>` | 打开 `files/web/` 下任意页面，例：`/wvpage bag.html` |
| `/wvclose` | 面板关闭通知（只打日志） |

---

## 四、页面从哪来（加面板不用重编 APK）

### 加载顺序

```
files/web/index.html          ← 优先：外部存储，可随意改/加
  ↑ 不存在时
assets/web/index.html         ← 兜底：APK 内置，只在首次启动复制过去当种子
```

启动时 `WebOverlay.prepareWebRoot()`：

1. 取 `/sdcard/Android/data/com.sampmobile.launcher/files/web/`
2. 目录里没有 `index.html` 就从 assets 复制一份（**有就绝不覆盖**）
3. 起一个只绑 `127.0.0.1` 的静态服务（随机端口），入口变成 `http://127.0.0.1:<port>/index.html`

为什么不用 `file://`：WebView 下 `file://` 受同源策略限制，`fetch`/ES module/`localStorage`
都有坑，部分机型读 `/sdcard` 直接失败。换成 loopback http 后页面就是一个正常源，相对路径、
模块、缓存控制全部正常。服务只绑 127.0.0.1（不开局域网），只处理 `GET`/`HEAD`，`..` 穿越直接拒。

### 加一个新面板（实测流程，全程不碰 APK）

```bash
# 1. 写页面（放在 files/web/ 下，可引用同目录的 css/js/图片）

# 2. 推上去
adb push bag.html /sdcard/Android/data/com.sampmobile.launcher/files/web/bag.html
adb shell chmod 644 /sdcard/Android/data/com.sampmobile.launcher/files/web/bag.html

# 3. 让服务端打开它
adb shell am broadcast -a com.sampmobile.OVERLAY -p com.sampmobile.launcher \
  --es cmd '/wvpage bag.html'
```

服务端代码只有一行：

```pawn
WV_OpenUrl(playerid, "bag.html");   // 还可带参数：WV_OpenUrl(playerid, "bag.html", 90, 85)
```

### 改已有页面

```bash
adb push index.html /sdcard/Android/data/com.sampmobile.launcher/files/web/index.html
adb shell am broadcast -a com.sampmobile.OVERLAY -p com.sampmobile.launcher --es action reload
```

`reload` 带时间戳参数，配合服务端的 `Cache-Control: no-store`，改完立刻生效。
面板 footer 会显示 `http` 或 `assets`，一眼看出页面来自外部目录还是 APK 内置。

### 什么时候仍然要重编 APK

| 改动 | 重编 APK | 重编 amx |
|---|---|---|
| 加 / 改页面（html/css/js） | 不要 | 不要 |
| 用现成 `OPENURL` 打开新页面 | 不要 | 不要 |
| 加新 action 语义（`OPENURL` 之外） | 要 | 要 |
| 改面板默认尺寸/位置 | 要 | 不要 |
| 改协议字段（`WV_Push` 的 JSON） | 不要（页面自己解析） | 要 |
| 要"出厂自带"（重装 APK 后还在） | 要（放进 `assets/web/`） | 不要 |

### 调试广播速查（仅 debug 包）

| 参数 | 作用 |
|---|---|
| `--es url <url> --ei mode 1 --ei x --ei y --ei w --ei h` | 打开任意 URL 到指定矩形 |
| `--es cmd '/wvpage bag.html'` | 直接发一条聊天命令，绕开手机输入法 |
| `--es action reload` | 热重载当前页面 |

> `--es cmd` 是专门为测试加的：`adb shell input text` 在中文输入法下会吞字符
> （实测 `/wvpage bag.html` 只进去 `ml`），走命令通道就不用敲键盘。

---

## 五、怎么测

1. 服务端起服（已编译好，`gamemodes\dayz.amx` 4,267,437 B）。
2. 手机装 `dist\samp-debug-wv.apk`。
3. debug 包可以直接用广播拉面板，不用先改启动器：

```bash
adb shell am broadcast -a com.sampmobile.OVERLAY \
  --es url "file:///android_asset/web/index.html" \
  --ei mode 1 --ei x 60 --ei y 200 --ei w 780 --ei h 520
```

4. 面板一出来会自动发 `/wvm`，服务端控制台应出现：

```
[WV] <玩家名>[<id>] 登记手机端
[WV] 推送面板 <玩家名>[<id>] 余额=xxx
```

5. 点「购买」，服务端应出现：

```
[WV] 购买 <玩家名>[<id>] #1 item=31 x1 花费=40 余额=xxx
```

余额会通过 `SYNC` 推回页面底部状态栏。背包满 / 钱不够会收到 `TOAST`。

---

## 六、编译这件事（重要）

### 环境

| 组件 | 版本 | 位置 |
|---|---|---|
| JDK | 21.0.2 | `D:\服务器\DAYZSA\_build_env\jdk\jdk-21.0.2` |
| Android SDK | platform-35 / build-tools 35.0.0 / platform-tools | `D:\android-sdk` |
| NDK | 25.1.8937393 | 同上 |
| CMake | 3.22.1 | 同上 |
| Gradle | 8.13（wrapper） | `~\.gradle\wrapper\dists`（腾讯镜像预置） |

### 命令

```powershell
$env:JAVA_HOME = "D:\服务器\DAYZSA\_build_env\jdk\jdk-21.0.2"
$env:GRADLE_OPTS = "-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=55282 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=55282"
cd D:\samp-mobile
.\gradlew.bat --no-daemon assembleDebug -PskipGtasaCheck=true
```

`-PskipGtasaCheck=true` 是必须的：仓库不含 Rockstar 的 `libGTASA.so`，
不打这个开关会卡在 `verifyGtasaAssets`。原生库是 `dlopen` 加载 GTASA 的，
链接期不需要它，所以能编出完整 APK。

结果：

```
BUILD SUCCESSFUL in 7m 36s
dist\samp-debug-wv.apk   31,597,465 B
```

### 修掉的上游编译错误

`AbiGuards.cpp` 单独编译时会报：

```
Net/LocalPlayer.h:225: error: unknown type name 'CVehicle'; did you mean 'sa::CVehicle'?
Net/TextDrawPool.h:16: error: unknown type name 'TEXT_DRAW_TRANSMIT'
```

原因是 `Net/*.h` 里裸写的 `CVehicle` / `CPlayerPed` / `CObject` / `CTextDraw` /
`TEXT_DRAW_TRANSMIT` 都是项目 `Game/` 层导出的**全局**类型，不是 `sa::` 里的同名结构；
其它 translation unit 都是先包含了 `Game/Game.h` 才不出事，唯独 `AbiGuards.cpp` 漏了。
补一行 `#include "Game/Game.h"` 即可 —— 它本身只做 `static_assert`，不影响运行时。

> 不要试图用 `using namespace sa;` 绕过：会和项目自己的全局 `CEntity` 撞名
> （`reference to 'CEntity' is ambiguous`），错误更多。

### 这个 APK 现在还不能玩

没有 GTA:SA 2.10 的 `libGTASA.so` 和 2GB 游戏数据，装上会缺库。
要出可运行包，还得：

```bash
python tools/prepare_gtasa.py --apk <你自己的 gtasa_2.10.apk>
python tools/build_gamedata.py --gtasa <obb/apk> --pc-samp "<PC 版 SA 目录>" \
       --cjk-font <中文字体.ttf> --out dist/gamedata/files
```

---

## 七、触摸冲突怎么处理的

1. **尺寸**：面板模式下 WebView 的 `LayoutParams` 缩成面板矩形，不是 `MATCH_PARENT`。
   面板外的摇杆/开火/视角拖动照常走 Activity 的 `onTouchEvent`。
   透明像素**不会**穿透，所以不能"全屏 + 中间画卡片"。
2. **可见性**：隐藏用 `INVISIBLE` 不用 `GONE` —— `GONE` 的 View 不参与布局，
   WebView 会丢视口，再显示要重新 layout + 重排文字。
3. **手势清零**：`show()`/`hide()` 都调 `SAMP.cancelAllNativeTouches()`，
   防止玩家按着摇杆点开面板后原生侧一直以为手指没抬。
4. **按键**：`onKeyDown/onKeyUp` 加了 `!webOverlay.isVisible()`；
   `onBackPressed` 里覆盖层排在 `ui.onBackPressed()` 之前。

预加载：`onCreate` 里 `main.post` 建 WebView 并加载本地壳页，
不拖慢启动，但玩家第一次点开时 Chromium / V8 / CSS 解析都已经跑完了。

---

## 八、回退

| 位置 | 备份 |
|---|---|
| `SAMP.java` | `SAMP.java.bak_webview` |
| `AndroidManifest.xml` | `AndroidManifest.xml.bak_wv` |
| 服务端 `dayz.amx` | `dayz.amx.bak_wvtest` |
| 服务端 `webview_test.inc` | 删掉文件 + 去掉 `dayz.pwn` 里那行 include 即可 |

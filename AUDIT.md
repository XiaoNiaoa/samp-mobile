# omp-cef Android 移植审计（阶段 0）

审计日期：2026-11-22  
目标：评估 `aurora-mp/omp-cef` 服务端协议与 `XiaoNiaoa/samp-mobile` Android 客户端的兼容实现边界。  
本阶段只审计和记录，不修改协议或客户端实现。

## 结论摘要

该目标在技术上可做，但不是把现有 WebView 覆盖层改名为 CEF，也不是仅复用共享序列化头文件。必须新建移动端协议/会话客户端，复刻 omp-cef 客户端的 UDP 握手、密钥协商、KCP、加密包、事件路由和资源同步，再把 Overlay2D 的浏览器生命周期映射到 Android WebView。`omp-cef` 服务端可以维持原样，但前提是逐包兼容并以原版服务端实测。

阶段0有几项明确边界：

- Windows CEF 客户端本身不能编译进 Android；它依赖 D3D9、Windows hook、PC SA-MP 进程和 PC GTA SA 内存布局。
- Android Activity 上可以叠加透明 WebView；当前 samp-mobile 已有原生实现雏形，但尚未接入 omp-cef 网络和浏览器事件。
- GTA SA Android 使用 OpenGL ES/RenderWare，并有 WarDrum GraphicsThread/RenderQueue；仅凭 Activity WebView 覆盖不等于能将浏览器纹理绘制进游戏世界。
- omp-cef 资源路径要求 `http://cef/<resource>/<path>` 行为。服务端 FileData 下载与浏览器 HTTP 请求是两条路径；必须实现资源包缓存、解包/解密及本机 HTTP 路由，不能只把 URL 改为 `file://`。
- 当前工作区 Gradle ABI 配置只构建 `arm64-v8a`，且注释说明缺少 32 位 `libGTASA.so`。需求中要求的双 ABI 目前不满足，恢复 32 位前必须取得匹配的游戏二进制及依赖。
- 当前可见仓库路径为 `D:/samp-mobile`；源码审计依据本地 checkout `/tmp/ompcef`。阶段1真机验证使用 Vivo V2312A、Android 13；透明 Overlay2D 的层级与基础网页点击已有设备证据，坐标转换和关闭/IME 仍需处理。

## 仓库与范围

审计的 omp-cef 代码包括 `src/shared`、`src/client/core/network`、`src/client/core/browser`、`src/client/core/rendering`、`src/client/core/hooks`、`src/server/common`、`src/server/samp` 与 `src/server/omp`。移动端核对 Gradle/NDK 配置、Manifest、`SAMP` Activity、JNI helper、游戏 Hook、网络依赖和 ABI 约束。

关键证据位置：

- omp-cef：`src/shared/packet.hpp`、`packet-serializer.hpp`、`crypto.hpp`、`events.hpp`、`version.hpp`
- omp-cef：`src/client/core/network/network_manager.cpp`、`src/client/core/app.cpp`
- omp-cef：`src/client/core/system/resource_manager.cpp`、`src/client/core/browser/scheme_handler.cpp`
- omp-cef：`src/client/core/browser/manager.cpp`、`src/client/core/rendering/render_manager.cpp`、`view_texture.cpp`、`src/client/core/hooks/d3d9.cpp`
- omp-cef：`src/server/common/network.cpp`、`session.cpp`、`resource_manager.cpp`、`api.cpp`
- samp-mobile：`app/build.gradle`、`app/src/main/AndroidManifest.xml`
- samp-mobile：`app/src/main/java/com/sampmobile/game/SAMP.java`、`ui/WebOverlay.java`、`ui/WebRootServer.java`
- samp-mobile：`app/src/main/cpp/SAMPMOBILE/Client.cpp`、`Java/Java.cpp`、`Java/MultiTouch.cpp`、`Game/Hooks.cpp`、`Net/NetGame.*`、`CMakeLists.txt`

## 必须回答的问题

### 游戏画面由什么 Android View 或 Surface 承载？

Java 层 `com.sampmobile.game.SAMP` 继承项目的 `GTASA` Activity。Activity/GTASA 生命周期启动 GTA SA 原生运行时，渲染和 NativeWindow/EGL 归游戏运行时管理；源码中没有应用自行构造的 `SurfaceView` 或 `GLSurfaceView`。真机 `dumpsys SurfaceFlinger --list` 显示 `SurfaceView[com.sampmobile.launcher/com.sampmobile.game.SAMP]#111317`、BLAST buffer 和 Activity Decor Surface 独立存在；因此游戏视图确实由 GTASA 提供的 SurfaceView 承载，而非普通 Java 内容 View。GTA SA 通过 RenderWare 与 OpenGL ES 渲染，Hook 中可见 EGL/GLES 调用和 RenderQueue 相关逻辑。

### 是否能够在游戏画面上方覆盖透明 WebView？

可以，已在 Vivo V2312A（Android 13）实机确认。通过调试广播显示半透明物资面板时，游戏世界、游戏 HUD 和游戏触控控件仍可见，面板正确合成在 SurfaceView 上方；屏幕截图可见网页透明区域透出地图/游戏画面。点击网页“医疗”分类后按钮高亮变化，证明页面触摸命中 WebView。当前设备分辨率属性为 `1080x2388`、480 dpi，横屏截图呈 `2388x1080`；Activity/WebView 报告视口宽约 2298 px，存在系统显示区域与截图物理尺寸差异，事件坐标不能直接照搬截图像素。

关闭按钮已通过自动触摸确认：初始使用截图缩放像素当作设备坐标，触点偏移后进入游戏地图/暂停界面；读取控件树后，按 DecorView 窗口坐标转换，关闭按钮点击成功隐藏 WebView，底层游戏画面仍在。系统返回键测试时，游戏暂停界面优先响应而 WebView 仍显示，因此“关闭按钮通过、系统返回键优先级未通过/待修”需区分记录。实测截图曾保存于工作区根目录并在本轮清理，不作为长期项目文件。

### C++ 原生代码如何调用 Java？

客户端保存 `JavaVM*`（`Client::javaVM()`），JNI 接口集中在 `SAMPMOBILE/Java/Java.*`。Java helper 持有 Activity 全局引用并缓存 `jmethodID`，再通过 JNIEnv 调用 Java 方法。现有输入/对话回调采用队列，将 JNI 收到的数据排队，之后在 GameMain 的 `Client::process()` 上消费。新增 CEF bridge 应沿用该模式，避免在游戏线程直接同步等待 WebView/UI 工作。

### Java 如何向 C++ 传递触摸事件？

`SAMP.onTouchEvent()` 把 Android `MotionEvent` 的 pointer ID、action、最多四组坐标打包，经 native `multiTouchEvent4Ex(...)` 传入 JNI；`Java/MultiTouch.cpp` 再将其转换为项目 NVEvent 并进入游戏多点触摸处理。实测网页分类按钮在正确设备逻辑坐标下能响应。自动触摸关闭按钮/返回键时，部分输入被游戏地图/暂停界面接收，说明 WebView 的触控区域和游戏侧手势在复杂全屏 Surface 合成下仍要校准。未进行中文 IME 验证：当前内置测试页没有文本输入框，不能据此宣称键盘路径正常。

### 游戏使用 OpenGL ES 还是其他渲染路径？

Android 游戏端使用 OpenGL ES/EGL 与 RenderWare；源码连接 `EGL`、`GLESv2`，Hook 中有 EGL/GLES 调用。部分游戏工作由 WarDrum GraphicsThread 的 RenderQueue 执行，不能假定在任意线程存在当前 EGL context。通过 WebView 直接覆盖是 Android View 合成；World2D/WorldObject3D 则需要将 Web 内容送入游戏可采样纹理，并处理正确的渲染线程/context 生命周期，属于不同问题。

### 原生 SA-MP 网络层如何与 omp-cef 网络层共存？

omp-cef 使用独立 UDP socket/本地端口与原服务端通信，不复用 SA-MP RakNet 会话；服务端将 CEF 客户端 UDP endpoint 与同一 player ID 映射。samp-mobile 已链接 RakNet，但尚未发现 omp-cef 的 UDP 客户端或协议适配。架构上可以并行建立独立 UDP socket，不应劫持/修改 RakNet 包；还须处理移动网络切换、超时、disconnect、线程生命周期和服务端同一玩家 endpoint 映射。原版服务端是否接受来自移动客户端的该 endpoint，必须联调验证。

## omp-cef 协议与数据路径

### 握手/传输

源码的客户端连接流程：

1. 独立 UDP socket 发送 `RequestJoin`（包 ID 是 `PacketType` 枚举顺序；包体为 player ID 与版本号）。客户端版本来自 `VERSION_MAJOR/MINOR/PATCH/BUILD`，当前仓库值为 `1.3.0.0`。
2. 收到 `HandshakeChallenge` 后，客户端生成密钥并发送 `HandshakeFinalize`，用 sodium 相关密钥协商派生双向会话密钥。
3. `JoinResponse` 接受后创建 KCP（conv ID 来自响应），10ms 更新，KCP payload 通过 libsodium secretbox 加密。
4. 握手前包直接 UDP；握手后走加密 KCP。服务端以 UDP 接收，再映射 endpoint 到 session 并处理 KCP。

协议不变量：序列化先写 1 字节包 ID；多字节标量直接以当前主机内存形式写出（现有目标均为小端平台，但实现本身没有显式网络字节序转换）；字符串为 `uint16` 长度 + 原始字节，字节数组为 `uint32` 长度 + 原始字节；事件参数类型 ID 为 string=0、integer=1、float=2、bool=3。字符串在协议层是字节序列，Pawn 到 CEF 的编码转换另有 server/common `encoding.hpp`，客户端不能擅自假定全链路 UTF-8 或改变长度单位。

### 包/事件

`packet.hpp` 定义 RequestJoin、握手、JoinResponse、ServerConfig、RequestFiles、FileData、DownloadComplete、PressKey、EmitEvent、EmitBrowserEvent、ClientEmitEvent 等 ID。注意 `PacketPayload` 的 variant 没有 DownloadComplete、PressKey 的专用结构，但 serializer 当前对其也没有处理分支；实际使用是否经由别的包/事件路径，应在阶段1对照 server/client 所有发送调用补全，不能自行发明包体。

服务端通过 `EmitEvent` 向客户端发送命令式事件，例如 CreateBrowser、SetBrowserVisible、DestroyBrowser、FocusBrowser、LoadUrl；`EmitBrowserEvent` 向指定 browser ID 的网页发事件；客户端网页事件经 `ClientEmitEvent` 发回服务端。客户端成功/失败创建浏览器通过 `BrowserCreateResult` 回报。服务端 Pawn natives/callback 注册在 `server/common/natives.*`、`cef_event_handlers.*`，两种服务端桥分别在 `server/samp` 和 `server/omp`，Pawn API 可保持原样，由 Android 侧实现协议语义。

### 资源

握手接受会携带 manifest JSON；ServerConfig 携带 master resource key。资源客户端比对 manifest 后发送 `RequestFiles`，服务端按 `FileData` 的 resourceName、relativePath、hash、chunkIndex、totalChunks、data 字段分块回传。客户端组装文件、验证完整性并将资源存入本地资源包/VFS。`crypto.hpp` 定义 OCEF2 entry header、AES-CBC resource-entry 加解密、旧格式兼容；KCP 传输层加密另用 libsodium secretbox，不能混为一层。

CEF URL handler 将 `http://cef/<resource>/<path>`（也识别 `cef://`）解析到本地资源 VFS，并处理 MIME 与相对路径。移动端需以 HTTP 拦截/自建本地路由实现同等 URL 语义，并验证 manifest 变化、缓存命中、更新覆盖、失败重试和路径穿越处理。

## 可复用源码与必须重写部分

### 可复用候选

- `src/shared/packet.hpp` 的数据结构和枚举，可作为字段/ID 契约来源；应新增 Android 自有编译目标，不直接改原仓。
- `src/shared/packet-serializer.hpp` 的线格式可作对照，但它依赖 iostream、全局 LOG 宏，且缺少若干类型分支/健壮性限制；不建议不经测试原样纳入 Android。
- `src/shared/events.hpp`、`version.hpp` 可作常量来源。
- `src/shared/crypto.hpp` 的算法定义可作互操作参考；需要验证 Android sodium/tiny-aes 版本、密钥长度及跨 ABI 结果。Shared CMake 使用 unofficial-sodium、tiny-aes、picosha2，当前不是 Android-ready 的接入配置。
- KCP 可复用同版本实现/线上行为；omp-cef 仓库通过 `deps/kcp` 构建，移动端应比较 vendored KCP 版本，避免出现 ABI/API 或行为漂移。

### 必须重写/平台适配

- Windows CEF 生命周期、浏览器窗口和 renderer 子进程管理：Android 采用系统 WebView，不实现 CEF API 假象。
- D3D9 texture 上传、Present/Reset hook、MinHook、DX9 屏幕绘制：Android 第一版 Overlay2D 采用 Activity View 合成。
- Windows WndProc/键盘鼠标捕获及 PC 窗口焦点语义：改为 Android MotionEvent、IME、Back 与 View 焦点。
- GTA SA PC 地址/结构访问、SA-MP PC DLL hook 和 WorldObject3D attach：不能搬用到 Android `libGTASA.so`。
- 客户端独立 UDP/KCP/crypto/session/resource manager：协议语义兼容，平台 socket、线程、存储和 Android 生命周期需重新实现。
- CEF custom scheme handler：实现 `http://cef/` URL 到缓存资源的等价 Android WebView 路由。

## 阶段推进建议与验收门槛

1. **阶段0（本文件）**：审计和风险边界，禁止提前大规模改代码。
2. **阶段1**：整理独立 CefManager/WebView overlay 与线程/生命周期契约，先用确定性本地测试页确认叠层、透明度、尺寸、触摸和 IME。现有 WebOverlay 可作为界面能力参照，但它不是协议客户端。
3. **阶段2**：实现独立协议核心和 golden packet tests；与原版实现对同一输入逐字节比对，再做真实服务端握手/KCP/加密联调。
4. **阶段3**：将 `CreateBrowser`、可见性、销毁、焦点和创建结果事件映射到多 browser ID WebView；只宣称覆盖 Overlay2D。
5. **阶段4**：双向 JS/Pawn 事件验证，包括参数类型、转义、销毁和断线。
6. **阶段5**：资源分发/缓存及 `http://cef/` 路由，另做真实触摸与输入法测试。
7. **阶段6-7**：分别评估 World2D、WorldObject3D 的 Android GLES 纹理通路；在 GraphicsThread/RenderQueue 同步方式明确前不承诺实现。
8. **阶段8**：arm64 构建、原版服务端端到端测试、设备性能和断线恢复；32 位构建仅在补齐 matching 32-bit `libGTASA.so` 与所有 ABI 依赖后进行。

## 阶段0结论与阶段1真机记录

阶段0静态审计已经完成；随后按用户指示在真机继续推进阶段1验证。设备为 Vivo V2312A、Android 13、480 dpi，物理分辨率属性 `1080x2388`，横屏运行。`/data/app/.../base.apk` 与 `app/build/outputs/apk/debug/samp.apk` SHA-256 相同（`a08a1e2af1dc2ddd3f6dddff7f591c9fee6917c9d0bc1b5bc2fc2998248eef45`），因此当前安装的就是本工作区已有 debug 构建产物；本轮没有安装/覆盖 APK。

### 阶段1实测通过

- 游戏进程 `com.sampmobile.launcher:game` 正常运行，SurfaceFlinger 枚举到 `SurfaceView[com.sampmobile.launcher/com.sampmobile.game.SAMP]#111317`，确认游戏底层 Surface 类型。
- WebView 页面从应用私有 files/web 目录由 `WebRootServer` 绑定 `127.0.0.1:40819` 提供；WebView 116.0.5845.92 启动并显示页面。
- 半透明网页面板叠在游戏 SurfaceView 上；游戏画面和 HUD 在面板外/透明区域仍可见。
- 点击网页“医疗”分类后，高亮状态改变，验证网页点击输入确实到达页面。
- WebView renderer 单独进程存在。采样时 `com.google.android.webview:sandboxed_process0` RSS 约 112,120 KB、PSS 约 62,721 KB；进程 `com.sampmobile.launcher:game` RSS 约 384,288 KB、PSS 约 231,515 KB。此为单次整机快照，不是“WebView 增量内存”对照值。

### 阶段1仍未通过/未验证

- WebView 内关闭按钮：已通过，读取控件树并将预览截图点位换算到横屏 DecorView 坐标后，关闭按钮隐藏面板、保留底层游戏画面。
- 系统返回键：未通过当前验收预期；实测优先改变游戏暂停/地图界面，面板未被关闭。需要后续核对游戏窗口/Activity 的按键分发与 WebView 焦点策略。
- 中文软键盘：内置物资页没有文本输入控件，未完成 IME 实测。
- SurfaceFlinger 对 `SurfaceView[...]#111317` 的 `--latency` 输出全零，不能用该 layer 数据评价游戏帧率；Activity Surface 时间戳可读，但不代表游戏 GPU 帧率。
- 服务端推送/omp-cef 原协议链路尚未开始实现或连接，因此页面当前来自本地测试资源，不是 `http://cef/` 资源，也没有测试 Pawn/JS 往返。
- 阶段1尚未运行 Gradle 重建：本轮只修订审计文档，未修改 Java/C++ 源码；实测使用工作区和手机上 hash 相同的既有 APK。
- 本次工作区新建了真机截图 `phone-current.png`、`phone-overlay.png`、`phone-overlay-tap.png`、`phone-overlay-close.png`、`phone-overlay-hide.png` 及控件树 `window-device.xml`，均为临时核验产物，不属于应用源码。

### 其他待解决项

- Android 上 libGTASA / RenderQueue 纹理生命周期与 WebView 内容纹理化的可行路径（仅影响 World2D/WorldObject3D，不阻断 Activity Overlay2D）。
- omp-cef 当前 branch 所有 PacketType 的发送/接收是否覆盖 serializer 结构里缺少 payload 的两个 ID；阶段2必须用全仓收发调用和服务端测试补齐证据。
- 服务端是否允许独立 Android CEF UDP endpoint 映射到同一玩家，以及网络切换后重连行为。
- `armeabi-v7a` 当前缺少匹配游戏 `.so`；双 ABI 要求仍受此条件阻塞。

验证记录：`git diff --check -- AUDIT.md` 无输出；本轮没有客户端源码更改，未执行 Gradle build。设备截图和日志只证明文档列明的具体 Overlay 观察结果，不代表完整 omp-cef 兼容。

## 阶段2记录：协议兼容层已实现并验证（2026-11-23）

### 已落地代码

新增原生模块 `app/src/main/cpp/SAMPMOBILE/Cef/`（未改动游戏核心逻辑，仅在 `Client::process()` 增加一行调用）：

- `CefProtocol.h/.cpp`：按 `src/shared/packet.hpp` + `packet-serializer.hpp` 逐字节复刻的包结构、序列化与反序列化。显式小端读写；字符串 `u16` 长度（上限 4096 截断）、字节数组 `u32` 长度、事件参数类型 0/1/2/3 与 PC 一致；复刻 `DownloadComplete` 的默认 variant 包体（`FF FF FF FF` + 4 字节 0）与 `JoinResponse` 尾字段的“有剩余才读”行为。
- `CefCrypto.h/.cpp`：用随源码内嵌的 TweetNaCl（官方 20140427，sha256 `02e65bc3…`）、BLAKE2 参考实现、tiny-AES-c 重新实现 libsodium 语义：
  - `crypto_kx_keypair` = `random(32)` + `X25519_base`
  - `crypto_kx_client_session_keys` = `X25519(sk, server_pk)` → `BLAKE2b-512(q‖client_pk‖server_pk)`（无 key/salt/person，rx=前 32、tx=后 32）；低阶公钥（共享密钥全零）按 libsodium 返回失败
  - `crypto_secretbox_easy/_open_easy`（XSalsa20-Poly1305，24 字节 nonce 前缀）
  - 资源条目 `OCEF2` 头 + AES-128-CBC + PKCS#7 填充，含旧格式回退；SHA-256 十六进制
- `CefNetwork.h/.cpp`：POSIX UDP + KCP（与 omp-cef 子模块同一 commit `f4f3a89`），握手状态机（RequestJoin 2s 重试 ×5 → HandshakeChallenge → HandshakeFinalize → JoinResponse → KCP `nodelay(1,10,2,1)`、`wndsize(128,128)`、10ms 驱动），握手前明文、握手后 secretbox 加密；`nonCefServer` 判定与原实现一致。
- `CefSession.h/.cpp`：游戏线程监督层，等价 `app.cpp` 的 Tick —— 监听 `pNetGame`、端点取 `m_host:(m_port+2)`（`app.hpp` 的 `cef_port_offset_ = 2`）、本地玩家 ID 变化或断线时重置；网络线程收包入队、游戏线程分发；`ServerConfig` 主密钥与 `JoinResponse` manifest 已保存，事件按 `EmitEvent`/`EmitBrowserEvent` 分流到预留回调。
- `CMakeLists.txt`：新增 `Cef/*.cpp` 与 `Cef/thirdparty/*.c` 源集（`.c` 按 C 编译）。
- 第三方源码：tweetnacl（官方）、ikcp（omp-cef 固定版本）、tiny-AES-c（omp-cef 固定版本）、BLAKE2 ref、picosha2。

### 验证（真机 V2312A / Android 13 / arm64）

`tools/cef_kat/` 提供可重复验证：

- `gen_vectors.py`：宿主侧用 PyNaCl（libsodium 绑定）与独立 Python 实现生成向量：9 组包字节向量 + X25519 公钥 / 会话 rx·tx / secretbox 密文。
- `cef_kat_main.cpp`：在设备上跑 30 项断言 —— **全部 PASS**，包括 9 组包“序列化字节完全相等”与“解析后重序列化字节相等”、会话密钥与 libsodium 结果一致、能解开 libsodium 生成的密文、篡改密文被拒、OCEF2/空载荷/SHA-256。
- `cef_probe_main.cpp`：单进程内起迷你 omp-cef 服务端（真 UDP socket + 真 KCP + 真加密），驱动真实 `CefNetwork` 客户端完成 `RequestJoin → Challenge → Finalize → JoinResponse → ServerConfig → CreateBrowser → BrowserCreateResult` 往返 —— **16 项全部 PASS**。
- Gradle `assembleDebug` **BUILD SUCCESSFUL**（39s 增量）；产物 `app/build/outputs/apk/debug/samp.apk` 41,481,776 B，其中 `lib/arm64-v8a/libSAMPMOBILE.so` 已含 Cef 模块（82 个导出符号、CefSession/CefNet 日志串可检索）。
- 复现命令：`bash tools/cef_kat/run_kat.sh`（可选参数 `kat` / `probe`）。

### 阶段2明确未完成

- 尚未与原版 omp-cef 服务端插件实连：需要在其 SA-MP 服务端（`samp` 变体插件）加载并配置 `master_resource_key`，客户端连上 7777 后走 7779。当前只证明“与本协议实现的服务端互通”，服务端插件侧仍待联调。
- 浏览器层（阶段3）、JS/Pawn 双向（阶段4）、资源下载与 `http://cef/` 路由（阶段5）尚未实现；`CefSession` 当前仅记录事件，不回 `BrowserCreateResult`。
- `PressKey` 在 omp-cef 中并非裸包类型而是 `ClientEmitEvent` 名为 `PressKey` 的事件，输入层（阶段6）实现时按该形式发送。
- 手机当前安装包仍是阶段1 的旧 APK；新构建未覆盖安装，本轮未打断用户游戏环境。

## 阶段3记录：Overlay2D 浏览器层已实现并真机验证（2026-11-23）

### 已落地代码

**原生侧** `app/src/main/cpp/SAMPMOBILE/Cef/CefBrowser.h/.cpp`

把 `app.cpp` 的 `App::OnPacketReceived` 事件分发搬到 Android，浏览器生命周期交给 Java：

| 服务端事件 | Android 动作 |
|---|---|
| `CreateBrowser(id,url,focused,controlsChat)` | `CefManager.createBrowser` |
| `SetBrowserVisible(id,visible)` | `CefManager.setBrowserVisible` |
| `DestroyBrowser(id)` | `CefManager.destroyBrowser` |
| `LoadUrl(id,url)` | `CefManager.loadUrl` |
| `ReloadBrowser(id,ignoreCache)` | `CefManager.reloadBrowser` |
| `FocusBrowser(id,focus)` | `CefManager.focusBrowser` |
| `SetBrowserLayer(id,layer)` | `CefManager.setBrowserLayer` |

- `JNI_OnLoad` 里缓存 `CefManager` 的 jclass/methodID：游戏线程是 `AttachCurrentThread` 附加的，用 bootstrap loader 调 `FindClass` 找不到 app 类。
- `World2D` / `WorldObject3D` / `AttachBrowserToObject` / 音频 / HUD 等事件按名识别并记录为阶段 6-7 未支持，不伪造实现。
- `FocusBrowser` 第二参数按 `BrowserManager::FocusBrowser` 的语义当作目标焦点状态（`app.cpp` 里变量名叫 toggle 但实现不是取反）。
- 创建结果经 `CefNetwork::SendBrowserCreateResult` 回 `ClientEmitEvent(BrowserCreateResult)`，参数顺序 `[bool success, int code, string reason]`，与服务端 `plugin.cpp:1018` 的解析一致。

**Java 侧** `app/src/main/java/com/sampmobile/game/ui/cef/`

- `CefManager`：静态单例，全部入口可从任意线程调用，内部 post 到主线程；未 install 时命令排队，install 后按序重放。`SAMP.onCreate` 调 `install(this)`，`onDestroy` 调 `shutdown()`。
- `CefWebView`：单个 browser 的 WebView（MATCH_PARENT、透明、硬件层、禁用缩放），`SetBrowserLayer` 映射为 `translationZ`。
- `CefResourceProvider`：`http://cef/<resource>/<path>` 路由。
- `proguard-rules.pro`：新增 `-keep class com.sampmobile.game.ui.cef.* { *; }`，防止开启 minify 后类/方法名被改导致 JNI 失配。

### 关键修正：资源 URL 形式

PC 端注册的是 `CefRegisterSchemeHandlerFactory("http", "cef", ...)`（`manager.cpp:314`），即真实 URL 是 **`http://cef/<resource>/<path>`**（scheme=http、host=cef），不是 `cef://`。首版按 scheme 判断导致 WebView 走 DNS 解析并报 `net::ERR_NAME_NOT_RESOLVED`；现同时接受 `http(s)://cef/...` 与 `cef://...`。

### 关键修正：CEF 层不再依赖 NetGame

`Client::process()` 里原本把 CEF 的 `Install()/Tick()` 放在 `if (pNetGame)` 内，单机模式（无 NetGame）下永不执行。现已移到条件外：`CefSession::Tick()` 自己判断 `pNetGame` 与 `GAMESTATE_CONNECTED`，离线时正常重置。

### 调试入口（仅 debuggable 包）

`CefManager` 注册 `com.sampmobile.CEFDEBUG` 广播，用于在没有游戏服务器时验证浏览器层：

```
am broadcast -a com.sampmobile.CEFDEBUG -p <pkg> --es action connect --es host 127.0.0.1 --ei port 17900 --ei playerid 1
am broadcast -a com.sampmobile.CEFDEBUG -p <pkg> --es action create --ei id 1 --es url http://cef/test/index.html
am broadcast -a com.sampmobile.CEFDEBUG -p <pkg> --es action visible --ei id 1 --ei on 0
am broadcast -a com.sampmobile.CEFDEBUG -p <pkg> --es action destroy --ei id 1
am broadcast -a com.sampmobile.CEFDEBUG -p <pkg> --es action disconnect
```

配套 `CefSession::DebugConnect/DebugDisconnect`：绕过 SA-MP 会话直接握手。两者共用 `debugMutex_` 串行化，避免与游戏线程的 `Connect()` 并发（首版存在 socket 被关闭后仍发送的竞态，日志表现为 `udp send failed: Destination address required`）。

### 验证（真机 V2312A / Android 13 / arm64）

`tools/cef_kat/cef_server.cpp`：设备上运行的独立 omp-cef 服务端（真 UDP + KCP + 加密），按时间线下发事件并打印回执。`tools/cef_kat/run_browser_test.sh` 一键完成：编译 → 推送 → 把测试页装进 `<pkg>/files/cef/resources/test/` → 起服务端 → 广播连接 → 四阶段截图 → 打印双端日志。

实测日志（服务端侧）：

```
<- RequestJoin playerid=1 version=0x01030000
-> HandshakeChallenge
<- HandshakeFinalize clientPk=32 bytes
<- JoinResponse sent, KCP up (conv=0xC0FFEE)
-> ServerConfig
-> CreateBrowser id=1 url=http://cef/test/index.html
<- ClientEmitEvent name=BrowserCreateResult browserId=1 args=3
     arg[0] bool 1
     arg[1] int 0
     arg[2] string "Successfully created"
-> SetBrowserVisible 0
-> SetBrowserVisible 1
-> DestroyBrowser 1
=== CREATE ROUND TRIP OK
```

客户端侧：`CEF_RESOURCE_HIT test/index.html 3391 bytes, mime=text/html` → `onPageFinished` → `setVisible want=false now=4` → `setVisible want=true now=V` → `browser 1 destroyed`。

画面证据（`tools/cef_kat/out/`）：`1-created.png` 蓝色面板覆盖游戏世界且透出画面、`2-hidden.png` 面板消失、`3-visible.png` 重新出现、`4-destroyed.png` 移除、`touch-after.png` 点"甲"后按钮高亮且页内日志新增 `[3] 选中分类：甲`。

### 性能事实与处理

- 首版实测 **WebView 冷创建到真正上屏约 6-9 秒**（Chromium 进程冷启动 + 首帧合成），服务端 `CreateBrowser` 后长时间空白。
- 处理：`CefManager.install()` 里预热一个 1x1、INVISIBLE、加载 `about:blank` 的常驻 WebView；可见性切回 VISIBLE 时主动 `requestLayout + invalidate + postInvalidateOnAnimation`（游戏 SurfaceView 持续合成时 Chromium 不一定立即提交新帧）。
- 处理后实测 **3 秒内上屏**。预热 WebView 在 `shutdown()` 中销毁。

### 阶段3明确未完成

- `window.cef.emit/on` 的页面侧注入（阶段4）：测试页日志明确显示 `window.cef 尚未注入 (阶段4)`。
- 资源真实下载（阶段5）：`CefResourceProvider` 已按 `<filesDir>/cef/resources/` 目录约定读文件，OCEF2 加密资源目前返回 503 并记录 `CEF_RESOURCE_ENCRYPTED`，解密与 manifest 校验未接。
- 触摸穿透策略未定：Overlay2D 目前全屏接收触摸，与游戏虚拟摇杆会冲突；omp-cef 靠服务端 `SetBrowserVisible` 与 `SetKeyCapture` 控制，Android 侧的具体映射放在阶段6。
- 未与原版 omp-cef 服务端插件实连（需在真实 SA-MP 服务器加载插件）。
- 旧的 `WebOverlay` 与 CEF 层并存，两者都挂 DecorView；本轮未删除旧组件，避免影响既有功能。

---

## 阶段4记录：JS 双向通信已实现并真机验证（2026-11-23）

目标（任务书第七章）：`网页 JS -> Android Java -> JNI/C++ -> omp-cef 协议 -> 服务端 Pawn` 与反向链路全部打通。

### 语义对齐（以 renderer/main.cpp 为准）

| 原版 API | Android 实现 | 说明 |
|---|---|---|
| `cef.emit(name, ...args)` | `CefJs` bootstrap -> `cefNative.emit(name, json)` -> `CefJsBridge` -> `nativeEmitFromJs` -> `CefBrowser::OnJsEmit` -> `ClientEmitEvent` | 参数按 CEF 规则过滤：只有 bool/number/string 过线，其余置 null 后被丢弃（对齐 `client.cpp` 对 `VTYPE_NULL` 的跳过） |
| `cef.on(name, cb)` | bootstrap 内 `handlers[name].push(cb)`，注册时 flush 该名字的 pending 队列 | 与原版 `pending_events_` 行为一致 |
| `cef.off()` / `off(name)` / `off(name, cb)` | 三种重载全部实现，语义同原版 | 设备端断言验证"旧回调被移除后 0 次命中" |
| `cef.set_focus(bool)` | `cefNative.setFocus` -> `CefManager.focusBrowser` | 等价 PC `cef_set_focus` -> `manager.FocusBrowser` |
| 服务端 `EmitBrowserEvent(name,args)` | `CefSession` -> `CefBrowser::HandleBrowserEvent` -> `CefManager.emitBrowserEvent` -> `window.__cefDispatch(name,args)` | 页面未加载完时在 `CefWebView` 内排队，`onPageFinished` 后按序补发（等价 `pending_emits_`） |

参数类型映射与原版 V8 一致：**整数值一律 Integer**（`CefV8Value::IsInt()` 对整数值为真，JS 里 `7.0` 就是 `7`），只有带小数的值走 Float。

### window.cef 注入方式（关键设计）

CEF 在 V8 context 创建时就挂好 `window.cef`，WebView 没有"文档前脚本"钩子。做法：

1. `CefResourceProvider` 在返回 `text/html` 字节流时**按字节**插入 `<script>bootstrap</script>` 到 `<head>` 之后 → 页面自己的内联脚本执行前对象已存在，**无竞态**；全程字节级操作，不破坏非 UTF-8 页面。
2. 远程 URL（服务端 LoadUrl 到外站）无法改字节流，退化为 `onPageStarted/onPageFinished` 两次 `evaluateJavascript` 补注入；bootstrap 带 `__ompcef_v1` 标记，重复注入是 no-op。
3. 桥对象 `cefNative` 由 `addJavascriptInterface` 提供，**per-WebView**，在 `loadUrl` 之前挂好。

### 新增/修改文件

- 新增 `Cef/CefJson.h/.cpp`：无依赖 JSON 读写（递归下降、深度上限 64、显式处理 `\uXXXX` 与代理对）。阶段4 解析 JS 参数，阶段5 复用于 manifest。
- 修改 `Cef/CefBrowser.h/.cpp`：`HandleBrowserEvent` / `OnJsEmit` / `OnSessionLost` / `BuildArgsJson`，新增 `emitBrowserEvent`、`destroyAllBrowsers` 方法 ID；`Install()` 同时注册 browser 事件与会话丢失回调。
- 修改 `Cef/CefSession.h/.cpp`：新增 `SetSessionLostHandler`，在 `ResetSession()` 与 `DebugDisconnect()` 触发。
- 新增 Java `CefJs.java`（bootstrap 源码 + 字节级注入）、`CefJsBridge.java`（`@JavascriptInterface`）。
- 修改 `CefManager`（`emitBrowserEvent` / `onJsEmit` / `destroyAllBrowsers` / `nativeEmitFromJs` / 新增调试动作 `emit`、`destroyall`）、`CefWebView`（桥挂载、`pageReady` 队列、`dispatchEvent`、`onPageStarted/Finished` 注入与补发）、`CefResourceProvider`（HTML 注入、favicon 404）。

### 验证（真机 V2312A / Android 13 / arm64）

**协议 KAT**：新增 17 项 JSON 断言（类型判定、转义、代理对、对象取值、5 类非法输入拒绝），`run_kat.sh kat` 全项 `ALL PASS (0 failures)`。

**端到端（`tools/cef_kat/run_browser_test.sh`）**：设备端独立 omp-cef 服务端按时间线发送
`ServerConfig -> CreateBrowser -> EmitBrowserEvent(test:early,test:hello,test:command,test:ping) -> Visible(false) -> Visible(true) -> DestroyBrowser`，服务端按收到的 `ClientEmitEvent` 做断言：

```
PASS  BrowserCreateResult received
PASS  page sent probe:ready (JS -> JNI -> wire)
PASS  event sent before page load was queued and delivered
PASS  test:hello acked by the page
PASS  ack args kept string/int/float/bool types
PASS  test:command acked by the page
PASS  cef.off removed the first handler (0 stale hits)
INFO  touch-driven test:click events: 1 (manual, not required)
=== STAGE 4 ROUND TRIP OK
```

关键日志（客户端）证明排队补发路径真的被走到：

```
[CefBrowser] EmitBrowserEvent 'test:early' -> browser 1 (2 args) ["early-tag",7]
[CefWebView] queue event 'test:early' for browser 1 (1 pending, page not ready)
[CefWebView] flushing 1 queued event(s) for browser 1
[CefWebView] dispatch 'test:early' -> browser 1 ["early-tag",7]
[CefBrowser] js emit 'test:earlyAck' -> server (browser 1, 4 args) ["early-tag",7,true,2.25]
```

类型与编码证据（服务端收到，逐字节解包）：

```
<- ClientEmitEvent name=test:ack browserId=1 args=4
     arg[0] string "ack:服务端🚀"      <- 4 字节 UTF-8（U+1F680），JVM 内是代理对
     arg[1] int 43
     arg[2] float 7.500000
     arg[3] bool 0
<- ClientEmitEvent name=test:click browserId=1 args=4
     arg[0] string "甲"
     arg[1] int 1
     arg[2] bool 1
     arg[3] float 1.600000
```

画面证据（`tools/cef_kat/out/`）：`1-created.png` 面板 + 三条下行事件日志、`1b-tap.png` 真机触摸后"甲"高亮且日志新增 `cef.emit test:click("甲", 1, true, 1.6)`、`3-visible.png` 隐藏再显示后 `下行事件: 4 / 上行事件: 5`。断线清理单独验证：`session lost - all browsers torn down` -> `browser 7 destroyed` -> `destroyAllBrowsers: 1 closed`（`cleanup-before.png` / `cleanup-after.png`）。

### 过程中修掉的真实缺陷

1. **断言写错，不是代码错**：首轮 `ack args kept ... types` FAIL，因为页面回 `frac*2 = 7.0`，而 JS 的 `7.0` 与 `7` 同值、V8 会判为 Integer。这正是原版行为，改成 `frac*2+0.5`（7.5）并在页面注释里写明"整数值一律 Integer"。
2. **favicon 误路由**：`http://cef/favicon.ico` 被当成 resource=`favicon.ico`、path 空 → 落到 `index.html` 并生成 503 诊断页，日志里出现假的 `CEF_RESOURCE_MISS`。改为"首段含 `.` 且无路径"时直接 404。
3. **Java -> UTF-8 代理对被展宽**：`GetStringUTFChars` 返回 modified UTF-8（CESU-8），emoji 会变成 6 字节，与 CefString/服务端期望的 4 字节 UTF-8 不符。改为 `String.getBytes("UTF-8")`，并用 🚀 做端到端断言。

### 阶段4明确未完成

- `omp:cef:internal:chatInputState` / 键盘捕获：WebView 的 IME 由系统管理，与游戏输入框的联动属阶段6（输入）。
- `game:data:pollPlayerStats`：需要读 SA-MP 内存里的玩家数值，随 HUD/World2D 一起做，当前明确记日志忽略、不编造数字。
- 远程 URL 的补注入仍晚于页面内联脚本（WebView 限制），本地 `http://cef/` 资源不受影响。

---

## 阶段5记录：真实资源下载与解密已实现并真机验证（2026-11-23）

目标（任务书第九章）：`RequestFiles/FileData` 组装、OCEF2 解密、manifest 校验、`http://cef/` 完整路由——页面必须来自服务端下发的加密 `pak`，而不是事先塞进手机的文件。

### 服务端事实（照 `resource_manager.cpp` / `plugin.cpp` 复刻）

- 资源单位是 **`scriptfiles/cef/<name>.pak`**（zip），不是单个文件；`manifest_json` 形如 `{"<resource>":[{"path":"<name>.pak","hash":"<sha256>","size":N}]}`。
- 客户端只对**缓存校验失败**的 pak 发 `RequestFiles`；服务端按 **1200 字节**分块回 `FileData(resourceName, relativePath, fileHash, chunkIndex, totalChunks, data)`。
- 收齐后校验 SHA-256 **以 manifest 为准**（不信包内 `fileHash`），落盘，再逐条目 `DecodeResourceEntry`（`OCEF2` 头 + AES-128-CBC，`.html/.css/.js/.json/.xml/.svg` 加密，其余明文），最后 `DownloadComplete` 一次。
- 客户端另需上报 `ClientEmitEvent(DownloadStart)` / `(DownloadFinish)`，服务端转成 Pawn 的 `OnCefDownloadStart/Finish`（`plugin.cpp:1036/1049`）。PC 端只在真的下载时发，缓存命中不发。
- `CreateBrowser` 在资源就绪前必须排队（`App::pending_creates_`），否则页面必然 404。

### Android 侧实现

- **新增 `Cef/CefPak.h/.cpp`**：自写 zip 解析（EOCD → 中央目录 → 本地头 → **raw DEFLATE，zlib**，NDK 已链接 `-lz`），只接受 method 0/8，显式拒绝 ZIP64、加密位、`../`、绝对路径、反斜杠、控制字符；单条目上限 512MB。
- **新增 `Cef/CefResources.h/.cpp`**：状态机 `Idle → AwaitingTrigger → VerifyingCache → Downloading → Completed`，与 PC `ResourceManager` 同构：
  - `TriggerDownload()` 遍历 manifest，命中缓存（`sha256(pak) == hash` 且解包标记 `.cefhash` 一致）则跳过；
  - `OnFileData()` 按块组装、越界/乱序/重复块拒收、超长拒收，收齐后校验哈希；
  - `ExtractPak()` 解压到 `<resources>/<res>.staging` → 旧目录改名 `.old` → staging 转正 → 删 `.old`（失败可回滚，不会出现"半套资源"）；
  - 明文落盘为 `<filesDir>/cef/resources/<res>/<path>`，正是 Java `CefResourceProvider` 的 `http://cef/<res>/<path>` 根目录，因此**不需要改服务端分发方式**；
  - 全部完成 → `DownloadFinish` + `DownloadComplete` + 通知浏览器层。
- **修改 `CefSession`**：`JoinResponse`/`ServerConfig`/`FileData` 转交资源层；`SendPacket()` 统一出口；`Tick()` 驱动下载；断线/调试断开/换服都触发 `OnSessionLost()` 清态；连接时上报 `<host>_<port>` 作为缓存桶名（与 PC 一致）。
- **修改 `CefBrowser`**：`CreateBrowser` 在 `!IsReady()` 时入 `pendingCreates_` 并记日志，资源就绪回调里按序重放；断线清空队列。
- **修改 `CefManager.java`**：`install()` 把 `<filesDir>/cef` 交给 native（`nativeSetStorageRoot`），保证加解密落盘目录与 Java 供文件目录是同一个。

### 测试工具

- 新增 `tools/cef_kat/make_pak.py`：把目录打成**真 pak**（zip deflate + OCEF2/AES-128-CBC，固定 IV 与时间戳使产物可复现，因此能测缓存命中），输出 sha256/size 给脚本。
- `cef_server.cpp`：读入 pak、在 `JoinResponse` 里下发真实 manifest、按 1200 字节分块流式发送（**故意延迟 2.5s 才开始发**，用来证明 `CreateBrowser` 确实被排队）、统计 `RequestFiles/FileData/DownloadComplete/DownloadStart/DownloadFinish`，每行日志带 `t+ms` 时间戳。
- `run_browser_test.sh`：先清空 `<pkg>/files/cef` 再跑；`KEEP_CACHE=1` 则保留缓存跑第二遍（缓存命中路径）。

### 验证（真机 V2312A / Android 13 / arm64）

**协议 KAT** `ALL PASS (0 failures)`，新增 11 项：真 pak（deflate + stored 两种 method）解析出 3 个条目、每个条目 OCEF2 解码后逐字节相等于 Python 侧原文、`../evil.html` 被拒、路径校验 5 组。

**首次进服（清缓存）**，服务端时间线（节选）：

```
[t= 3850ms] -> ServerConfig (master key 16 bytes)
[t= 3860ms] <- RequestFiles test/test.pak          <- 10ms 内触发下载
[t= 3872ms] <- ClientEmitEvent name=DownloadStart
[t= 4156ms] -> CreateBrowser id=1 url=http://cef/test/index.html
[t= 6368ms] -> FileData transfer complete (7 chunks, 7473 bytes)
[t= 6375ms] <- DownloadComplete
[t= 6412ms] <- ClientEmitEvent name=BrowserCreateResult ... "Successfully created"   <- 资源就绪后才创建
[t= 6448ms] <- ClientEmitEvent name=probe:ready ... "omp-cef Android · stage 5"
[t= 6684ms] <- ClientEmitEvent name=DownloadFinish
```

客户端日志同一条链路：

```
[CefResources] manifest 116 bytes
[CefResources] ready to download
[CefResources] ClientEmitEvent 'DownloadStart' sent
[CefResources] requested 1 of 1 file(s): test/test.pak (7473 bytes)
[CefBrowser]   CreateBrowser id=1 queued - resources downloading (1 pending)
[CefResources] 'test/test.pak' saved (7473 bytes, sha256 ok)
[CefResources] 'test' extracted: 1 file(s) written, 0 skipped -> /data/user/0/<pkg>/files/cef/resources/test
[CefResources] all 1 file(s) ready under .../files/cef/resources
[CefResources] ClientEmitEvent 'DownloadFinish' sent / DownloadComplete sent
[CefBrowser]   CreateBrowser id=1 url=http://cef/test/index.html focused=1 controlsChat=0
[CefResourceProvider] CEF_RESOURCE_HIT test/index.html 10462 bytes, mime=text/html
```

（10462 = 包内明文 7350 + 注入的 `window.cef` bootstrap 3112 字节，说明注入仍作用在解包后的文件上。）

断言 13 项全 PASS：`RequestFiles` / `requested test/test.pak` / `all chunks (no gaps)` / `exactly one DownloadComplete` / `DownloadStart once` / `DownloadFinish once` / `CreateBrowser was queued until resources were ready` + 阶段4 的 7 项。

**二次进服（保留缓存）**：`cache hit: client requested nothing`、`cache hit: no FileData streamed`、`DownloadComplete` 仍在 `ServerConfig` 后 17ms 发出，页面照常渲染；客户端 `'test' already extracted (hash unchanged)` / `all 1 file(s) already cached`。

设备上的落地证据：`files/cef/cache/127.0.0.1_17900/test.pak` = 7473 B（与 pak 完全同长），`files/cef/resources/test/index.html` = 7313 B（**解密后的明文**；zip 里该条目 7350 B = 明文 7313 + OCEF2 头 6 + IV 16 + PKCS#7 填充 15，字节数完全自洽，不是截断）。截图 `tools/cef_kat/out/1-created.png`：面板标题"阶段5 资源通道"、`来源: /test/index.html`。

### 明确限制（未做，不假装做了）

- `ServerConfig.resources_loader_ui` 的**下载进度 UI** 未实现：PC 会弹 `omp:cef:loader` 页面并调 `window.__ompcef.progress/done`，Android 只写日志。协议事件已按 PC 语义发出，服务端 Pawn 的 `OnCefDownloadStart/Finish` 能正常收到。
- 解包在**游戏线程**执行（与 PC 相同），7KB 包无感；若资源包达到数 MB 会掉帧，后续需要移到工作线程再回主线程登记（阶段8 性能项）。
- 不支持 ZIP64 与 zip 级加密（服务端 miniz 默认也不产生）。
- 仍未与原版 omp-cef 服务端插件实连（需服务端 = 阶段8 联调项），当前用同协议的设备端服务端验证。

## 阶段6记录：输入通道（触摸 / IME / 硬件键 / 聊天状态）已实现并真机验证（2026-11-23）

目标（任务书第十一章）：玩家能真正用手机操作下发的页面——页面有焦点时触摸给页面、没焦点时给游戏；点输入框能弹输入法并输入中文；硬件键按 PC 的 `PressKey` 语义上行；游戏聊天框与页面输入框不互相抢键盘。

### 服务端事实（以 `manager.cpp` / `focus.cpp` / `chat.cpp` 为准）

- **键捕获默认关闭**：`key_capture_enabled_ = false`、`key_allowed_` 全零（`manager.hpp:305`）。`PressKey` 只有在 `SetKeyCapture(true)` **且**该 VK 被 `EnableKey` 过才发。
- **`PressKey` 参数**：`[vk:int, scancode:int, modifiers:int, down:bool, repeat:bool]`，且 `if (!(down && repeat))` —— 自动重复一律丢弃，所以线上 `repeat` 恒为 false。
- **`modifiers`** 是 CEF 的 `EVENTFLAG_*`：`CAPS_LOCK_ON=1<<0`、`SHIFT_DOWN=1<<1`、`CONTROL_DOWN=1<<2`、`ALT_DOWN=1<<3`。
- **`ChatInputState`** 上行 `browserId = -1`（`chat.cpp:120`），同时给所有页面发**本地**事件 `omp:cef:internal:chatInputState`（不上行第二份）。
- **`omp:cef:internal:inputFocusChanged`** 走 `FocusManager::SetInputFocus`（`client.cpp:209`），**不是** `FocusBrowser`：浏览器焦点决定触摸/键盘归谁，输入框焦点只用于 `ShouldBlockChat()`。
- **`ShouldBlockChat()`**：`chat_input_enabled` 默认 true；有焦点的浏览器 `controls_chat_input` 为真时游戏聊天框不再打开（`focus.cpp:77`）。
- `ToggleChatInput` 是服务端事件 → `SetChatInputEnabled(bool)`。

### Android 侧实现

- **新增 `CefOverlayView.java`（触摸闸门）**：WebView 唯一父容器，`passThrough` 时 `dispatchTouchEvent` 直接返回 **false**，DecorView 继续把这次手势派发给游戏画面——与 PC `FocusManager::Update()` 把鼠标锁进 CEF / 还给游戏同构。切换方向时给 WebView 补 `ACTION_CANCEL`，避免半截手势。**不能**写在 `onInterceptTouchEvent`（返回 false 仍会交给子 View）。
- **新增 `CefKeys.java`**：Android keyCode → Windows VK + Set-1 扫描码 + `EVENTFLAG_*`。未收录的 keyCode 返回 VK 0（宁可不发，也不臆造近似值）。
- **修改 `CefWebView.java`**：持有 overlay；`focused && visible` 才消费触摸；`setInputFocus` / `hasInputFocus`；`hideIme()`；`dispatchInternalEvent`；**`setImeInset(px)`**（见下）。
- **修改 `CefManager.java`**：`setKeyCaptureEnabled` / `enableKey` / `setChatInputEnabled` / `setInputFocus` / `shouldBlockChat` / `onGameChatStateChanged` / `handleKeyEvent` / `onBackPressed` / `releaseAllFocus` / `cancelGameTouches`，以及 IME 观察器。
- **修改 `SAMP.java`**：`dispatchKeyEvent` 先走 `CefManager.handleKeyEvent`，再在 `ACTION_UP` 上拦 BACK（WebView 会自己吃掉 BACK，`onKeyDown` 收不到）；`onKeyDown/onKeyUp` 在页面有焦点时不再走游戏按键；`onPause` 释放所有页面焦点。
- **修改 `UI.java`**：`showKeyboard(true)` 先问 `CefManager.shouldBlockChat()`；`_onKeyboardVisibilityChanged` 把游戏聊天开/关同步成 `ChatInputState` + 本地事件。
- **修改 `Cef/CefBrowser.h/.cpp`**：`SendPressKey` / `SendChatInputState`；`HandleServerEvent` 新增 `SetKeyCapture` / `EnableKey` / `ToggleChatInput`；`inputFocusChanged` 改调 `SetInputFocus`（此前错调 `FocusBrowser`）。

### 关键修正：软键盘遮蔽（真机实测踩到、已修）

第一版只把窗口 `softInputMode` 从 `adjustPan` 换成 `adjustResize`，日志显示切换成功：
```
I/CefManager: softInputMode -> adjustResize (was 0x20)
```
但 `dumpsys window` 仍是 `sim={adjust=pan}`，截图里输入框依旧压在键盘下沿。原因是**CEF 覆盖层直接挂在 DecorView 上**（不是 `android.R.id.content`），即使窗口被 resize，覆盖层也不会缩——而 `adjustPan` 只平移窗口，Chromium 收不到"视口变小"的信号，也就不会把聚焦元素滚进可视区。

最终方案是**把可见区域直接换算成视口**：`sampleImeInset()` 用 `decor.getWindowVisibleDisplayFrame()` 量出键盘真实高度（阈值取屏高 1/4，用来滤掉导航条），再把同样的高度写成覆盖层的 `bottomMargin`：

```java
// CefWebView
ViewGroup.LayoutParams params = overlay.getLayoutParams();
((FrameLayout.LayoutParams) params).bottomMargin = px;
overlay.setLayoutParams(params);   // WebView 视口真的变矮，innerHeight 跟着变
```

采样时机有两处：全局布局回调（键盘动画会触发 traversal）与页面报告的 `inputFocusChanged(true)` 之后的 5 次补采（80/200/400/700/1200ms），有上限、不轮询。两步设置之间是自洽的：窗口若真被 resize，DecorView 高度就等于可见高度，量出来的 inset 自然是 0，不会重复退让。

### 测试工具

- **测试页新增**：IME 输入框（`input` 事件 → `test:input`）、"收起键盘"按钮、`视口` 统计项、`test:viewport` 探针（`resize` / `visualViewport.resize` / focus/blur 后 350ms 各上报一次 `[tag, innerHeight, visualViewport.height, screen.height]`）。
- **`cef_server.cpp`**：`SetKeyCapture(true)` + `EnableKey` 白名单 `{A,H,E,L,O,ENTER}`（**故意不含 Z**）；统计 `PressKey` 包数/参数类型/按下抬起/VK 0x41/被禁键、`ChatInputState` 开闭与 `browserId`、`test:input` 文本、`test:viewport` 极值；`SetBrowserVisible(false)` 改为**等 `ChatInputState` 开闭都到达再发**（上限 40s），消除"脚本慢一点就假失败"的竞态。
- **`run_browser_test.sh`**：真机注入（`input tap` / `input keyevent 29/54` / `input text hello` / `input keyevent 4`），并把 `test:click` 计数、`dumpsys input_method` 的 `mInputShown` 作为**报文级/系统级证据**（本 ROM 的 logcat 会自己清空，不能当唯一证据）。

### 验证（真机 V2312A / Android 13 / arm64，2388×1080 横屏）

服务端 **24 项断言全 PASS**，`=== STAGE 4+5+6 ROUND TRIP OK`。阶段6 新增的 9 项：

```
PASS  PressKey forwarded (SetKeyCapture + EnableKey honoured)           ← 12 个包
PASS  PressKey carried [vk,scancode,modifiers,down,repeat] with PC types
PASS  injected KEYCODE_A arrived as Windows VK 0x41
PASS  PressKey has both down and up transitions
PASS  key without EnableKey was not forwarded (VK 0x5A)
PASS  ChatInputState reported open and close before the browser hid
PASS  controlsChat page never reopened the game chat (ShouldBlockChat)
PASS  ChatInputState used browserId -1 like the PC client
PASS  IME text reached the page and came back (test:input)
PASS  page reported its viewport (test:viewport on resize)
PASS  IME shrunk the page viewport instead of only panning the window
```

脚本 3 项探针全 PASS：`focused page consumed the tap`（click 0→1）、`after BACK the tap went to the game`（1→1）、`IME was shown`（`mInputShown=true`）。

视口证据（`test:viewport` 上行 + 客户端日志互相印证）：

```
I/CefManager: ime inset=708px (visible bottom 372/1080)
I/CefWebView : setImeInset id=9 px=708 viewport=2298x1080
-> test:viewport ["resize",124,124,360]      <- innerHeight 360 -> 124，dpr 3 => 372px，与可见区一致
-> test:viewport ["resize",360,360,360]      <- 键盘收起后恢复
```

截图：`out/1c-typed.png` 里输入框连同 `hello` 完整显示在键盘上方（此前是被遮住）；`out/3-visible.png` 里页面重新显示后 `视口: 360/360`，聊天状态事件 `open=true/false` 都在页面日志里。

### 明确限制（未做，不假装做了）

- **IME 的"开始/完成"动作不联动表单提交**：现在回车键作为 `VK 0x0D` 上行（已 EnableKey），但页面 `<form>` 的隐式提交未经真机验证。
- **横屏键盘占屏极大**：实测 708/1080 ≈ 65%，可视区只剩 372 设备像素（124 CSS px）。退让是正确语义，但页面必须写成响应式（本次测试页是被迫压扁的），后续做 World2D 时要给页面一条"键盘态"提示。
- `game:data:pollPlayerStats`（需要 SA-MP 内存读值）未实现。
- 多浏览器同时存在输入框时的焦点抢占细节未覆盖（当前单焦点语义已对齐 PC）。
- 仍未与原版 omp-cef 服务端插件实连（阶段8 项）。

## 阶段7记录（第一批）：World2D 已实现并真机验证（2026-11-23）

任务书第十三章要求 World2D / WorldObject3D。本阶段先落地 **World2D**，并在文档里明确 WorldObject3D 的可行路径与当前边界。

### 服务端/PC 事实（照 `app.cpp` / `manager.cpp` 复刻）

- `CreateWorld2DBrowser` 参数 11 个：`[id, url, worldX, worldY, worldZ, width, height, offsetZ, pivotX, pivotY]`（`app.cpp:424`）。
- **`width/height` 是浏览器视口像素尺寸**，PC 端 `clamp((int)width, 1, 1024)`（`manager.cpp:812`），**不是**世界尺寸；Pawn 侧默认 `0.0` 会被夹成 1×1。
- 绘制：每帧把 `(x, y, z + offsetZ)` 用 **`CSprite::CalcScreenCoors`** 投影，`out.z <= 0` 就跳过不画；位置为
  `x = screen.x - width*pivotX`、`y = screen.y - height*pivotY`，再 `view.SetPosition` 后画进 2D 层（`manager.cpp:1569`）。
  默认 `pivot=(0.5, 1.0)`、`offsetZ=1.0`，即"锚在头顶、下边中点对齐投影点"。
- `SetWorld2DBrowserPos`：4 个参数 `[id, worldX, worldY, worldZ]`，只改坐标。
- World2D 浏览器 `controls_chat_input = false`，且 `OnBrowserCreated` 对 World2D 同样回报 `BrowserCreateResult`。

### Android 侧实现

- **投影复用游戏自己的函数**：`libGTASA.so` 导出了 `_ZN7CSprite15CalcScreenCoorsERK5RwV3dPS0_PfS4_bb`（设备上 `llvm-nm -D` 实测 `0x6e9df8 T`），因此不重写 FOV/裁剪数学，直接用 `g_saSym->GetSymbol` 解析后调用，与 PC 完全同源。
- **`CefBrowser::TickWorld2D()`**：由 `Client.cpp` 在 `CefSession::Tick()` 之后每帧调用（**游戏线程**，与 PC 的渲染线程语义一致，避免跨线程读 `TheCamera`），投影结果变化超过 0.5px 才回调 Java，静止目标不产生 JNI 噪声。
- **`CefManager.createWorld2DBrowser` / `setWorld2DScreen`**：WebView 的 `LayoutParams` 直接用服务端给的 `width×height`（`clamp 1..1024`，与 PC 相同的警告日志），位置用 `translationX/Y` 施加 `view.SetPosition` 的等价效果；投影失败/在相机背后时隐藏覆盖层（等价 PC 的 `continue`），且与服务端 `SetBrowserVisible` 取与。
- 生命周期与其它 browser 共用：`DestroyBrowser` 会同时从 World2D 表里摘除，断线（`OnSessionLost`）清空整表。

### 验证（真机 V2312A / Android 13 / arm64）

服务端新增 2 项断言（共 26 项全 PASS，`=== STAGE 4+5+6 ROUND TRIP OK`）：

```
PASS  World2D browser loaded (probe:ready from its own browser id)
PASS  World2D viewport honours the requested px size (384x192 -> 128x64 css)
INFO  world2d viewport 128x64 css (requested 384x192 px)
```

脚本新增 2 项命中探针（共 5 项全 PASS）：

```
PASS  tap inside the projected World2D rect reached the page (test:tap)
PASS  tap outside the World2D rect went to the game (no test:tap)
```

投影与命中的设备侧证据（`debug world2d` 把页面放在本地玩家头顶 +1.5m）：

```
[CefBrowser] debug world2d id=12 at ped (2090.6,1164.6,11.6) +1.5m size=512x256
[CefBrowser] world2d id=12 world=(2090.6,1164.6,11.6) -> screen=(1149.0,239.3) pos=(893,-17) size=512x256
[CefWebView] setFocus id=12 focus=true touch=page
[CefBrowser] js emit 'probe:ready' -> server (browser 12, 5 args) ["...",171,86,3,true]   <- 512x256 px / dpr3
[CefBrowser] js emit 'test:tap'   -> server (browser 12, 3 args) ["H1",56,42]
```

截图 `out/5-world2d.png`：512×256 的小面板出现在玩家头顶投影点处（`pos=(893,-17)`，被屏幕上沿裁掉一部分，与投影结果一致）。

### 明确限制（未做，不假装做了）

- **无世界遮挡**：页面是叠在游戏画面上的 View，不会像 PC 那样参与深度测试（PC 的 World2D 也是画在 2D 层，这一点反而接近）。
- **WorldObject3D 未实现**：它要求把页面纹理塞进 GTA 自己的 GL 场景（PC 是 `CEntity` 换贴图）。Android 可行路径是 `WebView → SurfaceTexture` + 在 `eglSwapBuffers` 钩子里用游戏上下文补一次 quad 绘制；这条通路涉及线程/上下文同步，属于下一批工作，当前 `CreateWorldBrowser` / `AttachBrowserToObject` / `DetachBrowserFromObject` 仍只记日志、不假装支持。
- World2D 的 `width/height` 单位是**物理像素**，而 Android dpr=3，因此同样写 512×256 时页面 CSS 视口只有 170×85；服务端调尺寸时需要按 dpr 折算（PC 上 dpr=1 无此问题）。
- 每帧回调走 `post` 到主线程，多目标（几十个）时会成为瓶颈，阶段8 需要合并成一次批量调用。

## 阶段 7.5 记录：离线可验证性（不需要设备）2026-11-23

### 为什么不是"用模拟器代替真机"

先给结论：**这套东西在模拟器上跑不起来**，不是配置问题，是三条硬约束：

1. 工程只编 `arm64-v8a`（`app/build.gradle` 的 `abiFilters`，因为 `libGTASA.so` 只有 arm64 一份）。x86_64 模拟器没有 arm64 翻译层（新版 emulator 的 arm64 镜像在 x86 主机上实际不可用），装上就是 `dlopen` 失败。
2. 客户端主体是**游戏进程内的 native 模块**：要 `dlopen` 8.9MB 的 `libGTASA.so`、读写游戏内存、调 `CSprite::CalcScreenCoors`。模拟器上既没有这个 so，也没有等效的游戏状态。
3. 本机 SDK 里根本没有 emulator：`D:/android-sdk/` 只有 build-tools / cmake / cmdline-tools / ndk / platform-tools / platforms，`emulator/`、`system-images/`、AVD 全都不存在（要装得先下 ~1.5GB 镜像，且落回第 1 条）。

所以设备端的事（触摸派发、IME inset、GLES 投影、JNI 往返）**只能在真机上验**。能离线的部分是"纯逻辑"，而纯逻辑恰好是最容易在后续改动里被悄悄改坏的部分。

### 抽出来的三处纯逻辑（行为等价，不是重写）

| 位置 | 原内联代码 | 抽到 |
|---|---|---|
| 键门控 | `handleKeyEvent` 里 capture 开关 / 白名单 / 丢弃自动重复三连 | `CefLogic.shouldSendKey(...)` |
| IME 高度 | `sampleImeInset` 里 `height - frame.bottom` + 1/4 阈值 | `CefLogic.imeInsetOf(...)` |
| 聊天让位 | `shouldBlockChat` 三级判定（含 `controls_chat_input`） | `CefLogic.shouldBlockChat(..., controlsChatInput)` |
| metaState→EVENTFLAG | `CefKeys.modifiersOf(KeyEvent)` | `CefKeys.modifiersOfMetaState(int)`，原方法保留为一行包装 |
| World2D 落点 | `TickWorld2D` 里 `screen - size*pivot` + `z<=0` 隐藏 + 0.5px 门限 | `cefworld::Place2D` / `MovedBy`（`CefWorldMath.h`） |

`CefLogic.java` 不 import 任何 android 类；`CefWorldMath.h` 不依赖 JNI/游戏符号。

### 离线断言怎么跑

```bash
bash tools/cef_kat/logic_tests/run_logic_tests.sh      # 142 项，约 2 秒
```

做法：把产品源码里的 `CefKeys.java` / `CefLogic.java` **原样**复制到临时 ASCII 目录，
`android.jar` 只作**编译期** classpath，**运行时故意不带** `android.jar`。这样：

- 键表常量被 javac 内联，`vkOf/scanOf` 在桌面 JVM 上能真跑；
- 一旦有人往这两个类里引入 android 的运行时类型，测试会当场 `NoClassDefFoundError`——
  "纯逻辑"这条约束被破坏时立刻报警。

`CefWorldMath.h` 用的是 `constexpr` + `static_assert`，**每次 `assembleDebug` 都会执行**，
不需要单独跑。

### 两套断言都做过负向验证（证明它们真的在跑）

| 故意改坏 | 结果 |
|---|---|
| `CefWorldMath.h` 里 `744.0f` 改成 `745.0f` | `error: static_assert failed due to requirement 'Place2D(...).x == 745.F'`，BUILD FAILED |
| `CefLogic` 的 IME 阈值 `4` 改成 `2` | `FAIL exactly 1/4 of the window is treated as keyboard [want=270 got=0]` 等 2 项，exit 1 |

两次都已改回，当前 142/142 + 编译全绿。

### 断言覆盖（142 项）

- **键表 26 字母 + 10 数字 + 常用键 + 方向/编辑/F1-F12/小键盘**：期望值在测试里**独立硬编码**一遍（按 PC/AT Set-1 规范，不复用产品表），能抓产品侧笔误。
- **表不变量**：有 VK 必有 scan、有 scan 必有 VK、所有 VK ≤ 255（超出 `EnableKey` 256 项白名单就永远发不出去）、收录数 ≥ 90。
- **未收录键必须返回 0**：`KEYCODE_BACK` / `KEYCODE_HOME` / `KEYCODE_MENU` / 音量键 / 越界 keyCode——绝不臆造近似 VK 骗服务端。
- **PressKey 门控**：capture 关不发、白名单外不发、`down && repeat` 丢弃、**`up && repeat` 必须发**（否则服务端"按下没有抬起"，页面 keyup 永不到达）、vk 0/256/负数/null 白名单都不发。
- **IME 高度**：真机实测 `(1080, 372) -> 708`；导航条 48px → 0；正好 1/4 边界 → 保留；越界/零高/负高 → 0。
- **聊天让位**：服务端关 chat 优先于一切；无输入焦点不阻断；`controls_chat_input=false` 的页面不阻断。
- **World2D 落点**：真机那一帧 `(1149,239,11.6) + 512x256 + pivot(0.5,1.0) -> (893,-17)`；z≤0 / 投影失败 → 隐藏且坐标归零；0.5px 亚像素门限两个方向。

### 一条命令跑完整验收

```bash
bash tools/cef_kat/verify_all.sh [package]
```

四段自动串起来，全程不需要在手机上操作（安装被系统拦成"继续安装"对话框时会自动点）：

1. 离线逻辑断言（142 项）
2. 编译（含 C++ constexpr 断言）
3. 装机 → 启动器 → 单机模式 → 等进游戏（轮询 `topResumedActivity`）
4. 设备端全量回归（26 项协议断言 + 5 项探针 + 6 张截图）

日志落 `tools/cef_kat/out/verify-{logic,build,device}.log`。

### 顺带修掉的测试工具缺陷

- `probe:ready` 原来在 WebView 布局完成前就发，`innerWidth/innerHeight` 还是 0，服务端把 0x0 当视口尺寸 → World2D 尺寸断言偶发 FAIL（上一轮 PASS，这一轮 FAIL，就是这个竞态）。现在页面等尺寸有效再报，最多重试 1s 后兜底发送。
- **World2D 落点探针原来依赖 logcat**：脚本从 `app.log` grep 客户端自己的投影日志来算点击坐标；这台 ROM 的 logcat 常常 0 行，脚本就回退到屏幕中心 → 点空 → 假 FAIL（`3 -> 3`）。改成客户端把投影结果走**已有上行通道**发 `debug:world2d [id,x,y,w,h,onScreen]`（400ms 节流，只在调试广播创建的条目上发），脚本从 `server.log`（本机文件，100% 可靠）读。实测 `pos=(892,-16) size=512x256 on=1` → 脚本点 `(1148,112)` → 命中。
- `verify_all.sh` 里 `adb install` 曾因 `MSYS_NO_PATHCONV=1` 把 `/d/服务器/...` 原样传给 adb 而失败（`failed to stat`，于是静默跑了旧包），改用 `cygpath -w`。
- 阶段6 那次还踩过：安装被 `PackageInterceptActivity` 拦下 → 脚本现在检测并自动点"继续安装"。

### 最终验收（2026-11-23 19:2x，真机 V2312A）

```
== 1/4 离线逻辑断言（不需要设备）
142 passed, 0 failed / LOGIC TESTS OK
== 2/4 编译
BUILD SUCCESSFUL in 40s   apk 41,486,989 bytes  sha256 302724de677df063
== 3/4 装机并进入游戏
Performing Streamed Install / Success / 已进入游戏
== 4/4 设备端全量回归
5 项探针 PASS（0 probe failure）
26 项协议断言 PASS  === STAGE 4+5+6 ROUND TRIP OK
=== VERIFY ALL OK
```

截图 `out/5-world2d.png` 里那个 `omp-cef` 蓝框就是 512×256 的 World2D 面板（CSS 视口只有 128×64，被压缩到只剩标题），位置与 `pos=(892,-16)` 一致。

### 明确限制（不假装）

- 离线断言只覆盖**纯函数**。触摸派发、IME 真实 inset、GLES 投影、JNI 往返、WebView 生命周期仍然只能真机验——`verify_all.sh` 的第 3/4 段就是干这个的。
- 没有 CI：断言靠本地 `verify_all.sh` 串起来，提交前手动跑。

## 阶段7 第二批记录：WorldObject3D（页面贴到游戏物体表面）2026-11-23

### 服务端/PC 事实（照 `app.cpp` / `manager.cpp` 复刻）

| 事件 | 参数 | PC 行为 |
|---|---|---|
| `CreateWorldBrowser` | `[id, url, textureName(string), width(float), height(float)]` | 建离屏浏览器 + `WorldRenderer(textureName, w, h)`，尺寸 `clamp(1..1024)` |
| `AttachBrowserToObject` | `[browserId, objectId]` | `entityToBrowserId_[entity] = browserId`，**解静音** |
| `DetachBrowserFromObject` | `[browserId, objectId]` | `OnAfterEntityRender` 恢复原纹理，**静音** |

PC 真正的贴图发生在 `OnAfterEntityRender`，通过换实体的材质/纹理实现。

### Android 实现（关键发现：不用自己发明贴图通道）

1. **材质替换复用 SA-MP 现成机制**：`CObject::ChangeCustomMaterialObjectCB` 直接把 `m_Material[i].pMaterialTexture` 赋给 `materials[i]->texture`，由 `CObject_Render` 钩子在渲染前后切换。所以只要设置 `pObject->m_Material[0].pMaterialTexture` + `m_bHasCustomObjectMaterial = true` 即可——和 `SetObjectMaterial` 是同一条路。
2. **纹理生成复用 `MaterialTextGenerator` 的配方**：`RwRasterCreate(w, h, 32, rwRASTERFORMAT8888 | rwRASTERTYPETEXTURE)` + `RwTextureCreate`。`ImTextureID` 在这套工程里本来就是 `RwRaster*`，说明 raster 可直接被采样。
3. **WebView → 纹理**：Java 侧 `captureFrame()` 用 `draw(Canvas)` 读回 RGBA，C++ 侧 `RwRasterLock` 逐行 memcpy（按 `raster->stride`，不是 width*4）再 `Unlock`，RenderWare 自己上传 GL。
4. **objectId → CObject**：协议路径走 `pNetGame->GetObjectPool()->GetAt(objectId)`；单机没有 SA-MP 池，调试入口用 `pGame->NewObject()`（内部 `ScriptCommand(&create_object)`）生成真实物体，走完全相同的材质通道。

### 四个真 bug（都是真机踩出来的，不是推演）

1. **纹理未初始化 → 游戏崩溃**。`RwRasterCreate` 后没写像素就被 GPU 采样，直接退回启动器（截图 `6-worldobj.png` 是服务器列表）。修：创建后立刻 `FillSolid()` 铺纯色。
2. **非 2 的幂 + WRAP 寻址**。512×256 在 GLES2 上不能用 `rwTEXTUREADDRESSWRAP`（`GL_INVALID_OPERATION`，采样结果未定义）。修：改 `CLAMP`。
3. **`DebugWorldObject` 在 Java 主线程执行游戏 API**。广播 receiver 直接调 `pGame->NewObject()`（内部 `ScriptCommand` + RenderWare 创建），跨线程把游戏循环搞停——表现为 63s 之后客户端一条上行都没有，但进程还活着。修：改成投递到游戏线程，在 `TickWorldObject3D` 顶部消费。
4. **`std::vector<WorldObjectEntry>` 跨线程 push_back**。JNI 线程 `push_back` 时游戏线程正在遍历，realloc 让引用失效。修：`std::recursive_mutex` + Tick 快照 id 列表。

另外把离屏 WebView 的图层从 `LAYER_TYPE_HARDWARE` 换成 `LAYER_TYPE_SOFTWARE`：硬件层下 Chromium 内容在合成器里，`draw(Canvas)` 要等合成，实测把主线程卡死；软件层走 CPU 光栅化，512×256 下开销可接受。

### 验证（真机 V2312A / Android 13 / arm64）

服务端断言（上行报文）：

```
[t= ...] PASS  WorldObject3D page swapped onto the object material      (stage=1)
[t= ...] PASS  WebView frame reached the RenderWare texture (first upload) (stage=2)
[t= 95053ms] === STAGE 4+5+6 ROUND TRIP OK
=== VERIFY ALL OK     （142 项离线 + 26 项协议 + 5 项探针）
```

视觉确认 `tools/cef_kat/out/10-worldobj.png`：玩家正前方那块板（模型 19377）表面显示的就是测试页面的 `omp-cef` 标题框——页面内容真的进了游戏物体的材质。

### 明确限制（不假装）

- **不是 PC 那种"换 entity 贴图"**：PC 直接改实体自己的 texture；Android 走的是 SA-MP 的 object material 通道（只作用于 `CObject`，不作用于任意 `CEntity`）。
- **只贴 material[0]**：多 material 模型只换第一个，其余保持原样。PC 也没有更好的通用做法，但这里限制更硬。
- **抓帧是 CPU readback**：4Hz、512×256，每次 ~0.5MB memcpy + 纹理上传。PC 是浏览器直接画进共享纹理，零拷贝。想提高到 30fps 需要 CEF 的 `OnPaint` 回调或 `SurfaceTexture`，那要换渲染引擎，不属于 WebView 方案。
- **模型 UV 决定观感**：贴在 19377 上会出现镜像 + 平铺，因为模型自带 UV 超出 [0,1]；服务端应选 UV 规整的模型（PC 同理）。
- **音频未接**：PC 在 attach 时解静音，Android 没实现 audio stream。


## 阶段8 记录（第一批）：WorldObject3D 抓帧链路优化 2026-11-23

### 优化目标只有一个：主线程每次 `draw(Canvas)` 的代价是唯一的预算

WebView 没有 GPU 纹理出口，页面必须由主线程 CPU 光栅化后再读回，所以"能抓多快"完全由这一次 draw 决定，跟游戏线程问得多勤无关。改动全部围绕这一点：

| 项 | 改前 | 改后 |
|---|---|---|
| 抓帧节奏 | 固定 250ms（4Hz），与代价无关 | 每条目按**上次实测** `draw+copy` 耗时 ×3 排期，clamp 66..400ms |
| 一 tick 抓几个 | 所有条目串行抓 | 最多 2 个，最老 deadline 优先，剩下的下个 tick 补 |
| 像素传输 | `Bitmap` → direct `ByteBuffer` → **新建 `byte[]`**（每帧 0.5MB 分配）→ `GetByteArrayElements`（再拷 0.5MB） | 复用 `Bitmap`/`Canvas`/direct `ByteBuffer`，JNI `GetDirectBufferAddress` 直接读，**零额外拷贝** |
| World2D 位置更新 | 每次投影变化都 `post` 一个 Runnable（相机一动最多 60/s，把主线程队列灌满，离屏抓帧挤不进去） | 只保留最新一帧：已有 Runnable 排队时只更新参数，不重复投递 |
| 释放 | 只有服务端 `DestroyBrowser` 才清条目 | Java 侧销毁（调试广播、服务端没确认的销毁）也走 `forgetWorldObject`，队列到游戏线程清纹理 |

### 实测（真机 V2312A，512×256 页面）

```
[t= 67644ms] debug:worldobjperf id=13 frames=39/3047ms (12.8 fps) rasterMs=5
[t= 70642ms] debug:worldobjperf id=13 frames=43/3009ms (14.3 fps) rasterMs=5
[t= 73680ms] debug:worldobjperf id=13 frames=43/3030ms (14.2 fps) rasterMs=4
[t= 74985ms] debug:worldobj id=13 object/model=-1 stage=6      <- 条目已释放，此后不再上报
```

13.8–14.6 fps，单次 draw+copy 3–7ms；改前是固定 4Hz（in-flight 保护下实际更低）。约 **3.5 倍**。

### 一个真 bug：try/catch 把 `UnsatisfiedLinkError` 吞了

`nativeForgetWorldObject` 忘了写 `extern "C"` → C++ 把它 mangle 成 `_Z66Java_...` → Java 调用抛 `UnsatisfiedLinkError` → 空的 catch 吞掉 → 现象是"代码改了、编译过了、行为完全没变"，wire 上表现为 destroy 之后 perf 还在报 `frames=0`。两处修：

1. 加 `extern "C" JNIEXPORT void JNICALL`，catch 里至少 `Log.w`。
2. 新增 `tools/cef_kat/jni_symbol_check.sh`：从 `CefManager.java` 解析 `native` 声明，用 `llvm-nm -D` 校验每条都有**未 mangle**的符号，接进 `verify_all.sh` 第 2b 段。负向验证：修之前它报 `FAIL nativeForgetWorldObject: 符号被 C++ mangle`，修之后 `JNI SYMBOLS OK (10 native methods in libSAMPMOBILE.so)`。

顺手修了这个脚本自己的一个坑：`printf ... | grep -q` 在 `set -o pipefail` 下会因为 grep 命中即退、printf 收 SIGPIPE 而把成功判成失败（100% 假阳性，10 条全报 mangle）——改成先写临时文件再 grep。

### 验证

服务端断言 26 → 30 项全 PASS，新增 4 条：

```
PASS  WorldObject3D reported capture throughput (debug:worldobjperf)
PASS  WorldObject3D sustained >6 fps (previous fixed ceiling was 4 Hz)
PASS  one 512x256 page rasterisation measured under 200 ms
PASS  WorldObject3D entry released after the browser was destroyed     (stage=6)
```

`verify_all.sh` 现在是 5 段（离线 142 项 → 编译 → JNI 符号 → 装机 → 设备全量回归），全部 PASS，`=== VERIFY ALL OK`。

视觉：`tools/cef_kat/out/6-worldobj.png` 里板子上的页面内容仍在（透过 App 自己的半透明商店面板能看到平铺的 `omp-cef` 标题框）。注意这一张的前景是 App 自带 `assets/web/index.html` 面板，不是 CEF 页面。

### 明确限制（不假装）

- **14 fps 是这套通道的上限附近，不是参数**：想 30fps 必须换渲染通道（`SurfaceTexture` / CEF `OnPaint` 回调），WebView 的 `draw(Canvas)` 走 CPU。
- **自适应按"上次"耗时排期**：冷启动头几帧页面还在布局，实际帧率会比稳态低一档。
- **只对 WorldObject3D 节流**：Overlay2D / World2D 页面由系统合成，不在这套调度里；World2D 只做了位置更新的合并。

### 阶段8 还剩（未做）

- 与原版 `aurora-mp/omp-cef` 服务端插件**实连**（现在跑的是 `tools/cef_kat/cef_server.cpp` 复刻的测试服务端，协议逐字段照抄但毕竟不是原插件；原插件是 open.mp component，需要 Windows/Linux 上的构建链）。
- 双 ABI：`armeabi-v7a` 缺 32 位 `libGTASA.so`，目前只出 arm64-v8a。


## 阶段8 第二批：与原版 omp-cef 服务端组件实连（真机端到端）2026-11-24

这一节就是任务书的最终验收条件：**用未修改的原版服务端插件跑通**。跑通了，一条命令可复现。

### 用什么跑的

| 项 | 值 | 说明 |
|---|---|---|
| 服务端 | open.mp `1.6.7.3218`（Windows x86） | 本机既有服务端，另开一个独立目录，端口 7877，不碰用户现有服 |
| CEF 组件 | `cef-omp-v1.3.0-x86.zip` 里的 `components/Cef.dll` | **官方 release 的预编译二进制**，不改一行源码 |
| 协议版本 | 客户端 `1.3.0.0` ↔ 组件 `1.3.0.0` | `plugin.cpp` 是 `==` 严格比较，差一个号就是 `CEF_INIT_VERSION_MISMATCH` |
| 客户端 | 本项目 APK，走**玩家路径**：启动器收藏夹 → 进服务器 → 游戏连上 → CEF 自动连 `端口+2` | 不是调试广播直连 |
| 测试页 | `omp-cef-build/www/index.html`，PC 上 `python -m http.server 8000` | 手机经局域网加载 |

### 一条命令

```
bash tools/cef_kat/e2e_real_plugin.sh
```

自动做：清端口 → 起 HTTP → 起 open.mp+Cef.dll → 设备写收藏 → 重启 App → 点服务器 → 点「进入服务器」→ 等握手 → 按时间戳切片断言 → 汇总。约 50 秒。

```
PASS  原版插件收到本轮客户端的 RequestJoin（含 IP 校验行）
PASS  握手成功（CEF_INIT_OK，版本/密钥/IP 全部匹配）
PASS  OnCefInitialize 之后服务端调了 CEF_CreateBrowser
PASS  浏览器在客户端建成功（BrowserCreateResult 回到 Pawn）
PASS  页面 window.cef.emit 回到服务端（CEF_RegisterEvent 触发了回调）
PASS  服务端在建物（CreateObject）后拿到真实 objectid
PASS  WorldObject3D：CEF_CreateWorldBrowser 下发了
PASS  WorldObject3D：CEF_AttachBrowserToObject 下发了
PASS  客户端把页面材质贴到了真实 objectid 上（stage=1 材质已替换）
PASS  页面首帧进了 RenderWare 纹理（stage=2 首帧上传）
=== E2E (REAL PLUGIN) OK
```

服务端日志（真机 V2312A / Android 13 / arm64）：

```
[e2e] OnPlayerConnect player=0
[CEF] RequestJoin pid=0 from=192.168.0.173:48395 official=192.168.0.173
[e2e] OnCefInitialize player=0 success=1 reason=0 message=""
[e2e] CEF_CreateBrowser(player=0, id=7, http://192.168.0.117:8000/index.html)
[e2e] OnCefReady player=0
[e2e] OnCefBrowserCreated player=0 browser=7 success=1 code=0 reason="Successfully created"
[e2e] PAGE -> SERVER  player=0 browser=7 text="android-webview 766x360"
[e2e] board object id=1 model=19377 at 0.0 8.0 3.0 (player 0.0 0.0 3.0 ang 0.0)
[e2e] CEF_CreateWorldBrowser(player=0, id=8, http://192.168.0.117:8000/index.html, 512x256)
[e2e] CEF_AttachBrowserToObject(player=0, browser=8, object=1)
CLIENT worldobj debug: browser=8 a=8 b=1 c=1        <- 材质已替换（object 1）
CLIENT worldobj debug: browser=8 a=8 b=1 c=2        <- 首帧已上传
PAGE -> SERVER  player=0 browser=8 text="android-webview 171x86"   <- 512/3 x 256/3，视口就是服务端给的像素尺寸
```

截图 `tools/cef_kat/out/e2e-2-ingame.png`：游戏里整屏是服务端下发的页面，页面上「服务端下发: server tick #12」说明**原版插件 → 页面**的方向也在实时跑。
截图 `e2e-3-world3d.png`：关掉 Overlay2D 后，服务端 `CreateObject` 出来的板子（model 19377）表面显示的就是这个页面（平铺+镜像，是模型自带 UV 的问题，见阶段 7 的限制）。

### 这一轮抓到的真 bug：WorldObject3D 的 attach 会先于物体到达

在调试入口里永远测不出来，因为那条路是客户端自己 `pGame->NewObject()`，物体一定先存在。真实服务端上：

- `CreateObject` 走**游戏 RakNet 通道**，`CEF_AttachBrowserToObject` 走**CEF 的 KCP 通道**，两条通道之间没有顺序保证；
- 服务端在同一 tick 里建物体 + attach 时，attach 先到，客户端 `GetObjectPool()->GetAt(1)` 还是空 → 以前直接 `return`，板子上永远是模型原纹理（截图证据：一块纯米色的墙）。

修法（`CefBrowser.cpp`）：attach 解析不到物体时**入队重试**（`pendingAttaches_`，8 秒超时），在 `TickWorldObject3D` 里每帧重试；解析成功才贴材质并发 stage=1，超时发 stage=8（失败）——两者都走 wire，能断言。物体销毁/浏览器销毁时同步清掉挂起项，避免贴到已销毁的纹理上。

修完 wire 上立刻出现 `c=1`（材质已替换）和 `c=2`（首帧上传），板子上也真的出现页面内容了。

### 踩到的五个真问题（都不是客户端 bug，但都会让验收失败）

1. **组件版本必须和客户端严格相等**。仓库 `main` 是 1.4.1，客户端声明 1.3.0，直接用 main 会 `reason=2`（版本不匹配）。要么拿 v1.3.0 的 release，要么把客户端版本抬上去并核对 1.3→1.4 的协议差异——本次选前者（不动代码）。
2. **open.mp 自己不开游戏端口**。第一次启动只看到组件初始化成功、但 7877 没监听、gamemode 一行日志都没有：原因是 `components/` 里只有 `Cef.dll`，缺 `LegacyNetwork.dll`（网络层是组件，不是核心）。补齐标准组件后立刻正常。
3. **`enable_query` 默认 false** → 手机启动器列表里这个服务器显示「无法连接」。那是**查询**失败，不是连不上；测试服必须开 `enable_query: true`。
4. **Windows 防火墙按程序路径放行**。同一个 `omp-server.exe` 换了目录，入站就被挡：`netsh advfirewall firewall add rule ... protocol=UDP localport=7877,7879`（另有 8000/TCP 给测试页）。
5. **服务端在 `OnCefBrowserCreated` 里立刻 `CEF_EmitEvent` 会丢**：那一刻页面还没加载完，页面自己的 `cef.on(...)` 还没注册。改成 2 秒定时推送后立刻正常。这说明客户端的"页面就绪前排队"只保证**文档已就绪**，不保证**页面的 handler 已注册**——PC 端同样如此，服务端脚本别指望第一条就送达。

### 测试基建：这次差点又出假 PASS

第一版脚本只 grep「出现过 RequestJoin」，结果**点击之前就全绿**：上一轮残留的游戏连接在服务器一起来的那一秒自动重连了（SA-MP 客户端有重连），断言验的是上一轮。而且 open.mp 的脚本 `print` 是缓冲落盘的，日志行序≠时间序，用"插入 marker 行再切片"也切不准。

改法：App `force-stop` 后按**点击时刻**取 `SINCE=$(date +%Y-%m-%dT%H:%M:%S)`，只统计时间戳 ≥ SINCE 的行。改完重跑，事件时间戳落在点击后 10 秒（00:02:06 → 00:02:16），才是真证据。

另外老坑复发一次：`adbq` 里 `MSYS_NO_PATHCONV=1` 让 `adb push "/d/服务器/..."` 直接失败，必须 `cygpath -w`。

### 明确限制（不假装）

- `World2D`（世界锚定的 2D 页面）这一轮没验 —— 已在**第三批**补上（见下一节）。
- 只测了 0.3.7-R5 客户端连 omp 1.6.7；0.3.DL-R1 没测。
- 服务端只跑 Windows x86 组件；Linux `.so`（`components/Cef.so`）没验。
- 脚本会改设备上的 `servers.json`（已备份为 `servers.json.bak`），并在后台留下 open.mp 与 HTTP 服务。
- 板子上的页面是**平铺 + 镜像**的，原因是模型 19377 自带 UV 超出 [0,1]；这是模型选择问题（PC 同理），不是渲染错误。服务端要选 UV 规整的模型。






---

## 阶段8 第三批：World2D 走原版插件（真机端到端）2026-11-24

第二批只验了 Overlay2D + WorldObject3D。World2D 是唯一还挂在"调试入口验过、真实协议没验"状态的一条，
本批补上：**服务端给世界坐标，客户端每帧自己投影**，这条链路里的"投影输入"来自真实 Pawn 调用。

### 协议事实（`cef.inc` / `cef_extension_api.cpp`）

| 事件 | 参数 | 客户端行为 |
|---|---|---|
| `CreateWorld2DBrowser` | `[id, url, worldX, worldY, worldZ, width, height, offsetZ, pivotX, pivotY]` | 建浏览器，每帧把 `(x, y, z+offsetZ)` 投影到屏幕 |
| `SetWorld2DBrowserPos` | `[id, worldX, worldY, worldZ]` | 只换锚点，不重建浏览器 |

- `width/height` 是**页面视口像素尺寸**，不是世界单位；`pivotX/Y` 是页面左上角相对投影点的偏移比例
  （`0.5/1.0` = 底边中点贴在锚点上，PC 默认值）。
- 客户端实现：`CSprite::CalcScreenCoors` 投影 → `CefWorldMath.h::Place2D` 算左上角 → Java `setScreenPosition` 摆 WebView。
  公式与 PC `manager.cpp` 一致，已在阶段 7 用 `static_assert` 固化。

### 本批新增的验证通道：`world2dprobe`

服务端建的 World2D 条目 `debugProbe=false`（原来只有调试广播建的条目会上报），所以真实插件下
**服务端看不到页面被算到了屏幕哪里**。新增一个只影响上报、不影响绘制/输入的开关：

```
adb shell am broadcast -a com.sampmobile.CEFDEBUG -p <pkg> --es action world2dprobe --ei on 1
```

打开后每条 World2D 都按 ~2.5Hz 发 `debug:world2d [id, x, y, w, h, onScreen]`。默认关，
正常玩家不为这个付开销。

### 真机结果

```
[e2e] CEF_CreateWorld2DBrowser(player=0, id=9, world=0.0 8.0 3.0, 512x256, offsetZ=2.2, pivot=0.5/1.0)
CLIENT world2d debug: browser=9 bid=9 x=893 y=110 w=512 h=256 on=1
[e2e] CEF_SetWorld2DBrowserPos(player=0, id=9, 0.0 8.0 4.5)  (+1.5m 向上)
CLIENT world2d debug: browser=9 bid=9 x=893 y=-109 w=512 h=256 on=1
[e2e] WORLD2D SHIFT ok dy=-219 (y 110 -> -109)
```

- `w=512 h=256` 说明尺寸走的是**协议字段**，不是本地默认值。
- 抬 1.5m 世界坐标 → 屏幕 y 变化 219px，方向向上，与透视一致（8m 距离、1080p 竖视角）。
- `x=893` 与阶段 7 记录的实测帧 `screen=(1149,239) → pos=(893,-17)` 完全同源（`893 = 1149 - 512*0.5`）。

截图 `tools/cef_kat/out/e2e-3-world3d.png`：一块板（WorldObject3D，模型 19377）表面是页面，
板子上方**另有一个悬浮的 512×256 页面**（World2D），两张图同框，说明两条链路同时在跑。
`e2e-4-world2d.png` 是抬高 1.5m 之后的同一帧：悬浮页整体上移 219px，顶端被屏幕边缘裁掉。

### 抓到的三个真问题

1. **`OnCefReady` 比 `OnPlayerSpawn` 早十几秒**：World2D 的锚点是 spawn 时按板子位置算的，
   最初在 `OnCefReady` 也调了创建 → 锚点 `(0,0,0)`，且 `gWorld2DReady` 已置位，spawn 后不再重建，
   页面永远贴在一个不存在的位置上。修：加 `gSpawned[playerid]` 门槛，两条路径都调、只认 spawn 之后的。
2. **原版组件传 bool 参数给 Pawn 会少一个槽**：探针第 6 个参数 `onScreen` 用 `Argument_Bool` 注册时，
   Pawn 侧 6 个参数只剩 5 个，**整体左移一个**（表现为 `x=9`、`w=893`、`on=256`，看着像投影算错）。
   修：客户端改发 int `0/1`，注册 `Argument_Integer`。这条是组件的类型推断行为，不是客户端 bug。
3. **探针节流是函数级 `static`**：服务端同时开两条 World2D 时，只有第一条能上报，第二条永远"没投影"。
   修：节流时间戳挪进 `World2DEntry`。

### 明确限制（不假装）

- 只验了 `pivot=(0.5,1.0)`。其它锚点组合靠 `CefWorldMath.h` 的编译期断言覆盖，没有逐组真机跑。
- World2D 的**点击命中**在真实插件路径没单独验（调试入口那条在阶段 6 验过，用的是同一段 `setScreenPosition` + 触摸分发）。
- 页面被世界物件遮挡时不会自动隐藏（WebView 是游戏画面之上的独立层），PC 端也一样，这是已知语义差异而非本批引入。
- 卸载 `world2dprobe` 开关后探针停止；验收脚本每次会显式打开，玩家侧永远是关的。

---

## 阶段8 收口：完成度与边界（2026-11-24）

阶段 8 三批做完后，把"完成"拆成可以逐项核对的东西。**这一节是结论，不是计划。**

### 可复现的两个入口

```bash
bash tools/cef_kat/verify_all.sh        # 5 段：离线断言 142 / 编译(含 static_assert) / JNI 符号 11 / 装机进游戏 / 设备端全量回归
bash tools/cef_kat/e2e_real_plugin.sh   # 原版 Cef.dll v1.3.0 + 真 open.mp + 真机，14 条 wire 断言
```

两个入口在 2026-11-24 00:49–00:56 完整跑过一遍：`verify_all.sh` → `=== VERIFY ALL OK`；
`e2e_real_plugin.sh` → 14 条全 PASS、`=== E2E (REAL PLUGIN) OK`。

顺带修掉一处验收脚本自身的噪音：World2D 探针是 2.5Hz 常开的，之前每条都 `printf` + 每次都打
`WORLD2D SHIFT ok`，一轮验收能刷两千多行；改成「y 变化才打原始值、结论只打一次」后同样一轮 87 行。

### 服务端事件覆盖：15 / 25

已实现（客户端有实际行为，非记录后丢弃）：

| 事件 | Android 行为 |
|---|---|
| `CreateBrowser` / `DestroyBrowser` / `LoadUrl` / `ReloadBrowser` | WebView 生命周期与导航 |
| `SetBrowserVisible` / `SetBrowserLayer` / `FocusBrowser` | View 可见性、层级、焦点与 IME |
| `SetKeyCapture` / `EnableKey` / `ToggleChatInput` | 按键转发开关、单键使能、聊天输入态 |
| `CreateWorld2DBrowser` / `SetWorld2DBrowserPos` | 世界锚定页面 + 每帧投影 |
| `CreateWorldBrowser` / `AttachBrowserToObject` / `DetachBrowserFromObject` | 页面纹理贴到游戏物体表面 |

未实现（`HandleServerEvent` 末尾统一 `LOGW("event '%s' not handled on Android")`，不是静默丢弃）：

| 事件 | 为什么 |
|---|---|
| `MuteBrowser` / `SetAudioMode` / `SetAudioSettings` | Android 没接 omp-cef 的音频流。PC 那套是每浏览器一路 KCP 音频 + 按 `maxDistance/refDistance` 做 3D 衰减；WebView 的声音走系统混音，没有 per-browser 音量与距离衰减的对应物。 |
| `ToggleHudComponent` / `ToggleSpawnScreen` | PC 直接改 SA 的 HUD 结构与选人界面可见性；Android 侧对应的游戏内存/界面钩子未接入。 |
| `ClearChat` / `SetEscapeMenuMode` / `SetPlayerListMode` | PC 清 SA-MP 聊天缓冲、拦截原生暂停菜单与玩家列表；同一类游戏侧钩子。 |
| `EnableDevTools` | PC 是开一个 CEF devtools 窗口。Android 的 WebView 调试是**进程级静态开关**（`WebView.setWebContentsDebuggingEnabled`），没有 per-browser 开关；CEF 路径目前未开，页面调试只能靠客户端自建的上报通道（`debug:*` 事件）。 |
| `ExitGame` | PC 是 `PostMessage(WM_CLOSE)` 收进程；Android 的等价动作是结束 Activity/进程，属于游戏生命周期接管，未做。 |

### 按用户决定不做（2026-11-24 确认，不再是待办）

- **双 ABI**：`armeabi-v7a` 缺 32 位 `libGTASA.so`，工程里只有 arm64 一份，是资产缺口。
- **0.3.DL-R1 客户端 / Linux 版 `components/Cef.so`**：只测了 0.3.7-R5 + Windows x86 组件。

### 架构决定、不是缺陷

- 页面不会被世界物件遮挡：WebView 是游戏画面之上的独立合成层（PC 端同为独立窗口）。
- World2D 的 `pivot` 只真机验了 `(0.5, 1.0)`，其余组合由 `CefWorldMath.h` 的编译期断言覆盖。
- 抓帧走 CPU 光栅：WebView 没有 GPU 纹理出口，512×256 页面实测 12.8–14.6 fps、单次 3–7ms（阶段 8 第一批）。

---

## 阶段9：接入指引与实测数据（回答"能不能代替 textdraw"）2026-11-24

这一节不写计划，写的是**已实测的数字**和**部署清单**，因为下面四个问题的答案都能落到具体行。

### 1. 数据流方向：双向，不是单向推送

```
Pawn                                  Android
CEF_EmitEvent(pid, bid, "name", args) ──KCP──► window.cef.on("name", cb)     [服务端→页面]
window.cef.emit("name", args) ──KCP──► CEF_RegisterEvent + public 回调        [页面→服务端]
```

两条方向都在真机上验过（`PASS 页面 window.cef.emit 回到服务端`）。页面自己也**可以**去请求 HTTP 接口，但那条路走的是手机的网络栈、拿不到服务端权限，日常刷新数据应该走 `CEF_EmitEvent`。

### 2. 服务端要部署什么

```
<服务器根>/
  omp-server.exe
  components/
    Cef.dll              ← 唯一必须新增的文件（release v1.3.0，协议版本必须与客户端严格相等）
    LegacyNetwork.dll    ← 不能省：只有 Cef.dll 时组件初始化成功但游戏端口不开
    ...其它标准组件
  gamemodes/
    cef.inc              ← 编译期用，不用部署到服
  scriptfiles/           ← 可选：CEF_AddResource 推给客户端的静态资源
```

部署检查单（这次踩过的四个坑都已验证）：

1. **协议版本严格相等**：`plugin.cpp` 里是 `==`，服务端 v1.3.1 连 v1.3.0 客户端会 `reason=2` 被拒。
2. `LegacyNetwork.dll` 必须在 `components/` 里，否则 7877 不开、gamemode 不加载。
3. **CEF 端口 = 游戏端口 + 2**（游戏 7777 → CEF 7779），防火墙要放行 **UDP 游戏端口+2**，且规则按**程序路径**配，换目录就被挡。
4. 客户端在**进服后自动连**，不需要 Pawn 侧做任何"连接"动作，只需要 `OnCefReady` 之后正常下发。

### 3. 动态刷新与延迟（真机实测，V2312A / 局域网）

往返 = 服务端 `CEF_EmitEvent` → 页面 JS 回调 → `window.cef.emit` 回服务端。

| 场景 | 样本 | p50 | p90 | p99 | max | 均值 |
|---|---|---|---|---|---|---|
| 同屏有 WorldObject3D CPU 抓帧 | 137 | 21ms | 28ms | 106ms | 115ms | 22.1ms |
| 无抓帧竞争 | 140 | **17ms** | 27ms | 77ms | 173ms | **18.8ms** |

- 单次往返的成本主要是 **KCP 的 flush 间隔 + 页面 JS 主线程调度**，不是带宽：一条消息 < 200 字节。
- 峰值（173ms）出现在页面首次布局或 CPU 抓帧同帧竞争时，不是常态。
- **前提**：这些数字是在所有页面都"不忙"时测的。一旦有一条页面 JS 跑死循环，其他页面 RTT 会翻倍到 44–47ms（见 6.1）。
- 基准建议：**HUD 类数据 4–10Hz 推送**（每 100–250ms 一次）完全够用；要数字滚动动画就做页面侧插值，别提高推送频率。
- 全屏覆盖层用 `focused=false` 创建时**不挡游戏触摸**（`touch=game`），这条已作为断言跑在真机上。

### 4. 静态资源与多 web 并发

- **静态资源**：`CEF_AddResource` 把文件推到客户端，页面用 `http://cef/<相对路径>` 引用；客户端侧有 `CefResources.cpp` 做 manifest + OCEF2 加密包解包校验。多文件、图片、字体都能走这条。
- **多 web 并发**：真机同时跑 4 个浏览器（1 个整屏 HUD + 1 个 WorldObject3D + 2 个 World2D）——

```
CLIENT world2d debug: browser=9  bid=9  x=893 y=110 w=512 h=256 on=1
CLIENT world2d debug: browser=11 bid=11 x=661 y=255 w=512 h=256 on=1   ← 各自独立投影，互不干扰
PING browser=8  n=50 min=7  avg=18 max=34
PING browser=9  n=50 min=11 avg=21 max=36
PING browser=11 n=50 min=14 avg=24 max=38
```

- 每条浏览器有独立的 KCP 会话，延迟没有因为并发明显劣化（单条 18–24ms）。
- **没有找到 browserid 范围上限或每玩家浏览器数的硬限制**（查过 `api.hpp` / `cef.inc` 全量接口，无数量校验）。
  实际约束是内存（+4MB/条，便宜）和**共享的 JS 主线程**（见 6.1，这才是真约束）。
- browserid 只是一个整数标识，随便给（实测 7/8/9/11/31 都正常），不需要连续。

### 5. 用来代替 textdraw 的话，哪些能替、哪些不能

| textdraw 用途 | 能否用 web 替 | 说明 |
|---|---|---|
| 静态文字/图标/进度条（HUD） | **能** | 整屏 overlay + `focused=false`，不挡输入；用 CSS 排版比 textdraw 的坐标硬算舒服得多 |
| 需要每帧跟世界位置的东西 | **能** | World2D，实测投影正确（抬 1.5m → y −219px） |
| 贴在物体表面的屏幕 | **能** | WorldObject3D，实测 12.8–14.6fps，单次光栅 3–7ms |
| 玩家列表 / 聊天框这类**原生 UI 替代** | **不能** | `SetPlayerListMode` / `ClearChat` 在 Android 侧未实现（见阶段8 收口） |
| 需要**被世界物件遮挡**的 HUD | **不能** | WebView 是游戏画面之上的独立合成层，不参与深度测试（PC 端同样） |
| 音频/提示音 | **不能** | 音频流未接，声音走系统混音 |

**结论**：纯展示型 HUD（血条、坐标、任务提示、商店面板）可以用 web 全面替掉 textdraw；但任何需要"被 3D 世界遮挡"或"接管原生 UI"的场景不要指望 web。

### 6. 性能账（全部实测，V2312A）

测法：`dumpsys meminfo <pid> | grep "TOTAL PSS"`；CPU 用 `/proc/<pid>/stat` 的 utime 增量（CLK_TCK=100，100 jiffies = 1 秒单核）。

| 状态 | 游戏进程 PSS | WebView 渲染进程 | 页面数 |
|---|---|---|---|
| 只有 1 条整屏 HUD | 228.8 MB | 61.0 MB | 1 |
| HUD + World3D + 2×World2D | 250.8 MB | **73.0 MB** | 4 |

**这是本批最有价值的发现**：同 App 的多个 WebView **共用一个 sandboxed 渲染进程**。
4 条页面比 1 条只多 12MB（平均 **+4MB/条**），不是每条约 +61MB。

- 内存成本曲线 ≈ `61MB + 4MB × (n-1)`。开 5 条 HUD 约 77MB，完全可以接受。
- 游戏进程侧 4 条页面多 22MB（每条 WebView 的 Java/View 层）。
- **代价在 CPU 上，而且是共享的**：所有页面同一个渲染进程、同一个 JS 主线程。

### 6.1 一条页面能拖慢全部（实测，必须写进开发规范）

用一个故意 `while` 忙等的页面（`requestAnimationFrame` 里空转 60ms/帧）做负向对照：

```
基线（4 条正常页）            渲染进程 utime 增量 ≈ 8% 单核
加入 1 条 busy 页面后        渲染进程 utime 增量 ≈ 113% 单核（吃满一核）
销毁该页面后                 回落 ≈ 8% 单核

对其他 3 条页面 RTT 的影响：
  无 busy 页:  browser=8 avg=18ms / 9 avg=21ms / 11 avg=24ms   max 34–38ms
  有 busy 页:  browser=8 avg=44ms / 9 avg=46ms / 11 avg=47ms   max 73–77ms
```

**结论：一条页面的 JS 死循环会让其他所有页面的数据刷新延迟翻倍。**
所以多 web 并发的真实约束不是内存、也不是数量上限，而是 **JS 主线程互斥**。

避免写法（做 HUD 时按这几条来）：

1. **不要用 `setTimeout`/`setInterval` 做动画**，用 CSS `transform`/`opacity` 过渡（走合成线程，不进 JS 主线程）。
2. **不要用同步 XHR / 大 JSON 在 JS 里 parse**；数据用 `window.cef.on` 推，页面只做赋值。
3. **长列表（玩家列表、物资清单）用虚拟滚动**，一次只渲染可见的十几行。
4. 高频数据（血条、坐标）不要每帧改 DOM 文本，攒到 `requestAnimationFrame` 里一次写。
5. 页面里**不要启动任何轮询定时器**——数据该由服务端 `CEF_EmitEvent` 推，页面被动接收。

---

## 阶段10：连接地址来源与非 CEF 服务器的行为（抓包实测）2026-11-24

用户问："有没有可能是需要该服务器部署了 omp-cef 插件并完成握手才会发 UDP 包呢？"
答案是分成两半的，两半都实测了。

### 1. APK 里没有任何硬编码的服务器地址

`CefSession::Tick()` 是唯一的连接入口（`Client.cpp:375` 每帧调用）：

```cpp
if (!pNetGame || pNetGame->GetGameState() != GAMESTATE_CONNECTED)
{
    ResetSession();          // 没进游戏世界 -> 什么都不做
    return;
}
const std::string host = pNetGame->m_host;      // ← 地址来自玩家实际连的游戏服务器
const int gamePort = pNetGame->m_port;          // ← 端口同理
const uint16_t cefPort = gamePort + CEF_PORT_OFFSET;   // CEF_PORT_OFFSET = 2
```

全工程搜 `192.168` / 已知 IP：`app/src/main/` 下**零命中**。搜到的 `127.0.0.1` 都是别的东西：

| 位置 | 是什么 | 与 CEF 连接无关 |
|---|---|---|
| `Settings.cpp:20` `m_host = "127.0.0.1"` | GTA 引擎的默认服务器地址（单机模式） | 是 |
| `WebRootServer.java` | 本地面板 HTTP 服务，绑 127.0.0.1 | 是 |
| `WebOverlay.java` `baseUrl` | 同上，读本地 assets | 是 |
| `ServerModel.java` / `Utils.java` | 收藏夹的默认值 | 是 |
| `CefManager.java:1075` | 调试广播的示例注释 | 是 |

**结论：APK 通用。** 换任何服务器，地址都由"玩家点进哪个服"决定。

### 2. 发不发包：需要"连上游戏"，不需要"对方装插件"

| 状态 | 是否向 `游戏端口+2` 发包 | 实测 |
|---|---|---|
| 没连上游戏（连不上 / 还在加载） | **不发**，`Tick` 第一行就 return | 假服务器 7790 抓包 45s，CEF 端口 **0 包** |
| 连上游戏，服务器没有 CEF | **发 5 次 `RequestJoin`，然后永久放弃** | 见下方逐包记录 |

第二行的实测环境：本机 open.mp 1.6.7 去掉 `components/Cef.dll`，换成不引用任何 `CEF_*` 的 gamemode，
7879（= 7877+2）交给一个只记录、不回复的 UDP 监听器。客户端成功进服后：

```
READY CEF-7879
[ 45.47s] #1  9B from 192.168.0.173:41917 hex=000000000000000301
[ 47.48s] #2  9B from 192.168.0.173:41917 hex=000000000000000301
[ 49.48s] #3  9B from 192.168.0.173:41917 hex=000000000000000301
[ 51.49s] #4  9B from 192.168.0.173:41917 hex=000000000000000301
[ 53.49s] #5  9B from 192.168.0.173:41917 hex=000000000000000301
DONE total=5
```

- **恰好 5 次**，间隔严格 2.00–2.01 秒，之后 90 秒窗口内再无任何包。
- 与代码常量逐一对上：`MAX_JOIN_ATTEMPTS = 5`（`CefNetwork.h:89`）、
  `CONNECT_RETRY_INTERVAL_MS = 2000`（`CefNetwork.h:90`）。
- 9 字节 = `PutU8(type=RequestJoin)` + `PutI32(playerid=0)` + `PutU32(client_version)`
  = 1 + 4 + 4，与 `SerializePacket()` 的 `RequestJoin` 分支完全吻合。逐字节验算（全小端）：

  ```
  type    = 0x00                  -> 00
  playerid= 0                     -> 00 00 00 00
  version = 0x01030000 (v1.3.0)   -> 00 00 03 01
  拼接                            -> 000000000000000301   ← 与抓包字节完全相同
  ```

  即 `client_version = 0x01030000`，对应 `MakeVersionU32(1,3,0,0)` = **v1.3.0**
  （`CefProtocol.h` 里 `VERSION_MAJOR=1 / MINOR=3 / PATCH=0 / BUILD=0`）。
- 第 6 次不再尝试，因为 `joinAttempts_ > MAX_JOIN_ATTEMPTS` 时置 `nonCefServer_ = true`
  并 `Disconnect()`，之后 `TickWorldObject` 里的 `!network_.IsNonCefServer()` 让重连分支永久跳过。

### 3. 对玩家的实际影响：零

- 非 CEF 服务器上，客户端**不建 WebView、不显示任何 UI、不弹提示**，只是 10 秒内悄悄重试 5 次后静默。
- 这 5 个包每个 9 字节，总计 **45 字节**，对服务器与被连接方都没有可感知负担。
- 断线重连到别的服时 `connectedPlayerId_` 变化会 `ResetSession()`，`nonCefServer_` 在
  `Initialize()` 里被重置为 false，所以**换到 CEF 服会重新正常握手**（不会因为上个服是非 CEF 而永久失效）。

### 4. "其他服务器只要用 CEF 且 Pawn 写法一致就能用"——成立，但有四条前提

成立的依据：客户端逻辑全在 APK 里，服务端只需 `components/Cef.dll`，Pawn 侧调用同一套 `cef.inc`。
下面四条是**服务端侧必须一致的**，少一条就不通：

1. **协议版本严格相等**（`plugin.cpp` 用 `==` 比较）。客户端声明的是 **v1.3.0**
   （`VERSION_MAJOR/MINOR/PATCH/BUILD = 1/3/0/0`，抓包 `client_version=0x01030000` 实测印证）。
   服务器必须装**同版本**的 `Cef.dll` —— 之前实测用的那份 release 正是 v1.3.0，与之匹配。
2. **`LegacyNetwork.dll` 必须在**，否则游戏端口不开。
3. **CEF 端口 = 游戏端口 + 2 必须可达**（UDP，防火墙按程序路径放行）。
4. **Pawn 侧要把该玩家注册进事件/浏览器流程**：`OnCefReady(playerid)` 之后才 `CEF_CreateBrowser`。
   不调 `OnCefReady` 不会崩，但页面上不来 —— **这是"Pawn 写法一致"的实际含义**。

反过来，**不符合这四条时客户端不会报错**，只是"没反应"。所以排查顺序应该是：
先看服务器日志有没有 `[CEF] RequestJoin`（说明第 1–3 条通了），再看有没有 `OnCefInitialize success=1`
（版本/密钥对上了），最后看 `OnCefReady` 有没有被触发（Pawn 流程对了）。

### 本轮环境副作用（已记录，不残留）

- 建立了 `D:/服务器/DAYZSA/omp-noncef/`（无 `Cef.dll` 的对照服务端，仅用于本次验证）
- 新增防火墙规则 `omp-noncef-7877-udp`
- 设备上 `servers.json` 仍是测试条目（备份在 `servers.json.bak`）

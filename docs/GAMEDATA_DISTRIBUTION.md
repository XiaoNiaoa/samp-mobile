# 游戏数据分发 —— 清单格式与托管约定

客户端下载器读这个清单来决定下什么、下多少、怎么校验、装到哪。

## 1. 清单（manifest.json）

放在分发的根目录，客户端启动时拉取。

```json
{
  "schema": 1,
  "version": "1",
  "minClientVersionCode": 10000,
  "game": {
    "label": "GTA:SA 游戏数据",
    "totalBytes": 2415919104,
    "required": true,
    "packages": [
      {
        "id": "game-data-v1",
        "label": "地图 / 模型 / 音频",
        "bytes": 2415919104,
        "sha256": "3f2a...",
        "chunks": [
          { "index": 0, "url": "chunks/game-data-v1/0000.bin", "bytes": 16777216, "sha256": "a1b2..." },
          { "index": 1, "url": "chunks/game-data-v1/0001.bin", "bytes": 16777216, "sha256": "c3d4..." }
        ]
      }
    ]
  },
  "online": {
    "label": "联机数据包",
    "totalBytes": 402653184,
    "required": true,
    "packages": [
      {
        "id": "samp-data-v1",
        "label": "SA-MP 资源",
        "bytes": 402653184,
        "sha256": "9e8f...",
        "chunks": [
          { "index": 0, "url": "chunks/samp-data-v1/0000.bin", "bytes": 16777216, "sha256": "1122..." }
        ]
      }
    ]
  }
}
```

### 字段说明

| 字段 | 必填 | 说明 |
|---|---|---|
| `schema` | 是 | 清单格式版本，当前恒为 `1`。客户端不认的值直接报错，不猜 |
| `version` | 是 | 数据包版本号，字符串。客户端比对本地已安装版本决定是否重下 |
| `minClientVersionCode` | 否 | 低于此 versionCode 的客户端不得下载（防止旧客户端装新格式） |
| `game` / `online` | 至少一个 | 两个独立分组，各自可只装一个 |
| `packages[].id` | 是 | 包标识，同时是本地解压目录名（`files/.dl/<id>/`） |
| `packages[].bytes` | 是 | 整包字节数，用于进度计算与总校验 |
| `packages[].sha256` | 是 | 整包 SHA-256（小写 hex）。拼装完成后校验，不匹配即判定失败 |
| `chunks[].url` | 是 | **相对清单 URL** 的路径，也可写绝对 URL |
| `chunks[].bytes` | 是 | 该分片字节数 |
| `chunks[].sha256` | 是 | 该分片 SHA-256，用于断点续传时判断已下载部分是否可信 |

## 2. 为什么分片

2.4GB 整包单请求的后果：

- **无法断点续传** —— 断一次从头再来，弱网环境下基本下不完
- **无法并行** —— 单 TCP 连接跑不满带宽，尤其跨境线路
- **无法校验中间结果** —— 下到 90% 发现损坏只能重来

分片后：每片 16MB，断点续传只需重下当前片；4 个并发连接；每片独立校验。

## 3. 服务端托管要求

### Cloudflare R2 + Workers（推荐）

R2 的**出流量不计费**，2.4GB 分发给 1000 人成本为 0（这是选它的唯一理由，其他平台都会按流量收钱）。

```
R2 Bucket: samp-dist
├── manifest.json
└── chunks/
    ├── game-data-v1/0000.bin ... 0159.bin
    └── samp-data-v1/0000.bin ... 0023.bin
```

需要配置：

1. **CORS** —— 允许客户端跨域读取（`GET`、`HEAD`，暴露 `Content-Length`、`ETag`）
2. **Range 请求** —— R2 原生支持，断点续传依赖它
3. **自定义域** —— 建议绑一个域名，R2 的默认 `r2.dev` 域限速且不适合生产

### 其他平台

| 平台 | 单文件上限 | 出流量 | 结论 |
|---|---|---|---|
| Cloudflare R2 | 5TiB | **免费** | 首选 |
| Cloudflare Pages | **25MiB** | 免费 | 装不下 2.4GB，只能放页面不能放包 |
| GitHub Releases | **2GiB** | 免费 | 2.4GB 超限；2GB 以下可行但国内慢 |
| Backblaze B2 | 无 | 1GB/天免费 | 需配 CF 做 CDN，否则很快超 |
| 阿里云 OSS | 5GB | 按量（约 0.5 元/GB） | 100 人下载就上百元 |

## 4. 客户端落盘位置

```
/Android/data/com.sampmobile.launcher/files/
├── gamedata/            ← 最终安装位置，游戏从这里读
├── .dl/
│   ├── manifest.json    ← 上次拉到的清单（判断版本用）
│   ├── <packageId>/
│   │   ├── 0000.part    ← 分片下载中（.part 表示未完成）
│   │   ├── 0000.chunk   ← 分片校验通过
│   │   └── state.json   ← 断点续传状态
│   └── installed.json   ← 已安装版本记录
└── ...
```

`.dl` 前缀是隐藏目录，且被 `.nomedia` 标记，不会被相册扫描。

## 5. 版本比对逻辑

```
拉清单 → 比对 installed.json 的 version
  ├─ 相同 → 不下载，直接进游戏
  └─ 不同 → 需要下载
       ├─ 本地已有同 id 分片 → 校验通过就跳过（跨版本复用同名分片）
       └─ 否则下载缺失分片
```

## 6. 客户端实现与验证记录

### 关键结论（真机 V2312A 实测）

| 项 | 结果 |
|---|---|
| 下载 → 校验 → 解压 → 安装 全链路 | ✅ 通过（`version: full-1`，5 个核心文件正确落地） |
| 断点续传 | ✅ 通过（第 3 片 655360B 中断，重启后从断点继续到 786432B，未重下） |
| 多分片拼装 | ✅ 通过（8 片拼回 6000000B，与源文件逐字节一致） |
| 已安装则跳过 | ✅ 通过（重启直接进 MainActivity，不重复下载） |
| 分片缓存清理 | ✅ 通过（安装成功后 `.dl` 只剩 installed.json） |

### 落盘路径（重要）

数据装到 **`files/` 根目录**，不是 `files/gamedata/`：

```
/Android/data/com.sampmobile.launcher/files/
├── texdb/gta3.img        ← 核心文件，游戏直接读
├── SAMP/main.scm
├── models/coll/peds.col
└── .dl/installed.json    ← 版本记录
```

原因：`SAMP.java` 与 `GameDataImporter` 都以 `files/` 为根。装进子目录游戏读不到。

### 路径归一化

复用 `GameDataImporter.normalizeZipEntryPath()`：包顶层带 `files/`、`gamedata/files/`
包装时会自动剥掉，统一落到根。**根级散文件（如 readme.txt）会被丢弃**——
只接受已知资源目录（texdb/、SAMP/、audio/、data/、models/、anim/ 等），这是既有设计。

### 用户配置保护

安装是"合并"而非"整体替换"，且跳过 `GameDataImporter.shouldPreserveExisting()`
命中以下路径的已有文件：

- `servers.json`（服务器书签）
- `SAMP/settings.json`
- `recent.json`
- `logcat/` 下所有

不这样做的话，玩家每次更新数据包都会丢掉自己的服务器列表。

### 就绪判定（单一来源 `GamedataState`）

```
就绪 = 存在 installed.json 版本记录  AND  GTA 核心文件齐全
```

只看核心文件：若数据包只含联机资源，会形成"下完→重启→又要下"的死循环。
只看版本记录：文件被用户清掉后游戏会崩。

两个调用方（SplashActivity 的启动检查、GamedataInstallerTask）都走这一个类，
避免口径漂移。

## 7. Cloudflare R2 实际部署记录

### 已配置

| 项 | 值 |
|---|---|
| Account ID | `2ae9601832aa67be77f84fc55c68cb82` |
| Bucket | `samp-mobile` |
| 公开访问 | `https://pub-f1de30f05be940ac84a5a9a7b737bd84.r2.dev` |
| 清单地址 | `https://pub-f1de30f05be940ac84a5a9a7b737bd84.r2.dev/manifest.json` |
| 对象数 | 136（135 分片 + manifest） |
| 总量 | 2.10 GiB |

### 上传方式

打包与上传**分开**，因为打包 2GB 要几分钟，而上传可能因网络中断重试多次：

```bash
# 1. 打包（只需一次，产物在 dist/cdn/）
python tools/publish_gamedata.py --game dist/gamedata/files \
    --out dist/cdn --work dist/cdn-work --version 1

# 2. 上传（凭据走环境变量，不进命令历史）
export R2_ACCESS_KEY_ID=...
export R2_SECRET_ACCESS_KEY=...
python tools/upload_r2.py --account-id 2ae9601832aa67be77f84fc55c68cb82 \
    --bucket samp-mobile --dir dist/cdn
```

`upload_r2.py` 特性：已存在且大小一致的对象跳过；单文件 3 次重试；16MB 分片上传
（单块失败只重传该块）；传完抽样 HEAD 校验远端 Content-Length。

实测：2.10 GiB / 136 文件，**399 秒**，约 5.4 MB/s 平均。

### 性能注意

`r2.dev` 子域**仅供测试**，Cloudflare 明确说明它：
- 有速率限制，不保证带宽
- 不做缓存优化

实测手机端下载速度仅 **0.6–1.1 MB/s**，2.1GB 需 30–60 分钟。

**正式分发必须绑自定义域名**（R2 → Settings → Custom Domains），
走 Cloudflare CDN 后速度可提升一个数量级，且出流量依然不计费。

### 客户端配置

地址写在 `local.properties`（已 gitignore，不进仓库）：

```properties
SAMP_GAMEDATA_MANIFEST=https://pub-f1de30f05be940ac84a5a9a7b737bd84.r2.dev/manifest.json
```

`app/build.gradle` 读取它注入 `BuildConfig.GAMEDATA_MANIFEST_URL`，
也支持环境变量 `SAMP_GAMEDATA_MANIFEST` 覆盖。

### 空间优化（2GB 包必需）

手机上装 2.1GB 数据，如果处理不当会需要 6GB 峰值空间。三处优化：

1. **拼接时边拼边删分片**：峰值从"分片 2G + zip 2G"降到"zip 2G + 一个分片 16M"
2. **逐包处理**：拼接 → 校验 → 解压 → 删 zip，不把所有 zip 攒在手上
3. **解压后立即删 zip**：给下一个包腾空间

改动前峰值约 6.3GB，改动后约 2.1GB（解压后）+ 临时 2.1GB，手机可承受。

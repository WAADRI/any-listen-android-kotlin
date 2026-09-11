# any-listen-android

[any-listen](https://github.com/any-listen/any-listen) 自建音乐服务的**原生 Android 客户端**。

用 Kotlin + Jetpack Compose 写界面，用 Media3/ExoPlayer 做原生播放，**服务端完全不动**。装上填服务器地址与访问密码即可使用。

## 定位：本地播放器，服务端只是曲库

这是本项目的核心决定，也解释了它和 Web 版的根本区别：

| 服务端提供 | 本机负责 |
|---|---|
| 歌单（`list.getAllUserLists`） | 播放队列 |
| 歌曲（`list.getListMusics`） | 当前播哪首 |
| 播放地址（`music.getMusicUrl`） | 播放进度（本地，不上报） |
| 封面、歌词（`music.getMusicLyric`） | 播放顺序（本地，不同步） |
| — | 音量（系统媒体音量） |

**播放进度、播放模式、队列都不上云**，不存在服务端，只存在本机的 DataStore 里。音量交给系统媒体音量，App 内不做音量控件。

**代价说明：没有跨设备续播。** 手机和桌面端各播各的，在电脑上听到一半无法在手机上接着听。这是有意的取舍——换来的是进度条与自动切歌真正可用。

## 为什么需要它

any-listen 的 Web 版在手机浏览器上有几个结构性缺陷，靠改前端无法根治：

| 问题 | 根因 |
|---|---|
| 进度条不动、播完不自动切歌 | Web 播放器写死 `crossOrigin='anonymous'` 并把 `<audio>` 接进 WebAudio 图（`mediaElementSource`），移动端 CORS 校验失败后拿不到 `duration`/`currentTime`，`timeupdate` 与 `ended` 随之失效 |
| 切后台/锁屏就停 | 音频走 AudioContext 图，移动端切后台会挂起 AudioContext |
| 进度依赖服务端往返 | 播放进度存在服务端，本机进度条要等一趟 RPC |
| 界面不适配 | 基于桌面 px 布局，`pxtorem` 又把根字号排除在缩放外，桌面尺寸原样输出 |

原生客户端把播放完全交给本机的 ExoPlayer/MediaSessionService，以上问题一次性消失，并顺带获得后台播放、锁屏控件、耳机线控与音频焦点管理。

## 架构

```
Android App (Kotlin + Compose)
├─ 配置层    服务器 URL + 密码 + 本地播放偏好（DataStore）
├─ 传输层    GET /api/ipc/id → POST /api/ipc/ah (m/s headers) → WS /api/ipc/socket?m=<JWT>&t=main
├─ RPC 层    message2call 协议编解码（JSON 数组，明文）
├─ 领域层    播放顺序状态机（纯函数，可单测）
├─ 播放层    Media3 ExoPlayer + MediaSessionService（队列与本机进度都在这）
└─ 界面层    Compose：播放器 / 音乐库 / 设置
```

### 必须遵守的协议细节

这些坑都曾导致「编译通过、测试通过、装到手机上却完全不工作」，改动时务必保留处理逻辑：

1. **`app.inited` 握手强制，且每次重连都要重发。**
   服务端所有广播都带 `if (socket.winType != 'main' || !socket.isInited) return` 守卫，而 `isInited` 是 **per-socket** 状态。漏掉这一步的表现是「连上了但永远收不到任何推送」，不报任何错。

2. **收发路径不对称。**
   服务端发给客户端的是**裸方法名**（`playerEvent` / `playerAction` / `playListAction` / `settingChanged`，`createRemoteGroup` 的分组名不上线），而客户端发给服务端**要带前缀**（`music.getMusicUrl` / `app.inited` / `list.getAllUserLists`），因为对应服务端 `exposeObj` 的嵌套结构。

3. **未注册的入站方法要有兜底。**
   服务端会把播放类广播发给每个就绪客户端，方法未注册时服务端会记错误日志。因此代码里保留了 `playerEvent` / `playerAction` / `playListAction` / `settingChanged` 的**空实现**——这是有意的，不要因为「看起来没人用」删掉。

4. **`music.getMusicUrl` 可能返回同源相对路径**（走代理或本地缓存的曲目），必须补上 baseUrl 再交给 ExoPlayer。

### 协议速查

```text
GET  /api/ipc/id                              → "OjppZDo6-<serverId>"
POST /api/ipc/ah  headers: m=sha256(密码+salt), s=salt
                                              → 响应头 token: <JWT>
WS   /api/ipc/socket?m=<JWT>&t=main           → t 必须为 main，否则直接断开
HTTP 401 = 密码错误；403 = 同 IP 连续失败超 10 次被封禁
```

RPC 线格式（[message2call](https://github.com/lyswhut/message2call)）：

```jsonc
[0, "<callId>", ["list","getListMusics"], ["<listId>"], []]  // 请求
[1, "<callId>", null, <结果>]                                 // 成功
[1, "<callId>", { "message": "..." }]                        // 失败
[2, "<callbackName>", [args...]]                             // 回调请求
[3, "<callbackName>", ...]                                   // 回调响应
```

**实际使用的服务端接口只有 5 个**，因为播放完全在本机：

```text
app.inited                握手（每次重连都要重发）
list.getAllUserLists      歌单列表
list.getListMusics        歌单内的曲目
music.getMusicUrl         解析音频流地址
music.getMusicLyric       歌词
```

### 歌词为什么需要一个专门的解析器

服务端**不返回结构化的歌词字段**，而是返回**一个 LRC 字符串**，其中翻译、罗马音、逐字层被 base64 编码塞进 `[awlrc:...]` 标签：

```text
[awlrc:lrc:<base64>,tlrc:<base64>,rlrc:<base64>,awlrc:<base64>]

[00:01.00]正文
```

（服务端在 `lrcTool.ts` 的 `buildAwlyric` / `buildLyrics` 里组装，Web 端用 `parseLyrics` 拆开。）

两个细节决定成败，任一做错都会**静默渲染出空白歌词页**：

1. **定时歌词在标签内层的 `lrc` key 里，而且正文常常完全没有时间戳。** `buildLyrics` 会把正文中所有带时间戳的行删掉，所以真实数据的正文常常只剩无法定位的纯文本。
2. **时间戳做 map key 前必须归一化。** 翻译行可能写作 `[0:04.2]` 而原文是 `[00:04.20]`，直接比字符串会导致**全部翻译静默丢失**。

解析器是 `domain/LyricsParser.kt`（纯函数，17 个单测覆盖），歌词页支持逐行高亮、自动滚动与点击跳转。

## 构建

由 GitHub Actions 云端构建，无需本地 Android SDK：

**Actions → 最新一次 `main` 的绿色 run → Artifacts → `any-listen-android-apks`**

产物含 debug 与 release 两个 APK，二者共用 debug 签名密钥，可共存安装。**建议装 debug 包。**

本地构建（需要 JDK 17+ 与 Android SDK）：

```bash
./gradlew assembleDebug     # 产物 app/build/outputs/apk/debug/
./gradlew testDebugUnitTest # 单元测试
```

## 状态

- [x] Phase 0 — 工程骨架 + CI 出包
- [x] Phase 1 — 协议层（鉴权 / M2cCodec / RpcSocket）+ 单测
- [x] Phase 2 — 服务器配置页与连接状态
- [x] Phase 3 — 播放闭环（播放/暂停/切歌/进度/拖拽跳转/自动切歌），全部本地
- [x] Phase 4 — 后台播放（MediaSessionService + 锁屏控件 + 通知权限）
- [x] Phase 5 — 音乐库浏览、主动点歌、本地播放模式切换与续播
- [x] Phase 6 — 歌词显示（同步高亮、自动滚动、翻译、点击跳转）
- [ ] 后续 — 搜索/专辑/艺术家浏览、正式签名密钥

## 尚未实现

诚实列出，避免误解：

- **逐字歌词未使用**：解析器会优先采用服务端的逐字层，但界面按行高亮，不做逐字卡拉OK效果。
- **搜索 / 专辑 / 艺术家浏览**：只有歌单两级。
- **没有 App 内音量控件**：这是有意的，音量归系统媒体音量管，按手机音量键即可。
- **release APK 使用 debug 签名密钥**：仅供自用安装，不适合发布。
- **划掉最近任务会停止播放**，不做后台驻留。
- **无跨设备续播**：播放状态纯本地，不与桌面端同步。

## 四个曾经静默失效的坑

都属于「编译通过、测试通过、装到手机上却完全不工作」的类型，已全部修复并各配回归测试：

| 现象 | 根因 |
|---|---|
| 歌词页永远空白 | 定时歌词在 `[awlrc:...]` 标签的**内层 `lrc` key** 里，而 `buildLyrics` 会删掉正文中所有带时间戳的行 |
| 「启动时恢复上次播放」从未生效 | `ClientSession` **先交出 socket 再 `start()`**，在回调里直接发请求时 socket 还是 `Idle` |
| 连接必定失败，报 `NetworkOnMainThreadException` | `viewModelScope` 在 `Dispatchers.Main`，而 OkHttp 的 `execute()` 是阻塞调用 |
| 对**正常服务器**也报「不是 any-listen 服务端」 | 请求路径被拼成字面量 `/api/IPC_PATH/ah`，服务端 404 |

后两个值得单独说，因为它们暴露的是**验证方式本身的问题**，不是某个疏忽：

- 前两个是**静默**失败（不报错、无日志），修法是换触发时机与修正解析 key。续播改挂在 socket 的 `Connected` 事件上，因此首次连接失败后重连成功仍能自愈。
- 后两个是**绿色 CI 掩盖了无法工作的客户端**：JVM 单测没有主线程检查，而测试断言了常量与 socket URL，**却从没断言过 HTTP 实际请求的路径**。修法是让 `AuthApi` 自己切 `Dispatchers.IO`，并加测试断言真实路径与执行线程。这也说明：**单测全绿不等于功能可用**，真机验证不可省略。

详见 `AGENTS.md` 的「协议陷阱」（第 8–10 条）。

## 致谢

接口与数据协议来自 [any-listen](https://github.com/any-listen/any-listen)，本项目仅实现客户端。

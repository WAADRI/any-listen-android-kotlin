# any-listen-android

[any-listen](https://github.com/any-listen/any-listen) 自建音乐服务的**原生 Android 客户端**。

用 Kotlin + Jetpack Compose 重写界面，用 Media3/ExoPlayer 做原生播放，**服务端完全不动**，所有数据都通过 any-listen Web Server 的 RPC 接口获取。装上填服务器地址与访问密码即可使用。

## 为什么需要它

any-listen 的 Web 版在手机浏览器上有几个结构性缺陷，靠改前端无法根治：

| 问题 | 根因 |
|---|---|
| 进度条不动、播完不自动切歌 | Web 播放器写死 `crossOrigin='anonymous'` 并把 `<audio>` 接进 WebAudio 图（`mediaElementSource`），移动端 CORS 校验失败后拿不到 `duration`/`currentTime`，`timeupdate` 与 `ended` 随之失效 |
| 切后台/锁屏就停 | 音频走 AudioContext 图，移动端切后台会挂起 AudioContext |
| 界面不适配 | 基于桌面 px 布局，`pxtorem` 又把根字号排除在缩放外，桌面尺寸原样输出 |

原生客户端把播放交给 ExoPlayer/MediaSessionService，以上问题一次性消失，并顺带获得后台播放、锁屏控件、耳机线控与音频焦点管理。

## 架构

```
Android App (Kotlin + Compose)
├─ 配置层    服务器 URL + 访问密码（DataStore 持久化）
├─ 传输层    GET /api/ipc/id → POST /api/ipc/ah (m/s headers) → WS /api/ipc/socket?m=<JWT>&t=main
├─ RPC 层    message2call 协议编解码（JSON 数组，明文）
├─ 领域层    播放顺序状态机 / 队列 / 进度
├─ 播放层    Media3 ExoPlayer + MediaSessionService
└─ 界面层    Compose：播放器 / 音乐库 / 服务器配置
```

### 责任划分（读源码确认，不是推测）

这块是最容易做错的地方，所以写清楚：

- **服务端**持有会话的播放列表、当前索引与设置，是「上次播到哪」的唯一来源 —— 断点续播与多设备一致靠它。
- **客户端**决定下一首播什么。any-listen 的切歌顺序（列表循环/随机/单曲循环/…）是在客户端算的，服务端只存**模式**本身。
- **音频永远在客户端播放**，服务端只负责给出音频流地址。

命令流：本地先执行 → 上报 `player.playerAction` → 服务端广播给所有就绪客户端（含自己）。动作幂等，回声无害，且保证多设备收敛。

### 三个必须遵守的协议细节

1. **`app.inited` 握手是强制的，且每次重连都要重发。**
   服务端所有广播都带 `if (socket.winType != 'main' || !socket.isInited) return` 守卫，而 `isInited` 是 **per-socket** 状态。漏掉这一步的表现是「连上了但永远收不到任何推送」，不报任何错。

2. **`toggle` 不能原样上报，要上报实际执行的 `play`/`pause`。**
   服务端拿 `toggle` 跟**它自己的** `playing` 标志比对，而那个标志只由客户端上报的 status 更新。先发 `toggle` 会导致服务端多切一首。

3. **`musicChanged` 是纯通知，队列要客户端自己改。**
   服务端处理切歌时**不改写自己存储的队列**（消息里带的 `list`/`index` 是变更前快照）。只从服务端重载队列的客户端永远跟不上切歌；而已经正确播放时又不能重载，否则歌会从头重放。

另外，**进度上报是单向的**：持有播放的那一端上报 `progress`，服务端把它广播给其他端；本端刻意忽略自己的回声，否则会和播放器打架。

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
[0, "<callId>", ["player","playerAction"], [{ "action": "next" }], []]  // 请求
[1, "<callId>", null, <结果>]                                           // 成功
[1, "<callId>", { "message": "..." }]                                   // 失败
[2, "<callbackName>", [args...]]                                        // 回调请求
[3, "<callbackName>", ...]                                              // 回调响应
```

**收发路径不对称**，这点很反直觉：服务端发给客户端的 `playerEvent` / `playerAction` 是**裸方法名**（不含 `player.` 前缀，`createRemoteGroup` 的分组名不上线），而客户端发给服务端时**要带前缀**（`player.getPlayInfo` / `music.getMusicUrl` / `app.inited` / `list.getAllUserLists`），因为它们对应服务端 `exposeObj` 的嵌套结构。

已使用的服务端接口：

```text
app.inited                       握手
app.getSetting                   播放模式 / 音质等
player.getPlayInfo               队列 + 当前索引 + 历史 + 进度
player.playerAction              播放/暂停/切歌/跳转/音量
player.playListAction            替换会话队列（点歌用）
music.getMusicUrl                解析音频流地址
list.getAllUserLists             歌单列表
list.getListMusics               歌单内的曲目
```

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
- [x] Phase 3 — 播放闭环（播放/暂停/切歌/进度/拖拽跳转/自动切歌）
- [x] Phase 4 — 后台播放（MediaSessionService + 锁屏控件 + 通知权限）
- [x] Phase 5 — 音乐库浏览与主动点歌
- [ ] 后续 — 歌词显示、搜索、音量控制 UI、播放模式切换 UI、正式签名密钥

## 尚未实现

诚实列出，避免误解：

- **歌词**：未显示（协议已具备 `music.getMusicLyric`）。
- **搜索 / 专辑 / 艺术家浏览**：只有歌单两级。
- **音量与播放模式无法在界面上调整**：跟随服务端设置，且已生效。
- **release APK 使用 debug 签名密钥**：仅供自用安装，不适合发布。
- **划掉最近任务会停止播放**，不做后台驻留。

## 致谢

接口与数据协议来自 [any-listen](https://github.com/any-listen/any-listen)，本项目仅实现客户端。

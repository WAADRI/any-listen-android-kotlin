# any-listen-android

[any-listen](https://github.com/any-listen/any-listen) 自建音乐服务的**原生 Android 客户端**。

用 Kotlin + Jetpack Compose 重写界面，用 Media3/ExoPlayer 做原生播放，**服务端完全不动**，所有数据都通过 any-listen Web Server 的 RPC 接口获取。

## 为什么需要它

any-listen 的 Web 版在手机浏览器上有几个结构性缺陷，靠改前端无法根治：

| 问题 | 根因 |
|---|---|
| 进度条不动、播完不自动切歌 | Web 播放器写死 `crossOrigin='anonymous'` 并把 `<audio>` 接进 WebAudio 图（`mediaElementSource`），移动端 CORS 校验失败后拿不到 `duration`/`currentTime`，`timeupdate` 随之失效 |
| 切后台/锁屏就停 | 音频走 AudioContext 图，移动端切后台会挂起 AudioContext |
| 界面不适配 | 基于桌面 px 布局，`pxtorem` 又把根字号排除在缩放外，桌面尺寸原样输出 |

原生客户端把播放交给 ExoPlayer/MediaSessionService，以上问题一次性消失，并顺带获得后台播放、锁屏控件、耳机线控。

## 架构

```
Android App (Kotlin + Compose)
├─ 配置层    服务器 URL + 访问密码（DataStore 持久化）
├─ 传输层    POST /api/id → POST /api/ah (m/s headers) → WS /api/socket?m=<JWT>&t=main
├─ RPC 层    message2call 协议编解码（JSON 数组，明文）
├─ 领域层    播放列表 / 播放状态 / 进度 / 歌词
└─ 播放层    Media3 ExoPlayer + MediaSessionService
```

**服务端是播放状态的唯一真源。** 客户端只做「执行器 + 上报器」：用户操作 → `playerAction` → 服务端更新状态并广播 → 客户端收到 `playerEvent` 后执行 → `progress` 回传。多端同步与断点续播因此天然正确。

### 协议速查

```text
POST /api/id                                    → "OjppZDo6-<serverId>"
POST /api/ah   headers: m=sha256(密码+salt), s=salt
                                                → 响应头 token: <JWT>
WS   /api/socket?m=<JWT>&t=main                 → 升级时校验 JWT
```

RPC 线格式（[message2call](https://github.com/lyswhut/message2call)）：

```jsonc
[0, "<callId>", ["player","playerAction"], [{ "action": "next" }], []]  // 请求
[1, "<callId>", null, <结果>]                                           // 成功
[1, "<callId>", { "message": "..." }]                                   // 失败
[2, "<callId>", [args...]]                                              // 回调请求
[3, "<callId>", ...]                                                    // 回调响应
```

## 构建

由 GitHub Actions 云端构建，无需本地 Android SDK。推送后到 **Actions → Android CI** 下载 `any-listen-android-apks` 产物。

本地构建（需要 JDK 17+ 与 Android SDK）：

```bash
./gradlew assembleDebug     # 产物 app/build/outputs/apk/debug/
./gradlew testDebugUnitTest # 单元测试
```

## 状态

- [x] Phase 0 — 工程骨架 + CI 出包
- [ ] Phase 1 — 协议层（鉴权 / M2cCodec / RpcSocket）+ 单测
- [ ] Phase 2 — 服务器配置页与连接状态
- [ ] Phase 3 — 播放闭环（播放/暂停/切歌/进度/音量）
- [ ] Phase 4 — 后台播放（MediaSessionService）
- [ ] Phase 5 — 打磨

## 致谢

接口与数据协议来自 [any-listen](https://github.com/any-listen/any-listen)，本项目仅实现客户端。

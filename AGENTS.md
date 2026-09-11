# AGENTS.md — 开发守则

> 适用于所有开发者与 AI 编码代理。默认分支 `main`，远程 `origin`（GitHub）。

## 核心规范

1. **所有更改必须通过 GitHub PR 合入 `main`**。
   禁止本地合并分支后 push、禁止直接 `git push origin main`、禁止任何绕过 PR 的合入。
2. **合入只能由 `gh pr merge` 执行，且必须使用压缩合并（squash）**。
   禁止本地 `git merge`/`git rebase` 合入 PR 分支；禁止在 GitHub Web 使用普通/Rebase merge。
3. **更改拆分为最小功能的 commit**；相关功能的 commit 汇总为一个 PR，squash 成一条提交落到 `main`。
   **PR 标题 = 压缩后的提交信息**，符合 Conventional Commits。

例外：仓库初始化（首次提交工程骨架）允许直接推 `main`。

## 标准流程

```text
同步 main → 建分支 → 最小 commit 逐步提交 → push 分支 → gh pr create → gh pr merge --squash
```

```bash
git checkout main && git pull origin main
git checkout -b <type>/<short-description>

# 每次只提交一个最小功能点
git commit -m "<type>: <简短描述>"
git push -u origin <branch>

gh pr create --base main --head <branch> --title "<type>: <简短描述>" --body "<变更说明>"

# 唯一合法的合入方式
gh pr merge <branch> --squash --delete-branch

# 合入后同步
git checkout main && git pull origin main && git branch -D <branch>
```

CI 失败或 review 需修改时：在**同一分支**补最小 commit 并 push，不要另开分支。

## 提交规范（Conventional Commits）

格式：`<type>(<scope>)?: <简短描述>`

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `refactor` | 重构（不改行为） |
| `docs` | 文档 |
| `chore` | 构建/依赖/配置/杂务 |
| `test` | 测试 |
| `perf` | 性能优化 |
| `style` | 格式（不改行为） |

- 一个 commit 只做一件事；描述用祈使句、小写开头，≤72 字符。
- 禁止 `update files` / `wip` 等无意义提交。

## 仓库结构规范

```
any-listen-android/
├─ .github/workflows/android.yml   CI：单测 + 出 debug/release APK
├─ app/                            唯一应用模块
│  └─ src/main/java/dev/waadri/anylisten/
│     ├─ data/config/              服务器 URL、密码等配置（DataStore）
│     ├─ data/remote/              鉴权、message2call 编解码、WebSocket 会话
│     ├─ domain/                   仓库与领域模型
│     ├─ playback/                 Media3 ExoPlayer 与 MediaSessionService
│     └─ ui/                       Compose 界面
├─ app/src/test/                   纯 JVM 单元测试（协议层必须覆盖）
└─ gradle/libs.versions.toml       依赖版本唯一真源
```

目录规则：

- 网络协议相关代码只放 `data/remote/`，不放 `ui/`。
- UI 只依赖 `domain/` 暴露的状态，禁止在 Composable 里直接调 RPC。
- 新增依赖必须写进 `gradle/libs.versions.toml`，禁止在模块里硬编码版本号。

## Kotlin / Android 规范

- **新增代码一律 Kotlin**，禁止 Java。新增界面一律 Compose，禁止 XML 布局。
- **协议层（`data/remote/`）的编解码必须是纯函数**，不得持有 Android 依赖，保证可在 `app/src/test/` 用纯 JVM 单测覆盖。
- **禁止 `!!` 强解包**；可空值必须显式处理。
- **数据模型必须显式声明字段**（`@Serializable` data class），禁止用 `Map<String, Any>` 之类的动态取值承载协议数据。
- 网络与磁盘操作禁止在主线程；协程作用域必须与生命周期绑定。

## 禁止事项

- ❌ 本地合并分支或直推 `main`；❌ 用 `gh pr merge` 之外的方式合入 PR。
- ❌ 一个 commit / 一个 PR 混入多项无关更改。
- ❌ 提交密钥、签名文件、`local.properties`、构建产物（见 `.gitignore`）。
- ❌ 修改 any-listen 服务端代码——本项目是纯客户端，服务端行为差异应通过兼容层解决。
- ❌ 文档类更改也**必须**走 PR 流程，不允许直接提交 `main`。

## 架构定位：本地播放器

**本项目是本地播放器，服务端只是曲库。** 这条决定了后面所有设计，改代码前必须先接受它：

| 服务端提供 | 本机负责 |
|---|---|
| 歌单（`list.getAllUserLists`） | 播放队列 |
| 歌曲（`list.getListMusics`） | 当前播哪首 |
| 播放地址（`music.getMusicUrl`） | 播放进度（本地，不上报） |
| 封面、歌词 | 播放顺序（本地，不同步） |
| — | 音量（交给系统媒体音量） |

具体禁止事项：

- ❌ 不上报 `progress` / `status`——进度是纯本地的。
- ❌ 不调 `app.setSetting` 改播放模式——模式存在本地 DataStore。
- ❌ 不用 `player.playListAction` 把队列推给服务端——队列是本地的。
- ❌ 不读服务端设置里的音量/模式——音量归**系统媒体音量**管，App 内不做音量控件。
- ❌ 不订阅 `playerEvent` / `playerAction` 来驱动播放。

这些通道在 `RpcSocket` 里仍然存在，是为了将来接歌词、远程控制等只读能力时不用重写协议层，**但当前不得用于同步播放状态**。

代价必须说清：**没有跨设备续播**。手机和桌面端各播各的。这是有意的权衡，也正是进度条与自动切歌在本项目能正常工作、而在手机浏览器里失效的原因。

## 协议陷阱（改 `data/remote/` 或 `playback/` 前必读）

以下每一条都曾导致过「编译通过、测试通过、装到手机上却完全不工作」的问题，改动相关代码时必须保留其处理逻辑：

1. **`app.inited` 每次重连都要重发。**
   服务端广播普遍带 `if (socket.winType != 'main' || !socket.isInited) return`，而 `isInited` 是 **per-socket** 状态，重连后归零。漏发的表现是「连上了但永远收不到任何推送」，且不报错。

2. **两个方向的方法名都是裸方法名，没有任何前缀。**
   服务端 → 客户端是裸名（`playerEvent`、`playerAction`、`playListAction`、`settingChanged`）。**客户端 → 服务端同样是裸名**（`inited`、`getAllUserLists`、`getMusicUrl`）：服务端用**平铺工厂**拼出调度对象——`const exposeObj = { ...createExposeApp(), ...createExposeList(), ...createExposeMusic() }`——而 `createExposeList()` 返回的就是 `{ getAllUserLists, getListMusics, ... }`，**不存在 `exposeObj.list` 可供下钻**。

   ⚠️ 本条以前写的是「客户端 → 服务端要带前缀，因为对应服务端 `exposeObj` 的嵌套层级」，**这是错的，并且因此让整条链路彻底不可用**：路径 `["app","inited"]` 会让服务端在 `undefined` 上取属性，抛出 JavaScript 的 `ReferenceError`，日志里就是字面的 `app is not defined` / `list is not defined`。握手与 WebSocket 都能成功，但**每一个**方法调用都失败。

   服务端源码里的 `createRemoteGroup('list', { queue: true, timeout: 0 })` 是**干扰项**：它只在调用方设置本地排队与超时，**不参与路径**。`socket.remoteQueueList.getAllUserLists()` 与 `socket.remote.getAllUserLists()` 走的是同一条路径。

   因此 `OUT_*` 常量一律是**单元素 `List<String>`**，不再是点号字符串再 `split(".")`——那正是 `app.inited` 被切成两段的来源。回归测试见 `RpcSocketTest`，它同时断言常量与 `M2cCodec` 编码出的**真实帧**。

3. **未注册的入站方法必须有兜底。**
   服务端会把播放类广播发给每个 ready 客户端，方法未注册时服务端会记错误日志。因此 `attachSocket` 中保留了 `playerEvent` / `playerAction` / `playListAction` / `settingChanged` 的**空实现**。这是有意的，不要因为「看起来没人用」而删除。

4. **`player.togglePlayMethod` 的 `'none'` 必须保留为独立取值。**
   语义上接近 `'list'`，但若折叠成一个值，「设为播完停止 → 读回」就无法往返一致。

5. **`music.getMusicUrl` 可能返回同源相对路径。**
   走代理或本地缓存的曲目会返回 `/xxx` 形式，必须补上 baseUrl 再交给 ExoPlayer，否则是无法解析的 URI。

6. **歌词 payload 的 tag key 是内层 `lrc`，且正文常常没有时间戳。**
   服务端返回的是**一个 LRC 字符串**，翻译/罗马音/逐字层被 base64 塞进 `[awlrc:...]` 标签里，格式为 `awlrc:lrc:<base64>,tlrc:<base64>,...`。**取定时歌词必须读标签内层的 `lrc` key，不是外层标签名 `awlrc`**。更关键的是 `buildLyrics` 会把正文中所有带时间戳的行删掉，因此真实数据的正文**常常完全没有时间戳**——先读正文、或找错 key 的解析器会静默渲染出**空白歌词页**，且看起来一切正常。另：时间戳做 map key 前必须归一化，`[0:04.2]` 与 `[00:04.20]` 是同一时刻，直接比字符串会丢掉全部翻译。详见 `domain/LyricsParser.kt` 与 `LyricsParserTest`。

7. **除曲库与歌词外不要依赖服务端状态。**
   队列、索引、播放模式都是本地的；服务端返回的播放信息只在浏览曲库时使用。

8. **`attachSocket` 回调触发时，socket 还没有 `start()`。**
   `ClientSession` 是**先**调用 `onSocketChanged(socket, url)`、**后**才 `socket.start()`（见 `ClientSession.kt`）。此刻 `RpcState` 仍是 `Idle`，**在该回调里直接发 RPC 必定立刻失败**。已有教训：启动续播原本就在这个回调里直接读歌单，于是每次都失败——功能「看起来实现了」却一次都没生效，而且**不报错、无日志**。正确做法是等 `socket.state` 发出 `Connected` 再发请求（见 `PlaybackRepository.observeResume`），这样首次连接失败后重连成功仍能自愈。**凡是要在连接建立后才做的初始化，都必须挂在 `Connected` 上，不能挂在 `attachSocket` 上。**

9. **阻塞式 HTTP 调用必须自己切到 `Dispatchers.IO`。**
   `viewModelScope` 跑在 `Dispatchers.Main`，OkHttp 的 `execute()` 是**阻塞**的。已有教训：`AuthApi.connect` 直接在主线程执行，导致**每台设备、每个服务器**都抛 `NetworkOnMainThreadException`，而**所有单测照样全绿**——JVM 测试没有主线程检查。修法是在 `data/remote/` 的挂起函数内部 `withContext(Dispatchers.IO)`，**不要指望调用方记得包一层**：早期版本有个 `connectOnIo()` 包装器，无人调用，它的存在反而掩盖了问题。回归测试见 `AuthApiThreadingTest`（从 `Dispatchers.Main` 发起，用 interceptor 校验实际执行线程）。

10. **拼 URL 时，字符串模板里的每个常量都要用 `${...}` 包起来。**
    错误写法（实际就是这么写错的）：

    ```kotlin
    "$base$API_PREFIX/IPC_PATH/ah"   // → /api/IPC_PATH/ah   ✗
    ```

    Kotlin 把 `$API_PREFIX` 当成变量，而**前面缺 `$` 的 `IPC_PATH` 变成纯文本**，请求打到不存在的路由上，服务端 404。已有教训：这个 bug 从协议层第一个提交起就在 `main` 上，**96 个单测全绿**，而客户端连不上任何服务器——因为测试断言了 `buildSocketUrl`（那里写对了，用的是 `${AuthApi.API_PREFIX}${AuthApi.IPC_PATH}`），**却从没断言过 HTTP 的实际请求路径**。**凡是拼 URL 的地方，测试都要断言真实路径**，不要只断言常量本身。回归测试见 `AuthApiThreadingTest`。

11. **服务端给的资源 URL 不是能直接取的 URL，必须过 `ServerUrl.resolve`。**
    服务端有两种「看起来像 URL 但不能直接用」的返回值，**而且两种都会真实出现**：

    | 服务端返回 | 例子（真机实测值） | 直接用会怎样 |
    |---|---|---|
    | 虚拟公共路径 | `al-ps-host:/public/medias/<sha256>.mp3` | 拼成 `https://host/al-ps-host:/public/...` → 404 |
    | 同源相对路径 | `./api/p_static/<sha256>.jpeg` | 交给图片加载器 → `FileNotFoundException` |

    关键点：虚拟标记是**被替换**成 host，**不是拼在 host 后面**。服务端 `buildRealPublicPath` 的实现就是一句 `virtualPath.replace(VIRTUAL_PROTOCOL, host)`（见 `packages/shared/common/tools.ts`，`VIRTUAL_PROTOCOL = 'al-ps-host:'`），Web 端的 `buildUrl()` 也是**先**做这一步替换、之后才考虑代理。

    已有教训：陷阱 5 只写了「同源相对路径要补 baseUrl」，**没写这条替换语义**，照那半句话实现就把标记拼在了 host 后面，音频 404；随后**封面又以完全相同的方式坏掉两次**——`mediaMetadataFor` 把原始 `meta.picUrl` 交给系统媒体通知（日志里是字面的 `FileNotFoundException: ./api/p_static/....jpeg`），播放页封面同样直接用原始值。

    因此规则是：**凡是来自服务端的 URL，一律先过 `ServerUrl.resolve(url, baseUrl)`**，且**只在一处解析**：
    - 播放地址 → `PlaybackRepository.absoluteUrl`
    - 歌单封面 → `Library.summaries`
    - 曲目封面 → `Library.trackCovers`，结果由 `PlaybackUiState.artworkUrl` 承载
    - 媒体通知封面 → `PlaybackService.mediaMetadataFor` 的 `artworkUrl` 形参（**它接收已解析的 URL，不接收曲目**，就是为了不给这个 bug 第三次机会）

    不要指望「每个调用点都记得解析」，那正是它坏了三次的原因。回归测试见 `ServerUrlTest` 与 `LibraryTest`。

改动上述任一环节时，请同步更新 `README.md` 的说明，并保证 `app/src/test/` 中有对应覆盖。

## 适用对象

- 本守则适用于所有人工与代理提交；冲突时以本文件为准。
- 本文件自身的更改同样必须通过 PR 流程合入。

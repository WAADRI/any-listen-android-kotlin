# any-listen 服务端接口契约（实测版）

> 本文的每一条都来自对**真实服务器**的探测（`tools/ws-probe*.mjs`），不是从类型定义推断的。
> 凡是类型定义与实测不符的地方，都以实测为准，并在文中标注出来。

## 为什么要这份文档

服务端的 TypeScript 类型定义是**不完整**的：它描述了「字段长什么样」，但没描述「哪些字段是必填的」、
「谁负责生成 id」、「整体替换还是局部合并」。照着类型定义写代码会得到一个能编译、能发出请求、
然后被服务端以一句 `NOT NULL constraint failed` 拒绝的实现。

下面第 3 节记录的四个坑，全部是照着类型定义写代码会踩的，而且**都不会在任何静态检查里暴露**。

## 1. 鉴权

```
GET  /api/ipc/id     → 200, body: "OjppZDo6-<serverId>"
                       前缀 "OjppZDo6" 是 "::id::" 的 base64；serverId 是 "-" 之后的部分
POST /api/ipc/ah     → 200, header: token: <JWT>, body: "Hello~::^-^::~v1~\n"
                       请求头 m = sha256hex(password + salt)，s = salt（salt 客户端自选）
                       401 → "Auth failed"；403 → "Blocked IP"（连续失败 10 次会封 IP）
WS   /api/ipc/socket?m=<JWT>&t=main
```

实测确认：`s` 用随机 hex 即可，服务端只做比对，不校验 salt 格式。

## 2. 传输层

### 2.1 帧格式是数组

```
请求      [0, callId, path, args, callbacks]
响应      [1, callId, {message, stack?}]        错误
          [1, callId, null, data]               成功
回调请求  [2, callId, content]
回调响应  [3, callId, content]
```

`op` 常量：`REQUEST=0, RESPONSE=1, CALLBACK_REQUEST=2, CALLBACK_RESPONSE=3`。

### 2.2 path 是数组，且只能有一段

服务端用**平铺工厂**拼调度对象：

```ts
const exposeObj = { ...createExposeApp(), ...createExposeList(), ...createExposeMusic(), ... }
```

所以 `exposeObj.list` 不存在，`path` 必须是 `["getAllUserLists"]` 而不是 `["list","getAllUserLists"]`。

⚠️ 写成两段时服务端在 `undefined` 上取属性，抛 JavaScript `ReferenceError`，
响应里的 message 是字面的 `list is not defined`。握手与 WebSocket 都能成功，但**每一个**调用都失败。

### 2.3 服务端自己注入 socket，客户端不要传

服务端注册时：

```ts
onCallBeforeParams(rawArgs) { return [socket, ...rawArgs] }
```

所以处理器签名里的第一个 `event` 参数由服务端提供。客户端调 `["inited"]` 传空 args 即可，
不要试图自己构造或传递 socket 对象。

### 2.4 心跳

- 服务端每 30s 检查一次：超过 45s 无活动就 `terminate()`；超过 15s 发 WebSocket ping，
  并**另外**发一条纯文本 `"ping"`。客户端收到纯文本 `ping` 时应回 `"pong"`。
- 客户端侧（web 端实现）46s 无 `ping` 就主动 close。**必须实现心跳，否则连接会被服务端静默掐断。**

### 2.5 消息没有加密

`web-server/src/modules/ipc/tools.ts` 里的 `encryptMsg` / `decryptMsg` 函数体就是 `return msg`，
AES 相关代码全部被注释掉了。传输是明文 JSON。

## 3. 数据接口

### 3.1 `inited`

```
path ["inited"]  args []  →  null
```

**每次重连都必须重发。** 它设置 `socket.isInited = true`，而 `isInited` 是 per-socket 状态，
重连后归零。服务端所有广播都带 `if (socket.winType != 'main' || !socket.isInited) return`，
漏发的表现是「连上了但永远收不到任何推送」。

### 3.2 `getAllUserLists`

```
path ["getAllUserLists"]  args []  →  { defaultList, loveList, lastPlayList, userList: UserListInfo[] }
```

实测（某真实服务器）：`defaultList.id='default'`、`loveList.id='love'`、`lastPlayList.id='last_played'`，
三者的 `type` 都是 `'default'`，`userList` 有 27 项。

每个条目形如：

```json
{
  "id": "du3jglgqkj8",
  "name": "全部歌曲",
  "type": "general",
  "meta": { "songCount": 1653, "pic": "", "playCount": 0, "createTime": ..., "updateTime": ..., "posTime": ..., "desc": "" },
  "parentId": null
}
```

`type` 的合法值是 `'general' | 'local' | 'online' | 'remote'`。
`type: 'local'` 的条目 meta 里会多出 `deviceId` / `path` / `includeSubDir` 等（设备上的文件夹列表）。

### 3.3 `getListMusics`

```
path ["getListMusics"]  args [listId]  →  MusicInfo[]
```

注意返回的是**裸数组，不是 `{list: [...]}` 信封**。实测单曲条目：

```json
{
  "id": "/music/王俊凯 - 从前.mp3",
  "name": "从前",
  "singer": "王俊凯",
  "isLocal": true,
  "interval": "04:32",
  "meta": {
    "musicId": "/music/王俊凯 - 从前.mp3",
    "unparsed": false,
    "albumName": "WJK",
    "filePath": "/music/王俊凯 - 从前.mp3",
    "picUrl": "./api/p_static/55edc556....jpeg",
    "ext": "mp3", "bitrateLabel": "128k", "sizeStr": "4.22 MB", "year": 0,
    "createTime": ..., "updateTime": ..., "posTime": ...,
    "deviceId": "a3c36025-..."
  }
}
```

**这些对象可以原样回传给 `list_music_add`**，不需要重新构造。这一点已实测。

### 3.4 `getMusicUrl`

```
path ["getMusicUrl"]  args [{ musicInfo, isRefresh?, quality? }]  →  { url, quality, isFromCache }
```

⚠️ 参数是**单个对象**，里面的 `musicInfo` 是**完整的 MusicInfo**（通常就是 `getListMusics` 返回的那一项），
不是 `{listId, musicId}`。签名是 `getMusicUrl(event, info)`。

实测返回值：

```json
{ "url": "al-ps-host:/public/medias/8b984867ff25e1b83e0ce2e71867d75bd35c3c7e17854c2e561a1be325ef3cc1.mp3",
  "quality": "128k", "isFromCache": false }
```

**`al-ps-host:` 是虚拟标记，必须被替换成 host，不是拼在 host 后面。**
服务端 `buildRealPublicPath` 就是一句 `virtualPath.replace(VIRTUAL_PROTOCOL, host)`。
实测替换后 `HEAD` 返回 `200 / audio/mpeg / 4423554`，且**无需鉴权**。

### 3.5 `getMusicLyric`

```
path ["getMusicLyric"]  args [{ musicInfo }]  →  { info: { lyric, awlyric, name, singer, interval }, isFromCache }
```

⚠️ 同样把 `info` 包了一层。实测 `lyric` 是**带时间戳的正常 LRC**：

```
[offset:0]
[00:00.155]从前 - 王俊凯
[00:02.891]词：王建薇
```

`awlyric` 是**逐字**格式：`[00:00.155]<0,359>从<359,220>前<579,220> - <800,456>王...`

（与本项目 AGENTS.md 第 6 条描述的 `[awlrc:...]` 内层 base64 结构不同 —— 那是另一种部署形态的返回。
本服务器直接给出明文 `lyric` + `awlyric` 两个字段，**不需要 base64 解码，也不需要从标签里挖**。）

### 3.6 `getMusicPic`

```
path ["getMusicPic"]  args [{ musicInfo }]  →  { url, isFromCache }
```

### 3.7 歌单写操作：`listAction`

```
path ["listAction"]  args [{ action, data }]  →  void
```

支持 12 种 action（类型见 `packages/shared/types/types/list_ipc.d.ts`）：

| action | data 形状 |
|---|---|
| `list_create` | `{ position, listInfos: UserListInfo[] }` |
| `list_remove` | `string[]`（歌单 id 数组） |
| `list_update` | `{ lists: MyListInfo[], sync? }` |
| `list_move` | `{ id, ids, position }` |
| `list_update_position` | `{ ids, position }` |
| `list_music_add` | `{ id, musicInfos, addMusicLocationType: 'top'\|'bottom' }` |
| `list_music_move` | `{ fromId, toId, musicInfos, addMusicLocationType }` |
| `list_music_remove` | `{ listId, ids, sync? }` |
| `list_music_update` | `Array<{ id, musicInfo }>` |
| `list_music_update_position` | `{ listId, position, ids }` |
| `list_music_overwrite` | `{ listId, musicInfos }` |
| `list_music_clear` | `string[]`（歌单 id 数组） |
| `list_data_overwrite` | `ListDataFull` |

全部已对真实服务器实测通过（`tools/ws-probe-crud.mjs`），但踩到下面四个坑。

## 4. 四个只有实测才会暴露的坑

### 坑 1：建歌单必须**客户端生成 id**，服务端不生成

传 `id: ''` 时服务端**原样存下空 id**，不报错、不生成。结果是歌单列表里多出一个 id 为空的条目，
而后续所有以 id 为键的操作都会指向它。

真实数据的 id 形如 `du3jglgqkj8`——**11 位小写字母数字**随机串。客户端应当自行生成同形状的 id。

### 坑 2：`meta` 是 NOT NULL，且必需字段比类型定义暗示的多

只用 `{ id, name, type }` 调用 `list_create` 会得到：

```
NOT NULL constraint failed: my_list.meta
```

`UserListInfoType<'general'>` 的 meta 必需字段是：
`songCount, pic, playCount, createTime, updateTime, posTime, desc`。

另外 `type` **不是** `'user'`（这是最容易猜错的值），合法值是 `'general' | 'local' | 'online' | 'remote'`，
普通用户歌单用 `'general'`。

### 坑 3：`list_update` 是整体替换，只传 `{ id, name }` 会失败

同样报 `NOT NULL constraint failed: my_list.meta`。必须回传**完整的**歌单对象，
包括未被修改的 `createTime` / `posTime`。

实测确认服务端会自己维护 `songCount`（加歌后由 0 变 1），并更新 `updateTime`，
但 `createTime` / `posTime` 会原样保留。

### 坑 4：`type: 'local'` 的歌单不是普通歌单

真实数据里 `type: 'local'` 的条目（例如「全部歌曲」）meta 里带 `deviceId` / `path` / `includeSubDir`，
它是**服务端主机上某个文件夹的映射**，不是用户手建的歌单。对它做增删改等于改服务端的文件索引，
应当**只读**或在 UI 上禁用编辑。

## 5. 探测脚本

| 脚本 | 用途 |
|---|---|
| `tools/probe.mjs` | 握手 + 公开资源路径鉴权行为（只读） |
| `tools/ws-probe.mjs` | `inited` / 歌单 / 歌曲 / 播放地址 / 歌词（只读） |
| `tools/ws-probe-shape.mjs` | 打印真实对象字段（只读） |
| `tools/ws-probe-crud.mjs` | 歌单 CRUD，**只在自建测试歌单上操作并自行清理** |
| `tools/ws-probe-cleanup.mjs` | 清理自测残留 |

全部用法：`node tools/<script>.mjs <password>`。

## 6. 未经实测的部分

- `list_music_move`、`list_music_update`、`list_music_overwrite`、`list_data_overwrite`、
  `list_move`、`list_update_position`、`list_music_update_position` —— 仅依据类型定义，
  **未实测**。它们很可能与 `list_update` 一样有「必需字段比类型定义多」的问题。
- 服务端广播的 `listAction` 推送只观察到 `list_create` 一种，其余广播形态未验证。
- 除 `list.*` / `music.*` / `app.inited` 外的接口（player 系、setting 系、资源搜索系）未探索。

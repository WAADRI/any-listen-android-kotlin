// any-listen WebSocket RPC 真实行为探测器（只读）。
//
// 全部格式均来自**实测**，不是猜测：
//   - 帧是数组：见服务器上的 view-main.ipc.*.js（线上真实客户端）
//       请求  [REQUEST,      callId, path, args, callbacks]
//       响应  [RESPONSE,     callId, {message}]  或  [RESPONSE, callId, null, data]
//       回调  [CALLBACK_REQUEST, callId, content] / [CALLBACK_RESPONSE, ...]
//     其中 REQUEST=0, RESPONSE=1, CALLBACK_REQUEST=2, CALLBACK_RESPONSE=3
//   - path 是数组，最后一段是方法名；服务端 exposeObj 是**平铺**的，故 path 只有一段
//   - args 是数组；服务端会自己把 socket 注入为第一个参数，客户端不要传
//   - 服务端发纯文本 'ping' 做心跳，客户端要回 'pong'
//     （客户端侧心跳超时 46s 就 close，服务端 45s 无活动就 terminate）
import crypto from 'node:crypto'

const BASE = 'https://music.waadri.top'
const PASSWORD = process.argv[2]
if (!PASSWORD) {
  console.error('usage: node ws-probe.mjs <password>')
  process.exit(2)
}

const OP = { REQUEST: 0, RESPONSE: 1, CALLBACK_REQUEST: 2, CALLBACK_RESPONSE: 3 }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let seq = 0
const nextId = () => String(++seq)

// ---- 握手拿 token ----
const salt = crypto.randomBytes(16).toString('hex')
const m = crypto.createHash('sha256').update(PASSWORD + salt).digest('hex')
const ah = await fetch(`${BASE}/api/ipc/ah`, { method: 'POST', headers: { m, s: salt } })
if (ah.status !== 200) {
  console.error(`鉴权失败: ${ah.status}`)
  process.exit(1)
}
const token = ah.headers.get('token')
console.log(`握手成功, token 长度 ${token.length}`)

// ---- WebSocket ----
const wsUrl = `${BASE.replace('https://', 'wss://')}/api/ipc/socket?m=${encodeURIComponent(token)}&t=main`
const ws = new WebSocket(wsUrl)

const pending = new Map()
const rawFrames = []
const pushed = []

ws.addEventListener('message', (ev) => {
  const raw = typeof ev.data === 'string' ? ev.data : '[binary]'
  rawFrames.push(raw)

  if (raw === 'ping') {
    ws.send('pong')
    return
  }

  let frame
  try {
    frame = JSON.parse(raw)
  } catch {
    console.log(`  ← 非 JSON 帧: ${raw.slice(0, 120)}`)
    return
  }

  // 只可能是响应：[RESPONSE, callId, error, data]
  if (Array.isArray(frame) && frame[0] === OP.RESPONSE) {
    const resolver = pending.get(frame[1])
    if (resolver) {
      pending.delete(frame[1])
      resolver({ callId: frame[1], error: frame[2] ?? null, data: frame[3] })
    } else {
      pushed.push(frame)
    }
    return
  }

  pushed.push(frame)
  console.log(`  ← 推送/回调: ${JSON.stringify(frame).slice(0, 200)}`)
})

function call(path, ...args) {
  const callId = nextId()
  return new Promise((resolve) => {
    pending.set(callId, resolve)
    ws.send(JSON.stringify([OP.REQUEST, callId, path, args, []]))
    setTimeout(() => {
      if (pending.delete(callId)) resolve({ callId, error: { message: 'timeout(10s)' }, data: undefined })
    }, 10_000)
  })
}

function shape(value, depth = 0) {
  const pad = '  '.repeat(depth)
  if (Array.isArray(value)) {
    if (!value.length) return '[] (空数组)'
    return `Array(${value.length})，第一项:\n${pad}${shape(value[0], depth + 1)}`
  }
  if (value === null) return 'null'
  if (typeof value !== 'object') return `${typeof value} = ${JSON.stringify(value)}`
  const keys = Object.keys(value)
  if (!keys.length) return '{} (空对象)'
  return (
    '{\n' +
    keys
      .map((k) => {
        const v = value[k]
        let desc
        if (Array.isArray(v)) desc = `Array(${v.length})`
        else if (v === null) desc = 'null'
        else if (typeof v === 'object') desc = `{${Object.keys(v).slice(0, 10).join(', ')}}`
        else if (typeof v === 'string') desc = `string(${v.length}) = ${JSON.stringify(v.slice(0, 60))}`
        else desc = `${typeof v} = ${JSON.stringify(v)}`
        return `${pad}  ${k}: ${desc}`
      })
      .join('\n') +
    `\n${pad}}`
  )
}

function report(label, res) {
  console.log(`\n=== ${label} ===`)
  if (res.error != null) {
    console.log(`  ✗ error: ${JSON.stringify(res.error)}`)
    return null
  }
  console.log(`  ✓ ${shape(res.data, 1).split('\n').join('\n  ')}`)
  return res.data
}

ws.addEventListener('open', async () => {
  console.log('WebSocket 已连接')
  await sleep(300)

  report('inited', await call(['inited'], []))
  await sleep(200)

  const lists = report('getAllUserLists', await call(['getAllUserLists']))

  let target
  if (lists && typeof lists === 'object') {
    const all = [
      ...(lists.defaultList ? [lists.defaultList] : []),
      ...(lists.loveList ? [lists.loveList] : []),
      ...(lists.lastPlayList ? [lists.lastPlayList] : []),
      ...(Array.isArray(lists.userList) ? lists.userList : []),
    ]
    console.log(`\n  歌单: ${JSON.stringify(all.map((l) => ({ id: l?.id, name: l?.name, n: l?.meta?.songCount })))}`)
    target = all.find((l) => (l?.meta?.songCount ?? 0) > 0) ?? all[0]
  }

  let music
  if (target) {
    const musics = report(`getListMusics("${target.id}")`, await call(['getListMusics'], target.id))
    if (Array.isArray(musics) && musics.length) {
      music = musics[0]
      console.log(`\n  第一首完整对象（截断）: ${JSON.stringify(music).slice(0, 500)}`)
    }
  }

  if (music) {
    const urlInfo = report('getMusicUrl({ musicInfo })', await call(['getMusicUrl'], { musicInfo: music }))
    if (urlInfo) {
      console.log(`  → url 字面值: ${JSON.stringify(urlInfo.url)}`)
      console.log(`     （若以 al-ps-host: 开头，说明必须做虚拟协议替换）`)
      // 验证这个 url 到底能不能取到
      if (urlInfo.url) {
        const resolved = urlInfo.url.startsWith('al-ps-host:')
          ? urlInfo.url.replace('al-ps-host:', BASE)
          : urlInfo.url.startsWith('./')
            ? BASE + urlInfo.url.slice(1)
            : urlInfo.url.startsWith('http')
              ? urlInfo.url
              : BASE + urlInfo.url
        console.log(`  → 解析后: ${resolved}`)
        const head = await fetch(resolved, { method: 'HEAD' }).catch((e) => ({ status: `ERR ${e.message}` }))
        console.log(`  → HEAD 请求: ${head.status}  content-type=${head.headers?.get?.('content-type')}  length=${head.headers?.get?.('content-length')}`)
      }
    }

    const lyric = report('getMusicLyric({ musicInfo })', await call(['getMusicLyric'], { musicInfo: music }))
    if (lyric) {
      const inner = lyric.info ?? lyric
      for (const [k, v] of Object.entries(inner)) {
        const s = typeof v === 'string' ? v : JSON.stringify(v)
        console.log(`    字段 ${k}: ${typeof v}, 长度 ${s ? s.length : 0}, 开头 ${JSON.stringify(String(s).slice(0, 110))}`)
      }
    }
  }

  console.log(`\n=== 原始帧前 4 条（截断 180 字符）===`)
  rawFrames.slice(0, 4).forEach((f, i) => console.log(`  [${i}] ${f.slice(0, 180)}`))
  console.log(`\n=== 未配对的推送帧: ${pushed.length} ===`)
  pushed.slice(0, 8).forEach((p) => console.log(`  ${JSON.stringify(p).slice(0, 160)}`))

  ws.close()
  process.exit(0)
})

ws.addEventListener('error', (e) => {
  console.error(`WebSocket 错误: ${e.message ?? e}`)
  process.exit(1)
})

setTimeout(() => {
  console.error('\n总超时，退出。')
  process.exit(1)
}, 90_000)

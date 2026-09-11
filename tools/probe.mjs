// 只读探测：验证 any-listen 握手与公开资源路径的真实行为。
// 仅做 GET / 鉴权 / 列歌单，不写任何数据。
import crypto from 'node:crypto'

const BASE = 'https://music.waadri.top'
const PASSWORD = process.argv[2]
if (!PASSWORD) {
  console.error('usage: node probe.mjs <password>')
  process.exit(2)
}

const log = (...a) => console.log(...a)

// ---- 1. server id ----
const idRes = await fetch(`${BASE}/api/ipc/id`)
const idBody = await idRes.text()
log(`GET /api/ipc/id -> ${idRes.status}`)
log(`  body: ${JSON.stringify(idBody)}`)

// 服务端返回形如 "OjppZDo6-<serverId>"，前缀 OjppZDo6 是 "::id::" 的 base64。
const serverId = idBody.includes('-') ? idBody.slice(idBody.indexOf('-') + 1) : idBody
log(`  解析出的 serverId: ${serverId}`)

// ---- 2. 鉴权 ----
// 协议：m = sha256hex(password + salt)，s = salt（salt 可以是任意值，服务端只做比对）
const salt = crypto.randomBytes(16).toString('hex')
const m = crypto.createHash('sha256').update(PASSWORD + salt).digest('hex')
log(`\nPOST /api/ipc/ah  (s=${salt.slice(0, 8)}… m=${m.slice(0, 12)}…)`)

const ahRes = await fetch(`${BASE}/api/ipc/ah`, {
  method: 'POST',
  headers: { m, s: salt },
})
const ahBody = await ahRes.text()
log(`  -> ${ahRes.status}`)
log(`  token header: ${ahRes.headers.get('token') ? '有' : '无'}`)
log(`  body: ${JSON.stringify(ahBody.slice(0, 200))}`)

if (ahRes.status !== 200) {
  log('\n鉴权失败，后面的探测无意义，停止。')
  process.exit(1)
}

const token = ahRes.headers.get('token')
log(`  token 前 24 位: ${token ? token.slice(0, 24) : '(无)'}…`)

// ---- 3. 公开静态资源是否需要鉴权 ----
// 已知服务器会给音频/封面发 al-ps-host:/public/medias/<sha256>.mp3 这类路径。
// 这里只试一个必然不存在的文件名，目的是看「未鉴权访问公开路径」返回 404 还是 401/403。
log('\n未带 token 访问 /public/medias/<不存在的文件>：')
const pub = await fetch(`${BASE}/public/medias/0000000000000000000000000000000000000000000000000000000000000000.mp3`)
log(`  -> ${pub.status}  (404 = 路径无需鉴权只是文件不存在; 401/403 = 需要凭据)`)

// ---- 4. 未带 token 访问受保护的 socket 端点，确认它是受保护的 ----
log('\n未带 token 访问 WebSocket 端点（用普通 GET 触发握手拒绝）：')
const ws = await fetch(`${BASE}/api/ipc/socket?m=none&t=main`)
log(`  -> ${ws.status}  body: ${JSON.stringify((await ws.text()).slice(0, 120))}`)

log('\n完成。token 已拿到，可用于后续 WebSocket RPC 验证。')

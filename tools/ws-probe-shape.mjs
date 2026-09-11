// 打印真实 userList 条目的完整字段，用于确定 list_create 必须提供哪些字段。
import crypto from 'node:crypto'

const BASE = 'https://music.waadri.top'
const PASSWORD = process.argv[2]
const OP = { REQUEST: 0, RESPONSE: 1 }
let seq = 0

const salt = crypto.randomBytes(16).toString('hex')
const m = crypto.createHash('sha256').update(PASSWORD + salt).digest('hex')
const ah = await fetch(`${BASE}/api/ipc/ah`, { method: 'POST', headers: { m, s: salt } })
const token = ah.headers.get('token')

const ws = new WebSocket(`${BASE.replace('https://', 'wss://')}/api/ipc/socket?m=${encodeURIComponent(token)}&t=main`)
const pending = new Map()
ws.addEventListener('message', (ev) => {
  const raw = typeof ev.data === 'string' ? ev.data : ''
  if (raw === 'ping') return void ws.send('pong')
  let f; try { f = JSON.parse(raw) } catch { return }
  if (Array.isArray(f) && f[0] === OP.RESPONSE) {
    const r = pending.get(f[1]); if (r) { pending.delete(f[1]); r(f[3]) }
  }
})
function call(path, ...args) {
  const id = String(++seq)
  return new Promise((resolve) => {
    pending.set(id, resolve)
    ws.send(JSON.stringify([OP.REQUEST, id, path, args, []]))
    setTimeout(() => { if (pending.delete(id)) resolve(undefined) }, 10_000)
  })
}

await new Promise((r) => ws.addEventListener('open', r))
await new Promise((r) => setTimeout(r, 300))
await call(['inited'])
await new Promise((r) => setTimeout(r, 200))

const all = await call(['getAllUserLists'])
console.log('=== defaultList ===')
console.log(JSON.stringify(all?.defaultList, null, 2))
console.log('\n=== loveList ===')
console.log(JSON.stringify(all?.loveList, null, 2))
console.log('\n=== lastPlayList ===')
console.log(JSON.stringify(all?.lastPlayList, null, 2))
console.log('\n=== userList[0]（真实用户歌单）===')
console.log(JSON.stringify(all?.userList?.[0], null, 2))
console.log('\n=== userList[0] 的所有键 ===')
console.log(JSON.stringify(Object.keys(all?.userList?.[0] ?? {})))
console.log('\n=== meta 的所有键 ===')
console.log(JSON.stringify(Object.keys(all?.userList?.[0]?.meta ?? {})))
ws.close()
process.exit(0)

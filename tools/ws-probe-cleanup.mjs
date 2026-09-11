// 清理自测产生的残留歌单（包括第一次 id 为空的那条）。
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
    const r = pending.get(f[1]); if (r) { pending.delete(f[1]); r({ error: f[2] ?? null, data: f[3] }) }
  }
})
function call(path, ...args) {
  const id = String(++seq)
  return new Promise((resolve) => {
    pending.set(id, resolve)
    ws.send(JSON.stringify([OP.REQUEST, id, path, args, []]))
    setTimeout(() => { if (pending.delete(id)) resolve({ error: { message: 'timeout' } }) }, 10_000)
  })
}

await new Promise((r) => ws.addEventListener('open', r))
await new Promise((r) => setTimeout(r, 300))
await call(['inited'])
await new Promise((r) => setTimeout(r, 200))

const all = await call(['getAllUserLists'])
const junk = (all.data?.userList ?? []).filter(
  (l) => l.name?.startsWith('__anylisten_adapter_selftest_') || l.id === '',
)
console.log(`找到 ${junk.length} 条自测残留:`)
for (const l of junk) console.log(`  id=${JSON.stringify(l.id)}  name=${l.name}`)

if (!junk.length) {
  console.log('无需清理。')
  ws.close()
  process.exit(0)
}

const res = await call(['listAction'], { action: 'list_remove', data: junk.map((l) => l.id) })
console.log(`\nlist_remove -> ${res.error ? `✗ ${JSON.stringify(res.error)}` : '✓'}`)
await new Promise((r) => setTimeout(r, 800))

const after = await call(['getAllUserLists'])
const left = (after.data?.userList ?? []).filter(
  (l) => l.name?.startsWith('__anylisten_adapter_selftest_') || l.id === '',
)
console.log(`清理后剩余 ${left.length} 条；当前用户歌单总数 ${(after.data?.userList ?? []).length}`)
ws.close()
process.exit(0)

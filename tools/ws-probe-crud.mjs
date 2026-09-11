// any-listen 写接口（歌单 CRUD）真实行为验证。
//
// 帧格式来自实测（见 ws-probe.mjs 的说明）：数组帧，REQUEST=0/RESPONSE=1。
//
// 安全策略：只在**自己新建的**测试歌单上做增删改，最后把它删掉。
// 不触碰用户任何现有歌单。用户已授权测试删改，且数据有备份。
import crypto from 'node:crypto'

const BASE = 'https://music.waadri.top'
const PASSWORD = process.argv[2]
if (!PASSWORD) {
  console.error('usage: node ws-probe-crud.mjs <password>')
  process.exit(2)
}

const OP = { REQUEST: 0, RESPONSE: 1 }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let seq = 0

// ---- 握手 ----
const salt = crypto.randomBytes(16).toString('hex')
const m = crypto.createHash('sha256').update(PASSWORD + salt).digest('hex')
const ah = await fetch(`${BASE}/api/ipc/ah`, { method: 'POST', headers: { m, s: salt } })
if (ah.status !== 200) {
  console.error(`鉴权失败 ${ah.status}`)
  process.exit(1)
}
const token = ah.headers.get('token')

// ---- WebSocket ----
const ws = new WebSocket(`${BASE.replace('https://', 'wss://')}/api/ipc/socket?m=${encodeURIComponent(token)}&t=main`)
const pending = new Map()
const pushed = []

ws.addEventListener('message', (ev) => {
  const raw = typeof ev.data === 'string' ? ev.data : ''
  if (raw === 'ping') return void ws.send('pong')
  let f
  try { f = JSON.parse(raw) } catch { return }
  if (Array.isArray(f) && f[0] === OP.RESPONSE) {
    const r = pending.get(f[1])
    if (r) { pending.delete(f[1]); r({ error: f[2] ?? null, data: f[3] }) } else pushed.push(f)
  } else {
    pushed.push(f)
    // 服务端广播的歌单变更通知——这正好能验证写操作是否真的落库并广播
    if (Array.isArray(f) && f[0] === OP.REQUEST) {
      console.log(`  ← 服务端推送: path=${JSON.stringify(f[2])} args=${JSON.stringify(f[3]).slice(0, 200)}`)
    }
  }
})

function call(path, ...args) {
  const id = String(++seq)
  return new Promise((resolve) => {
    pending.set(id, resolve)
    ws.send(JSON.stringify([OP.REQUEST, id, path, args, []]))
    setTimeout(() => { if (pending.delete(id)) resolve({ error: { message: 'timeout' }, data: undefined }) }, 10_000)
  })
}

const ok = (r, label) => {
  if (r.error) { console.log(`  ✗ ${label} 失败: ${JSON.stringify(r.error)}`); return null }
  console.log(`  ✓ ${label} 成功`)
  return r.data
}

await new Promise((r) => ws.addEventListener('open', r))
await sleep(300)
await call(['inited'])
await sleep(200)

const TEST_NAME = `__anylisten_adapter_selftest_${Date.now()}`
let createdId = null

// 实测教训：服务端**不会**为新建歌单生成 id —— 传 id:'' 它就存 id:''。
// 真实数据的 id 形如 "du3jglgqkj8"（11 位小写字母数字），所以 id 必须由客户端生成，
// 并让服务端原样采用。这里用同样的形状以便与现有数据保持一致。
const genListId = () => {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789'
  let out = ''
  for (let i = 0; i < 11; i++) out += alphabet[crypto.randomInt(alphabet.length)]
  return out
}
const wantedId = genListId()
console.log(`\n客户端生成的目标 id: ${wantedId}`)

console.log(`\n=== 1. 新建测试歌单「${TEST_NAME}」===`)
// list_create 的 data 形状：{ position, listInfos: UserListInfo[] }
//
// 实测教训：第一次只用 { id, name, type:'user' } 调用，服务端报
//   "NOT NULL constraint failed: my_list.meta"
// 类型定义里 UserListInfoType<'general'> 要求 meta 为 UserListInfoByGeneralMeta，
// 其必需字段是 songCount / pic / playCount / createTime / updateTime / posTime / desc。
// 而且 type 的合法值是 'general' | 'local' | 'online' | 'remote'（不是 'user'）。
// position: -1 表示追加到末尾。
const now = Date.now()
const createRes = await call(['listAction'], {
  action: 'list_create',
  data: {
    position: -1,
    listInfos: [
      {
        id: wantedId,
        parentId: null,
        name: TEST_NAME,
        type: 'general',
        meta: {
          songCount: 0,
          pic: '',
          playCount: 0,
          createTime: now,
          updateTime: now,
          posTime: now,
          desc: '',
        },
      },
    ],
  },
})
ok(createRes, 'list_create')
await sleep(700)

console.log('\n=== 2. 读回歌单列表，找到刚建的歌单 ===')
const all = ok(await call(['getAllUserLists']), 'getAllUserLists')
if (all) {
  const found = (all.userList ?? []).find((l) => l.name === TEST_NAME)
  if (found) {
    createdId = found.id
    console.log(`  找到测试歌单: id=${createdId}`)
    console.log(`  完整对象: ${JSON.stringify(found)}`)
  } else {
    console.log(`  ✗ 没找到刚建的歌单！userList 里的名字: ${JSON.stringify((all.userList ?? []).map((l) => l.name))}`)
  }
}

if (!createdId) {
  console.log('\n建歌单没成功，后续增删改无法验证。退出。')
  process.exit(1)
}

console.log('\n=== 3. 往测试歌单加歌（从 last_played 取 2 首真实曲目）===')
const musics = ok(await call(['getListMusics'], 'last_played'), 'getListMusics(last_played)')
if (Array.isArray(musics) && musics.length >= 2) {
  const picks = musics.slice(0, 2)
  // list_music_add 的 data：{ id, musicInfos, addMusicLocationType }
  const addRes = await call(['listAction'], {
    action: 'list_music_add',
    data: { id: createdId, musicInfos: picks, addMusicLocationType: 'bottom' },
  })
  ok(addRes, `list_music_add（${picks.map((p) => p.name).join(', ')}）`)
  await sleep(700)

  console.log('\n=== 4. 读回测试歌单，确认歌曲真的进去了 ===')
  const after = ok(await call(['getListMusics'], createdId), `getListMusics(${createdId})`)
  if (Array.isArray(after)) {
    console.log(`  歌单现有 ${after.length} 首: ${JSON.stringify(after.map((x) => `${x.name} - ${x.singer}`))}`)
  }

  console.log('\n=== 5. 移除其中一首 ===')
  if (Array.isArray(after) && after.length) {
    const rmRes = await call(['listAction'], {
      action: 'list_music_remove',
      data: { listId: createdId, ids: [after[0].id] },
    })
    ok(rmRes, `list_music_remove(${after[0].name})`)
    await sleep(700)
    const afterRm = ok(await call(['getListMusics'], createdId), '读回')
    if (Array.isArray(afterRm)) {
      console.log(`  移除后剩 ${afterRm.length} 首: ${JSON.stringify(afterRm.map((x) => x.name))}`)
    }
  }

  console.log('\n=== 6. 重命名测试歌单（list_update）===')
  // 实测教训：只传 { id, name } 会报 "NOT NULL constraint failed: my_list.meta"。
  // list_update 是**整体替换**语义，必须带上完整的 meta（而且要原样带上未被修改的
  // 字段，例如 createTime），否则服务端会把它当缺失。
  const updRes = await call(['listAction'], {
    action: 'list_update',
    data: {
      lists: [
        {
          id: createdId,
          parentId: null,
          name: TEST_NAME + '_renamed',
          type: 'general',
          meta: {
            songCount: 0,
            pic: '',
            playCount: 0,
            createTime: now,
            updateTime: Date.now(),
            posTime: now,
            desc: '',
          },
        },
      ],
    },
  })
  ok(updRes, 'list_update')
  await sleep(700)
  const afterUpd = ok(await call(['getAllUserLists']), '读回歌单列表')
  if (afterUpd) {
    const renamed = (afterUpd.userList ?? []).find((l) => l.id === createdId)
    console.log(`  重命名后名字: ${JSON.stringify(renamed?.name)}`)
    console.log(`  meta 是否保留: ${JSON.stringify(renamed?.meta)}`)
  }
}

console.log('\n=== 7. 清理：删除测试歌单 ===')
const delRes = await call(['listAction'], { action: 'list_remove', data: [createdId] })
ok(delRes, 'list_remove')
await sleep(700)
const finalAll = ok(await call(['getAllUserLists']), '最终读回')
if (finalAll) {
  const stillThere = (finalAll.userList ?? []).some((l) => l.id === createdId)
  console.log(`  测试歌单是否仍存在: ${stillThere ? '✗ 是（清理失败，需要手动删）' : '✓ 否（已清理干净）'}`)
  console.log(`  当前用户歌单数: ${(finalAll.userList ?? []).length}`)
}

ws.close()
process.exit(0)

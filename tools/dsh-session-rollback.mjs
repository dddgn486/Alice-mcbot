#!/usr/bin/env node
// Alice 开发工具：**云端 → 本机的增量回迁**（清单 / 计划 / 打包 / 重建 / 文本出口 / 自检）。
//
// 为什么需要它（2026-09-24 实测）：`tools/cloud-rollback.sh` 早期版本把**整个** `~/.dsh/sessions`
// 打包回本机 —— 当时云端只有 2 个会话（21 KB，能用）。迁移 177 个会话后再跑，那一份会变成 **292 MB**
// ⇒ 在"额度快耗尽、链路不稳"的场景下不可用。本工具只搬**本机没有的那一段字节**。
//
// 核心事实（本工具成立的前提，✅ 2026-09-24 实测）：会话日志是**只追加**的，
// 且云端那份是**从本机这份复制出去**的 ⇒ `本机文件` 是 `云端文件` 的**前缀**（分叉点之前逐字节相同）。
//   本机 = 前缀[0,F) + 本机分支     云端 = 前缀[0,F) + 云端分支      F = 迁移那一刻的字节数
// ⇒ 只需传 `云端[F:]`；本机拼回 `本机[0,F) + 云端[F:]` = **云端文件的逐字节副本**，用 sha256 对账。
// ⚠️ 分叉点 F **不能靠"本机现在的长度"猜**（本机分叉后也长过）⇒ 用**哈希阶梯**反查：
//    云端给出"前 1 MiB / 2 MiB / 3 MiB … 的 sha256"，本机找**最大的、与自己前缀相同**的那一级。
//
// ⚠️ 同一会话 id 在两端**各自长过** ⇒ 两边的 seq 编号会**重叠但内容不同**。所以：
//    **不要把重建出来的云端会话直接塞进本机 `~/.dsh/sessions/`**（同 id 会与活着的本机分支互踩）。
//    它只作为**归档 + 文本**存在：`node tools/dsh-session-log.mjs --file <归档里的文件>` 即可读。
//
// 子命令（`plan`/`rebuild`/`transcript` 在本机跑；`inventory`/`pack` 在云端跑，本仓库工具可直接上传）：
//   node tools/dsh-session-rollback.mjs inventory --out /tmp/cloud-inventory.json   # 云端：清单 + 哈希阶梯
//   node tools/dsh-session-rollback.mjs plan --cloud <清单> --out /tmp/plan.json    # 本机：算增量计划
//   node tools/dsh-session-rollback.mjs pack --plan /tmp/plan.json --staging /tmp/staging   # 云端：按计划切字节
//   node tools/dsh-session-rollback.mjs rebuild --parts <解包目录> --dest <归档目录>        # 本机：拼回 + 校验
//   node tools/dsh-session-rollback.mjs transcript --in <jsonl> --out <md>          # 文本出口（层次 b）
//   node tools/dsh-session-rollback.mjs selftest                                    # 确定性自检（不进真 ~/.dsh）
//
// 失败即报错：重建时**任何一项 sha256 对不上** ⇒ 打印明细并以 exit 1 结束（不许静默降级）。
// 依赖：Node ≥ 18（只用 node: 内置模块）；无第三方依赖。

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import { spawnSync } from 'node:child_process'

const argv = process.argv.slice(2)
const flag = (name, def = null) => {
  const i = argv.indexOf(`--${name}`)
  return i === -1 ? def : (argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : true)
}
const cmd = argv[0]
const die = (m) => { console.error(`dsh-session-rollback: ${m}`); process.exit(1) }
const log = (m) => console.log(m)

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex')
const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd])
const LOG_RE = /^session\.v\d+\.jsonl(\.zstd)?$/
const STEP_DEFAULT = 1024 * 1024
const EXTRA_MAX = 4 * 1024 * 1024

/** 会话目录里的日志文件（没有就返回 null）。 */
function sessionLog(dir) {
  if (!fs.existsSync(dir)) return null
  const n = fs.readdirSync(dir).find((f) => LOG_RE.test(f))
  return n ? path.join(dir, n) : null
}

/** 递归列文件（相对 root 的相对路径）。 */
function walkFiles(root, rel = '') {
  const abs = path.join(root, rel)
  let out = []
  for (const e of fs.readdirSync(abs, { withFileTypes: true })) {
    const r = rel ? `${rel}/${e.name}` : e.name
    if (e.isDirectory()) out = out.concat(walkFiles(root, r))
    else if (e.isFile()) out.push(r)
  }
  return out
}

/** 从 off 起（含）的第一个 zstd 帧起点；找不到返回 -1。 */
function frameStartAtOrAfter(buf, off) {
  const i = buf.indexOf(MAGIC, off)
  return i
}

// ------------------------------------------------------------------ inventory（云端）

function cmdInventory() {
  const dshRoot = flag('dsh-root', path.join(os.homedir(), '.dsh'))
  const step = Number(flag('ladder-step', STEP_DEFAULT))
  const out = flag('out', null)
  const inv = { unit: 'dsh-rollback-inventory', version: 1, capturedAt: new Date().toISOString(), host: os.hostname(), dshRoot, ladderStep: step, sessions: [], attachments: [], extras: [] }

  const sroot = path.join(dshRoot, 'sessions')
  if (fs.existsSync(sroot)) {
    for (const slug of fs.readdirSync(sroot)) {
      let entries = []
      try { entries = fs.readdirSync(path.join(sroot, slug), { withFileTypes: true }) } catch { continue }
      for (const e of entries) {
        if (!e.isDirectory()) continue
        const file = sessionLog(path.join(sroot, slug, e.name))
        if (!file) continue
        const buf = fs.readFileSync(file)
        const st = fs.statSync(file)
        const ladder = []
        for (let off = step; off < buf.length; off += step) ladder.push({ offset: off, sha256: sha256(buf.subarray(0, off)) })
        inv.sessions.push({ slug, id: e.name, name: path.basename(file), file, bytes: st.size, mtime: Math.round(st.mtimeMs), sha256: sha256(buf), ladder })
      }
    }
  }

  const aroot = path.join(dshRoot, 'attachments')
  if (fs.existsSync(aroot)) {
    for (const rel of walkFiles(aroot)) {
      const buf = fs.readFileSync(path.join(aroot, rel))
      inv.attachments.push({ rel, bytes: buf.length, mtime: Math.round(fs.statSync(path.join(aroot, rel)).mtimeMs), sha256: sha256(buf) })
    }
  }

  // extras：storages/ 全量 + settings.yaml* + profiles/web/package.json（都小；本机那份 settings 才是权威，这里只为留证）
  const extraPaths = []
  const sdir = path.join(dshRoot, 'storages')
  if (fs.existsSync(sdir)) extraPaths.push(...walkFiles(sdir).map((r) => `storages/${r}`))
  for (const r of ['settings.yaml', 'settings.yaml.foreign', 'settings.yaml.copied-from-local', 'profiles/web/package.json']) {
    if (fs.existsSync(path.join(dshRoot, r))) extraPaths.push(r)
  }
  for (const rel of extraPaths) {
    const p = path.join(dshRoot, rel)
    const st = fs.statSync(p)
    if (st.size > EXTRA_MAX) continue
    inv.extras.push({ rel, bytes: st.size, sha256: sha256(fs.readFileSync(p)) })
  }

  const text = JSON.stringify(inv, null, 1)
  if (out) fs.writeFileSync(out, text)
  const totalSessions = inv.sessions.reduce((a, s) => a + s.bytes, 0)
  log(`清单：${inv.sessions.length} 个会话（${(totalSessions / 1048576).toFixed(1)}MB）· ${inv.attachments.length} 个附件 · ${inv.extras.length} 个小文件`)
  log(out ? `→ ${out}` : text.slice(0, 400))
}

// ------------------------------------------------------------------ plan（本机）

function cmdPlan() {
  const cloudFile = flag('cloud')
  if (!cloudFile || cloudFile === true) die('plan 需要 --cloud <清单.json>')
  const inv = JSON.parse(fs.readFileSync(cloudFile, 'utf8'))
  const dshRoot = flag('dsh-root', path.join(os.homedir(), '.dsh'))
  const want = []
  const skip = []

  for (const s of inv.sessions) {
    const lfile = sessionLog(path.join(dshRoot, 'sessions', s.slug, s.id))
    const base = { kind: 'session', slug: s.slug, id: s.id, name: s.name, file: s.file, bytes: s.bytes, sha256: s.sha256 }
    if (!lfile) { want.push({ ...base, mode: 'whole', why: '本机没有' }); continue }
    const lbuf = fs.readFileSync(lfile)
    if (lbuf.length === s.bytes && sha256(lbuf) === s.sha256) { skip.push({ ...base, why: '两端逐字节相同' }); continue }
    // 哈希阶梯：找最大的、与会话前缀相同的一级（L=0 ⇒ 无公共前缀）
    let L = 0
    for (const rung of s.ladder ?? []) {
      if (rung.offset > lbuf.length) break
      if (sha256(lbuf.subarray(0, rung.offset)) === rung.sha256) L = rung.offset
    }
    if (L >= s.bytes) { skip.push({ ...base, why: '本机已是云端内容的超集' }); continue }
    if (L === 0) want.push({ ...base, mode: 'whole', why: '无公共前缀' })
    else want.push({ ...base, mode: 'suffix', offset: L, why: `共同前缀 ${(L / 1048576).toFixed(2)}MB` })
  }

  for (const a of inv.attachments ?? []) {
    const lp = path.join(dshRoot, 'attachments', a.rel)
    const base = { kind: 'attachment', rel: a.rel, bytes: a.bytes, sha256: a.sha256 }
    if (!fs.existsSync(lp)) { want.push({ ...base, why: '本机没有' }); continue }
    if (sha256(fs.readFileSync(lp)) === a.sha256) skip.push({ ...base, why: '内容相同' })
    else want.push({ ...base, why: '内容不同' })
  }

  for (const x of inv.extras ?? []) {
    want.push({ kind: 'extra', rel: x.rel, bytes: x.bytes, sha256: x.sha256, why: '留证' })
  }

  const bytes = want.reduce((a, w) => a + (w.mode === 'suffix' ? Math.max(0, w.bytes - w.offset) : w.bytes), 0)
  const plan = { unit: 'dsh-rollback-plan', version: 1, createdAt: new Date().toISOString(), cloudCapturedAt: inv.capturedAt, want, skip, transferBytes: bytes }
  const out = flag('out', null)
  const text = JSON.stringify(plan, null, 1)
  if (out) fs.writeFileSync(out, text)

  log(`计划：要搬 ${want.length} 项 / 跳过 ${skip.length} 项 ⇒ 传输约 ${(bytes / 1048576).toFixed(2)}MB`)
  for (const w of want) {
    const what = w.kind === 'session' ? `${w.slug}/${w.id}` : `${w.kind}:${w.rel}`
    const how = w.mode === 'suffix' ? `后缀 @${(w.offset / 1048576).toFixed(2)}MB（${((w.bytes - w.offset) / 1048576).toFixed(2)}MB）` : `整份 ${(w.bytes / 1048576).toFixed(2)}MB`
    log(`   + ${what}  ${how}  — ${w.why}`)
  }
  if (out) log(`→ ${out}`)
}

// ------------------------------------------------------------------ pack（云端）

function cmdPack() {
  const planFile = flag('plan')
  if (!planFile || planFile === true) die('pack 需要 --plan <计划.json>')
  const plan = JSON.parse(fs.readFileSync(planFile, 'utf8'))
  const staging = flag('staging', '/tmp/rollback-staging')
  const dshRoot = flag('dsh-root', path.join(os.homedir(), '.dsh'))
  fs.rmSync(staging, { recursive: true, force: true })
  fs.mkdirSync(path.join(staging, 'parts'), { recursive: true })

  const items = []
  for (const w of plan.want) {
    if (w.kind === 'session') {
      const buf = fs.readFileSync(w.file)
      const now = sha256(buf)
      if (now !== w.sha256) die(`云端 ${w.id} 在计划生成后变了（期望 ${w.sha256.slice(0, 16)}，现在 ${now.slice(0, 16)}）⇒ 重新 plan`)
      let start = 0
      if (w.mode === 'suffix') {
        start = frameStartAtOrAfter(buf, w.offset)
        if (start < 0) die(`云端 ${w.id}：偏移 ${w.offset} 之后找不到帧起点`)
      }
      const part = `parts/${w.slug}__${w.id}.part`
      fs.writeFileSync(path.join(staging, part), buf.subarray(start))
      items.push({ kind: 'session', slug: w.slug, id: w.id, name: w.name, mode: w.mode, offset: w.offset ?? 0, start, bytes: w.bytes, sha256: w.sha256, part })
      log(`   · ${w.id} → ${w.mode === 'suffix' ? `从 ${start}（帧起点，阶梯 ${w.offset}）` : '整份'} 起 ${((w.bytes - start) / 1048576).toFixed(2)}MB`)
    } else {
      const src = w.kind === 'attachment' ? path.join(dshRoot, 'attachments', w.rel) : path.join(dshRoot, w.rel)
      if (!fs.existsSync(src)) die(`云端缺少 ${w.kind} ${w.rel}`)
      const buf = fs.readFileSync(src)
      if (w.sha256 && sha256(buf) !== w.sha256) die(`云端 ${w.rel} 内容与清单不一致`)
      const part = `parts/${w.kind}/${w.rel}`
      fs.mkdirSync(path.dirname(path.join(staging, part)), { recursive: true })
      fs.writeFileSync(path.join(staging, part), buf)
      items.push({ kind: w.kind, rel: w.rel, bytes: buf.length, sha256: w.sha256, part })
    }
  }
  fs.writeFileSync(path.join(staging, 'manifest.json'), JSON.stringify({ unit: 'dsh-rollback-manifest', version: 1, packedAt: new Date().toISOString(), host: os.hostname(), items }, null, 1))
  const total = items.reduce((a, i) => a + (i.kind === 'session' ? i.bytes - i.start : i.bytes), 0)
  log(`已暂存 ${items.length} 项 / ${(total / 1048576).toFixed(2)}MB → ${staging}（外层自行 tar 成 bundle）`)
}

// ------------------------------------------------------------------ rebuild（本机）

function cmdRebuild() {
  const partsDir = flag('parts')
  const dest = flag('dest')
  if (!partsDir || partsDir === true || !dest || dest === true) die('rebuild 需要 --parts <目录> --dest <目录>')
  const manifestFile = path.join(partsDir, 'manifest.json')
  if (!fs.existsSync(manifestFile)) die(`找不到 ${manifestFile}（没解包？）`)
  const man = JSON.parse(fs.readFileSync(manifestFile, 'utf8'))
  const dshRoot = flag('dsh-root', path.join(os.homedir(), '.dsh'))
  const rows = []
  let bad = 0
  for (const it of man.items) {
    const part = fs.readFileSync(path.join(partsDir, it.part))
    if (it.kind !== 'session') {
      const ok = !it.sha256 || sha256(part) === it.sha256
      if (!ok) { bad++; rows.push([`${it.kind}:${it.rel}`, '✗ sha256 不符', '']); continue }
      const out = it.kind === 'attachment' ? path.join(dest, '.dsh', 'attachments', it.rel) : path.join(dest, '.dsh', it.rel)
      fs.mkdirSync(path.dirname(out), { recursive: true })
      fs.writeFileSync(out, part)
      rows.push([`${it.kind}:${it.rel}`, '✓', `${(part.length / 1024).toFixed(0)}KB`])
      continue
    }
    const lfile = sessionLog(path.join(dshRoot, 'sessions', it.slug, it.id))
    let content
    if (it.mode === 'suffix') {
      if (!lfile) { bad++; rows.push([`${it.slug}/${it.id}`, '✗ 要拼本机前缀，但本机找不到该会话', '']); continue }
      content = Buffer.concat([fs.readFileSync(lfile).subarray(0, it.start), part])
    } else {
      content = part
    }
    const got = sha256(content)
    if (got !== it.sha256) {
      bad++
      rows.push([`${it.slug}/${it.id}`, `✗ sha256 不符（期望 ${it.sha256.slice(0, 16)} 得到 ${got.slice(0, 16)}）`, ''])
      continue
    }
    const out = path.join(dest, '.dsh', 'sessions', it.slug, it.id, it.name)
    fs.mkdirSync(path.dirname(out), { recursive: true })
    fs.writeFileSync(out, content)
    const kind = it.mode === 'suffix' ? `前缀 ${(it.start / 1048576).toFixed(2)}MB + ${((content.length - it.start) / 1048576).toFixed(2)}MB` : `整份 ${(content.length / 1048576).toFixed(2)}MB`
    rows.push([`${it.slug}/${it.id}`, `✓ ${sha256(content).slice(0, 16)}`, kind])
  }
  for (const r of rows) log(`   ${r[1]}  ${r[0]}  ${r[2]}`)
  log(`重建 ${rows.length - bad}/${rows.length} 项成功${bad ? `，**${bad} 项失败**` : '，全部 sha256 对账通过'} ⇒ ${dest}`)
  if (bad) process.exit(1)
}

// ------------------------------------------------------------------ transcript（本机）

function cmdTranscript() {
  const inFile = flag('in')
  const outFile = flag('out', null)
  if (!inFile || inFile === true) die('transcript 需要 --in <jsonl>')
  const toolN = Number(flag('tool-result', 400))
  const reasoning = argv.includes('--reasoning')
  const tz = Number(flag('tz-offset', 8))
  const when = (ms) => {
    if (typeof ms !== 'number') return '?'
    return new Date(ms + tz * 3600e3).toISOString().replace('T', ' ').slice(0, 19) + `+${String(tz).padStart(2, '0')}`
  }
  const one = (s, n) => String(s ?? '').replace(/\s+/g, ' ').trim().slice(0, n)
  const texts = (blocks) => (blocks ?? []).filter((b) => b.type === 'text').map((b) => b.text ?? '').join('\n')

  const lines = fs.readFileSync(inFile, 'utf8').split('\n').filter(Boolean)
  const out = []
  const hist = {}
  let seqMin = Infinity, seqMax = -Infinity, tMin, tMax
  for (const l of lines) {
    let e
    try { e = JSON.parse(l) } catch { continue }
    hist[e.type] = (hist[e.type] ?? 0) + 1
    if (typeof e.seq === 'number') { seqMin = Math.min(seqMin, e.seq); seqMax = Math.max(seqMax, e.seq) }
    if (typeof e.time === 'number') { tMin = tMin === undefined ? e.time : Math.min(tMin, e.time); tMax = tMax === undefined ? e.time : Math.max(tMax, e.time) }
  }
  out.push(`# 会话文本出口（层次 b：给下一个主工作流读的原文）`)
  out.push('')
  out.push(`- 来源：\`${inFile}\``)
  out.push(`- 事件 ${lines.length} 条 · seq ${seqMin}–${seqMax} · 时间 ${when(tMin)} → ${when(tMax)}`)
  out.push(`- ⚠️ 本文是**只读交接材料**：不要把对应会话文件塞回本机 \`~/.dsh/sessions/\`（同 id 会与活着的本机分支互踩）`)
  out.push('')
  out.push('## 事件类型直方图')
  out.push('')
  for (const [k, v] of Object.entries(hist).sort((a, b) => b[1] - a[1])) out.push(`- \`${k}\` × ${v}`)
  out.push('')
  out.push('---')
  out.push('')

  for (const l of lines) {
    let e
    try { e = JSON.parse(l) } catch { continue }
    const d = e.data ?? {}
    const head = `*[seq ${e.seq} · ${when(e.time)}]*`
    switch (e.type) {
      case 'user/message':
        out.push(`## 👤 用户 ${head}`)
        out.push('')
        out.push(texts(d.content) || '(空)')
        out.push('')
        break
      case 'assistant/message': {
        const parts = []
        for (const b of d.message?.content ?? []) {
          if (b.type === 'text' && b.text) parts.push(b.text)
          else if (b.type === 'reasoning' && reasoning) parts.push(`> [思考] ${b.text}`)
        }
        if (parts.length === 0) break
        out.push(`### 🤖 助手 ${head}`)
        out.push('')
        out.push(parts.join('\n\n'))
        out.push('')
        break
      }
      case 'tool/call':
        out.push(`- 🔧 \`${d.name}\` ${one(d.arguments, 300)}`)
        break
      case 'tool/result': {
        const t = (d.message?.content ?? []).flatMap((c) => c.content ?? []).map((c) => c.text ?? '').join('\n')
        out.push(`  - ↳ ${one(t, toolN)}${t.length > toolN ? ` …[+${t.length - toolN} 字]` : ''}`)
        break
      }
      case 'compaction/summary': {
        const s = texts(d.summary)
        out.push('')
        out.push(`## 🗜 压缩摘要 \`${String(d.compactionId).slice(0, 8)}\` 遮蔽 seq ${d.shadowedRange?.start ?? '?'}-${d.shadowedRange?.end ?? '?'} ${head}`)
        out.push('')
        out.push(s || '(空)')
        out.push('')
        break
      }
      case 'deliverables/presented':
        out.push(`- 📦 交付：${(d.files ?? []).map((f) => `\`${f.path}\``).join(' · ')}`)
        break
      case 'todo/write':
        out.push(`- ☑ TODO（${(d.todos ?? []).length} 项）：${(d.todos ?? []).map((t) => `[${t.status}] ${one(t.content, 60)}`).join(' / ')}`)
        break
      case 'goal/change':
        out.push(`- 🎯 goal ${d.operation}：${one(d.goal?.objective, 200)}${d.goal ? `（phase=${d.goal.phase}）` : ''}`)
        break
      case 'command/run':
        out.push(`- ⌨️ /${d.name ?? '?'} ${one(d.args, 100)}`)
        break
      case 'command/done':
        out.push(`  - ⌨️ ${d.kind}: ${one(d.text, 200)}`)
        break
      case 'llm/retry':
        out.push(`- ♻️ LLM 重试：${one(JSON.stringify(d), 200)}`)
        break
      case 'step/start': case 'step/end': case 'turn/start': case 'turn/end': case 'assistant/attempt':
      case 'request/header': case 'request/context': case 'compaction/start': case 'compaction/end':
      case 'compaction/prune': case 'agent/inbox/spliced': case 'session/end-seed': case 'session':
      case 'system/message': case 'session/title': case 'session/title-llm-request': case 'web/deepseek-search-llm-request':
      case 'llm/retry-started': case 'permission/preset': case 'sandbox/mode': case 'approval/policy': case 'subagent/catalog':
        break
      default:
        out.push(`- ❔ \`${e.type}\` ${one(JSON.stringify(d), 200)}`)
    }
  }
  const text = out.join('\n') + '\n'
  if (outFile) { fs.writeFileSync(outFile, text); log(`文本出口 → ${outFile}（${(text.length / 1024).toFixed(0)}KB）`) } else console.log(text)
}

// ------------------------------------------------------------------ selftest

function cmdSelftest() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'rollback-selftest-'))
  const mkdsh = (name) => { const r = path.join(tmp, name); fs.mkdirSync(path.join(r, 'sessions'), { recursive: true }); return r }
  const cloud = mkdsh('cloud'), local = mkdsh('local')
  let fails = 0, checks = 0
  const check = (cond, label) => { checks++; log(`   ${cond ? '✓' : '✗'} ${label}`); if (!cond) fails++ }

  // 造一个 3.2MB 的"会话文件"：64KB 一帧，每帧以 zstd 魔数开头（模拟帧边界）
  const frame = (seed) => {
    const b = Buffer.alloc(65536)
    for (let i = 0; i < b.length; i++) b[i] = (seed * 31 + i * 7) % 251
    MAGIC.copy(b, 0)
    return b
  }
  const build = (n, seed0) => Buffer.concat(Array.from({ length: n }, (_, i) => frame(seed0 + i)))
  const put = (root, slug, id, buf) => {
    const d = path.join(root, 'sessions', slug, id)
    fs.mkdirSync(d, { recursive: true })
    fs.writeFileSync(path.join(d, 'session.v3.jsonl.zstd'), buf)
  }
  const SLUG = '--home-fb486-projects--'
  const common = build(16, 1)                      // 1 MiB 公共前缀（16 帧 × 64KB）
  const cloudOnly = build(3, 101)                  // 云端分支
  const localOnly = build(2, 201)                  // 本机分支
  const cloudBuf = Buffer.concat([common, cloudOnly])       // 1.25MB
  const localBuf = Buffer.concat([common, localOnly])
  put(cloud, SLUG, 'session-aaa', cloudBuf)                 // 分叉：应判 suffix
  put(local, SLUG, 'session-aaa', localBuf)
  put(cloud, SLUG, 'session-bbb', cloudBuf)                 // 本机一模一样：应 skip
  put(local, SLUG, 'session-bbb', Buffer.from(cloudBuf))
  put(cloud, SLUG, 'session-ccc', cloudBuf)                 // 本机没有：应 whole
  put(cloud, SLUG, 'session-ddd', build(4, 301))            // 无公共前缀：应 whole
  put(local, SLUG, 'session-ddd', build(4, 401))
  fs.mkdirSync(path.join(cloud, 'attachments', 'v1'), { recursive: true })
  fs.writeFileSync(path.join(cloud, 'attachments', 'v1', 'obj1'), Buffer.from('hello-attachment'))
  fs.mkdirSync(path.join(cloud, 'storages'), { recursive: true })
  fs.writeFileSync(path.join(cloud, 'storages', 'workspace.json'), Buffer.from('{"unit":{}}'))

  const run = (args, expectStatus = 0) => {
    const r = spawnSync(process.execPath, [new URL(import.meta.url).pathname, ...args], { encoding: 'utf8' })
    if (expectStatus === 0 && r.status !== 0) { log(r.stdout); log(r.stderr); fails++; log(`   ✗ 子命令退出码 ${r.status}`) }
    return r
  }
  const inv = path.join(tmp, 'inv.json'), plan = path.join(tmp, 'plan.json'), staging = path.join(tmp, 'staging'), dest = path.join(tmp, 'dest')
  run(['inventory', '--dsh-root', cloud, '--ladder-step', '1048576', '--out', inv])
  const invObj = JSON.parse(fs.readFileSync(inv, 'utf8'))
  const aaa = invObj.sessions.find((s) => s.id === 'session-aaa')
  check(aaa && aaa.ladder.length >= 1 && aaa.ladder[0].offset === 1048576, '清单带回阶梯（1MiB 一级）')

  run(['plan', '--dsh-root', local, '--cloud', inv, '--out', plan])
  const p = JSON.parse(fs.readFileSync(plan, 'utf8'))
  const mode = (id) => p.want.find((w) => w.id === id)?.mode ?? 'skip'
  check(mode('session-aaa') === 'suffix', `分叉会话判为 suffix（得到 ${mode('session-aaa')}）`)
  check(mode('session-bbb') === 'skip', `逐字节相同判为 skip（得到 ${mode('session-bbb')}）`)
  check(mode('session-ccc') === 'whole', `本机没有判为 whole（得到 ${mode('session-ccc')}）`)
  check(mode('session-ddd') === 'whole', `无公共前缀判为 whole（得到 ${mode('session-ddd')}）`)
  const aaaWant = p.want.find((w) => w.id === 'session-aaa')
  check(aaaWant.offset === 1048576, `分叉点取到 1MiB 那级（得到 ${aaaWant?.offset}）`)
  check(p.transferBytes < cloudBuf.length * 3, `传输量远小于全量（${(p.transferBytes / 1024).toFixed(0)}KB）`)
  check(p.want.some((w) => w.kind === 'attachment' && w.rel === 'v1/obj1'), '附件进计划')
  check(p.want.some((w) => w.kind === 'extra' && w.rel === 'storages/workspace.json'), '小文件进计划')

  run(['pack', '--dsh-root', cloud, '--plan', plan, '--staging', staging])
  run(['rebuild', '--dsh-root', local, '--parts', staging, '--dest', dest])
  const sameFile = (a, b) => fs.existsSync(a) && fs.existsSync(b) && sha256(fs.readFileSync(a)) === sha256(fs.readFileSync(b))
  for (const id of ['session-aaa', 'session-ccc', 'session-ddd']) {
    check(sameFile(path.join(dest, '.dsh', 'sessions', SLUG, id, 'session.v3.jsonl.zstd'),
                   path.join(cloud, 'sessions', SLUG, id, 'session.v3.jsonl.zstd')), `重建 ${id} 与云端逐字节相同`)
  }
  // 被 skip 的会话**没有**进 bundle（本机那份本来就逐字节相同）⇒ 断言本机那份 = 云端那份
  check(sameFile(path.join(local, 'sessions', SLUG, 'session-bbb', 'session.v3.jsonl.zstd'),
                 path.join(cloud, 'sessions', SLUG, 'session-bbb', 'session.v3.jsonl.zstd')), 'skip 的会话：本机那份本来就逐字节相同')
  check(fs.existsSync(path.join(dest, '.dsh', 'attachments', 'v1', 'obj1'))
        && fs.readFileSync(path.join(dest, '.dsh', 'attachments', 'v1', 'obj1'), 'utf8') === 'hello-attachment', '附件重建')
  check(fs.existsSync(path.join(dest, '.dsh', 'storages', 'workspace.json')), '小文件重建')

  // 反向臂：故意改坏一个 part ⇒ rebuild 必须**红**（不许静默通过）
  const badStaging = path.join(tmp, 'staging-bad')
  fs.cpSync(staging, badStaging, { recursive: true })
  const man = JSON.parse(fs.readFileSync(path.join(badStaging, 'manifest.json'), 'utf8'))
  const target = man.items.find((i) => i.kind === 'session' && i.id === 'session-ccc')
  const tb = fs.readFileSync(path.join(badStaging, target.part)); tb[100] ^= 0xff
  fs.writeFileSync(path.join(badStaging, target.part), tb)
  const r = run(['rebuild', '--dsh-root', local, '--parts', badStaging, '--dest', path.join(tmp, 'dest-bad')], 1)
  check(r.status === 1, `part 被改坏 ⇒ rebuild 退出码 1（得到 ${r.status}）`)

  fs.rmSync(tmp, { recursive: true, force: true })
  log(`SELFTEST_RESULT ${fails === 0 ? 'PASS' : 'FAIL'}: checks=${checks} failed=${fails}`)
  process.exit(fails === 0 ? 0 : 1)
}

// ------------------------------------------------------------------ 入口

switch (cmd) {
  case 'inventory': cmdInventory(); break
  case 'plan': cmdPlan(); break
  case 'pack': cmdPack(); break
  case 'rebuild': cmdRebuild(); break
  case 'transcript': cmdTranscript(); break
  case 'selftest': cmdSelftest(); break
  case '--help': case '-h': case undefined:
    console.log(fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').slice(1, 32).map((l) => l.replace(/^\/\/ ?/, '')).join('\n')); break
  default: die(`未知子命令 ${cmd}（inventory/plan/pack/rebuild/transcript/selftest）`)
}

#!/usr/bin/env node
// Alice 开发工具（只读）：把 DSH 会话日志解码成可 grep 的 JSONL，并支持"按 seq 取回被压缩遮蔽的原文"。
//
// 为什么需要它：DSH 的压缩只改**上下文**，不改**存储**——`compaction/summary` 事件带
// `shadowedSeqs`/`shadowedRange`，其指向的原始事件在磁盘上全部还在。压缩后用本工具按 seq 取回原文，
// 就不必依赖（1% 量级有损的）摘要，也不需要任何第三方插件。
//
// 纪律（固定动作⑤，见 AGENTS.md §上下文）：
//   **只在"压缩吞掉了当前需要的细节"时用；不做每轮例行解码。**
//
// 用法：
//   node tools/dsh-session-log.mjs                          # 最新会话：类型直方图 + 压缩表
//   node tools/dsh-session-log.mjs --list                   # 列出所有会话（id/大小/时间/标题）
//   node tools/dsh-session-log.mjs --session <id> --stats   # 指定会话
//   node tools/dsh-session-log.mjs --shadowed [id|last]     # 列出某次压缩遮蔽了哪些 seq（含取回命令）
//   node tools/dsh-session-log.mjs --seq 2770-2905 --full   # 取回原文（--full 不截断）
//   node tools/dsh-session-log.mjs --grep 'shadowedSeqs' --max 5
//   node tools/dsh-session-log.mjs --out /tmp/s.jsonl       # 只解码落盘（之后自己 grep）
//   node tools/dsh-session-log.mjs --json                   # 机器可读
//   node tools/dsh-session-log.mjs --file <路径> --stats    # ⭐ 直接解**归档里的**会话文件（不查 ~/.dsh；回迁用）
//
// 只读保证：从不写入 `~/.dsh`；解码产物默认写 /tmp（可用 --out 改）。
// 失败即报错：任何一行无法解析为 JSON ⇒ exit 1（磁盘格式带世代迁移 v0→v1→v2→v3，升代后本工具会**响亮地**失效）。
//
// 依赖：Node ≥ 22.15（需要 zlib.zstdDecompressSync）；无第三方依赖。

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import zlib from 'node:zlib'

const argv = process.argv.slice(2)
const flag = (name, def = null) => {
  const i = argv.indexOf(`--${name}`)
  return i === -1 ? def : (argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : true)
}
const SESSIONS_ROOT = path.join(os.homedir(), '.dsh', 'sessions')
// ⭐ `--file`：直接解一个**指定的**会话文件（回迁/归档场景）——绕开 `~/.dsh` 下的会话发现。
const FILE_ARG = typeof flag('file', null) === 'string' ? flag('file', null) : null
const PREVIEW = Number(flag('preview', 300))
const MAX = Number(flag('max', 20))
const FULL = argv.includes('--full')
const JSON_OUT = argv.includes('--json')

function die(msg, code = 1) {
  console.error(`dsh-session-log: ${msg}`)
  process.exit(code)
}

if (!FILE_ARG && !fs.existsSync(SESSIONS_ROOT)) die(`找不到会话目录 ${SESSIONS_ROOT}（本机没跑过 DSH？）`)
if (FILE_ARG && !fs.existsSync(FILE_ARG)) die(`--file 指向的文件不存在：${FILE_ARG}`)
if (typeof zlib.zstdDecompressSync !== 'function') die(`需要 Node ≥ 22.15 的 zlib.zstdDecompressSync（当前 ${process.version}）`)

/** 枚举所有会话：{id, slug, file, bytes, mtime}。 */
function listSessions() {
  const out = []
  for (const slug of fs.readdirSync(SESSIONS_ROOT)) {
    const slugDir = path.join(SESSIONS_ROOT, slug)
    let entries
    try { entries = fs.readdirSync(slugDir, { withFileTypes: true }) } catch { continue }
    for (const e of entries) {
      if (!e.isDirectory()) continue
      const dir = path.join(slugDir, e.name)
      const log = fs.readdirSync(dir).find((f) => /^session\.v\d+\.jsonl(\.zstd)?$/.test(f))
      if (!log) continue
      const st = fs.statSync(path.join(dir, log))
      out.push({ id: e.name, slug, file: path.join(dir, log), bytes: st.size, mtime: st.mtimeMs })
    }
  }
  return out.sort((a, b) => b.mtime - a.mtime)
}

/** 逐帧解码：按 zstd 魔数切候选点，切片从最小候选开始、解不开就延长（压缩载荷内的假魔数由此被滤掉）。 */
function decode(file) {
  const buf = fs.readFileSync(file)
  const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd])
  const offs = []
  for (let i = 0; i + 3 < buf.length; i++) {
    if (buf[i] === 0x28 && buf[i + 1] === 0xb5 && buf[i + 2] === 0x2f && buf[i + 3] === 0xfd) offs.push(i)
  }
  if (offs.length === 0 || offs[0] !== 0) die(`${file}: 不是预期的 zstd 帧格式（首个魔数不在偏移 0）`)
  const parts = []
  let idx = 0
  while (idx < offs.length) {
    let ok = false
    for (let j = idx + 1; j <= offs.length; j++) {
      const end = j < offs.length ? offs[j] : buf.length
      try {
        parts.push(zlib.zstdDecompressSync(buf.subarray(offs[idx], end)))
        idx = j
        ok = true
        break
      } catch { /* 切片被截断或含假魔数 ⇒ 延长 */ }
    }
    if (!ok) die(`${file}: 第 ${idx} 个帧起点(${offs[idx]}) 之后无法解出有效帧——日志可能被截断或格式已升代`)
  }
  const text = Buffer.concat(parts).toString('utf8')
  const raw = text.split('\n').filter(Boolean)
  const events = []
  const bad = []
  for (let i = 0; i < raw.length; i++) {
    try {
      events.push(JSON.parse(raw[i]))
    } catch {
      bad.push(i + 1)
    }
  }
  if (bad.length > 0) {
    die(`${file}: ${bad.length}/${raw.length} 行无法解析（首个在第 ${bad[0]} 行）——磁盘格式很可能已升代，本工具需更新`)
  }
  return { text, events, frames: parts.length, candidates: offs.length }
}

const cut = (s, n = PREVIEW) => (FULL || s.length <= n ? s : `${s.slice(0, n)}…[+${s.length - n} 字]`)
const findSession = (id) => {
  if (FILE_ARG) {
    const st = fs.statSync(FILE_ARG)
    return { id: `(file) ${path.basename(path.dirname(FILE_ARG))}`, slug: '(file)', file: FILE_ARG, bytes: st.size, mtime: st.mtimeMs }
  }
  const all = listSessions()
  if (!id) {
    const env = process.env.DSH_SESSION_ID
    const hit = env ? all.find((s) => s.id === env) : null
    return hit ?? all[0] ?? die('没有任何会话日志')
  }
  return all.find((s) => s.id === id || s.id === `session-${id}`) ?? die(`找不到会话 ${id}（用 --list 看有哪些）`)
}

// ------------------------------------------------------------------ 子命令

function cmdList() {
  const all = listSessions()
  if (JSON_OUT) return console.log(JSON.stringify(all, null, 2))
  for (const s of all.slice(0, MAX)) {
    const when = new Date(s.mtime).toISOString().replace('T', ' ').slice(0, 19)
    console.log(`${s.id}  ${(s.bytes / 1e6).toFixed(2)}MB  ${when}  ${s.slug}`)
  }
  if (all.length > MAX) console.log(`… 共 ${all.length} 个（--max N 看更多）`)
}

/** 统计 + 压缩表：默认视图。 */
function cmdStats(sess) {
  const { events, frames, candidates, text } = decode(sess.file)
  const hist = {}
  for (const e of events) hist[e.type ?? '?'] = (hist[e.type ?? '?'] ?? 0) + 1
  const compactions = events
    .filter((e) => e.type === 'compaction/summary')
    .map((e) => ({
      compactionId: e.data.compactionId,
      sourceCommandId: e.data.sourceCommandId ?? null,
      seq: e.seq,
      time: new Date(e.time).toISOString(),
      shadowedRange: e.data.shadowedRange,
      shadowedEvents: (e.data.shadowedSeqs ?? []).length,
      shadowedTokens: e.data.shadowedTokenCount,
      summaryChars: (e.data.summary ?? []).map((p) => p.text ?? '').join('').length,
      model: e.data.model,
    }))
  const shadowedAll = new Set(compactions.flatMap((c) => events.find((e) => e.seq === c.seq).data.shadowedSeqs ?? []))
  const seqSet = new Set(events.filter((e) => typeof e.seq === 'number').map((e) => e.seq))
  const recoverable = [...shadowedAll].filter((s) => seqSet.has(s)).length

  if (JSON_OUT) {
    return console.log(JSON.stringify({ session: sess.id, file: sess.file, frames, candidates, bytes: text.length, events: events.length, types: hist, compactions, shadowedRecoverable: `${recoverable}/${shadowedAll.size}` }, null, 2))
  }
  console.log(`会话 ${sess.id}`)
  console.log(`  文件 ${sess.file}（${(sess.bytes / 1e6).toFixed(2)}MB）`)
  console.log(`  解码 ${candidates} 候选 → ${frames} 帧 → ${(text.length / 1e6).toFixed(2)}MB / ${events.length} 事件 / 0 行不可解析`)
  console.log(`  类型 ${JSON.stringify(Object.fromEntries(Object.entries(hist).sort((a, b) => b[1] - a[1])))}`)
  if (compactions.length > 0) {
    console.log(`  压缩 ${compactions.length} 次；被遮蔽事件**仍在磁盘**：${recoverable}/${shadowedAll.size}`)
    for (const c of compactions) {
      const kind = c.sourceCommandId ? `手动(${c.sourceCommandId})` : '自动'
      console.log(`    ${c.compactionId.slice(0, 8)} ${kind} seq=${c.seq} 遮蔽 ${c.shadowedRange.start}-${c.shadowedRange.end}`
        + ` 事件${c.shadowedEvents} token${c.shadowedTokens} → 摘要${c.summaryChars}字`)
    }
    console.log(`  取回原文：node tools/dsh-session-log.mjs --shadowed last`)
  }
}

function cmdShadowed(sess, which) {
  const { events } = decode(sess.file)
  const summaries = events.filter((e) => e.type === 'compaction/summary')
  if (summaries.length === 0) return console.log('本会话没有压缩记录')
  const pick = !which || which === 'last' ? summaries[summaries.length - 1]
    : summaries.find((e) => e.data.compactionId.startsWith(String(which))) ?? die(`找不到压缩 ${which}`)
  const d = pick.data
  const seqs = d.shadowedSeqs ?? []
  const seqSet = new Set(events.filter((e) => typeof e.seq === 'number').map((e) => e.seq))
  const missing = seqs.filter((s) => !seqSet.has(s))
  console.log(`压缩 ${d.compactionId}（${d.sourceCommandId ? `手动 ${d.sourceCommandId}` : '自动'}）`)
  console.log(`  遮蔽区间 ${d.shadowedRange.start}-${d.shadowedRange.end}，事件 ${seqs.length}，token ${d.shadowedTokenCount}`)
  console.log(`  摘要 ${(d.summary ?? []).map((p) => p.text ?? '').join('').length} 字，模型 ${d.model}`)
  console.log(`  原文仍在磁盘：${seqs.length - missing.length}/${seqs.length}${missing.length ? `（缺失 ${missing.slice(0, 5).join(',')}…）` : '（全部可取回）'}`)
  console.log(`  取回命令：node tools/dsh-session-log.mjs --seq ${d.shadowedRange.start}-${d.shadowedRange.end}`)
  console.log('  摘要预览：', cut((d.summary ?? []).map((p) => p.text ?? '').join(''), PREVIEW))
}

function cmdSeq(sess, spec) {
  const { events } = decode(sess.file)
  const m = /^(\d+)(?:-(\d+))?$/.exec(String(spec)) ?? die(`--seq 需要 <a> 或 <a>-<b>，收到 ${spec}`)
  const a = Number(m[1])
  const b = m[2] ? Number(m[2]) : a
  const hit = events.filter((e) => typeof e.seq === 'number' && e.seq >= a && e.seq <= b)
  console.log(`seq ${a}-${b}：命中 ${hit.length} 条（截断长度 ${FULL ? '无' : PREVIEW}）`)
  const shown = hit.slice(0, MAX)
  for (const e of shown) {
    console.log(`[seq=${e.seq} ${e.type}] ${cut(JSON.stringify(e.data ?? e))}`)
  }
  if (hit.length > shown.length) console.log(`… 还有 ${hit.length - shown.length} 条（--max N / --full / --json）`)
  if (JSON_OUT) console.log(JSON.stringify(hit, null, 2))
}

function cmdGrep(sess, re) {
  const rx = new RegExp(re, 'i')
  const { events } = decode(sess.file)
  const hit = events.filter((e) => rx.test(JSON.stringify(e)))
  console.log(`/ ${re} / ⇒ ${hit.length} 条命中（显示前 ${Math.min(MAX, hit.length)} 条）`)
  for (const e of hit.slice(0, MAX)) console.log(`[seq=${e.seq} ${e.type}] ${cut(JSON.stringify(e.data ?? e))}`)
}

function cmdOut(sess, out) {
  const { text, events, frames } = decode(sess.file)
  fs.writeFileSync(out, text)
  console.log(`已解码 ${events.length} 事件 / ${frames} 帧 → ${out}（${(text.length / 1e6).toFixed(2)}MB）；之后自己 grep（勿整份读入上下文）`)
}

// ------------------------------------------------------------------ 入口

if (argv.includes('--list')) {
  cmdList()
} else if (argv.includes('--help') || argv.includes('-h')) {
  console.log(fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').slice(1, 22).map((l) => l.replace(/^\/\/ ?/, '')).join('\n'))
} else {
  const sess = findSession(flag('session', null) === true ? null : flag('session', null))
  if (argv.includes('--shadowed')) cmdShadowed(sess, flag('shadowed', 'last') === true ? 'last' : flag('shadowed', 'last'))
  else if (argv.includes('--seq')) cmdSeq(sess, flag('seq'))
  else if (argv.includes('--grep')) cmdGrep(sess, flag('grep'))
  else if (argv.includes('--out')) cmdOut(sess, flag('out'))
  else cmdStats(sess)
}

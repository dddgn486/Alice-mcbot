#!/usr/bin/env node
// Alice 开发工具（**只读**）：审计 DSH 会话「目录名 ↔ 头部 cwd」是否自洽。
//
// 为什么需要它（2026-10-07 真事故 —— 整个 dsh 起不来）：
//   从云端把会话迁回本地时，我把会话文件直接放进本机 `--home-fb486-projects--/`，
//   而它的头部 `cwd` 仍是 `/home/vscode` ⇒ 同时违反持久层的两条约束：
//     ① `session header cwd must be an absolute path`
//     ② `corrupt session log: header id "…" and cwd identify "…"` —— 身份断言 `assertStoredIdentity`：
//        **会话目录名是从头部 cwd 编码出来的**
//   ⚠️ 致命点：`dsh-workspace` 在**启动阶段枚举所有会话**做校验 ⇒ 撞上第一个就 **boot 失败**，
//   而 `--dump-config` 照过 ⇒ 「能过配置、真启动才炸」，极具迷惑性。
//
// 判据来源：**从本机 155 个真实会话反推**（`--learn` 会把候选规则全跑一遍）——
//   slug = '-' + cwd.replaceAll('/', '-').replaceAll('@', '~0040') + '--'
//   例：/home/fb486/projects → --home-fb486-projects--
//   ⚠️ 特殊字符是 `~0040`，⛔ 不是 `-0040`（后者是把 `~` 看错成 `-` 的常见笔误）
//
// 用法：
//   node tools/dsh-session-slug-audit.mjs              # 审计（有不自洽 ⇒ 退出码 1，可挂进门禁）
//   node tools/dsh-session-slug-audit.mjs --learn      # 从真实数据反推/复核 slug 规则
//   DSH_HOME=/path node tools/dsh-session-slug-audit.mjs
// ⛔ 本工具**不改任何文件**。

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

const ROOT = path.join(process.env.DSH_HOME || path.join(os.homedir(), '.dsh'), 'sessions');
const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

/** 只解**第一帧**（= 会话头 JSON）；后面的帧是追加的事件，⛔ 本工具不碰。 */
function readHeader(file) {
  const buf = fs.readFileSync(file);
  const i = buf.indexOf(MAGIC);
  if (i < 0) return null;
  const j = buf.indexOf(MAGIC, i + 4);
  let head;
  try {
    head = zlib.zstdDecompressSync(buf.subarray(i, j < 0 ? buf.length : j)).toString('utf8');
  } catch {
    return null;
  }
  let hdr = null;
  try {
    hdr = JSON.parse(head.slice(0, head.indexOf('\n') < 0 ? head.length : head.indexOf('\n')));
  } catch {
    /* 头不是 JSON */
  }
  let frames = 0;
  for (let k = buf.indexOf(MAGIC); k !== -1; k = buf.indexOf(MAGIC, k + 4)) frames++;
  return { hdr, frames, size: buf.length };
}

/** 候选中按真实数据挑出对的那条（见文件头「判据来源」）。 */
const RULES = [
  { name: 'A: - + cwd(斜杠→-) + --', at: false },
  { name: 'B: A + @→~0040', at: true },
];
function slugFor(cwd, rule) {
  const s = rule.at ? cwd.replaceAll('@', '~0040') : cwd;
  return '-' + s.replaceAll('/', '-') + '--';
}

if (!fs.existsSync(ROOT)) {
  console.error(`✗ 找不到会话根：${ROOT}`);
  process.exit(2);
}

const rows = [];
for (const slug of fs.readdirSync(ROOT)) {
  const slugDir = path.join(ROOT, slug);
  if (!fs.statSync(slugDir).isDirectory()) continue;
  for (const sid of fs.readdirSync(slugDir)) {
    const f = path.join(slugDir, sid, 'session.v3.jsonl.zstd');
    if (!fs.existsSync(f)) continue;
    const r = readHeader(f);
    rows.push({ slug, sid, dir: path.join(slugDir, sid), cwd: r?.hdr?.cwd, id: r?.hdr?.id, frames: r?.frames, size: r?.size });
  }
}

if (process.argv.includes('--learn')) {
  console.log(`从 ${rows.length} 个真实会话反推 slug 编码规则：`);
  for (const rule of RULES) {
    let hit = 0;
    let miss = 0;
    for (const r of rows) {
      if (!r.cwd) continue;
      if (slugFor(r.cwd, rule) === r.slug) hit++;
      else miss++;
    }
    console.log(`  ${rule.name.padEnd(26)} 命中 ${hit} / 不符 ${miss}`);
  }
  console.log('\n样本：');
  for (const r of rows.filter((x) => x.cwd).slice(0, 5)) {
    console.log(`  cwd = ${r.cwd}`);
    console.log(`    dir = ${r.slug}`);
    console.log(`    算出 = ${slugFor(r.cwd, RULES[1])}  ${slugFor(r.cwd, RULES[1]) === r.slug ? '✅' : '⛔'}`);
  }
  process.exit(0);
}

const bad = rows.filter((r) => !r.cwd || !RULES.some((rule) => slugFor(r.cwd, rule) === r.slug));
console.log(`会话总数 ${rows.length} · 自洽 ${rows.length - bad.length} · ⛔ 不自洽 ${bad.length}`);
for (const r of bad) {
  console.log(`\n  ⛔ ${r.sid}`);
  console.log(`     所在目录    = ${r.slug}`);
  console.log(`     头部 cwd    = ${r.cwd ?? '(读不到 / 非绝对路径)'}`);
  if (r.cwd) console.log(`     目录名应为  = ${slugFor(r.cwd, RULES[1])}`);
}
if (bad.length) {
  console.log('\n⇒ ⛔ **先修好再启动 dsh**（一个不自洽就够让整个 boot 失败）。');
  console.log('   改头部帧用 `node tools/dsh-session-cwd-rewrite.mjs <会话目录> <新cwd> --write`，');
  console.log('   然后 `mv` 到上面「目录名应为」那个名字。⛔ 两步必须同刀做。');
  process.exit(1);
}

#!/usr/bin/env node
// Alice 开发工具：改写 DSH 会话**头部帧**里的 `cwd`（并**只重压那一帧**）。
//
// 为什么需要它：跨机搬会话时，头部 `cwd` 往往是**来源机**的路径（如云端的 `/home/vscode`），
// 而本地既没有这个目录、也不满足持久层的两条硬约束（见 `tools/dsh-session-slug-audit.mjs` 的文件头）：
//   ① 头部 `cwd` 必须是**本地真实存在的绝对路径**；
//   ② **会话目录名 = 头部 cwd 的编码**（`assertStoredIdentity`）。
// ⛔ 两步必须**同刀**：只改头部不搬目录（或反之）⇒ `dsh-workspace` 启动阶段枚举会话时 boot 失败，
//    而 `--dump-config` 照过 ⇒ 极具迷惑性（2026-10-07 真事故）。
//
// 技术要点：会话文件是**多帧 zstd 拼接**，第 0 帧 = 会话头 JSON，第 1 帧起 = 逐次追加的事件。
//   本工具**只重压第 0 帧**，第 1 帧起的字节**原样保留**，并在写盘前**自证**这两点；
//   自证不过 ⇒ 拒绝写入（⛔ 绝不产出半残会话）。
//
// 用法：
//   node tools/dsh-session-cwd-rewrite.mjs <会话目录> <新cwd绝对路径>            # 试跑（⛔ 不写盘）
//   node tools/dsh-session-cwd-rewrite.mjs <会话目录> <新cwd绝对路径> --write    # 真写（原文件先备份）
//   写完脚本会打印你必须执行的 `mv` 命令 —— ⛔ 别漏（漏了就 boot 失败）。
// 收尾自检：`node tools/dsh-session-slug-audit.mjs` 必须报「⛔ 不自洽 0」。

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

function frameOffsets(buf) {
  const offs = [];
  for (let i = buf.indexOf(MAGIC); i !== -1; i = buf.indexOf(MAGIC, i + 4)) offs.push(i);
  return offs;
}
function firstFrame(buf) {
  const o = frameOffsets(buf);
  return buf.subarray(o[0], o[1]);
}
export function slugFor(cwd) {
  return '-' + cwd.replaceAll('@', '~0040').replaceAll('/', '-') + '--';
}

const args = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const [dir, newCwd] = args;
const doWrite = process.argv.includes('--write');

if (!dir || !newCwd) {
  console.error('用法: node tools/dsh-session-cwd-rewrite.mjs <会话目录> <新cwd绝对路径> [--write]');
  process.exit(2);
}
if (!newCwd.startsWith('/')) {
  console.error(`✗ 新 cwd 必须是绝对路径（持久层硬约束①）：${newCwd}`);
  process.exit(2);
}
if (!fs.existsSync(newCwd)) {
  console.error(`⚠️ 新 cwd 本地**不存在**：${newCwd} —— ⛔ 不至于炸 boot，但会话的工作目录无效，会很难用`);
}

const file = path.join(dir, 'session.v3.jsonl.zstd');
if (!fs.existsSync(file)) {
  console.error(`✗ 找不到 ${file}`);
  process.exit(2);
}

const buf = fs.readFileSync(file);
const offs = frameOffsets(buf);
if (offs.length < 2) {
  console.error(`✗ 只找到 ${offs.length} 帧，取样异常（拒绝动手）`);
  process.exit(2);
}
const headEnd = offs[1];
const head = zlib.zstdDecompressSync(buf.subarray(offs[0], headEnd)).toString('utf8');
const nl = head.indexOf('\n');
const hdr = JSON.parse(head.slice(0, nl < 0 ? head.length : nl));

console.log(`会话 id    = ${hdr.id}`);
console.log(`头部 cwd   = ${hdr.cwd}`);
console.log(`新  cwd    = ${newCwd}`);
console.log(`帧数       = ${offs.length}（只重压第 0 帧，其余 ${offs.length - 1} 帧原样保留）`);
console.log(`当前目录名 = ${path.basename(dir)}`);
console.log(`改后应为   = ${slugFor(newCwd)}`);

if (hdr.cwd === newCwd) {
  console.log('→ 头部 cwd 已是目标值，⛔ 无需改写（⚠️ 但仍要确认目录名 == 上面那个「改后应为」）');
  process.exit(0);
}

hdr.cwd = newCwd;
const out = Buffer.concat([
  zlib.zstdCompressSync(Buffer.from(JSON.stringify(hdr) + '\n', 'utf8')),
  buf.subarray(headEnd),
]);

const sameTail = out.subarray(out.length - (buf.length - headEnd)).equals(buf.subarray(headEnd));
const back = JSON.parse(zlib.zstdDecompressSync(firstFrame(out)).toString('utf8').split('\n')[0]);
console.log(`自证·第 1 帧起字节逐字相同 = ${sameTail ? '✅' : '⛔ 拒绝写入'}`);
console.log(`自证·回读头部 cwd          = ${back.cwd} ${back.cwd === newCwd ? '✅' : '⛔'}`);
console.log(`自证·帧数不变              = ${frameOffsets(out).length === offs.length ? '✅' : '⛔'}`);
if (!sameTail || back.cwd !== newCwd || frameOffsets(out).length !== offs.length) {
  console.error('⇒ 自证未全过，⛔ 拒绝写入');
  process.exit(3);
}

if (!doWrite) {
  console.log('\n（试跑：⛔ 未写盘。加 --write 才真写）');
  process.exit(0);
}

const bak = `${file}.bak-${new Date().toISOString().replace(/[-:T]/g, '').slice(0, 15)}`;
fs.copyFileSync(file, bak);
const tmp = `${file}.tmp`;
fs.writeFileSync(tmp, out);
fs.renameSync(tmp, file);
console.log(`\n已写 ${file}\n备份 ${path.basename(bak)}`);
console.log(`\n⚠️ 还差一步（⛔ 漏了就让 dsh 起不来）：`);
console.log(`   mv "${dir}" "${path.join(path.dirname(dir), slugFor(newCwd))}"`);

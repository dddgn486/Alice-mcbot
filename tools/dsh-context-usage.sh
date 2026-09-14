#!/usr/bin/env bash
# 读取**真实**上下文用量（禁止估算）：来源 = DSH 会话 JSONL
#   ~/.dsh/sessions/<cwd-slug>/<DSH_SESSION_ID>/session.v3.jsonl.zstd
#   窗口   ← request/context.data.contextWindow
#   用量   ← assistant/message.data.usage.totalTokens（= input + output + cacheRead，即该步之后真实上下文占用）
# 阈值   ← @deepseek-ai/dsh-compaction-basic 默认 thresholdRatio=0.8 / retainRatio=0.16
#          （settings.yaml 若覆盖 quota/thresholdRatio，本脚本不读它 ⇒ 以 harness 生效值为准）
# 用法: bash tools/dsh-context-usage.sh [会话id]   （默认用 $DSH_SESSION_ID）
set -euo pipefail
SID="${1:-${DSH_SESSION_ID:-}}"
[ -n "$SID" ] || { echo "需要会话 id（或设置 DSH_SESSION_ID）"; exit 2; }
DIR="$(cd "$(dirname "$0")/.." && pwd)"
node - "$SID" "$DIR" <<'JS'
const fs=require("fs"),zlib=require("zlib"),path=require("path");
const sid=process.argv[2], root=process.argv[3];
const base=path.join(process.env.HOME||"", ".dsh","sessions");
let file=null;
for(const slug of fs.readdirSync(base)){
  const c=path.join(base,slug,sid,"session.v3.jsonl.zstd");
  if(fs.existsSync(c)){file=c;break;}
}
if(!file){console.error("找不到会话文件:",sid);process.exit(3);}
const raw=fs.readFileSync(file), M=Buffer.from([0x28,0xB5,0x2F,0xFD]);
let idxs=[],i=0; while((i=raw.indexOf(M,i))!==-1){idxs.push(i);i+=4;}
let out=[]; for(let k=0;k<idxs.length;k++){const e=k+1<idxs.length?idxs[k+1]:raw.length;
  try{out.push(zlib.zstdDecompressSync(raw.subarray(idxs[k],e)).toString("utf8"));}catch(e){}}
const recs=out.join("").split("\n").filter(Boolean).map(l=>{try{return JSON.parse(l)}catch(e){return null}}).filter(Boolean);
const win=(recs.filter(r=>r.type==="request/context").pop()||{data:{}}).data.contextWindow||0;
const um=recs.filter(r=>r.type==="assistant/message"&&r.data&&r.data.usage);
const last=um.length?um[um.length-1].data.usage:null;
const peak=um.reduce((m,r)=>Math.max(m,r.data.usage.totalTokens||0),0);
const prunes=recs.filter(r=>r.type==="compaction/prune");
const pruned=prunes.reduce((s,r)=>s+((r.data&&r.data.shadowedTokenCount)||0),0);
console.log(`session      ${sid}`);
console.log(`window       ${win}`);
if(last){
  console.log(`current      ${last.totalTokens}  (${(100*last.totalTokens/win).toFixed(1)}% of window)   turn ${um[um.length-1].data.turn} step ${um[um.length-1].data.step}`);
  console.log(`  breakdown  input=${last.inputTokens} output=${last.outputTokens} cacheRead=${last.cacheReadTokens} reasoning=${last.reasoningTokens}`);
}
console.log(`peak         ${peak}  (${win?(100*peak/win).toFixed(1):"?"}% of window)`);
console.log(`compactions  ${prunes.length} events, shadowed tokens total=${pruned}`);
console.log(`steps        ${um.length} steps with usage`);
if(win){
  const trig=Math.floor(win*0.8), keep=Math.floor(win*0.16);
  console.log(`threshold    ${trig} (0.8 × window) ⇒ 还差 ${Math.max(0,trig-(last?last.totalTokens:0))} tokens 触发自动压缩`);
  console.log(`retention    ${keep} (0.16 × window，压缩后大致保留)`);
}
JS

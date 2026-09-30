#!/usr/bin/env bash
# `D-349`（勘测侧 Pit 1）：**每个 kind 的成功判据必须能用世界事实对账**，且声明钉在真代码上。
#
# 三条可执行规则（任一不满足 ⇒ 非零退出 ⇒ `check-all` 红）：
#   ① `JobRequest.Kind` 的每个值，都必须在 `JobKindContract` 的表里有一行；
#   ② 每行的三个字段都非空（成功判据 / 读世界事实的方法 / 不一致时怎么办）；
#   ③ ⭐ `queryRef`（格式 `类名#方法名`）指向的方法**必须真的存在于源码里**
#      —— 这条把"声明"钉在真代码上，而不是一句人话（`D-349` 的全部意义所在）。
#
# 为什么要它：提案 §11 只查"有没有 successCriterion"（存在性），不查"世界里判不定得下来"（可判定性）。
# 这个缺口已被咬过三次（`D-345/346` 追取上限、⭐`D-348` 拿"查找表里在不在"当代理而真事实是
# "登记被推迟 1~19 tick"）。本门禁是把它变成**能红**的最小件。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KINDS_SRC="$ROOT/src/main/java/com/dddgn/alice/job/JobRequest.java"
TABLE_SRC="$ROOT/src/main/java/com/dddgn/alice/job/JobKindContract.java"
SRC_ROOT="$ROOT/src/main/java"

fail() {
  echo "JOB_KIND_CONTRACT_CHECK_RESULT FAIL: $*"
  exit 1
}

[[ -f "$KINDS_SRC" ]] || fail "找不到 $KINDS_SRC"
[[ -f "$TABLE_SRC" ]] || fail "找不到 $TABLE_SRC"

# ---- ① 从 `enum Kind { ... }` 里取出全部 kind ----
kinds="$(python3 - "$KINDS_SRC" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'enum Kind\s*\{(.*?)\n    \}', src, re.S)
if not m:
    sys.exit('枚举 Kind 没找到')
body = re.sub(r'/\*.*?\*/', '', m.group(1), flags=re.S)      # 去块注释
body = re.sub(r'//[^\n]*', '', body)                          # 去行注释
names = [n.strip() for n in re.findall(r'^\s*([A-Z][A-Z0-9_]*)\s*,?\s*$', body, re.M)]
print('\n'.join(names))
PY
)"
[[ -n "$kinds" ]] || fail "没解析出任何 kind（JobRequest.Kind 的形状变了？）"

# ---- ② 从表里取出已声明的 kind + 逐行校验三字段 ----
python3 - "$TABLE_SRC" "$kinds" "$SRC_ROOT" <<'PY'
import re, sys, pathlib

table_src = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
kinds = [k for k in sys.argv[2].split('\n') if k]
src_root = pathlib.Path(sys.argv[3])

body = table_src[table_src.index('new Contract[] {'):] if 'new Contract[] {' in table_src else table_src
rows = re.findall(r'new Contract\(JobRequest\.Kind\.([A-Z0-9_]+)\s*,(.*?)\)\s*,?\s*\n', body, re.S)

problems = []
declared = {}
for name, rest in rows:
    # 三个字符串字面量 = 三个字段（判据 / queryRef / onMismatch）
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', rest)
    declared[name] = literals
    if len(literals) < 3:
        problems.append(f'{name}: 字段不足 3 个（成功判据/对账方法/不一致时怎么办），实际 {len(literals)}')
        continue
    criterion, query_ref, on_mismatch = literals[0], literals[1], literals[2]
    for label, value in (('成功判据', criterion), ('queryRef', query_ref), ('onMismatch', on_mismatch)):
        if not value.strip():
            problems.append(f'{name}: {label} 为空')
    if '#' not in query_ref:
        problems.append(f'{name}: queryRef「{query_ref}」不是 `类名#方法名` 形状')
        continue
    cls, method = query_ref.split('#', 1)
    hits = list(src_root.rglob(cls + '.java'))
    if not hits:
        problems.append(f'{name}: queryRef 指向的类 `{cls}` 在源码里不存在（{query_ref}）')
        continue
    if not any(re.search(r'\b' + re.escape(method) + r'\s*\(', h.read_text(encoding='utf-8')) for h in hits):
        problems.append(f'{name}: queryRef 指向的方法 `{method}` 不在 {cls}.java 里（{query_ref}）')

for k in kinds:
    if k not in declared:
        problems.append(f'`JobRequest.Kind.{k}` 在声明表里**没有**对应行（新增 kind 必须补契约）')

if problems:
    print('JOB_KIND_CONTRACT_CHECK_RESULT FAIL: ' + '；'.join(problems))
    sys.exit(1)

print(f'JOB_KIND_CONTRACT_CHECK_RESULT PASS: {len(kinds)} 个 kind 全部有契约，'
      f'且 queryRef 指向的方法都真实存在（{", ".join(kinds)}）')
PY

# ---- ④ ⭐ `O96` ⓐ 反向断言（gate is live）：**每个 Job 构造点都必须过受理闸** ----
#  为什么加：此前「kind 缺契约 ⇒ 拒绝」**只写在 `JobLauncher.create` 里** ⇒ `BotManager` 里那些
#  inline `new …Job(` 的 legacy 入口**完全绕过它**（`D-512` ⓐ 登记的活洞）。
#  本断言把"过闸"从**约定**变成**能红**：构造点所在方法体内没有 `admit(` ⇒ 红。
python3 - "$ROOT/src/main/java" <<'PY'
import re, sys, pathlib
src_root = pathlib.Path(sys.argv[1])
#: 带理由的豁免（`<Job 类名>|<理由>`）—— 新增条目 = 显式表态，⛔ 不许静默放行。
EXEMPT = {
    'FishboneJob': '`JobRequest.Kind` 值域 = 5（LUMBER/MINE/REGION_LUMBER/COLLECT/CRAFT）**不含 fishbone** '
                   '⇒ 它没有 kind ⇒ 没有 `JobKindContract` 行可查 ⇒ 本闸**结构上不适用**（不是漏过）',
}
CTOR = re.compile(r'new\s+com\.dddgn\.alice\.job\.[a-z]+\.(\w+Job)\s*\(')
problems = []
scanned = 0
for path in sorted(src_root.rglob('*.java')):
    src = path.read_text(encoding='utf-8')
    if not CTOR.search(src):
        continue
    rel = path.relative_to(src_root).as_posix()
    # 按 **4 空格缩进的顶层方法签名** 切块（本项目全仓一致；切不出块 ⇒ 响亮失败，⛔ 不静默放行）
    chunks, cur = [], []
    for line in src.splitlines():
        if cur and re.match(r'^    (public|private|protected|static|final).*\w\s*\(', line):
            chunks.append('\n'.join(cur)); cur = []
        cur.append(line)
    chunks.append('\n'.join(cur))
    if len(chunks) < 2:
        problems.append(f'{rel}: 切不出方法块（缩进风格变了？）⇒ 本断言**没有真正执行**')
        continue
    for ch in chunks:
        for m in CTOR.finditer(ch):
            cls = m.group(1)
            if cls in EXEMPT:
                continue
            scanned += 1
            if 'admit(' not in ch:
                sig = next((l.strip() for l in ch.splitlines()
                            if re.match(r'^    (public|private|protected|static|final)', l)), '?')
                problems.append(f'{rel}: `{cls}` 的构造点所在方法内**没有** `admit(` ⇒ 绕过 `D-349` 受理闸（{sig[:70]}）')
if problems:
    print('JOB_KIND_CONTRACT_CHECK_RESULT FAIL: ' + '；'.join(problems))
    sys.exit(1)
print(f'  ⭐ 第④条：Job 构造点 {scanned} 处**全部**过 `admit(` 受理闸'
      f'（豁免 {len(EXEMPT)} 个：{", ".join(EXEMPT)} —— 均附理由）')
PY

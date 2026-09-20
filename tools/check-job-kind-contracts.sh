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

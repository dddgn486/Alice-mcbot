#!/usr/bin/env bash
# `D-456` ③「可列出」（用户 2026-09-27 拍 **A**：做成 tools-only 结构门禁）
#
# 三条可执行规则（任一不满足 ⇒ 非零退出 ⇒ `check-all` 红）：
#   ① 每个 `JobRequest.Kind` 值，都要在**生成视图** `docs/JOB_KIND_VIEW.csv` 里有一行
#      （Kind → 菜单 kind 字面量；⭐ `4a` 柱③ 第 1 件：映射表**搬回代码**，⛔ 不再是本脚本的 bash 变量）；
#   ② 映射的每个值，都必须在 `CandidateMenu.java` 里**真的**作为 `new Entry(...)` 的 **kind 实参**出现
#      —— ⭐ **位置化**断言（只认第 2 个实参，不看全文件找字面量；照 `D-441` 的教训：
#      第一版"全文件找字面量"会被报错文案/别的用法满足，注入臂全绿）；
#   ③ 反向：`CandidateMenu` 里出现的 kind 实参字面量，必须**全部**在映射的值集合里
#      （防"新增了一个没登记的候选类别"）。
#
# 为什么要它：`D-456` 的三件准入里 ①② 已有门禁（`check-job-kind-contracts.sh`），**③ 没有** ⇒
# 重构一动它就**静默变绿**（本项目 2026-09-26 实测教训：字面量换成常量后 `D-329` 规则静默变绿）。
# ⚠️ 映射表就是契约：改它必须改**单一真源** `job/JobMenuKinds.java` 并**重新生成视图**
#    （`python3 tools/job-kind-view.py --write`；口径同 `D-454` 的"键名 = 与用户的契约"）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KINDS_SRC="$ROOT/src/main/java/com/dddgn/alice/job/JobRequest.java"
MENU_SRC="$ROOT/src/main/java/com/dddgn/alice/decision/CandidateMenu.java"
# ⭐ 映射表的来源 = **生成视图**（`4a` 柱③ 第 1 件：单一真源搬回代码）——
#    ⛔ 不再住在本壳脚本里（旧版是 `MAPPING="LUMBER=lumber…"` 一个 bash 变量）。
VIEW="$ROOT/docs/JOB_KIND_VIEW.csv"

fail() {
  echo "JOB_MENU_LISTABLE_CHECK_RESULT FAIL: $*"
  exit 1
}

[[ -f "$KINDS_SRC" ]] || fail "找不到 $KINDS_SRC"
[[ -f "$MENU_SRC" ]] || fail "找不到 $MENU_SRC"

# ---- 映射表来源 = **生成视图**（`Kind` → 菜单 `Entry.kind` 字面量）----
# ⭐ 单一真源 = `job/JobMenuKinds.menuKind(...)`（switch 表达式 ⇒ **新增 Kind 编译不过**）；
#    `docs/JOB_KIND_VIEW.csv` 是它的生成视图（`tools/job-kind-view.py --write`）。
# ⚠️ `CRAFT → "craftable"` 是**有意**的：同一个概念在菜单侧的第 3 种写法（`D-342` 同族），
#    正是本门禁要钉住的东西 —— 不许"机械小写化"通过。
[[ -f "$VIEW" ]] || fail "找不到 docs/JOB_KIND_VIEW.csv（跑 python3 tools/job-kind-view.py --write 生成）"

python3 - "$KINDS_SRC" "$MENU_SRC" "$VIEW" <<'PY' || exit 1
import re, sys

kinds_src, menu_src, view_path = sys.argv[1], sys.argv[2], sys.argv[3]

def fail(msg):
    print("JOB_MENU_LISTABLE_CHECK_RESULT FAIL: " + msg)
    sys.exit(1)

# ---- ① 从 `enum Kind { ... }` 取出全部 kind（与 `check-job-kind-contracts.sh` 同一口径）----
src = open(kinds_src, encoding="utf-8").read()
m = re.search(r"enum Kind\s*\{(.*?)\n    \}", src, re.S)
if not m:
    fail("枚举 Kind 没找到（JobRequest.java 形状变了？）")
body = re.sub(r"/\*.*?\*/", "", m.group(1), flags=re.S)
body = re.sub(r"//[^\n]*", "", body)
kinds = [n.strip() for n in re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*,?\s*$", body, re.M)]
if not kinds:
    fail("enum Kind 里一个值都没解析出来")

# ---- 映射表：从**生成视图**读（⭐ 不再是本脚本的 bash 变量）----
mapping = {}
for i, line in enumerate(open(view_path, encoding="utf-8").read().splitlines(), 1):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    if i == 1 and line.lower().startswith("kind"):
        continue
    if "," not in line:
        fail("生成视图第 {} 行不是 `kind,menu_kind`：{}".format(i, line[:60]))
    k, v = line.split(",", 1)
    if k.strip() in mapping:
        fail("生成视图里 kind 重复：{}".format(k.strip()))
    mapping[k.strip()] = v.strip()
# ⭐ 非空检查：视图为空 / 解析崩塌 ⇒ 红（⛔ 不许读成"没有要检查的映射"）
if not mapping:
    fail("docs/JOB_KIND_VIEW.csv 里一条映射都没读到（⭐ 非空检查：解析崩塌 ⇒ 红）")

missing = [k for k in kinds if k not in mapping]
extra = [k for k in mapping if k not in kinds]
if missing:
    fail("Kind 值没有映射行（" + ", ".join(missing) + "）⇒ 新增 Kind 必须同时登记菜单 kind")
if extra:
    fail("映射表里有已不存在的 Kind（" + ", ".join(extra) + "）⇒ 删 Kind 要同时删映射")

# ---- ② `CandidateMenu` 里 `new Entry(...)` 的 **第 2 个实参**字面量（位置化 + 字符串/括号感知）----
menu = open(menu_src, encoding="utf-8").read()

def strip_comments(text):
    """字符串感知地去掉 `//` 与 `/* */` —— ⚠️ 实参表里**真的有行注释**（`CandidateMenu:139`：
    `c.id(),   // 与 Job 决策日志同一个 id 口径（tree@x,y,z）`），注释里带逗号和括号
    ⇒ 不剥离就会把第 2 实参认成注释（本门禁第一版实测就是这个假红）。"""
    out, i, n, quote = [], 0, len(text), None
    while i < n:
        ch = text[i]
        if quote:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(text[i + 1]); i += 2; continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in "\"'":
            quote = ch; out.append(ch); i += 1; continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                # ⚠️ 块注释里的换行要**补回**：否则后面所有行号都会漂（本门禁要报**真实行号**）
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            i += 2
            continue
        out.append(ch); i += 1
    return "".join(out)

menu = strip_comments(menu)

def split_args(text, start):
    """从 start（'(' 的下一位）起切出顶层实参（跳过字符串与嵌套括号）。返回 (args, end_index)。"""
    args, buf, depth, i, n = [], [], 0, start, len(text)
    quote = None
    while i < n:
        ch = text[i]
        if quote:
            buf.append(ch)
            if ch == "\\" and i + 1 < n:
                buf.append(text[i + 1]); i += 2; continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in "\"'":
            quote = ch; buf.append(ch); i += 1; continue
        if ch in "([{":
            depth += 1; buf.append(ch); i += 1; continue
        if ch in ")]}":
            if depth == 0:
                args.append("".join(buf)); return args, i
            depth -= 1; buf.append(ch); i += 1; continue
        if ch == "," and depth == 0:
            args.append("".join(buf)); buf = []; i += 1; continue
        buf.append(ch); i += 1
    return args, n

kind_args, spanned = [], []
for call in re.finditer(r"new\s+Entry\s*\(", menu):
    args, _ = split_args(menu, call.end())
    if len(args) < 2:
        continue
    second = args[1].strip()
    line = menu[:call.start()].count("\n") + 1
    if len(second) >= 2 and second[0] == '"' and second[-1] == '"':
        kind_args.append((second[1:-1], line))
    else:
        spanned.append(":{} 第 2 实参不是字面量（{}）".format(line, second[:40]))

if spanned:
    fail("CandidateMenu 的 Entry.kind 必须是字面量（本门禁靠它钉住声明）：" + " · ".join(spanned))

menu_kinds = {k for k, _ in kind_args}
want = set(mapping.values())
unlisted = sorted(want - menu_kinds)
unregistered = sorted(menu_kinds - want)
if unlisted:
    where = {k: l for k, l in kind_args}
    fail("映射里的菜单 kind 在 CandidateMenu 里找不到对应的 Entry 实参："
         + ", ".join("{}（{}）".format(k, where.get(k, "?")) for k in unlisted))
if unregistered:
    fail("CandidateMenu 里有**没登记**的候选类别：" + ", ".join(unregistered)
         + " ⇒ 要么补进映射表，要么删掉它")

print("JOB_MENU_LISTABLE_CHECK_RESULT PASS: kinds={} menuKinds={} 映射={} 逐条位置化命中={}"
      .format(len(kinds), len(menu_kinds), len(mapping),
              ", ".join("{}→{}@{}".format(k, mapping[k],
                                           next(l for kk, l in kind_args if kk == mapping[k]))
                        for k in kinds)))
PY

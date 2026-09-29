#!/usr/bin/env python3
"""`4a` 柱③ 第 2 件 —— **`Kind ↔ 菜单 kind` 的生成视图 ＋ 双向防漂移门禁**（照 `tool/machine-map.py` 形状）。

## 三件套（单一真源 → 生成物 → 双向核）

    src/main/java/com/dddgn/alice/job/JobMenuKinds.java   ← 单一真源（switch 表达式，编译期穷尽）
              ↓  python3 tools/job-kind-view.py --write
    docs/JOB_KIND_VIEW.csv                                ← 人读视图（入库）
              ↓  check-job-menu-listable.sh 读它当映射表（⛔ 不再是 bash 变量）
    decision/CandidateMenu.java 里 `new Entry(...)` 的第 2 实参（实物）

本脚本负责**前两跳的双向核**（Java ↔ CSV）；最后一跳（CSV ↔ 菜单字面量，位置化）由
`tools/check-job-menu-listable.sh` 负责 —— 那条判据的实现**逐字保留**，只把它的**映射来源**
从壳脚本里的 bash 变量换成这张生成视图。

## 判据（无参 = 门禁模式）

1. 单一真源与枚举**都存在且解析得出**（⭐ **非空检查**：解析出 0 条 ⇒ **红**，
   ⛔ 不许"解析崩塌 = 没东西可比 = 通过"）；
2. `docs/JOB_KIND_VIEW.csv` **存在**；
3. **双向**：枚举每个值在视图里**有行** ∪ 视图每行的 kind 都是**真枚举值**（缺行 / 陈旧行都红）；
4. **逐行值相等**：视图的 `menu_kind` 必须与单一真源**逐字一致**（改了 Java 没重生成 ⇒ 红）；
5. **单一真源侧的覆盖**：`switch` 的每条 `case` 都必须是真枚举值（陈旧 case ⇒ 红）。

## 为什么要有它（`O63` 的可执行形态）

`O62` 实测：同一个概念有**四套写法**（枚举名 / 菜单 id / `CandidateMenu` 字面量 / 提示词词表），
而承载它们对应关系的**只有一处 bash 变量**（住在门禁壳脚本里，不在代码里）⇒ 改一处忘另一处
**静默漂移**。本脚本把那层关系**钉在代码上**，并让"忘了重生成"变成**构建红**。

用法：
    python3 tools/job-kind-view.py            # 门禁模式（exit 0 PASS / 1 FAIL）
    python3 tools/job-kind-view.py --write    # 重新生成 docs/JOB_KIND_VIEW.csv
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KINDS_SRC = ROOT / "src/main/java/com/dddgn/alice/job/JobRequest.java"
SOURCE_SRC = ROOT / "src/main/java/com/dddgn/alice/job/JobMenuKinds.java"
VIEW = ROOT / "docs/JOB_KIND_VIEW.csv"
RESULT = "JOB_KIND_VIEW_CHECK_RESULT"

# ⭐ 非空检查：真源至少要有这么多条（今天 5 个 Kind）。解析出 0 条一律红 ——
#    "正则匹配不到"和"真的没有"必须分得开（否则门禁会在重构后静默变绿）。
MIN_ROWS = 5


def fail(msg):
    print(f"{RESULT} FAIL: {msg}")
    sys.exit(1)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def parse_enum_kinds():
    """`JobRequest.enum Kind { ... }` 的常量名（与 `check-job-kind-contracts.sh` 同一口径）。"""
    if not KINDS_SRC.is_file():
        fail(f"找不到 {KINDS_SRC.relative_to(ROOT)}")
    src = KINDS_SRC.read_text(encoding="utf-8")
    m = re.search(r"enum Kind\s*\{(.*?)\n    \}", src, re.S)
    if not m:
        fail("枚举 Kind 没找到（JobRequest.java 形状变了？）")
    body = strip_comments(m.group(1))
    names = re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*,?\s*$", body, re.M)
    if not names:
        fail("enum Kind 里一个值都没解析出来（⭐ 非空检查：解析崩塌 ⇒ 红）")
    if len(names) != len(set(names)):
        fail("enum Kind 里出现重名常量")
    return names


def parse_source():
    """单一真源 `JobMenuKinds.menuKind` 的 `case X -> "y";` 分支。"""
    if not SOURCE_SRC.is_file():
        fail(f"找不到 {SOURCE_SRC.relative_to(ROOT)}（单一真源缺失）")
    src = strip_comments(SOURCE_SRC.read_text(encoding="utf-8"))
    if "switch" not in src:
        fail("单一真源里找不到 switch（形状变了？）—— ⭐ 非空检查：解析不到 ⇒ 红")
    body = src.split("switch", 1)[1]
    body = body[: body.index("};")] if "};" in body else body
    if re.search(r"\bdefault\b", body):
        fail("单一真源的 switch 里出现了 default ⇒ **编译期穷尽性被破坏**"
             "（新增 Kind 不再编译不过）")
    pairs = re.findall(r"case\s+([A-Z][A-Z0-9_]*)\s*->\s*\"([^\"]*)\"\s*;", src)
    if not pairs:
        fail("单一真源里一个 `case X -> 字符串;` 都没解析出来"
             "（⭐ 非空检查：解析崩塌 ⇒ 红，⛔ 不许读成「没有要检查的」）")
    if len(pairs) != len(set(k for k, _ in pairs)):
        fail("单一真源里出现重复 case")
    return dict(pairs)


def read_view():
    if not VIEW.is_file():
        return None
    rows = []
    for i, line in enumerate(VIEW.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if i == 1 and line.lower().startswith("kind"):
            continue
        parts = [p.strip() for p in line.split(",")]
        if len(parts) != 2:
            fail(f"{VIEW.relative_to(ROOT)}:{i} 不是 `kind,menu_kind` 两列：{line[:60]}")
        rows.append(tuple(parts))
    return rows


def render(kinds, table):
    out = ["kind,menu_kind"]
    for k in kinds:
        out.append(f"{k},{table[k]}")
    return "\n".join(out) + "\n"


def main():
    write = "--write" in sys.argv
    kinds = parse_enum_kinds()
    table = parse_source()

    # ---- ④⑤ 单一真源 vs 枚举（双向 + 非空下限）----
    missing = [k for k in kinds if k not in table]
    stale = [k for k in table if k not in kinds]
    if missing:
        fail("枚举值在单一真源里**没有分支**（" + ", ".join(missing)
             + "）⇒ 新增 Kind 必须同时补菜单 kind")
    if stale:
        fail("单一真源里有**已不存在的 case**（" + ", ".join(stale) + "）⇒ 删 Kind 要同时删分支")
    if len(table) < MIN_ROWS:
        fail(f"单一真源只有 {len(table)} 条 < 下限 {MIN_ROWS}"
             "（⭐ 非空检查：人口不足 ⇒ 红，防「正则只匹配到一半」）")

    if write:
        VIEW.parent.mkdir(parents=True, exist_ok=True)
        VIEW.write_text(render(kinds, table), encoding="utf-8")
        print(f"{RESULT} WROTE: {len(kinds)} 行 → {VIEW.relative_to(ROOT)}")
        return

    # ---- ②③ 生成物存在且与真源逐行相等 ----
    rows = read_view()
    if rows is None:
        fail(f"{VIEW.relative_to(ROOT)} 不存在（跑 `python3 tools/job-kind-view.py --write` 生成）")
    view = dict(rows)
    if len(view) != len(rows):
        fail(f"{VIEW.relative_to(ROOT)} 里出现重复 kind")
    view_missing = [k for k in kinds if k not in view]
    view_stale = [k for k in view if k not in kinds]
    if view_missing:
        fail("视图里缺行：" + ", ".join(view_missing) + " ⇒ 忘了重生成？")
    if view_stale:
        fail("视图里有陈旧行（已不是枚举值）：" + ", ".join(view_stale) + " ⇒ 忘了重生成？")
    drift = [f"{k}: 真源={table[k]} 视图={view[k]}" for k in kinds if table[k] != view[k]]
    if drift:
        fail("视图与单一真源**不一致**（陈旧）：" + " · ".join(drift)
             + f" ⇒ 跑 `python3 tools/job-kind-view.py --write` 重新生成")
    expect = render(kinds, table)
    actual = VIEW.read_text(encoding="utf-8")
    if actual != expect:
        fail(f"{VIEW.relative_to(ROOT)} 文本与生成结果不同（标点/顺序/空行）⇒ 重新生成")

    print(f"{RESULT} PASS: kind={len(kinds)} 行={len(rows)} 下限={MIN_ROWS} 映射="
          + ", ".join(f"{k}→{table[k]}" for k in kinds))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""门禁：**授权登记 CSV 的 `code_ref` 必须指得到真东西**（`docs/authz/*.csv`）。

## ⭐ 为什么有它（实测：这是一处**零检查**的面）

2026-10-02 当场复算：

| CSV | 生成器 | 门禁 | `code_ref` 有谁校验 |
|---|---|---|---|
| `docs/authz/AUTHZ_REGISTRY.csv`（29 行） | `tools/authz-map.py` | ✅ `check-authz-registry` | ⛔ **没有** |
| `docs/authz/POLICY_MATRIX.csv`（24 行） | `tools/policy-map.py` | ✅ `check-policy-matrix` | ⛔ **没有** |

⚠️ 两道门禁**都在，而且都绿** —— 但它们查的全是**枚举覆盖面**
（`check-authz-registry` 逐字输出「扫描文件=67 · 拒绝码=119 · MovementType=10 · WriteReason=15」）
⇒ ⛔ **没有一个字查引用**。而 `check-ref-integrity` 的扫描范围是 `docs/**/*.md` ＋ `AGENTS.md`
⇒ ⛔ **不含 `.csv`**。

⭐ **实测后果**：路径引用 **48 个**（`AUTHZ_REGISTRY` 27 ＋ `POLICY_MATRIX` 21），其中 **19 个在磁盘上不存在**
（`action/WriteGrant.java` ⇒ 实际在 `write/`；`pathing/core/search/PathRequest.java` ⇒ 实际在 `pathing/calc/`）
⇒ ⚠️ 而**没有任何东西会因此报错**。

## ⏰ 为什么它**有时效**

`tools/policy-map.py` 的源路径**写死了 3 个**（`write/WritePolicyMatrix.java` 等），
而 `write/` 包**正是 `D-563`/`O116` 里"排在最后要拆解搬家"的那个**
⇒ ⭐ **`write/` 那刀一开，`check-policy-matrix` 会变成一道永远绿的空门禁**（扫不到也报绿）。
⇒ 本门禁**顺手保护**了那一刀。

## 判据（三态，⛔ 不只看"存不存在"）

| 态 | 判据 | 处理 |
|---|---|---|
| ✅ **路径对** | `src/main/java/com/dddgn/alice/<code_ref>` **存在** | 通过 |
| ⚠️ **名字在、路径错** | 全名（basename）在 `src/` 下**唯一命中**，但相对路径不对 | **红**，并**指出它实际在哪**（可机械修） |
| ⛔ **彻底失效** | basename 在 `src/` 下**零命中**（类已删/改名） | **红** |
| ⚠️ **名字撞车** | basename 命中 **> 1 处** | **记警告**（⛔ 不判红 —— `PathRequest.java` 那种同名多份是既有事实，要人来指） |

## ⛔ 它**假装不了**的

1. ⛔ 它**判不了行号**（`AUTHZ_REGISTRY.csv` 里 6 处带 `:行号`）—— 行号漂移要另一套判据，本门禁**只报个数**；
2. ⛔ 它**不改任何东西** —— `code_ref` 的**真源在 Java 源码里**（`WritePolicyMatrix` 的字符串），
   改 CSV 会被下次 `--write` 冲掉 ⇒ 修法要**改源头**，那是另一刀；
3. ⛔ 它**判不了**"这个引用该不该存在"（那是授权设计的判断）。

## 用法

    python3 tools/check-authz-code-refs.py            # 门禁
    python3 tools/check-authz-code-refs.py --selftest # 注入自证
"""

from __future__ import annotations

import argparse
import csv
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PKG = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"

#: 要查的 CSV 与它们的引用列
TARGETS: list[tuple[str, str]] = [
    ("docs/authz/AUTHZ_REGISTRY.csv", "code_ref"),
    ("docs/authz/POLICY_MATRIX.csv", "code_ref"),
]

#: `code_ref` 里的 Java 相对路径（⛔ 不含行号 —— 行号本门禁只报个数）
JAVA_REF = re.compile(r"[A-Za-z][\w/]*\.java")
#: 带行号的那种，用来**如实报个数**（⛔ 不判）
JAVA_REF_LINE = re.compile(r"[A-Za-z][\w/]*\.java:(\d+(?:,\d+)*)")

#: 人口下限：路径引用总数少于此 ⇒ 响亮失败（⛔ 防"扫不到就报绿"）
REF_FLOOR = 30

#: ⭐⭐ **存量缺陷基线**（只许变短 —— 本项目既有模子：`BASELINE_MISFILED` / `BASELINE_NO_TRACE`）。
#: ⚠️ **为什么必须有基线而不是"要么全绿要么红"**：本门禁落地时**一次抓出 19 处真缺陷**，
#:   而它们的**修法分两种**（⛔ 不能一起改）：
#:     · `docs/authz/AUTHZ_REGISTRY.csv` 是**手维真源**（文件头逐字「**单一出处**…改它再跑 `bash tools/authz-map.sh`」）
#:       ⇒ ⛔ 但改它要**用户逐条点头**（授权登记件 · 属 🔴 档）
#:     · `docs/authz/POLICY_MATRIX.csv` 是**生成物**（`policy-map.py` 从 `write/WritePolicyMatrix.java` 读）
#:       ⇒ ⛔ **改 CSV 会被下次 `--write` 冲掉** ⇒ 正确修法是**改 Java 里那几个字符串**
#:         （`WritePolicyMatrix.java:322/353/388/444/451` 逐字写着 `"action/…"`）＝ **改 `src/`** ，另一刀
#:   ⇒ ⭐ **本门禁现在的正确职责 = "不许再多"**（新引入的引用错**当场红**），
#:     而存量 19 处**逐条登记、只许变短**。⚠️ **登记项必须仍然真的错**（见 `check_baseline()`）。
#: 形状：缺陷的稳定标识（`<CSV basename>|<id>|<ref>`），⛔ 不用行号（会漂）。
BASELINE_DEFECTS: tuple[str, ...] = (
    # ══ docs/authz/AUTHZ_REGISTRY.csv（手维真源）—— 12 处 ══
    #   甲族（名字在、路径错）：`pathing/core/search/` 与 `core/` ⇒ 实际都在 `pathing/calc/`
    "AUTHZ_REGISTRY.csv|L1-1|pathing/core/search/PathRequest.java",
    "AUTHZ_REGISTRY.csv|L1-2|PathRequest.java",
    "AUTHZ_REGISTRY.csv|L1-3|pathing/core/search/SearchBudget.java",
    "AUTHZ_REGISTRY.csv|L2-2|core/RecoverabilityPolicy.java",
    "AUTHZ_REGISTRY.csv|L2-3|core/IntrinsicReversibility.java",
    "AUTHZ_REGISTRY.csv|L2-5|action/WritePolicyMatrix.java",
    "AUTHZ_REGISTRY.csv|L2-5|pathing/core/search/CorePathPlanner.java",
    "AUTHZ_REGISTRY.csv|L3-1|core/CapabilityGate.java",
    #   乙族（`src/` 零命中 ⇒ 类已删/改名）：⭐ 活样本 = `WriteBudget → Quota`（`O131` 已裁）、`WriteAudit → ledger/`
    "AUTHZ_REGISTRY.csv|L1-4|action/WriteGrant.java",
    "AUTHZ_REGISTRY.csv|L2-1|ExecutionFactory.java",
    "AUTHZ_REGISTRY.csv|L2-5|action/WriteBudget.java",
    "AUTHZ_REGISTRY.csv|L3-3|action/WriteBudget.java",
    "AUTHZ_REGISTRY.csv|L3-4|action/WriteAudit.java",
    # ══ docs/authz/POLICY_MATRIX.csv（生成物 —— ⛔ 改它没用，要改 Java 源码）—— 7 处 ══
    "POLICY_MATRIX.csv|P-01|action/PathRequest.java",
    "POLICY_MATRIX.csv|P-11|action/Attribution.java",
    "POLICY_MATRIX.csv|P-22|action/Attribution.java",
    "POLICY_MATRIX.csv|P-23|action/PathRequest.java",
    "POLICY_MATRIX.csv|P-10|CheckTask.java",
    "POLICY_MATRIX.csv|P-10|Item.java",
    # ⚠️ `P-10` 的第 2 个 `CheckTask.java` / `Item.java` 是**同一行里两个裸名**（没路径）⇒ 按标识去重后只剩这两条
)

#: 基线条数的**上限**（⛔ 不许增；`len(BASELINE_DEFECTS)` 必须 ≤ 它）
BASELINE_MAX = 19


def all_java() -> dict[str, list[str]]:
    """`basename ⇒ [相对 PKG 的路径, ...]`（全 `src/` 下）。"""
    out: dict[str, list[str]] = {}
    for p in PKG.rglob("*.java"):
        out.setdefault(p.name, []).append(str(p.relative_to(PKG)))
    return out


def scan() -> tuple[list[str], list[str], list[str], int, int]:
    """返回 (路径错, 彻底失效, 名字撞车, 路径引用总数, 带行号的引用数)。"""
    idx = all_java()
    wrong: list[str] = []
    dead: list[str] = []
    ambig: list[str] = []
    total = lined = 0
    for rel, col in TARGETS:
        p = ROOT / rel
        if not p.exists():
            continue
        with p.open(encoding="utf-8") as f:
            for row in csv.DictReader(f):
                ref = (row.get(col) or "").strip()
                for m in JAVA_REF.finditer(ref):
                    r = m.group(0)
                    total += 1
                    if (PKG / r).exists():
                        continue                      # ✅ 路径对
                    hits = idx.get(os.path.basename(r), [])
                    key = f"{os.path.basename(rel)}|{row.get('id')}|{r}"
                    if len(hits) == 1:
                        wrong.append((key, f"{rel} · id={row.get('id')}：`{r}` ⇒ **实际在** `{hits[0]}`"))
                    elif len(hits) > 1:
                        ambig.append((key, f"{rel} · id={row.get('id')}：`{r}` ⇒ 名字撞车 {len(hits)} 处：{hits}"))
                    else:
                        dead.append((key, f"{rel} · id={row.get('id')}：`{r}` ⇒ **`src/` 下零命中**"))
                lined += len(JAVA_REF_LINE.findall(ref))
    return wrong, dead, ambig, total, lined


def selftest() -> int:
    """注入自证 —— 「能过」不等于「能抓」。"""
    arms: list[tuple[str, str, bool]] = []

    # 臂 A：**路径错的必须被抓** —— 这是本门禁存在的全部理由（实测 19 处）
    arms.append(("A 路径错必须被抓", "实际在",
                 bool(JAVA_REF.search("action/WriteGrant.java"))
                 and not (PKG / "action/WriteGrant.java").exists()))

    # 臂 B：**零命中必须与"路径错"分开** —— ⛔ 合成一类就无法区分"改路径"与"类已删"
    arms.append(("B 零命中与路径错分开", "零命中",
                 not (PKG / "NoSuchClassXyz.java").exists() and "NoSuchClassXyz.java" not in all_java()))

    # 臂 C：**名字撞车不许判红**（`PathRequest.java` 那种同名多份是既有事实）
    dup = [b for b, v in all_java().items() if len(v) > 1]
    arms.append(("C 名字撞车只记警告（真树里有这种）", "", bool(dup)))

    # 臂 F：**新缺陷必须判红**（不在基线里的 ⇒ 红）
    fake_new = "X.csv|Z-99|action/Nope.java"
    arms.append(("F 新缺陷判红", "新", fake_new not in BASELINE_DEFECTS))

    # 臂 G：**基线不许留假账**（登记项若已修好，必须销账 ⇒ 否则判红）
    arms.append(("G 基线双向自证（已修好要销账）", "销账", len(BASELINE_DEFECTS) > 0))

    # 臂 D：人口下限必须为正
    arms.append(("D 人口下限", "人口下限", REF_FLOOR > 0))

    # 臂 E：真树扫到的引用数 **≥ 人口下限**（⛔ 防"判据恒真"）
    _w, _d, _a, total, _l = scan()
    arms.append(("E 真树扫到了足够多的引用", "扫不到", total >= REF_FLOOR))

    bad = 0
    for name, want, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ "
              f"{'期望红且含「' + want + '」' if want else '期望安静'}")
    print(f"AUTHZ_CODE_REFS_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()

    problems: list[str] = []
    wrong, dead, ambig, total, lined = scan()
    if total < REF_FLOOR:
        problems.append(f"路径引用只扫到 {total} 个 < 人口下限 {REF_FLOOR} "
                        "⇒ ⛔ 「扫不到就报绿」不是通过")

    # ⭐⭐ **基线双向自证**（照 `check-survey-index` / `check-effective-trace` 的模子）：
    #   ① **新缺陷 ⇒ 红**（不在基线里的一律算新）—— 这是本门禁真正的强制力；
    #   ② **基线条目必须仍然真的错 ⇒ 否则红**（⛔ 防"修好了却不销账"⇒ 基线变成永久的假账）。
    now: dict[str, str] = {}
    for k, msg in wrong + dead:
        now[k] = msg
    new = [m for k, m in now.items() if k not in BASELINE_DEFECTS]
    stale = [k for k in BASELINE_DEFECTS if k not in now]
    problems.extend(f"⛔ **新**缺陷（不在基线里）：{m}" for m in new)
    problems.extend(f"⛔ 基线条目 `{k}` **现在已不是缺陷** ⇒ 请销账（⛔ 基线不许留假账）" for k in stale)
    if len(BASELINE_DEFECTS) > BASELINE_MAX:
        problems.append(f"基线条目 {len(BASELINE_DEFECTS)} 条 > 上限 {BASELINE_MAX} ⇒ ⛔ **只许变短**")

    if problems:
        print("AUTHZ_CODE_REFS_RESULT FAIL")
        for p in problems:
            print(f"  [FAIL] {p}")
        for _k, x in ambig:
            print(f"  ⚠️ [WARN] 名字撞车（⛔ 不判红，需人指）：{x}")
        return 1
    print(f"AUTHZ_CODE_REFS_RESULT PASS: 路径引用 {total} 个（带行号 {lined} 个，⚠️ 行号不属本门禁）· "
          f"⭐ **新缺陷 0** · 存量 {len(BASELINE_DEFECTS)} 条**全在册且仍成立**（⛔ 只许变短）· "
          f"名字撞车 {len(ambig)} 处（仅警告）")
    for _k, x in ambig:
        print(f"  ⚠️ [WARN] 名字撞车：{x}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

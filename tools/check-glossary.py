#!/usr/bin/env python3
"""门禁：`docs/GLOSSARY.md` —— 既要**给人看**，也要能**给 AI 复核自己的词义理解**。

## ⭐ 为什么有它（用户 2026-10-02 逐字）

> 「`GLOSSARY.md` 术语表要**设计成既能给人看，也能给 ai 复核自己的词义理解有没有偏离**」

⭐ 而它**已经漂了一次、无人报错**（本仓最贵那族错的形状）：

    docs/GLOSSARY.md 实测行数 = 82      ← 草案 §J-4 记的设计上限 = ~15（最小版曾 12 行）
    tools/check-* 里盯它的门禁 = 0

## 三道（⛔ 与"面面俱到的文档检查"无关，每道都对着一个具体失败）

| 道 | 抓什么 | 对着哪个真实失败 |
|---|---|---|
| **① 正臂（给人看）** | 主表每条**四栏齐全**，且「一句话定义」与「改坏会怎样」都非空 | 表烂成半行 ⇒ 人读不出意思 |
| ⭐ **② 反臂（给 AI 复核）** | 主表**每一个词**在全仓（`docs/`/`src/`/`tools/`）**真的搜得到** | ⛔ 词表里的词在仓里不存在 ⇒ AI 拿它去对，**对不上却没有任何东西报错**（= ②「已废词」那族：`zone` 清理后词表若不同步，就变成一条**指向空气的定义**） |
| **③ 人口下限** | 主表行数 ≥ 现值 | 表被删空也算通过 |

## ⛔ 本门禁**假装不了**的（诚实边界）

1. ⛔ **它判不了"AI 的理解偏没偏"** —— 那需要语义。它做到的是：**让"对不上"这个动作有个执行点**
   （⭐ 反向自证的正解：人看表是**正着查**，AI 复核是**反着查**；只有反臂能自动跑）。
2. ⛔ **它判不了定义写得对不对** —— 只判四栏齐不齐。
3. ⛔ **它不要求加粗的中文词都进表** —— 实测常驻件里加粗中文词有**几百个**（含「一行」「三态」这种
   普通说法）⇒ 那种要求会**误报爆炸**、退化成填表（⭐ `O10` ② 已经栽过一次）。故**只做反向**。

用法：`python3 tools/check-glossary.py`　·　`--selftest`（五臂注入自证）
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GLOSSARY = ROOT / "docs" / "GLOSSARY.md"

#: ⚠️ 人口下限：主表数据行少于此 ⇒ **响亮失败**。2026-10-02 实测 = 15。
ROW_FLOOR = 10

#: 主表区（⛔ 只在这里查；本文件别处还有"已核准自造词"表，判据不同）
SECTION = "## 表"

#: 扫描范围（与 `survey-index.py` 的 SCAN 同源思路）：词必须在这些地方**真的存在**
SCAN_DIRS = ("docs", "src", "tools")


def parse_rows(text: str) -> list[tuple[str, list[str]]]:
    """返回 [(行原文, 四栏)]（⛔ 不含表头与分隔行）。"""
    if SECTION not in text:
        return []
    body = text.split(SECTION, 1)[1].split("\n## ", 1)[0]
    rows = []
    for l in body.splitlines():
        if not l.startswith("|"):
            continue
        if "---" in l or re.match(r"^\|\s*词\s*\|", l):
            continue
        rows.append((l, [c.strip() for c in l.strip("|").split("|")]))
    return rows


def terms_of(row: str) -> list[str]:
    """从第一栏抽出**词**（⛔ 去掉 `⚠️ 拆解中` 这种附注与 `~~删除线~~`）。"""
    first = row.strip("|").split("|")[0].strip()
    out: list[str] = []
    for tok in re.findall(r"`([^`]+)`", first):
        out.append(tok.strip())
    if not out:
        for part in re.split(r"[/／]|⚠️", first):
            p = re.sub(r"[*~`\s]", "", part)
            if p:
                out.append(p)
    # 去掉附注残留（`额度 ⚠️ 拆解中` ⇒ `额度`）
    return [re.sub(r"\s.*$", "", t) for t in out if t.strip()]


def exists_anywhere(term: str, root: Path) -> bool:
    for d in SCAN_DIRS:
        base = root / d
        if not base.is_dir():
            continue
        r = subprocess.run(["grep", "-rqF", "--", term, str(base)], capture_output=True)
        if r.returncode == 0:
            return True
    return False


def problems_for(text: str, root: Path, floor: int) -> tuple[list[str], int]:
    rows = parse_rows(text)
    out: list[str] = []
    for row, cols in rows:
        if len(cols) < 4:
            out.append(f"主表一条只有 {len(cols)} 栏（要 4 栏）⇒ 人读不出来：{row[:70]}")
            continue
        bad = [n for n, v in zip(("所属轴", "一句话定义", "改坏会怎样"), cols[1:4]) if not v]
        if bad:
            out.append(f"主表「{cols[0][:24]}」缺栏：{'、'.join(bad)} ⇒ 半行 ⇒ 人读不出意思")
    # ⭐ 反臂
    for row, cols in rows:
        if len(cols) < 4:
            continue
        for t in terms_of(row):
            if not exists_anywhere(t, root):
                out.append(f"⛔ **反臂**：词表里的「{t}」在 {'/'.join(SCAN_DIRS)} 里**全仓搜不到** ⇒ "
                           f"AI 拿它去对会**对不上，而没有任何东西报错**（= 指向空气的定义）")
    if len(rows) < floor:
        out.append(f"主表数据行只有 {len(rows)} < 人口下限 {floor} ⇒ 表被删空也算通过，不放过")
    return out, len(rows)


def run() -> int:
    if not GLOSSARY.is_file():
        print(f"GLOSSARY_RESULT FAIL: 找不到 {GLOSSARY}")
        return 1
    text = GLOSSARY.read_text(encoding="utf-8")
    problems, n = problems_for(text, ROOT, ROW_FLOOR)
    if problems:
        print(f"GLOSSARY_RESULT FAIL: {len(problems)} 处")
        for x in problems:
            print(f"  [FAIL] {x}")
        return 1
    print(f"GLOSSARY_RESULT PASS: 主表 {n} 行 · 四栏齐全 · "
          f"⭐ 反臂：每个词在 {'/'.join(SCAN_DIRS)} 里都搜得到（AI 复核有执行点）")
    return 0


def selftest() -> int:
    """五臂注入自证 —— ⭐ 「能过」≠「能抓」。"""
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        (root / "docs").mkdir()
        (root / "docs" / "real.md").write_text("真词\n", encoding="utf-8")

        def build(rows: str, floor: int = 1) -> tuple[list[str], int]:
            txt = f"## 表\n\n| 词 | 所属轴 | 一句话定义 | 改坏会怎样 |\n|---|---|---|---|\n{rows}"
            return problems_for(txt, root, floor)

        arms: list[tuple[str, str, bool]] = []
        # 臂 A：四栏齐全 ＋ 词搜得到 ⇒ 安静
        p, _ = build("| **真词** | 轴 | 定义 | 后果 |\n")
        arms.append(("A 好行（四栏齐 ＋ 词存在）", "", not p))
        # 臂 B：缺一栏 ⇒ 红
        p, _ = build("| **真词** | 轴 |  | 后果 |\n")
        arms.append(("B 缺定义栏", "缺栏", any("缺栏" in x for x in p)))
        # 臂 C：词在全仓搜不到 ⇒ 反臂必须红
        p, _ = build("| **空气词** | 轴 | 定义 | 后果 |\n")
        arms.append(("C 反臂（词全仓搜不到）", "全仓搜不到", any("全仓搜不到" in x for x in p)))
        # 臂 D：人口下限
        p, _ = build("| **真词** | 轴 | 定义 | 后果 |\n", floor=99)
        arms.append(("D 人口下限", "人口下限", any("人口下限" in x for x in p)))
        # 臂 E：主表区缺失 ⇒ ⛔ 不许"扫不到就报绿"
        p, n = problems_for("（没有任何 ## 表 节）", root, 1)
        arms.append(("E 主表区缺失 ⇒ 必须红", "人口下限", any("人口下限" in x for x in p)))

        bad = 0
        for name, want, ok in arms:
            bad += not ok
            print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ "
                  f"{'期望红且含「' + want + '」' if want else '期望安静'}")
        print(f"GLOSSARY_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
        return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="五臂注入自证")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    return run()


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""门禁：**指向"读不到的位置"的证据指针必须自报"不可复算"**（证据 E4 类）。

## 依据

草案 `docs/HANDOVER.md` **`§D′-3` 证据的四类** —— ⭐ 用户 2026-10-02 逐字：

> 「客户端的日志是有力证据，但是**测试员的观察、截图、测试感受、问题反馈则是更宝贵的材料**，
>  只是**天生不适合作为"数据证据"管理**」

⇒ 用户裁 **(乙)**：**原件不进仓**，但 **每处引用必须写**
「**原件位置 ＋ ⛔ 不可复算 ＋ 转写测试单编号**」。工序 = `W7-5`。

## ⭐ 为什么它必须是门禁（真损失，实测）

2026-10-02 当场复算：文档里引 **8 个** `screenshots/<日期>.png` 具体路径，
**磁盘上存在 0 个**、仓内**无** `screenshots/` 目录、`.gitignore` **也没排除它**
⇒ ⭐ **不是"被忽略"，是从未进仓** ⇒ 那些引用**全是永久读不到的指针**。

⛔ 而**没有任何东西会因此报错** —— 这正是本仓最贵那族错（`§E`：假边界会被当依据引用）。
⚠️ 注意它**不是**"客户端没交证据"：`client-tests/` 里 **20/22 轮**有 `evidence-report.md`；
缺的是**这些引用指不到任何地方**。

## 判据两道（都机械可判）

| 道 | 判据 | 失败意味着 |
|---|---|---|
| **① 仓内 png 必须真存在** | 正文引 `screenshots/<名字>.png` ⇒ 仓里**必须有**该文件 | ⛔ 引了一个不存在的路径 |
| **② 仓外原件必须自报** | 引的 png **不在仓内** ⇒ 同处或**上一行**必须出现标记词（见 `MARK_NEEDLES`） | ⛔ 读者会以为它可验证 |

## ⛔ 它**假装不了**的

1. ⛔ 它**判不了**"原件在用户机器的哪个盘" —— 云端**没有** `/mnt/d`、**没有**客户端 `screenshots/`，
   所以判据②只要求**如实自报**，⛔ 不要求报得对（那要用户确认）；
2. ⛔ 它**判不了**"这张图该不该留" —— 那是用户的判断；
3. ⚠️ 它**只管 `.md`** —— 其它后缀里的截图引用不归它。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: 匹配正文里的截图路径。⚠️ **两条收紧，都是被真树逼出来的**：
#:   ① 只认**具体某个 png**（`<数字开头>.png`）—— ⛔ 不认 `screenshots/*.png` 这种"说目录"；
#:   ② ⭐ 前面**不许是 `/`** —— 因为 `…/client-info/test1/screenshots/x.png` 说的是
#:      「这个文件**在传输目录里**（已经传上来了）」，⛔ **不是**"引一张读不到的图"。
#:      （`docs/CLIENT_AGENT_NEW_DEVICE_TEST.md:220` 就是这一条当场抓出来的**假阳性**：
#:        逐字「`screenshots/2026-09-22_22.08.36.png` **2289872 字节**」—— 它有字节数，说明**拿到了**。）
SHOT = re.compile(r"(?<!/)screenshots/([0-9][0-9A-Za-z_.\-]*\.png)")

#: 「已自报仓外」的标记词 —— ⚠️ **必须与我在 8 处落的标记一致**
#: 形状：`⛔ **E4 · 仓外原件（不可复算）**`
MARK_NEEDLES = ("E4 · 仓外原件", "不可复算")

#: ⭐ **可核对元数据** —— 出现它 ⇒ 该引用是"传输/核对记录"，判据②豁免。
#:   形状：`2289872 字节` ／ `sha256 a9f92d2a…`
META = re.compile(r"\d{4,}\s*字节|sha256", re.I)

#: 人口下限：至少要有这么多"仓外引用"被发现 —— ⛔ 防"一个都没扫到 ⇒ 报绿"（本仓已踩过多次）
#: 实测（2026-10-02）：**9 处**（8 个路径里的 `2026-09-21_00.02.45.png` 是第 9 处，同一行）。
OUTSIDE_FLOOR = 5


def md_files() -> list[str]:
    """⭐ **必须用 `-z`** —— ⚠️ 这是本门禁第一版的一个**静默洞**（当场抓到）：

    `git ls-files` 默认把**含非 ASCII 的路径加引号并转义**输出（形如
    `"docs/reviews/2026-09-21-\345\256\242..."`）⇒ `Path(rel)` **打不开** ⇒ 被**静默跳过**。
    实测：本仓有 **3 份**这样的文件，它们**恰好都引了仓外截图** ⇒ 第一版把 3 处真引用漏掉了**而报绿**。
    ⭐ 那正是本项目最贵那族错（`§E`：**扫不到就报绿**）。
    ⇒ 用 `-z`（NUL 分隔、**不加引号不转义**）取路径。
    """
    out = subprocess.run(["git", "ls-files", "-z", "*.md"], cwd=ROOT,
                         capture_output=True, text=True).stdout
    return [x for x in out.split("\0") if x]


def scan() -> tuple[list[str], list[str], int]:
    """返回 (仓内有但文件不存在, 仓外但没自报, 仓外引用总数)。"""
    missing: list[str] = []
    unmarked: list[str] = []
    outside = 0
    for rel in md_files():
        p = ROOT / rel
        if not p.exists():
            continue
        lines = p.read_text(encoding="utf-8", errors="ignore").splitlines()
        for i, line in enumerate(lines):
            for m in SHOT.finditer(line):
                name = m.group(1)
                # 仓内是否存在这个文件（全仓任意位置）
                hit = subprocess.run(["git", "ls-files", f"*{name}"], cwd=ROOT,
                                     capture_output=True, text=True).stdout.strip()
                if hit:
                    continue                      # ① 通过：仓里真有
                outside += 1
                # ② ⭐ **豁免**：本行随附**可核对元数据**（字节数 / sha256）⇒
                #    那是**「这个文件被传/被核对过」的记录**（有据可查），⛔ 不是"引一张读不到的图"。
                #    ⚠️ 这条判据是**真树的假阳性逼出来的**：
                #    `docs/CLIENT_AGENT_NEW_DEVICE_TEST.md:220` 逐字「`screenshots/2026-09-22_22.08.36.png`
                #    **2289872 字节**」—— 它有字节数 ⇒ **文件确实到了手里**。
                if META.search(line):
                    continue
                # ③ 必须自报：本行 或 上一行 含标记词
                window = line + ("\n" + lines[i - 1] if i > 0 else "")
                if not all(n in window for n in MARK_NEEDLES):
                    unmarked.append(f"{rel}:{i + 1} 引了仓外原件却**没自报**：`screenshots/{name}`")
    return missing, unmarked, outside


def selftest() -> int:
    """注入自证 —— 「能过」不等于「能抓」。"""
    arms: list[tuple[str, str, bool]] = []

    # 臂 A：具体 png 必须被识别（⛔ 别把 `screenshots/*.png` 这种"说目录"也算进来）
    arms.append(("A 只认具体 png、不认通配", "误报",
                 bool(SHOT.search("见 `screenshots/2026-09-20_21.31.33.png`"))
                 and not SHOT.search("传 `screenshots/*.png`")))

    # 臂 B：**没自报必须被判红** —— ⛔ 这是本门禁存在的全部理由
    fake_line = "| 截图 | `screenshots/1999-01-01_00.00.00.png` |"
    ok_unmarked = not all(n in fake_line for n in MARK_NEEDLES)
    arms.append(("B 仓外且没自报 ⇒ 判红", "没自报", ok_unmarked))

    # 臂 C：**自报在上一行也算** —— ⚠️ 否则任何"标记行 + 引用行"的写法都会被误判
    prev = "> ⛔ **E4 · 仓外原件（不可复算）**"
    arms.append(("C 标记在上一行也认", "误报", all(n in prev for n in MARK_NEEDLES)))

    # 臂 F：**带目录前缀的不算**（那是"文件在传输目录里/已传到"，⛔ 不是"引一张读不到的图"）
    #        ⚠️ 这一臂是**真树的假阳性逼出来的**（`CLIENT_AGENT_NEW_DEVICE_TEST.md:220`）
    arms.append(("F 带 `/` 前缀的不算（传输目录≠引用）", "假阳性",
                 not SHOT.search("`~/client-info/test1/screenshots/2026-09-22_22.08.36.png`")
                 and bool(SHOT.search("`screenshots/2026-09-20_21.31.33.png`"))))

    # 臂 G：**随附元数据的引用必须被豁免** —— ⚠️ 真树假阳性逼出来的那一条
    arms.append(("G 随附字节数/sha256 ⇒ 豁免", "假阳性",
                 bool(META.search("`screenshots/x.png` **2289872 字节**"))
                 and bool(META.search("sha256 `a9f92d2a1cdf2f75`"))
                 and not META.search("见 `screenshots/x.png` 里的沟渠")))

    # 臂 H：⭐ **非 ASCII 文件名的文件必须真的被扫到** —— ⚠️ 这一臂是**本门禁第一版的静默洞**
    #       逼出来的（`git ls-files` 会给非 ASCII 路径加引号 ⇒ `Path()` 打不开 ⇒ 静默跳过 ⇒ **报绿**）。
    arms.append(("H 非 ASCII 路径没被静默跳过", "静默跳过",
                 any(any(ord(c) > 127 for c in f) for f in md_files())))

    # 臂 D：人口下限必须为正（一个都没扫到 ⇒ 不许报绿）
    arms.append(("D 人口下限", "人口下限", OUTSIDE_FLOOR > 0))

    # 臂 E：真树上**确实扫到了** ≥ 人口下限的仓外引用（⛔ 防"判据恒真"）
    _m, _u, n = scan()
    arms.append(("E 真树扫到了仓外引用", "扫不到", n >= OUTSIDE_FLOOR))

    bad = 0
    for name, want, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ "
              f"{'期望红且含「' + want + '」' if want else '期望安静'}")
    print(f"E4_OFFREPO_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()

    problems: list[str] = []
    missing, unmarked, outside = scan()
    if outside < OUTSIDE_FLOOR:
        problems.append(f"只扫到 {outside} 处仓外引用 < 人口下限 {OUTSIDE_FLOOR} "
                        "⇒ ⛔ 「扫不到就报绿」不是通过（判据本身可能失效了）")
    problems.extend(f"⛔ ① {m}" for m in missing)
    problems.extend(f"⛔ ② {u}" for u in unmarked)
    if problems:
        print("E4_OFFREPO_RESULT FAIL")
        for p in problems:
            print(f"  [FAIL] {p}")
        return 1
    print(f"E4_OFFREPO_RESULT PASS: 仓外引用 {outside} 处全部自报「不可复算」· "
          f"仓内引用 0 处指向不存在的文件")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""门禁：**咨询通道必须成对**（用户 2026-10-04 裁 **ⓐ：未配对的问题文档 ⇒ `check-all` 直接红**）。

## 通道的形状（用户 2026-10-04 逐字，两段）

> ①「现在我要给项目一个**特殊通道**，把**重难点问题和关键决策问题**等通过文档记录下来，
>    在这个通道里**接受外来技术顾问的建议**，**未收到回执，绝对不能擅自解决相关问题**」
> ②「通道的载体就是**在仓库新加一个目录**，一个子目录放**现状问题文档**，一个子目录放**回执**，
>    **带编号**，**两边必须成对出现**，才能算完成一次咨询」

⭐ 另有三条用户令，本门禁按它们成形：
> ③「这个咨询流程**只能我自己手动让你触发**」
> ④「只要**提交出咨询请求**，就**必须等咨询完成才能继续工作**」
> ⑤「**咨询没有决策权**，但是咨询实际流程是我和顾问人工交互的，所以**顾问会标记哪些是开发者决策的**」

⇒ ⭐ 配对形状（用户 2026-10-04 选「纯序号，**两边同名**」）：
`consult/request/<编号>-<短标题>.md`  ⇄  `consult/receipt/<编号>-<短标题>.md`

## 判据（用户裁 **ⓐ = 直接红**）

| # | 判据 | 为什么 |
|---|---|---|
| **1** | ⛔ **未配对的问题文档**（有 request 无 receipt） | ⭐ **这就是「提交即停工」的机械形态**（用户令 ④） |
| **2** | ⛔ **孤儿回执**（有 receipt 无 request） | 回执没有对应的问题 ⇒ 它答的是什么 |
| **3** | ⛔ **编号重复**（同一侧两个同编号的件） | 成对的键是**编号** ⇒ 重号 = 配不上 |
| **4** | ⛔ **同号不同名** | 用户令「**两边同名**」⇒ 名字不一致就不是那一对 |
| **5** | ⛔ **扫不到 request 件**（人口下限） | ⭐ 防「**扫不到就报绿**」（本仓最贵那族） |
| **6** | ⛔ **两个子目录任缺一个** | 通道被搬走／改名 ⇒ ⛔ **响亮失败**，⛔ 不是静默通过 |

## 边界（⛔ 如实写）

* ⛔ 它**不判**问题的内容质量、⛔ 不判回执说得对不对（那是人的事）；
* ⛔ 它**不判**「有没有人偷偷把问题解决了」—— ⭐ **机械判不了**（本仓最贵那族）；它判的是**是否成对**；
* ⚠️ 因此用户令 ① 的后半句（「未收到回执，绝对不能擅自解决相关问题」）**靠纪律 ＋ 本门禁的红**共同守：
  ⭐ **红的意思是"这一轮不许往下走"**，⛔ 不是"这份文件写错了"。

⚠️ ⛔ **不许把活样本写进本文件**（本仓踩过多次"门禁命中它自己"）——
⭐ 编号正则用 `\\d`，⛔ 不写具体编号；臂里的样本**全部在临时目录里现造**。
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

CHANNEL_DIR = "consult"
REQUEST_DIR = "request"
RECEIPT_DIR = "receipt"

#: ⛔ 不写具体编号（防自污染）。
PAIR_ID = re.compile(r"^(\d+)")

#: 通道自述件**不参与配对**（它是通道自己的一部分，⛔ 不是一次咨询）。
SKIP_NAMES = frozenset({"README.md"})

#: 人口下限：问题文档至少这么多件（⛔ 目录被清空／正则坏掉 ⇒ 不许报绿）。
REQUEST_FLOOR = 1


def scan(d: Path) -> list[str]:
    """列出该侧的件名（只 `.md`，⛔ 跳过自述件）。"""
    if not d.is_dir():
        return []
    return sorted(p.name for p in d.iterdir()
                  if p.is_file() and p.name.endswith(".md") and p.name not in SKIP_NAMES)


def by_id(names: list[str]) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for n in names:
        m = PAIR_ID.match(n)
        out.setdefault(m.group(1) if m else "（无编号）", []).append(n)
    return out


def audit(req_dir: Path, rcp_dir: Path, floor: int = REQUEST_FLOOR) -> list[str]:
    """返回问题清单（空 = 通过）。⭐ 单一出处：真树与臂都走这一个函数。"""
    problems: list[str] = []

    if not req_dir.is_dir():
        problems.append(f"⛔ 问题文档目录不存在：{req_dir} ⇒ 通道被搬走或改名（⛔ 不是通过）")
        return problems
    if not rcp_dir.is_dir():
        problems.append(f"⛔ 回执目录不存在：{rcp_dir} ⇒ 通道被搬走或改名（⛔ 不是通过）")
        return problems

    reqs, rcps = scan(req_dir), scan(rcp_dir)
    if len(reqs) < floor:
        problems.append(f"⛔ 问题文档只有 {len(reqs)} 件 < 人口下限 {floor}"
                        f" ⇒ ⭐ 扫不到就报绿的入口，不放过")

    rq, rc = by_id(reqs), by_id(rcps)

    for label, d in (("问题侧", rq), ("回执侧", rc)):
        for k, v in sorted(d.items()):
            if len(v) > 1:
                problems.append(f"⛔ 编号重复（{label} {k}）：{' ／ '.join(v)}"
                                f" ⇒ 成对的键是编号，重号配不上")

    for k, v in sorted(rq.items()):
        if k not in rc:
            problems.append(
                f"⛔⛔ **未配对的问题文档：{REQUEST_DIR}/{v[0]}** ⇒ 咨询未完成 ⇒ "
                f"⭐ **按用户令停工**（「只要提交出咨询请求，就必须等咨询完成才能继续工作」）"
                f" ｜待补：{RECEIPT_DIR}/{v[0]}")

    for k, v in sorted(rc.items()):
        if k not in rq:
            problems.append(f"⛔ 孤儿回执：{RECEIPT_DIR}/{v[0]} ⇒ 没有对应的问题文档")

    for k in sorted(set(rq) & set(rc)):
        if rq[k][0] != rc[k][0]:
            problems.append(f"⛔ 同号不同名（编号 {k}）：问题侧 {rq[k][0]} ／ 回执侧 {rc[k][0]}"
                            f" ⇒ ⭐ 用户裁「两边同名」")

    return problems


def run() -> int:
    req = ROOT / CHANNEL_DIR / REQUEST_DIR
    rcp = ROOT / CHANNEL_DIR / RECEIPT_DIR
    problems = audit(req, rcp)
    n_req, n_rcp = len(scan(req)), len(scan(rcp))
    if problems:
        print("CONSULT_PAIRS_RESULT FAIL")
        for p in problems:
            print(f"  [FAIL] {p}")
        print(f"  ⇒ 问题 {n_req} 件 · 回执 {n_rcp} 件 · ⛔ **咨询未完成 ⇒ "
              f"按用户令停工，⛔ 不许往下走**")
        return 1
    print(f"CONSULT_PAIRS_RESULT PASS: 问题 {n_req} 件 · 回执 {n_rcp} 件 · "
          f"⭐ 全部成对同名 ⇒ 已完成 {n_req} 次咨询 · ⛔ 无未回执项")
    return 0


# ---------------------------------------------------------------- 注入自证臂

def _mk(base: Path, reqs: list[str], rcps: list[str]) -> tuple[Path, Path]:
    rq, rc = base / REQUEST_DIR, base / RECEIPT_DIR
    rq.mkdir(parents=True, exist_ok=True)
    rc.mkdir(parents=True, exist_ok=True)
    for n in reqs:
        (rq / n).write_text("x", encoding="utf-8")
    for n in rcps:
        (rc / n).write_text("x", encoding="utf-8")
    return rq, rc


def selftest() -> int:
    """⭐ 每条臂都断言一件**它该抓到的事**；正对照断言**必须安静**。"""
    arms: list[tuple[str, str, bool]] = []
    with tempfile.TemporaryDirectory() as td:
        base = Path(td)

        # 正对照：成对 ＋ 同名 ⇒ 必须安静（否则门禁会把正常状态判红）
        rq, rc = _mk(base / "ok", ["901-a.md"], ["901-a.md"])
        arms.append(("A 成对同名（正对照）", "必须安静", audit(rq, rc) == []))

        # 核心臂：未配对的问题文档 ⇒ ⭐ 必须抓到（用户裁 ⓐ 的那一条）
        rq, rc = _mk(base / "unpaired", ["902-a.md"], [])
        arms.append(("B 未配对的问题 ⇒ 停工", "必须抓到", bool(audit(rq, rc))))

        # 孤儿回执
        rq, rc = _mk(base / "orphan", ["903-a.md"], ["903-a.md", "904-b.md"])
        arms.append(("C 孤儿回执", "必须抓到", bool(audit(rq, rc))))

        # 编号重复（问题侧）
        rq, rc = _mk(base / "dup", ["905-a.md", "905-b.md"], ["905-a.md"])
        arms.append(("D 编号重复（问题侧）", "必须抓到", bool(audit(rq, rc))))

        # 同号不同名
        rq, rc = _mk(base / "name", ["906-a.md"], ["906-b.md"])
        arms.append(("E 同号不同名", "必须抓到", bool(audit(rq, rc))))

        # 人口下限：两侧都空 ⇒ 不许报绿
        rq, rc = _mk(base / "empty", [], [])
        arms.append(("F 人口下限（两侧都空）", "必须抓到", bool(audit(rq, rc))))

        # 目录缺失 ⇒ 响亮失败
        missing = base / "missing"
        missing.mkdir()
        arms.append(("G 目录缺失", "必须抓到",
                     bool(audit(missing / REQUEST_DIR, missing / RECEIPT_DIR))))

        # 无编号件 ⇒ 归到「（无编号）」键，且与有编号的一件不成对
        rq, rc = _mk(base / "nonum", ["x.md"], [])
        arms.append(("H 无编号件", "必须抓到", bool(audit(rq, rc))))

    ok = True
    for name, want, got in arms:
        flag = "✅" if got else "⛔"
        if not got:
            ok = False
        print(f"  {flag} 臂 {name}：{want} ⇒ {'抓到' if got else '没抓到'}")
    print(f"CONSULT_PAIRS_SELFTEST {'PASS' if ok else 'FAIL'}: {len(arms)} 臂")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="咨询通道成对门禁")
    ap.add_argument("--selftest", action="store_true", help="只跑注入自证臂")
    a = ap.parse_args()
    return selftest() if a.selftest else run()


if __name__ == "__main__":
    sys.exit(main())

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
> ⑤「**咨询没有决策权**，但是咨询实际流程是我和顾问人工交互的，所以**顾问会标记哪些是开发者决策**」

## ⭐⭐ 2026-10-06 用户令：**一个申请可以对应多个回执**（本门禁据此改判据）

> 「马上修改下门禁，**允许一个咨询申请能对应多个咨询回执**，但是**主编号必须相同**，**允许用子编号**」

⇒ ⭐ **件名形状（本刀起）**：`<主编号>[.<子编号>]-<短标题>.md`
  · 例：申请 `004-标题.md` ／ 回执 `004-标题.md` ＋ `004.1-标题.md` ＋ `004.2-标题.md`
  · ⭐ **主编号 = 一次咨询的键**；**子编号 = 同一咨询的第几轮**（可选）
  · ⛔ **子编号只许是 `.` ＋ 数字**（⛔ 不许 `004b` 那种字母写法 —— 一词一义）

⇒ ⭐ **判据（六条）**

| # | 判据 | 它防什么 |
|---|---|---|
| **1** | ⛔ **未配对的问题文档**（主编号在回执侧一件都没有） | ⭐ **这就是「提交即停工」的机械形态**（用户令 ④） |
| **2** | ⛔ **孤儿回执**（回执的主编号在问题侧不存在） | 回执没有对应的问题 ⇒ 它答的是什么 |
| **3** | ⛔ **同一侧「主编号 ＋ 子编号」重复** | ⭐ 键是**这两者**（⚠️ 本刀**收窄**了旧判据：同主编号**多件回执是允许的**） |
| **4** | ⛔ **无子编号回执的短标题与申请不一致** | ⭐ 用户令「**两边同名**」的**保留部分**（⚠️ **带子编号的回执 ⛔ 标题自由** —— 那正是「追加回执」这个正当形状） |
| **5** | ⛔ **件名不带编号**（不匹配形状） | ⭐ 用户令 ② 逐字「**带编号**」—— ⚠️ 不加这条，两侧各一个 `x.md` 会**配成一对** |
| **6** | ⛔ **扫不到 request 件**（人口下限）｜**两个子目录任缺一个** | ⭐ 防「扫不到就报绿」／通道被搬走 ⇒ **响亮失败**，⛔ 不是静默通过 |

## 边界（⛔ 如实写）

* ⛔ 它**不判**问题的内容质量、⛔ 不判回执说得对不对（那是人的事）；
* ⛔ 它**不判**「有没有人偷偷把问题解决了」—— ⭐ **机械判不了**（本仓最贵那族）；它判的是**是否成对**；
* ⚠️ 因此用户令 ① 的后半句（「未收到回执，绝对不能擅自解决相关问题」）**靠纪律 ＋ 本门禁的红**共同守：
  ⭐ **红的意思是"这一轮不许往下走"**，⛔ 不是"这份文件写错了"；
* ⛔ 它**不判**「子编号是否连续」（实测**刻意不判** —— 轮次可能因回执无效而占号，⛔ 不必连续）。

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

#: ⭐ 件名形状：`<主编号>[.<子编号>]-<短标题>.md`（⛔ 不写具体编号，防自污染）。
PAIR_ID = re.compile(r"^(?P<main>\d+)(?:\.(?P<sub>\d+))?-(?P<title>.+)$")

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


def parse(name: str) -> tuple[str, str, str]:
    """拆成 `(主编号, 子编号, 短标题)` —— ⛔ 拆不出 ⇒ 主编号标成哨兵值。"""
    m = PAIR_ID.match(name)
    if not m:
        return ("（无编号）", "", name)
    return (m.group("main"), m.group("sub") or "", m.group("title"))


def by_main(names: list[str]) -> dict[str, list[tuple[str, str, str]]]:
    """⭐ 键 = **主编号**（⚠️ 本刀起同键可以有多件 —— 那就是"多轮回执"）。"""
    out: dict[str, list[tuple[str, str, str]]] = {}
    for n in names:
        main, sub, title = parse(n)
        out.setdefault(main, []).append((n, sub, title))
    return out


def _label(main: str, sub: str) -> str:
    return f"{main}.{sub}" if sub else main


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

    #: 判据 ⑤：件名必须带编号（⛔ 否则两侧各一个无编号件会配成一对）
    for label, names in (("问题侧", reqs), ("回执侧", rcps)):
        for n in names:
            if parse(n)[0] == "（无编号）":
                problems.append(f"⛔ 件名不带编号（{label}）：{n} ⇒ "
                                f"形状必须是 `主编号[.子编号]-短标题.md`（用户令「带编号」）")

    rq, rc = by_main(reqs), by_main(rcps)

    #: 判据 ③：同一侧「主编号 ＋ 子编号」重复（⚠️ 同主编号**多件是允许的**）
    for label, d in (("问题侧", rq), ("回执侧", rc)):
        for k, v in sorted(d.items()):
            seen: dict[str, list[str]] = {}
            for n, sub, _t in v:
                seen.setdefault(sub, []).append(n)
            for sub, ns in sorted(seen.items()):
                if len(ns) > 1:
                    problems.append(
                        f"⛔ 同一侧「主编号 ＋ 子编号」重复（{label} {_label(k, sub)}）："
                        f"{' ／ '.join(ns)} ⇒ ⭐ 键是这两者（⚠️ 同主编号多件回执**是允许的**）")

    #: 判据 ①：未配对 ⇒ ⭐ 停工
    for k, v in sorted(rq.items()):
        if k not in rc:
            problems.append(
                f"⛔⛔ **未配对的问题文档：{REQUEST_DIR}/{v[0][0]}** ⇒ 咨询未完成 ⇒ "
                f"⭐ **按用户令停工**（「只要提交出咨询请求，就必须等咨询完成才能继续工作」）"
                f" ｜待补：{RECEIPT_DIR}/<主编号[.子编号]>-<同名>.md（⭐ 可多件）")

    #: 判据 ②：孤儿回执
    for k, v in sorted(rc.items()):
        if k not in rq:
            problems.append(f"⛔ 孤儿回执：{RECEIPT_DIR}/{v[0][0]} ⇒ "
                            f"主编号 {k} 在问题侧不存在")

    #: 判据 ④：**无子编号的回执** ⇒ 短标题必须与申请一致（⭐ 2026-10-04 令「两边同名」的**保留部分**）
    #:    ⚠️⭐ **带子编号的回执 ⛔ 标题自由** —— 2026-10-06 令逐字只要求「**主编号必须相同**」，
    #:    ⛔ 「短标题也必须一致」是**我第一版多加的**（⛔ 那道加严会挡掉「追加回执」这个正当形状）。
    for k in sorted(set(rq) & set(rc)):
        base_req_titles = {t for _n, s, t in rq[k] if not s}
        for n, sub, title in rc[k]:
            if sub:
                continue
            if base_req_titles and title not in base_req_titles:
                problems.append(
                    f"⛔ 无子编号回执的短标题与申请不一致（主编号 {k}）："
                    f"{' ／ '.join(sorted(base_req_titles))} ／ {title}"
                    f" ⇒ ⭐ 用户令「两边同名」（⚠️ **带子编号的回执不在此限**）")

    return problems


def run() -> int:
    req = ROOT / CHANNEL_DIR / REQUEST_DIR
    rcp = ROOT / CHANNEL_DIR / RECEIPT_DIR
    problems = audit(req, rcp)
    reqs, rcps = scan(req), scan(rcp)
    n_req, n_rcp = len(reqs), len(rcps)
    n_rounds = len({parse(n)[:2] for n in rcps})
    if problems:
        print("CONSULT_PAIRS_RESULT FAIL")
        for p in problems:
            print(f"  [FAIL] {p}")
        print(f"  ⇒ 问题 {n_req} 件 · 回执 {n_rcp} 件 · ⛔ **咨询未完成 ⇒ "
              f"按用户令停工，⛔ 不许往下走**")
        return 1
    print(f"CONSULT_PAIRS_RESULT PASS: 问题 {n_req} 件 · 回执 {n_rcp} 件"
          f"（⭐ 键 {n_rounds} 个）· ⭐ 全部成对同名（⭐ 允许一申请多回执）· "
          f"已完成 {n_req} 次咨询 · ⛔ 无未回执项")
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

        # 编号重复（问题侧，同主编号同子编号）
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

        # 无编号件 ⇒ ⛔ 抓到（判据 ⑤）—— ⚠️ 这条堵的是「两侧各一个 x.md ⇒ 配成一对」
        rq, rc = _mk(base / "nonum", ["x.md"], [])
        arms.append(("H 无编号件", "必须抓到", bool(audit(rq, rc))))

        # ⭐⭐ 本刀的主臂：**一个申请 ＋ 两个子编号回执** ⇒ 必须安静
        rq, rc = _mk(base / "multi", ["907-a.md"], ["907-a.md", "907.1-a.md", "907.2-a.md"])
        arms.append(("I 一申请 ＋ 多子编号回执 ⇒ 允许", "必须安静", audit(rq, rc) == []))

        # ⭐ 正臂：**带子编号的回执标题自由** ⇒ 必须安静（判据 ④ 只管**无子编号**那一件）
        #    ⚠️ 这正是「追加回执」的正当形状（⛔ 我第一版把它判红了）
        rq, rc = _mk(base / "subfreetitle", ["908-a.md"], ["908.1-a.md", "908.2-另一个主题.md"])
        arms.append(("J 带子编号的回执标题自由 ⇒ 允许", "必须安静", audit(rq, rc) == []))

        # 反臂：**无子编号**回执标题不同 ⇒ 抓到（判据 ④ 保留的那一半）
        rq, rc = _mk(base / "basetitle", ["913-a.md"], ["913-b.md"])
        arms.append(("J′ 无子编号回执标题不同", "必须抓到", bool(audit(rq, rc))))

        # 反臂：回执**主编号不同** ⇒ 孤儿（判据 ②）
        rq, rc = _mk(base / "wrongmain", ["909-a.md"], ["909.1-a.md", "910.1-a.md"])
        arms.append(("K 回执主编号不同 ⇒ 孤儿", "必须抓到", bool(audit(rq, rc))))

        # 反臂：同一侧同一子编号两件 ⇒ 抓到（判据 ③）
        rq, rc = _mk(base / "dupsub", ["911-a.md"], ["911.1-a.md", "911.1-b.md"])
        arms.append(("L 同一侧子编号重复", "必须抓到", bool(audit(rq, rc))))

        # 正臂：只有子编号回执（申请不带子编号）⇒ 必须安静（⭐ 最常见形状）
        rq, rc = _mk(base / "subonly", ["912-a.md"], ["912.1-a.md"])
        arms.append(("M 只有子编号回执 ⇒ 安静", "必须安静", audit(rq, rc) == []))

    ok = True
    for name, want, got in arms:
        flag = "✅" if got else "⛔"
        if not got:
            ok = False
        print(f"  {flag} 臂 {name}：{want} ⇒ {'抓到' if got else '没抓到'}")
    print(f"CONSULT_PAIRS_SELFTEST {'PASS' if ok else 'FAIL'}: {len(arms)} 臂")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="注入自证（13 臂）")
    args = ap.parse_args()
    return selftest() if args.selftest else run()


if __name__ == "__main__":
    sys.exit(main())

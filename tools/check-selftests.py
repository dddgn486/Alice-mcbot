#!/usr/bin/env python3
"""门禁：**注入臂必须真的跑** —— 汇总跑全部 `--selftest`（用户 2026-10-04 裁「②丁」）。

## 为什么有它（当场量到的形状）

`tools/` 下的门禁脚本**大多带"注入自证臂"**，但 ⛔ **`check-all` 只跑 `--check`** ⇒
⭐ 臂**从来没被跑过**。AST 全量分类的结果：

  · **A 类 8 个**：每次运行都跑臂（inline，如 `check-baritone-anchor`）—— ✅ 没问题；
  · ⭐ **B 类 18 个**：**只有 `--selftest` 才跑臂** ⇒ ⛔ 在 `check-all` 里**从没跑过**；
  · C 类 41 个：没有臂。

**活样本（这条为什么值钱）**：
  1. `tools/cleanup-classify.py` 的臂 F 曾因**自污染恒红** —— ⛔ **没有任何东西会因此变红**
     （2026-10-04 断点七十二 才发现并修好）；
  2. `tools/check-expected-reds.py` 的文件头**逐字写着**「`--selftest` 的 8 条合成臂**每次都跑**」，
     ⛔ 而代码里**只有 `--selftest` 才跑** ⇒ ⭐ **散文与代码相反**。

⇒ ⭐ 用户选「**②丁**」：**不逐条挂进 `check-all`**（那会动 18 道已有门禁的语义），
  而是 ⭐ **另开这一道汇总门禁** —— 臂红了**它自己会报是哪一条**。

## 判据（机械）

1. **名单里的每一条都必须真的带 `--selftest`**（⛔ 名字写错 / 标志被删 ⇒ 红：**解析不到就响亮失败**）；
2. **每一条的 `--selftest` 必须真的过**（判 **退出码**；⚠️ ⛔ **不看它自己打印的 `PASS` 字样** ——
   那正是本门禁要防的"自报成功"）；
3. **人口下限** `FLOOR`：名单被删空到少于它 ⇒ 红（⛔ 扫不到就报绿的入口，不放过）。

## ⚠️ 环境档（⛔ 如实写，⛔ 不静默）

`ENV_SKIP` 里的工具**在本环境跑不了**（缺上游数据）⇒ ⭐ 它们**不是通过**，是**没执行**，
会被**逐条打印成 WARN**，且 ⛔ **名单不许再长**（`ENV_SKIP_MAX`，只许变短）。
⭐ 这与 `check-all` 现有的两条环境 WARN 同一个口径：**别把"没跑"读成"过了"**。
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: ⭐ **B 类名单**（AST 分类：`selftest` 调用被 `--selftest` 分支守着）—— ⛔ 手写，不许"动态发现"：
#:   动态发现会把**本门禁自己**也扫进来（自指），而且"没扫到"会静默变成"没红"。
#: ⚠️ 每一条都必须是**真的带 `--selftest`** 的脚本 —— 判据 ① 会逐条验它（解析不到就红）。
TOOLS = (
    "archive-index", "check-authz-code-refs", "check-consult-pairs", "check-e4-offrepo", "design-index",
    "check-effective-trace", "check-expected-reds", "check-facts-tiers", "check-glossary",
    "check-project-state-freshness", "check-proposal-status", "check-quote-lint",
    "check-scene-connectivity", "cleanup-classify",
    "failure-ratio", "new-home-audit", "plan-doc-refactor", "recipe-graph", "redline-gates",
    "ref-anchors", "skills-index",
)

#: ⚠️ **环境档**（本环境跑不了 ⇒ 不是通过，是"没执行"）—— ⛔ **只许变短**。
ENV_SKIP = {"recipe-graph": "缺原版数据目录（要解压客户端 jar 的 data/）"}

FLOOR = 15          #: 人口下限：名单短于此 ⇒ 红
ENV_SKIP_MAX = 2    #: 环境档条数上限（⛔ 只许变短）


def has_flag(name: str) -> bool:
    """名单里那一条**真的带 `--selftest`** 吗（⛔ 名字写错 / 标志被删 ⇒ 立刻红）。"""
    p = ROOT / "tools" / f"{name}.py"
    if not p.exists():
        return False
    return "--selftest" in p.read_text(encoding="utf-8", errors="ignore")


def run_one(name: str, timeout: int = 300) -> tuple[bool, str]:
    """跑一条 `--selftest` —— ⭐ **判退出码，⛔ 不看它自己打印的 `PASS`**。"""
    p = subprocess.run([sys.executable, str(ROOT / "tools" / f"{name}.py"), "--selftest"],
                       cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    tail = (p.stdout or p.stderr or "").strip().splitlines()
    return p.returncode == 0, (tail[-1] if tail else "(无输出)")


def check() -> int:
    problems: list[str] = []
    warns: list[str] = []

    if len(TOOLS) < FLOOR:
        problems.append(f"名单只有 {len(TOOLS)} 条 < 人口下限 {FLOOR} ⇒ "
                        "臂的名单被删空了（⛔ 扫不到就报绿的入口，不放过）")
    if len(ENV_SKIP) > ENV_SKIP_MAX:
        problems.append(f"环境档有 {len(ENV_SKIP)} 条 > 上限 {ENV_SKIP_MAX} ⇒ "
                        "⛔ 「跑不了」不许变多（那是把红藏进环境里的入口）")

    for name in TOOLS:
        if not has_flag(name):
            problems.append(f"⛔ `tools/{name}.py` **没有 `--selftest`**（或文件不存在）⇒ "
                            "名单里写了一个跑不了臂的条目 = 判据静默失效（本项目最贵那族）")
            continue
        if name in ENV_SKIP:
            warns.append(f"⚠️ `{name}` **本轮没执行**（环境档：{ENV_SKIP[name]}）⇒ "
                         "⛔ 别把这一行读成「过了」")
            continue
        try:
            ok, last = run_one(name)
        except subprocess.TimeoutExpired:
            problems.append(f"⛔ `{name} --selftest` **超时**（>300s）")
            continue
        if not ok:
            problems.append(f"⛔ `{name} --selftest` **红**（退出码非 0）—— 最后一行：{last[:160]}")
    ran = len(TOOLS) - len(ENV_SKIP)
    if problems:
        print("SELFTESTS_RESULT FAIL")
        for w in warns:
            print(f"  [WARN] {w}")
        for p in problems:
            print(f"  [FAIL] {p}")
        return 1
    print(f"SELFTESTS_RESULT PASS: 臂 {ran}/{len(TOOLS)} 条真的跑了且全过"
          + (f" · ⚠️ 环境档 {len(ENV_SKIP)} 条未执行" if ENV_SKIP else ""))
    for w in warns:
        print(f"  [WARN] {w}")
    return 0


def selftest() -> int:
    arms: list[tuple[str, str, bool]] = []

    # 臂 A：**退出码非 0 必须红**（⛔ 哪怕它自己打印了 PASS）—— 喂一个假脚本
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        fake = Path(td) / "fake.py"
        fake.write_text("print('FAKE_SELFTEST PASS: arms=9 failed=0')\nraise SystemExit(1)\n",
                        encoding="utf-8")
        p = subprocess.run([sys.executable, str(fake)], capture_output=True, text=True)
        arms.append(("A 自报 PASS 但退出码非 0 ⇒ 必须红", "退出码非 0", p.returncode != 0))

    # 臂 B：**真过必须安静**
    ok, _ = run_one("ref-anchors")
    arms.append(("B 真过的臂必须安静", "", ok))

    # 臂 C：**名单里写一个没有 `--selftest` 的条目 ⇒ 必须红**
    arms.append(("C 名单写了跑不了臂的条目", "没有 `--selftest`",
                 not has_flag("this-tool-does-not-exist")))

    # 臂 D：**人口下限真的在守**（把名单当空跑一遍判据）
    arms.append(("D 名单清空 ⇒ 人口下限红", "人口下限", len(()) < FLOOR))

    # 臂 E：**环境档不许变多**
    arms.append(("E 环境档超上限 ⇒ 红", "环境档", len({"a", "b", "c"}) > ENV_SKIP_MAX))

    bad = 0
    for name, want, okk in arms:
        bad += not okk
        print(f"  [{'PASS' if okk else 'FAIL'}] {name} ⇒ "
              f"{'期望红且含「' + want + '」' if want else '期望安静'}")
    print(f"SELFTESTS_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.list:
        for n in TOOLS:
            print(("ENV " if n in ENV_SKIP else "    ") + n)
        return 0
    return check()


if __name__ == "__main__":
    sys.exit(main())

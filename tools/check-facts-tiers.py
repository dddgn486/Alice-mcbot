#!/usr/bin/env python3
"""门禁：**事实/数据（⑦ 类）必须自报档位**，且 ⑦c 的 `generatedAtTick` 不许缺。

## ⭐ 为什么有它（出处 = 草案 v2 的修订，⛔ 不是想当然）

目标文档体系 v2（`docs/HANDOVER.md` `§D′`）新增了 **⑦ 事实/数据**类，且按**可变性**分三档
（`§D′-2`，用户 2026-10-02 逐字纠正：**运行时产出 ≠ 不变数据**）：

| 档 | 判据（必须写进文件头） | 过期怎么发现 |
|---|---|---|
| **⑦a 不变** | **锁定物**（jar 名 ＋ 字节数） | ⚠️ 换版本 ⇒ 只能靠人 |
| **⑦b 派生** | 生成物 ＋ 门禁 | ✅ 自动（本门禁**管不到**它，它有生成器自己的门禁） |
| **⑦c 运行时可变** | `generatedAtTick` ＋ 出处 ＋ 复算命令 · ⛔ 不许当"已确认"引用 | ⚠️ 今天只能靠人 |

⛔ **本门禁存在的唯一理由**：`§D′-2` 那三档**今天全靠人记得** —— 而 "只靠人记得" 正是
本仓反复踩的那族（`AGENTS.md` 的原话：「挂在旁边的验证 = 下一个 `BotSelftest`」）。
⇒ 把它变成**能让构建失败的东西**（纪律：门禁 > 散文）。

## 判据（三道）

1. **档位臂**：每个 `docs/*_FACTS.md` 的**头部**必须声明 `⑦a` / `⑦b` / `⑦c` 之一 ——
   ⛔ 不声明 ⇒ 红（「这份数据会不会过期」读不出来）。
2. **⑦c 臂**：声明 `⑦c` 的，头部必须给出下列**三者之一**（⛔ 不许含糊）：
   - `generatedAtTick=<数字>` —— ⭐ 可复算的那种；
   - 逐字 `不可从仓内复算` —— ⛔ **诚实边界也是合法答案**，但**必须写出来**
     （⭐ 本条是用户这条纪律的落地：`⛔ 不许静默`。说不出数就说"说不出"，⛔ 不许留一个"见文件头"）；
   - 逐字 `同一次导出` ＋ 一个**已存在的**文件路径 —— 来源可核到另一份登记。
3. **出处臂**：头部必须含**反引号包住的路径**，且该路径**真存在** ——
   ⛔ 挡住"指向一个永远读不到的位置"（本项目已实测过这一族：8 个 `screenshots/*.png`
   引用，磁盘上存在 **0** 个）。

## ⛔ 本门禁**假装不了**的（诚实边界）

1. ⛔ **它判不了数字对不对** —— 它只判"档位与出处登记在不在"。数字要人去复算。
2. ⛔ **它判不了 `tools/recipe-readability.py` 复算出来的值是否等于文件里那个数** ——
   那需要源 JSON，而它**不在本仓**。
3. ⛔ **⑦b 不在它管辖内** —— 派生件有生成器自己的门禁（`machine-map` / `job-kind-view` / …）。

用法：`python3 tools/check-facts-tiers.py`　·　`--selftest`（四臂注入自证）
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

#: ⚠️ 人口下限：`docs/*_FACTS.md` 少于此 ⇒ **响亮失败**
#:（目录搬了 / 改名了 ⇒ "全绿"读起来完全正常 —— 本仓反复踩的那族错）。2026-10-02 实测 = 3。
FACTS_FLOOR = 3

#: 只看**头部**这几行 —— ⛔ 不全文搜：
#: 否则正文里偶然提到 "⑦c" 就会被当成档位声明（⭐ 那正是"扫到什么算什么"的坑）。
HEAD_LINES = 40

TIER = re.compile(r"⑦([abc])\b")
TICK = re.compile(r"generatedAtTick\s*=\s*\d+")
NO_RECOMPUTE = "不可从仓内复算"
SAME_EXPORT = "同一次导出"
BACKTICK_PATH = re.compile(r"`([^`]+)`")

#: ⛔ 只扫这些后缀的路径（免得把 `alice:machine_probe` / `rule_*` 那种 token 当成路径）
PATHISH = (".py", ".json", ".md", ".csv", ".txt")


def problems_for(path: Path, docs_root: Path) -> list[str]:
    """返回该文件的问题（空 = 过）。⭐ 参数化 `docs_root` ⇒ 注入自证才喂得进假文件。"""
    lines = path.read_text(encoding="utf-8").splitlines()
    head = "\n".join(lines[:HEAD_LINES])
    out: list[str] = []

    m = TIER.search(head)
    if not m:
        out.append(f"{path.name}: ⛔ 头部 {HEAD_LINES} 行内**没有档位声明** ⇒ "
                   f"「这份数据会不会过期」读不出来（草案 `§D′-2`：必须写 ⑦a/⑦b/⑦c 之一）")
        return out

    tier = m.group(1)
    if tier == "c":
        ok_tick = bool(TICK.search(head))
        ok_no = NO_RECOMPUTE in head
        ok_same = SAME_EXPORT in head and any(
            (docs_root.parent / p).exists() or (ROOT / p).exists()
            for p in BACKTICK_PATH.findall(head) if p.endswith(PATHISH)
        )
        if not (ok_tick or ok_no or ok_same):
            out.append(f"{path.name}: ⛔ 声明了 ⑦c，却**既没有** `generatedAtTick=<数字>`、"
                       f"**也没有**逐字「{NO_RECOMPUTE}」、**也没有**「{SAME_EXPORT}」＋一个存在的路径 ⇒ "
                       f"⛔ 不许含糊（说不出数就说「说不出」，这是用户这条纪律：⛔ 不许静默）")

    # 出处臂：头部至少一个反引号路径，且它真存在
    cands = [p for p in BACKTICK_PATH.findall(head) if p.endswith(PATHISH)]
    if not cands:
        out.append(f"{path.name}: ⛔ 头部**没有**反引号包住的路径 ⇒ 出处不可核")
    else:
        miss = [p for p in cands
                if not (docs_root.parent / p).exists() and not (ROOT / p).exists()
                and not (docs_root / Path(p).name).exists()]
        if len(miss) == len(cands):
            out.append(f"{path.name}: ⛔ 头部引的路径**一个都不存在**：{miss[:3]} ⇒ "
                       f"'指向一个永远读不到的位置'（本项目已实测过这一族）")
    return out


def collect(docs_root: Path) -> list[Path]:
    return sorted(docs_root.glob("*_FACTS.md"))


def run(docs_root: Path | None = None) -> int:
    docs_root = docs_root or DOCS
    files = collect(docs_root)
    if len(files) < FACTS_FLOOR:
        print(f"FACTS_TIERS_RESULT FAIL: 只扫到 {len(files)} 份 `*_FACTS.md` < 人口下限 {FACTS_FLOOR} "
              f"⇒ 解析/范围崩塌（扫不到就报绿的入口，不放过）")
        return 1

    problems: list[str] = []
    tiers: dict[str, int] = {}
    for p in files:
        problems.extend(problems_for(p, docs_root))
        m = TIER.search("\n".join(p.read_text(encoding="utf-8").splitlines()[:HEAD_LINES]))
        if m:
            tiers[m.group(1)] = tiers.get(m.group(1), 0) + 1

    if problems:
        print(f"FACTS_TIERS_RESULT FAIL: {len(problems)} 处")
        for x in problems:
            print(f"  [FAIL] {x}")
        return 1

    mix = " · ".join(f"⑦{k}×{v}" for k, v in sorted(tiers.items()))
    print(f"FACTS_TIERS_RESULT PASS: {len(files)} 份 `*_FACTS.md` 全部自报档位（{mix}）· "
          f"出处路径逐条存在")
    return 0


def selftest() -> int:
    """四臂注入自证 —— ⭐ 「能过」≠「能抓」。"""
    arms: list[tuple[str, str, bool]] = []
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        docs = root / "docs"
        docs.mkdir()
        (root / "tools.py").write_text("x", encoding="utf-8")

        # 臂 A：不声明档位 ⇒ 红
        (docs / "A_FACTS.md").write_text("# t\n\n> 出处：`tools.py`\n", encoding="utf-8")
        arms.append(("A 不声明档位", "没有档位声明", True))
        # 臂 B：⑦c 但含糊（既无 tick、也无"不可复算"）⇒ 红
        (docs / "B_FACTS.md").write_text("# t\n\n> 档位 ⑦c `tools.py`\n", encoding="utf-8")
        arms.append(("B ⑦c 含糊", "不许含糊", True))
        # 臂 C：⑦c ＋ 逐字"不可从仓内复算" ⇒ **安静**（诚实边界是合法答案）
        (docs / "C_FACTS.md").write_text(
            f"# t\n\n> 档位 ⑦c `tools.py` · `generatedAtTick` 不可从仓内复算\n", encoding="utf-8")
        arms.append(("C ⑦c ＋ 如实声明不可复算", "", False))
        # 臂 D：出处引的路径不存在 ⇒ 红
        (docs / "D_FACTS.md").write_text("# t\n\n> 档位 ⑦a `tools/nope.py`\n", encoding="utf-8")
        arms.append(("D 出处路径不存在", "一个都不存在", True))

        bad = 0
        for name, want, want_red in arms:
            got: list[str] = []
            for f in sorted(docs.glob("*_FACTS.md")):
                got.extend(problems_for(f, docs))
            # 每臂只放行它自己的那个文件：把别的挪走
            got = [g for g in got if g.startswith(name[0] + "_FACTS.md")]
            is_red = bool(got)
            ok = (is_red == want_red) and (want in " ".join(got) if want_red else True)
            bad += not ok
            print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ 期望{'红' if want_red else '安静'}，"
                  f"实际：{(got[0].split('⇒')[-1][:60] if got else '（安静）')}")

        # 臂 E：人口下限
        empty = root / "emptydocs"
        empty.mkdir()
        import io
        from contextlib import redirect_stdout
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = run(empty)
        ok = rc == 1 and "人口下限" in buf.getvalue()
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] E 范围崩塌 ⇒ 期望红且含「人口下限」")

    print(f"FACTS_TIERS_SELFTEST {'PASS' if not bad else 'FAIL'}: arms=5 failed={bad}")
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

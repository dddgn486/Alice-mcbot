#!/usr/bin/env python3
"""门禁/工具清单普查（**生成物，⛔ 不手写**）—— 2026-10-02 文档整顿复检用。

> 背景：`docs/HANDOVER.md` 断点六十三 §H 的四档分类是**从 6–7 个工具归纳**出来的，⛔ **不是统计**；
> 而"42 个未被 `check-all` 直接调用的脚本"里**孤儿 / 二级调用没区分**。本脚本把这两条**变成可复算**。
>
> ⚠️ 本脚本**只打印**，⛔ 不判红、⛔ 不进 `check-all` —— 它是普查用的尺子，不是门禁。
>
> 用法：`python3 tools/gate-inventory.py [--csv]`
"""
from __future__ import annotations

import csv
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
CHECK_ALL = TOOLS / "check-all.sh"

# check-all.sh 里 `run_gate "标签" <命令>` / `run_headless_battery` 两种挂法
RUN_GATE = re.compile(r'^\s*run_gate\s+"([^"]+)"\s+(.*)$')
RUN_FN = re.compile(r'^\s*(run_[a-z_]+)\s*$')


def all_scripts() -> list[Path]:
    return sorted([*TOOLS.glob("*.sh"), *TOOLS.glob("*.py"), *TOOLS.glob("*.mjs")],
                  key=lambda p: p.name)


def read(p: Path) -> str:
    try:
        return p.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def invoked_by_check_all() -> tuple[dict[str, str], list[str]]:
    """返回 ({被调脚本名: check-all 里的标签}, [check-all 里的特殊挂法])。"""
    out: dict[str, str] = {}
    special: list[str] = []
    for line in read(CHECK_ALL).splitlines():
        m = RUN_GATE.match(line)
        if m:
            label, cmd = m.group(1), m.group(2)
            for tok in cmd.split():
                name = tok.rsplit("/", 1)[-1]
                if name in {p.name for p in all_scripts()}:
                    out[name] = label
            continue
        m2 = RUN_FN.match(line)
        if m2 and m2.group(1) != "run_gate":
            special.append(m2.group(1))
    return out, special


def who_calls(name: str) -> list[str]:
    """除 check-all 外，还有谁引用这个脚本名（= 二级调用者）。"""
    callers: list[str] = []
    pat = re.compile(r'(?<![\w.-])' + re.escape(name) + r'(?![\w-])')
    for p in [*TOOLS.glob("*"), *ROOT.glob("*.yml"), *ROOT.glob(".github/workflows/*.yml")]:
        if not p.is_file() or p.name == name or p == CHECK_ALL:
            continue
        if p.suffix not in (".sh", ".py", ".mjs", ".yml", ".md"):
            continue
        if pat.search(read(p)):
            callers.append(p.relative_to(ROOT).as_posix())
    return sorted(callers)


def kind_of(p: Path, text: str) -> str:
    """粗分类（判据写在名字/内容里，⛔ 不是人工判断）。"""
    n = p.name
    if n.startswith("check-") or n in ("redline-gates.py", "ref-integrity.py",
                                       "machine-map.py", "policy-map.py", "authz-map.py",
                                       "capability-list.py", "exec-record.py", "step-names.py",
                                       "station-mapping.py", "transfer-clock.py", "risk-surface.py",
                                       "goal-vocabulary.py", "fixture-hygiene.py", "failure-ratio.py",
                                       "task-dispatch-table.py", "task-retirement-map.py",
                                       "job-kind-view.py", "kernel-predicates.py"):
        return "GATE"
    if "*_RESULT" in text or "CHECK_RESULT" in text:
        return "GATE"
    if n in ("decisions-index.py",) or n.startswith(("gen-", "render-")) or "INDEX" in text:
        return "GEN"
    if n.startswith(("mirror-", "sync-", "install-", "make-", "jar-content-hash")):
        return "TOOL"
    return "?"


def main() -> int:
    ca, special = invoked_by_check_all()
    rows = []
    for p in all_scripts():
        t = read(p)
        rows.append({
            "name": p.name,
            "kind": kind_of(p, t),
            "lines": len(t.splitlines()),
            "in_check_all": "yes" if p.name in ca else "no",
            "label": ca.get(p.name, ""),
            "second_level_callers": "; ".join(who_calls(p.name)) if p.name not in ca else "",
        })
    if "--csv" in sys.argv:
        w = csv.DictWriter(sys.stdout, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
        return 0

    print(f"# 脚本普查（{len(rows)} 个）· check-all 直接挂载 {len(ca)} 个"
          + (f" ＋ 特殊挂法 {special}" if special else ""))
    print()
    for key, title in (("in_check_all", "① 被 check-all 直接调用"),
                       ("orphan", "② ⛔ 未被 check-all 调用")):
        sub = [r for r in rows if (r["in_check_all"] == "yes") == (key == "in_check_all")]
        print(f"## {title}（{len(sub)} 个）")
        for r in sub:
            tag = f"  ← 被 {r['second_level_callers']} 调用" if r["second_level_callers"] else ""
            print(f"  [{r['kind']:4}] {r['name']:42} {r['lines']:>5} 行"
                  f"{'  label=' + r['label'] if r['label'] and r['label'] != r['name'].rsplit('.',1)[0] else ''}{tag}")
        print()
    print("## 小结")
    from collections import Counter
    c_all = Counter(r["kind"] for r in rows)
    c_ca = Counter(r["kind"] for r in rows if r["in_check_all"] == "yes")
    for k in sorted(c_all):
        print(f"  {k:5}: 共 {c_all[k]:>2} · 其中 check-all 直挂 {c_ca.get(k, 0):>2}")
    orphans = [r for r in rows if r["in_check_all"] == "no"]
    called2 = [r for r in orphans if r["second_level_callers"]]
    print(f"\n  ⛔ 未被 check-all 调用 {len(orphans)} 个 ⇒ 其中 **有二级调用者 {len(called2)} 个**、"
          f"**疑似孤儿 {len(orphans) - len(called2)} 个**")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

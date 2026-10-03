#!/usr/bin/env python3
"""**生效-痕迹门禁**（`docs/AI_DECISIONS.md` 的 `生效` 条目必须带批准痕迹）。

## 为什么有它（`D-532` 排期里的 `C4`；2026-10-02 落地并**拆成独立门禁**）
用户 2026-10-02 裁定：`C4` 属「**新增**门禁」（🟢 自决档），而**改已有门禁的判据**属 🔴
⇒ 它**不能**塞进 `check-decisions-index`（那是已有门禁）⇒ 本文件独立成一道新门禁。

## 判据（机械、无需语义）
状态以 `生效` 开头的条目，其**条目体**里必须至少有**一处**批准痕迹：
`[用户确认: YYYY-MM-DD]` / 用户 ＋ 日期 ＋ 裁定词 / `用户逐字` / 用户「…」/ 日期 ＋ 用户。

⚠️ 只查 **`生效`** —— 它是"这条裁定现在作数"的**唯一**声称。
`已实施` / `已验收` 说的是"做完了 / 验过了"，**不是**"谁批的"，⛔ 不查它们。

## ⚠️⚠️ 为什么必须带基线（实测，不是偏好）
按字面挂 ⇒ **立刻红 33 处**，而它们**全是早期条目**（`D-001`…`D-067`、`D-208/209`）——
那些裁定当年是**靠会话记录**批准的，条目体内本来就没有痕迹。
⛔ 直接挂会逼出一件事：**为了消红而给历史编批注**（那比不挂更坏）。
⇒ 与 `decisions-index.py` 的断言②③ 同一套模子：**旧账进基线 ⇒ 只对「新出现的」红**；基线**只许变短**。

## ⭐ 这道门禁真正防的是什么
防**新增**一条"`生效` 但谁也说不清谁批的"裁定 —— 那正是本项目最大的病灶
（用「用户在别处做过一个选择」当引子，把 AI 的判断办成永久件）。
⚠️ 它**不**声称基线里那 33 条是假的：它只说"**这些条目的批准行为不在条目里**"。

## 用法
`python3 tools/check-effective-trace.py`（挂 `tools/check-effective-trace.sh` → `tools/check-all.sh`）
"""

from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: ⚠️ **复用** `decisions-index.py` 的解析（条目体口径必须与索引**同一份**，否则两处会分叉）
_DI = ROOT / "tools" / "decisions-index.py"
_spec = importlib.util.spec_from_file_location("_di_for_trace", _DI)
di = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(di)

#: 批准痕迹（表驱动，⛔ 不散落判断）
APPROVAL_TRACES: tuple[tuple[str, str], ...] = (
    (r"\[用户确认[:：]", "`[用户确认:]`"),
    (r"用户\s*20\d\d-\d\d-\d\d\s*(?:裁定|拍板|钦定|定案|否决|确认|同意|决定)", "用户＋日期＋裁定词"),
    (r"用户\s*逐字", "`用户逐字`"),
    (r"用户\s*[「『]", "用户「…」"),
    (r"20\d\d-\d\d-\d\d\s*用户", "日期＋用户"),
)

#: ⛔ **旧账基线：只许变短。** 2026-10-02 实测 = 33 条（`--baseline` 可重新打印）。
BASELINE_NO_TRACE: frozenset[str] = frozenset({
    "D-001", "D-002", "D-003", "D-004", "D-005", "D-006", "D-007", "D-008", "D-009",
    "D-010", "D-011", "D-012", "D-013", "D-014", "D-016", "D-017", "D-018", "D-020",
    "D-025", "D-026", "D-029", "D-030", "D-031", "D-032", "D-033", "D-034", "D-035",
    "D-036", "D-039", "D-050", "D-067", "D-208", "D-209",
})

#: ⚠️ 人口下限：判据至少得认出这么多"`生效` 条目"，否则说明它坏了（⛔ 不许"扫不到就报绿"）
EFFECTIVE_FLOOR = 30


def effective_entries() -> list[dict]:
    entries, _t, _l, _m = di.parse_decisions(di.read_text(di.DECISIONS))
    return [e for e in entries if e["status"].startswith("生效")]


def has_trace(block: str) -> bool:
    return any(re.search(pat, block) for pat, _n in APPROVAL_TRACES)


def run() -> int:
    eff = effective_entries()
    problems: list[str] = []
    if len(eff) < EFFECTIVE_FLOOR:
        problems.append(f"⛔ 判据**扫不到东西**了：只认出 {len(eff)} 条 `生效` < 下限 {EFFECTIVE_FLOOR}"
                        " ⇒ 状态口径变了？照这样它会对新条目**静默报绿**")
    no_trace = [e for e in eff if not has_trace(e["block"])]
    new = [e for e in no_trace if e["num"] not in BASELINE_NO_TRACE]
    for e in new:
        problems.append(f"⛔ **`{e['num']}` 声称 `生效`，但条目里找不到任何批准痕迹**（@行 {e['line']}）"
                        " ⇒ 读者无法判断**谁批的**。要么补 `[用户确认: YYYY-MM-DD]`，"
                        "要么把状态改成 `待定` / `裁定已落`")
    gone = sorted(BASELINE_NO_TRACE - {e["num"] for e in eff}, key=lambda s: int(s[2:]))
    if gone:
        print(f"  ⚠️ 基线里 {len(gone)} 条『`生效` 无痕迹』已消失（补了痕迹或改了状态 ⇒ 好事）："
              f"{', '.join(gone[:10])}{' …' if len(gone) > 10 else ''}")
    if problems:
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        print(f"EFFECTIVE_TRACE_RESULT FAIL: {len(problems)} 处"
              f"（基线 33 条只许变短；判据见 tools/check-effective-trace.py 头部）", file=sys.stderr)
        return 1
    print(f"EFFECTIVE_TRACE_RESULT PASS: `生效` {len(eff)} 条 · 无痕迹 {len(no_trace)} 条"
          f"（= 基线 {len(BASELINE_NO_TRACE)}，⛔ 只许变短）· **新增无痕 0 条**")
    return 0


def print_baseline() -> int:
    """`--baseline`：打印当前实测的"无痕迹"清单（基线只许变短 ⇒ 这个命令用来核对，⛔ 不自动改文件）。"""
    no = sorted((e["num"] for e in effective_entries() if not has_trace(e["block"])),
                key=lambda s: int(s[2:]))
    print(f"# 实测 `生效` 无痕迹 {len(no)} 条（2026-10-02）：")
    row: list[str] = []
    print("BASELINE_NO_TRACE: frozenset[str] = frozenset({")
    for x in no:
        row.append(f'"{x}",')
        if len(row) == 9:
            print("    " + " ".join(row)); row = []
    if row:
        print("    " + " ".join(row))
    print("})")
    return 0


def selftest() -> int:
    """⛔ 自证：正/负对照 —— 判据必须**能抓**，而不只是"能过"。"""
    cases = [
        ("- 状态：生效", "负：裸 `生效` ⇒ 无痕迹", False),
        ("- 状态：生效\n- 用户 2026-10-03 裁定：就这样。", "正：用户＋日期＋裁定词", True),
        ("- 状态：生效\n- 内容。`[用户确认: 2026-10-03]`", "正：`[用户确认:]`", True),
        ("- 状态：生效\n- 用户逐字：「可以」", "正：`用户逐字`", True),
        ("- 状态：生效\n- 用户「可以」", "正：用户「…」", True),
        ("- 状态：生效\n- 2026-10-03 用户说行", "正：日期＋用户", True),
    ]
    bad = 0
    for block, name, want in cases:
        got = has_trace(block)
        ok = got == want
        bad += 0 if ok else 1
        print(f"  {'✅' if ok else '❌'} {name}\n       has_trace={got} want={want}")
    eff = effective_entries()
    floor_ok = len(eff) >= EFFECTIVE_FLOOR
    print(f"  {'✅' if floor_ok else '❌'} 人口下限：认出 `生效` {len(eff)} 条 ≥ {EFFECTIVE_FLOOR}")
    bad += 0 if floor_ok else 1
    #: ⚠️ 过滤臂：`已实施` / `已验收` **不该**进这道门禁（它们说的不是"谁批的"）
    other = [e for e in di.parse_decisions(di.read_text(di.DECISIONS))[0]
             if not e["status"].startswith("生效")]
    filt_ok = all(not e["status"].startswith("生效") for e in other) and len(other) > 0
    print(f"  {'✅' if filt_ok else '❌'} 过滤臂：另有 {len(other)} 条非 `生效` 状态 ⇒ 不进本门禁")
    bad += 0 if filt_ok else 1
    print(f"\n自检：{len(cases) + 2 - bad}/{len(cases) + 2} 过")
    return 1 if bad else 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(selftest())
    if "--baseline" in sys.argv:
        raise SystemExit(print_baseline())
    raise SystemExit(run())

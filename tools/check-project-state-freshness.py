#!/usr/bin/env python3
"""门禁：**`AI_PROJECT_STATE.md` 的保鲜期**（2026-10-05 立；出处 = 咨询回执 `001` 的 `T4`）。

⭐⭐ **为什么有它**：`§D′-1` ④ 的**生命周期格**逐字写着
「**`AI_PROJECT_STATE.md`** = 超过**保鲜期**未更新 ⇒ 失效」，
⛔ 而全草案搜「保鲜期」**只命中那一格** ⇒ ⭐ **「超过保鲜期」当时不可判**（无天数、无门禁、无口径）。
⇒ 用户 2026-10-05 批：「**保鲜期 = 14 天**」＋「必须有 `更新时间`，超过 **14 天**未更新 ⇒ **门禁警告**」。

⚠️ ⭐ **字段名以草案为准，⛔ 不以回执为准**：回执写的是 `最后更新:`
（`T4` 原文「必须有 `最后更新: YYYY-MM-DD`」），而 ⭐ **草案 `§D′-1` ④ 的载体格逐字写着
「头部必有 `更新时间`」**，实测 `AI_PROJECT_STATE.md` 用的**也是** `更新时间`
⇒ ⭐ 按本仓既定的「**门禁没有权利否决草案**」⇒ 本门禁解析 **`更新时间`**。

判据（**三态**，⛔ 不是两态，⭐ 这是本门禁最要紧的一处设计）：

| 情形 | 退出码 | 为什么 |
|---|---|---|
| **解析不到** `更新时间：YYYY-MM-DD` | **1（红）** | ⭐ 「**扫不到就报绿**」是本项目最贵那族错 ⇒ ⛔ **必须响亮失败**（字段改名／格式变坏都要当场红） |
| **超过 `14` 天** | ⭐ **2（警告）** | ⭐ 回执逐字是「门禁**警告**」，⛔ **不是红** —— 状态陈旧该**醒目**，⛔ 但不该**拦住施工**（它坏的是"读起来准不准"，⛔ 不是"东西对不对"） |
| **未超过** | 0（绿） | —— |

⚠️ **它判不了什么**（如实记）：⛔ **只判"多久没更新"**，⛔ **不判内容对不对** ——
「`现在在哪` 写得准不准」是读它的人的活，⭐ 没有任何机械判据（⛔ 本门禁不假装能判）。

⭐ `--selftest`：**六臂**注入自证（⭐ 「能过」≠「能抓」）—— 含**边界臂**（恰好 14 天）与**两条响亮失败臂**。
"""

from __future__ import annotations

import argparse
import re
import tempfile
import sys
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / "docs" / "AI_PROJECT_STATE.md"

#: ⭐ 保鲜期（天）。**用户 2026-10-05 裁：14 天**（出处 = 咨询回执 `001` 的 `T4`）。
#: ⚠️ 这个值**是裁定来的**，⛔ 不是估的 ⇒ 改它**要走裁定**（⛔ 不许就地放宽）。
FRESHNESS_DAYS = 14

#: ⚠️ **只认行首**的 `更新时间：`（⭐ 全角／半角冒号都收）。
#: ⛔ 不认正文里顺带提到的日期 —— 那会让「改一句话」变成「刷新状态」，⭐ 那就白判了。
STAMP = re.compile(r"^\s*更新时间\s*[：:]\s*(\d{4})-(\d{2})-(\d{2})\s*$", re.MULTILINE)


def check(state: Path = STATE, today: date | None = None) -> tuple[int, str]:
    """→ `(退出码, 一句话读数)`。⭐ `state` / `today` **可换** ⇒ 本函数**能注入自证**。"""
    today = today or date.today()
    if not state.is_file():
        return 1, f"⛔ `{state}` **不存在** ⇒ 保鲜期判不了（⭐ ⛔ 不许「扫不到就报绿」）"
    m = STAMP.search(state.read_text(encoding="utf-8", errors="replace"))
    if m is None:
        return 1, (f"⛔ `{state.name}` 里**找不到**行首的 `更新时间：YYYY-MM-DD` "
                   f"⇒ ⭐ 字段被改名／格式变坏 ⇒ 保鲜期**静默失效**（这正是本门禁要拦的那族错）")
    try:
        stamp = date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return 1, f"⛔ `更新时间` 的日期**不合法**：`{m.group(0).strip()}`"
    age = (today - stamp).days
    if age > FRESHNESS_DAYS:
        return 2, (f"⚠️ `{state.name}` 已 **{age} 天**未更新"
                   f"（更新时间 **{stamp.isoformat()}** · 保鲜期 **{FRESHNESS_DAYS} 天**）"
                   f" ⇒ ⭐ 它自称「**唯一一份「现在在哪」**」⇒ 那个功能**已失效**（`§D′-1` ④ 的生命周期格）")
    return 0, (f"`{state.name}` 保鲜期 OK：**{age} 天**前更新"
               f"（更新时间 **{stamp.isoformat()}** · 保鲜期 **{FRESHNESS_DAYS} 天**）")


def selftest() -> int:
    """**六臂**注入自证。⭐ 每条都喂一棵临时树，⛔ 不动真文件。"""
    arms: list[tuple[str, bool]] = []
    with tempfile.TemporaryDirectory() as td:
        r = Path(td)
        f = r / "AI_PROJECT_STATE.md"
        today = date(2026, 10, 5)

        def arm(name: str, body: str | None, want: int) -> None:
            if body is None:
                f.unlink(missing_ok=True)
            else:
                f.write_text(body, encoding="utf-8")
            got, msg = check(f, today)
            arms.append((f"{name} ⇒ 期望 {want}，实得 {got}", got == want))
            if got != want:
                print(f"      ↳ {msg}")

        arm("A 新鲜（0 天）", "更新时间：2026-10-05\n", 0)
        #: ⭐ **边界臂**：恰好 14 天 ⇒ ⛔ **不许红**（`>` 不是 `>=`，⭐ 边界写错是本族最常见的一处）
        arm("B 边界（恰好 14 天）⇒ 仍绿", "更新时间：2026-09-21\n", 0)
        arm("C 超一天（15 天）⇒ 警告", "更新时间：2026-09-20\n", 2)
        arm("D 没有 `更新时间` 行 ⇒ 红", "# 标题\n\n没有那行\n", 1)
        arm("E 日期不合法 ⇒ 红", "更新时间：2026-13-45\n", 1)
        arm("F 文件不存在 ⇒ 红", None, 1)

    bad = 0
    for name, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    print(f"PROJECT_STATE_FRESHNESS_SELFTEST {'PASS' if not bad else 'FAIL'}"
          f": arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="AI_PROJECT_STATE.md 保鲜期门禁")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    code, msg = check()
    #: ⚠️ 三态：0 = PASS · 2 = WARN（check-all 读作「未执行」档，⭐ 本门禁有意借用它表达"警告"）· 1 = FAIL
    tag = {0: "PASS", 2: "WARN", 1: "FAIL"}[code]
    print(f"PROJECT_STATE_FRESHNESS_RESULT {tag}: {msg}")
    return code


if __name__ == "__main__":
    sys.exit(main())

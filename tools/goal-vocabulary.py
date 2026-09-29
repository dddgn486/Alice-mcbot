#!/usr/bin/env python3
"""`G-P1`（`D-267 F2`，2026-09-17 用户裁定）：**动作词汇必须与白名单一致**。

问题（`survey/16 §5.4` + `survey/17` 复核）：动作名活在**两处**且各自维护 ——
① `GoalDirector.VOCABULARY`（给 LLM 的 system prompt 文本）；
② `GoalAction.parse` 的 `case` 白名单（真正决定"认不认"）。
今天两边**恰好都是 6 个**，但**没有一个断言**把它们锁在一起 ⇒ 哪天只改一处，
LLM 就会"被允许说一个不存在的动作"或"有一个动作永远说不出来"，而且**没有任何东西会响**。

本规则就是那个断言：两边集合必须相等（多一个少一个都红）。

---

## ⭐ `4a` 柱③（用户 2026-09-29 裁「按 `O63` 的形状做」）：**`start_job` 的 `kind` 也要锁**

`O62` §⑤ 实测的同族缺口：`VOCABULARY` 里的 **`"kind":"…"`** 与 `GoalAction.parseStartJob` 的
`switch (kind)` **之间没有任何断言**（本文件此前只解析 `"action"`）⇒ 改一处忘另一处，
**LLM 的合法选择被静默拒**（症状 = `Refused("unknown_job_kind:…")`，而构建全绿）。

两处与"动作"那条的**唯一差别**：`kind` 有**别名**（今天 `region` = `region_lumber` 的别名）
⇒ 所以不是"集合相等"，而是：

* **prompt 广告的每个 kind 都必须被接受**（`advertised ⊆ accepted`）—— 否则 LLM 照着 prompt
  说一个**必然被拒**的 kind；
* **接受面里多出来的**必须在 `KIND_ALIASES` 里**具名登记＋写理由**（并且**双向**：登记项必须
  仍是被接受的 case，否则红 ⇒ 影子别名不允许存在）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DECISION = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "decision"

#: ⚠️ **`kind` 别名具名登记**（`4a` 柱③）。**双向**：条目必须仍是 `switch (kind)` 的 case，否则红。
KIND_ALIASES = {
    "region":
        "`region_lumber` 的**别名**：prompt 只广告 `region_lumber`（`VOCABULARY` 第 3 条），"
        "`region` 留给手输/兼容路径。⛔ 不许静默存在 —— 要加别名必须登记在这里并写理由。",
}


def vocabulary_block() -> str:
    text = (DECISION / "GoalDirector.java").read_text(encoding="utf-8")
    start = text.index("public static final String VOCABULARY")
    end = text.index('"""', text.index('"""', start) + 3)
    return text[start:end]


def vocabulary_actions() -> set[str]:
    return set(re.findall(r'"action"\s*:\s*"([a-z_]+)"', vocabulary_block()))


def vocabulary_job_kinds() -> set[str]:
    """`VOCABULARY` 里**`start_job` 那一行**的 `kind`（⚠️ 按行取，否则会把
    `maintain_tool` 的 `"kind":"pickaxe"`（**工具** kind，另一个概念）混进来）。"""
    kinds: set[str] = set()
    for line in vocabulary_block().splitlines():
        if '"action":"start_job"' not in line:
            continue
        kinds.update(re.findall(r'"kind"\s*:\s*"([a-z_]+)"', line))
    return kinds


def _case_labels(block: str) -> set[str]:
    """取 `switch` 块里**全部** `case` 标签的字面量。

    ⚠️ **必须按标签列表取**，不能写 `case\\s+"([a-z_]+)"` —— 多标签 `case "a", "b" ->` 里
    第二个标签前面没有 `case` ⇒ 单标签正则会**静默漏掉它**（本文件升级 `4a` 柱③ 时**实测踩到**：
    `region` 被漏掉，于是别名登记被判成"陈旧条目"、门禁假红）。
    """
    labels: set[str] = set()
    for chunk in re.findall(r"\bcase\s+([^->]+?)\s*->", block, re.S):
        labels.update(re.findall(r'"([a-z_]+)"', chunk))
    return labels


def whitelist_actions() -> set[str]:
    text = (DECISION / "GoalAction.java").read_text(encoding="utf-8")
    start = text.index("switch (action)")
    end = text.index("default ->", start)
    return _case_labels(text[start:end])


def accepted_job_kinds() -> set[str]:
    """`GoalAction.parseStartJob` 里 `switch (kind)` 接受的 case（含一个 case 多标签的写法）。"""
    text = (DECISION / "GoalAction.java").read_text(encoding="utf-8")
    start = text.index("switch (kind)")
    end = text.index("default ->", start)
    return _case_labels(text[start:end])


def main() -> int:
    vocab = vocabulary_actions()
    allowed = whitelist_actions()
    kinds = vocabulary_job_kinds()
    accepted = accepted_job_kinds()
    problems = []
    if not vocab:
        problems.append("没能从 VOCABULARY 里解析出任何动作（prompt 改了写法？同步本规则）")
    if not allowed:
        problems.append("没能从 GoalAction.parse 里解析出任何 case（改写？同步本规则）")
    for name in sorted(vocab - allowed):
        problems.append(f"prompt 允许说 `{name}`，但白名单**不认** ⇒ LLM 会一直被 Refused(unknown_action)")
    for name in sorted(allowed - vocab):
        problems.append(f"白名单有 `{name}`，但 prompt **没告诉** LLM ⇒ 这个动作永远说不出来")

    # ---- `start_job` 的 `kind`（`4a` 柱③，`O62` §⑤ 的缺口）----
    if not kinds:
        problems.append("没能从 VOCABULARY 的 `start_job` 行里解析出任何 `kind`"
                        "（prompt 改了写法？同步本规则）")
    if not accepted:
        problems.append("没能从 `GoalAction.parseStartJob` 的 `switch (kind)` 里解析出任何 case"
                        "（改写？同步本规则）")
    for name in sorted(kinds - accepted):
        problems.append(f"prompt 广告了 `kind={name}`，但 `switch (kind)` **不认**"
                        " ⇒ LLM 照着 prompt 说的合法选择会被静默拒"
                        "（`Refused(\"unknown_job_kind:…\")`，而构建全绿）")
    for name in sorted(accepted - kinds - set(KIND_ALIASES)):
        problems.append(f"`switch (kind)` 接受了 `{name}`，但 prompt **没广告**、也**没登记为别名**"
                        " ⇒ 要么它是漏登记的 kind，要么该补进 `KIND_ALIASES` 并写理由")
    for name, reason in KIND_ALIASES.items():
        if name not in accepted:
            problems.append(f"`KIND_ALIASES` 里的 `{name}` 不再是 `switch (kind)` 的 case"
                            f" ⇒ 陈旧条目（理由原文：{reason[:40]}…）")
        elif not reason.strip():
            problems.append(f"`KIND_ALIASES` 里的 `{name}` 没写理由 ⇒ 别名必须具名说明")

    for line in problems:
        print(f"[G·动作词汇] {line}")
    ok = not problems
    print(f"GOAL_VOCABULARY_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"prompt={sorted(vocab)} / 白名单={sorted(allowed)}"
          f"（G-P1 = 两处动作集合必须相等：加动作要同时改 prompt 与白名单）"
          f" ｜ ⭐ `start_job` kind：广告={sorted(kinds)} 接受={sorted(accepted)}"
          f" 别名登记={sorted(KIND_ALIASES)}（`4a` 柱③）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

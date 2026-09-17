#!/usr/bin/env python3
"""G-P1（D-267 F2，2026-09-17 用户裁定）：**动作词汇必须与白名单一致**。

问题（`survey/16 §5.4` + `survey/17` 复核）：动作名活在**两处**且各自维护 ——
① `GoalDirector.VOCABULARY`（给 LLM 的 system prompt 文本）；
② `GoalAction.parse` 的 `case` 白名单（真正决定"认不认"）。
今天两边**恰好都是 6 个**，但**没有一个断言**把它们锁在一起 ⇒ 哪天只改一处，
LLM 就会"被允许说一个不存在的动作"或"有一个动作永远说不出来"，而且**没有任何东西会响**。

本规则就是那个断言：两边集合必须相等（多一个少一个都红）。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DECISION = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "decision"


def vocabulary_actions() -> set[str]:
    text = (DECISION / "GoalDirector.java").read_text(encoding="utf-8")
    start = text.index("public static final String VOCABULARY")
    end = text.index('"""', text.index('"""', start) + 3)
    return set(re.findall(r'"action"\s*:\s*"([a-z_]+)"', text[start:end]))


def whitelist_actions() -> set[str]:
    text = (DECISION / "GoalAction.java").read_text(encoding="utf-8")
    start = text.index("switch (action)")
    end = text.index("default ->", start)
    return set(re.findall(r'case\s+"([a-z_]+)"\s*->', text[start:end]))


def main() -> int:
    vocab = vocabulary_actions()
    allowed = whitelist_actions()
    problems = []
    if not vocab:
        problems.append("没能从 VOCABULARY 里解析出任何动作（prompt 改了写法？同步本规则）")
    if not allowed:
        problems.append("没能从 GoalAction.parse 里解析出任何 case（改写？同步本规则）")
    for name in sorted(vocab - allowed):
        problems.append(f"prompt 允许说 `{name}`，但白名单**不认** ⇒ LLM 会一直被 Refused(unknown_action)")
    for name in sorted(allowed - vocab):
        problems.append(f"白名单有 `{name}`，但 prompt **没告诉** LLM ⇒ 这个动作永远说不出来")
    for line in problems:
        print(f"[G·动作词汇] {line}")
    ok = not problems
    print(f"GOAL_VOCABULARY_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"prompt={sorted(vocab)} / 白名单={sorted(allowed)}"
          f"（G-P1 = 两处动作集合必须相等：加动作要同时改 prompt 与白名单）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""`G3`（2026-09-21 用户裁定「这不是小事」）：**架构红线必须带门禁指针，或带复核触发的「未门禁」标记**。

背景（可复核的计数）：`AGENTS.md` §不可悄悄改变的架构边界 有 6 条红线，而 `tools/check-*.{sh,py}` 有 18 个——
**只有 D-076 一条真正被门禁覆盖**，其余 5 条**完全靠人记得**。本次事故（`D-374`：一格高夹缝在整张图里
没有入边）正好落在**零门禁**的那条（`D-036` Alice = Baritone 兼容内核）上：Baritone 有目的地头位的破坏能力，
Alice 丢了它、而且**从未登记偏离**，而没有任何东西会因此变红。

一条红线只有三种合法状态（本项目「规则准入尺子」的可执行化）：
  1. `[gate: <脚本>]` —— 有可执行门禁（点名脚本必须真实存在，否则是**假指针**）；
  2. `[未门禁: <原因>；复核触发: <条件>]` —— 承认没有门禁，但**必须写明什么时候回头补**；
  3. ❌ **什么都没有** —— 这一条正是本门禁要判红的：它在文档里看起来和「有门禁」一模一样。

反模式（本门禁自己也要防）：**别把「未门禁」当成免死金牌** —— 没有 `复核触发:` 的 `[未门禁: …]` 一律判红。
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
AGENTS = ROOT / "AGENTS.md"
SECTION = "## 不可悄悄改变的架构边界"

GATE = re.compile(r"\[gate:\s*([^\]]*)\]")
UNENFORCED = re.compile(r"\[未门禁:\s*([^\]]*)\]")
SCRIPT = re.compile(r"([A-Za-z0-9_.-]+\.(?:sh|py))")


def bullets(text: str) -> list[tuple[str, str]]:
    """返回该小节里的 (首行摘要, 整块文本)，按出现顺序。"""
    lines = text.split("\n")
    try:
        start = next(i for i, line in enumerate(lines) if line.strip() == SECTION)
    except StopIteration:
        raise SystemExit(f"在 AGENTS.md 里找不到小节 {SECTION!r}（结构变了 ⇒ 本门禁要跟着改）")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    result: list[tuple[str, str]] = []
    current: list[str] = []
    for line in lines[start + 1:end]:
        if line.startswith("- "):
            if current:
                result.append((current[0], "\n".join(current)))
            current = [line]
        elif current and line.strip():
            current.append(line)
    if current:
        result.append((current[0], "\n".join(current)))
    return result


def main() -> int:
    text = AGENTS.read_text(encoding="utf-8")
    problems: list[str] = []
    gated = 0
    unenforced = 0
    for index, (first, block) in enumerate(bullets(text), start=1):
        label = first[2:62]
        gate = GATE.search(block)
        unenf = UNENFORCED.search(block)
        if not gate and not unenf:
            problems.append(f"红线#{index}（{label}）既没有 `[gate: …]` 也没有 `[未门禁: …]`"
                            " ⇒ 它在文档里与「有门禁」长得一模一样，但没有任何东西会在它被破坏时变红")
            continue
        if gate:
            gated += 1
            names = SCRIPT.findall(gate.group(1))
            if not names:
                problems.append(f"红线#{index}（{label}）的 `[gate: …]` 里没有一个脚本名"
                                "（形如 `check-xxx.sh`）⇒ 指针不可解析")
            for name in names:
                if not (ROOT / "tools" / name).exists():
                    problems.append(f"红线#{index}（{label}）指向的门禁 `tools/{name}` **不存在**"
                                    "（假指针：读起来像有门禁，实际没有）")
        if unenf:
            unenforced += 1
            if "复核触发" not in unenf.group(1):
                problems.append(f"红线#{index}（{label}）的 `[未门禁: …]` 没有写 `复核触发:`"
                                " ⇒ 「未门禁」会变成免死金牌（永远不回头补）")
        if gate and unenf:
            problems.append(f"红线#{index}（{label}）同时标了 `[gate: …]` 与 `[未门禁: …]`"
                            " ⇒ 状态自相矛盾，读者无法判断到底有没有门禁")
    for line in problems:
        print(f"[G3·红线门禁] {line}")
    total = len(bullets(text))
    ok = not problems
    print(f"REDLINE_GATE_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"红线={total} 有门禁={gated} 未门禁={unenforced} problems={len(problems)}"
          f"（未门禁条目必须带「复核触发」，门禁指针必须指向真实存在的 tools/<脚本>）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

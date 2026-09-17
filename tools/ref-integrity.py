#!/usr/bin/env python3
"""R-P1（队列第⑦项，2026-09-17）：**引用的 `文件:行` 不许过期**（硬门禁只抓"必然过期"那一类）。

背景：2026-09-17 一次勘测分诊就抓到 **16 处**勘测件的路径/行号/数字错误（`task/craft/RecipeDump.java` 不存在、
`STATION_BY_TYPE` 写 18 条实际 11 条、`BotManager:1568-1584` 行号错配、`/workspace/Alice-mcbot` 路径……）。
人工核对不可持续 ⇒ 把**机器能判的那一类**变成门禁：

* **硬失败（FAIL）**：引用指向**存在的文件**，但**行号超出该文件长度** ⇒ 引用**必然过期**（文件改了/引错了）。
* **提示（不失败）**：引用指向**不存在的文件**。这一类**不能硬红** —— 因为大量历史记录**合法地**引用
  已删除/已移动的文档（`docs/SUPERVISION_PROTOCOL.md` 等 21 类），而 `survey/` 属于勘测员，
  指向未实现文件（提案）也是常态。所以只打印计数与前几例，供分诊时人工核对。

解析根：仓库根、`src/main/java`、`docs`、`tools`，以及 Baritone 参照仓
（`/home/fb486/projects/reference/baritone`，见 `AGENTS.md` 的内核路线）。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROOTS = [ROOT, ROOT / "src" / "main" / "java", ROOT / "docs", ROOT / "tools",
         Path("/home/fb486/projects/reference/baritone")]
REF = re.compile(r'`([A-Za-z0-9_./+-]+\.(?:java|py|sh|md|csv|json|mcfunction))(?::(\d+)(?:-(\d+))?)?`')


SUFFIXES = (".java", ".py", ".sh", ".md", ".csv", ".json", ".mcfunction")
_BY_NAME: dict[str, list[Path]] | None = None


def _index_by_name() -> dict[str, list[Path]]:
    """全仓基名索引（只收代码/工具/文档类，供**裸文件名**引用解析）。"""
    global _BY_NAME
    if _BY_NAME is not None:
        return _BY_NAME
    index: dict[str, list[Path]] = {}
    # ⚠️ **必须包含 Baritone 参照仓**：`MovementHelper.java` 等名字在两边都有，
    # 只索引本仓会把它误判成"我们的文件"（行号立刻超界 = 假阳性，2026-09-17 实测）。
    # 多命中 ⇒ 歧义 ⇒ 跳过（宁可漏判，不要误伤）。
    for root in (ROOT / "src", ROOT / "tools", ROOT / "docs",
                 Path("/home/fb486/projects/reference/baritone")):
        if not root.exists():
            continue
        for f in root.rglob("*"):
            if f.is_file() and f.suffix in SUFFIXES:
                index.setdefault(f.name, []).append(f)
    _BY_NAME = index
    return index


def resolve(path: str) -> Path | None:
    for root in ROOTS:
        candidate = root / path
        if candidate.exists() and candidate.is_file():
            return candidate
    # 裸文件名（`Foo.java:12` 这种最常见的写法）：**基名唯一**才认，歧义/找不到一律跳过
    if "/" not in path:
        hits = _index_by_name().get(path, [])
        if len(hits) == 1:
            return hits[0]
    return None


def line_count(path: Path) -> int:
    return len(path.read_text(encoding="utf-8", errors="ignore").splitlines())


def main() -> int:
    hard: list[str] = []
    missing: list[str] = []
    checked = 0

    for target in list((ROOT / "docs").rglob("*.md")) + [ROOT / "AGENTS.md"]:
        for m in REF.finditer(target.read_text(encoding="utf-8", errors="ignore")):
            path, ln, ln2 = m.group(1), m.group(2), m.group(3)
            hit = resolve(path)
            if hit is None:
                if path.startswith(("src/", "tools/", "docs/")) or "/" not in path:
                    missing.append(f"{target.relative_to(ROOT)} → 找不到/歧义 `{path}`")
                continue
            if not ln:
                continue
            checked += 1
            worst = max(int(ln), int(ln2) if ln2 else 0)
            total = line_count(hit)
            if worst > total:
                hard.append(f"{target.relative_to(ROOT)} → `{path}:{worst}` 超界（该文件只有 {total} 行）")

    for line in hard:
        print(f"[R·引用过期] {line}")
    for line in missing[:5]:
        print(f"[R·提示（不失败）] {line}")
    if len(missing) > 5:
        print(f"[R·提示（不失败）] …另有 {len(missing) - 5} 处指向不存在文件的引用（历史记录/提案，不阻塞）")

    ok = not hard
    print(f"REF_INTEGRITY_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"校验了 {checked} 处带行号的引用 / 行号超界={len(hard)} / 文件不存在={len(missing)}（仅提示）"
          f"（R-P1 = 指向存在文件的 `文件:行` 不得超出该文件长度）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

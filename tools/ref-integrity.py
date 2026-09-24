#!/usr/bin/env python3
"""R-P1（队列第⑦项，2026-09-17）：**引用的 `文件:行` 不许过期**（硬门禁只抓"必然过期"那一类）。

背景：2026-09-17 一次勘测分诊就抓到 **16 处**勘测件的路径/行号/数字错误（`task/craft/RecipeDump.java` 不存在、
`STATION_BY_TYPE` 写 18 条实际 11 条、`BotManager:1568-1584` 行号错配、`/workspace/Alice-mcbot` 路径……）。
人工核对不可持续 ⇒ 把**机器能判的那一类**变成门禁：

* **硬失败（FAIL）**：引用指向**存在的文件**，但**行号超出该文件长度** ⇒ 引用**必然过期**（文件改了/引错了）。
* **提示（不失败）**：引用指向**不存在的文件**。这一类**不能硬红** —— 因为大量历史记录**合法地**引用
  已删除/已移动的文档（`docs/SUPERVISION_PROTOCOL.md` 等 21 类），而 `survey/` 属于勘测员，
  指向未实现文件（提案）也是常态。所以只打印计数与前几例，供分诊时人工核对。

**扫描范围**：`docs/**/*.md` + `AGENTS.md`（**不含 `survey/`** —— 勘测目录「只增不改」，红它无法修复）。

**两类引用都查**（2026-09-18 补第二类）：
1. `文件.java:行`（带扩展名）；
2. ⚠️ `Class.method:行`（**不带扩展名**）—— 旧版正则**结构上匹配不到**它，所以是一处**已实测的盲区**：
   补上前 `docs` 范围内 **310 处**类限定引用里藏着 **1 处必然过期**（`GoalAction.parse:351` 而该文件只有 313 行）。
   解析口径与裸文件名一致：`Class.java` **基名唯一**才认，歧义/找不到一律跳过。

解析根：仓库根、`src/main/java`、`docs`、`tools`，以及 Baritone 参照仓
（见 `AGENTS.md` 的内核路线；路径解析见 {@link _baritone_roots}）。

**裸文件名（含 `Class.method:行`）的判据 = 候选里的最大值**（2026-09-24 修正）：
`MovementHelper.java` 这类名字**两边都有**（我们有 474 行、Baritone 有 863 行），而本仓文档大量
引用的是**Baritone 的行号**。旧口径"基名唯一才认，歧义/找不到一律跳过"**依赖参照仓真的被索引到** ——
云端把参照仓放在 `$HOME/reference/baritone-1.20.1`（不是写死的本地 WSL 路径）⇒ 索引不到 ⇒
我们自己的同名文件成了唯一命中 ⇒ **26 条 Baritone 引用被误判成"行号超界"**（2026-09-24 实测）。
⇒ 现在：裸基名**取全部候选**，只有"**候选都装不下这个行号**"才算过期（仍然"宁可漏判、不要误伤"，
但不再取决于参照仓在不在）；路径限定引用（含 `/`）仍按**精确路径**单文件判定。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROOTS = [ROOT, ROOT / "src" / "main" / "java", ROOT / "docs", ROOT / "tools"]


def _baritone_roots() -> list[Path]:
    """Baritone 参照仓的候选位置。

    ⚠️ 写死本地 WSL 路径会让**云端**（`$HOME/reference/baritone-1.20.1`）索引不到参照仓 ⇒
    两个仓都有的 `MovementHelper.java` 只剩我们那份 ⇒ Baritone 行号被判"超界"（假阳性，实测 26 条）。
    ⇒ 允许显式 `ALICE_BARITONE_DIR`，并按"本地固定路径 → `$HOME/reference/baritone*`"依次兜底。
    """
    import os
    roots: list[Path] = []
    explicit = os.environ.get("ALICE_BARITONE_DIR")
    if explicit:
        roots.append(Path(explicit))
    roots.append(Path("/home/fb486/projects/reference/baritone"))
    home_ref = Path.home() / "reference"
    roots.append(home_ref / "baritone")
    roots.extend(sorted(p for p in home_ref.glob("baritone*") if p.is_dir()))
    seen: list[Path] = []
    for r in roots:
        if r.exists() and r not in seen:
            seen.append(r)
    return seen


BARITONE_ROOTS = _baritone_roots()
ROOTS = [*ROOTS, *BARITONE_ROOTS]
REF = re.compile(r'`([A-Za-z0-9_./+-]+\.(?:java|py|sh|md|csv|json|mcfunction))(?::(\d+)(?:-(\d+))?)?`')
# 类限定引用（`Class.method:123`）：**没有扩展名** ⇒ 上面的正则看不到（旧版的实测盲区）。
# 只在 `` ` `` 包裹内匹配，且必须紧跟 `:数字`，避免把正文里的 `foo.bar` 当引用。
REF_METHOD = re.compile(r'`([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*):(\d+)(?:-(\d+))?`')


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
    for root in (ROOT / "src", ROOT / "tools", ROOT / "docs", *BARITONE_ROOTS):
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


def overflows(rel: str, label: str, worst: int, candidates: list[Path]) -> str | None:
    """裸基名引用：**候选都装不下**才算过期（见文件头；这是"宁可漏判、不要误伤"的落地形状）。"""
    counts = [(line_count(c), c) for c in candidates]
    if not counts:
        return None
    if worst <= max(n for n, _ in counts):
        return None
    detail = "、".join(f"{c.name}={n}行" for n, c in sorted(counts, key=lambda x: -x[0])[:4])
    return f"{rel} → `{label}:{worst}` 超界（候选都装不下：{detail}）"


def main() -> int:
    hard: list[str] = []
    missing: list[str] = []
    checked = 0
    checked_method = 0

    targets = list((ROOT / "docs").rglob("*.md")) + [ROOT / "AGENTS.md"]
    for target in targets:
        rel = target.relative_to(ROOT)
        text = target.read_text(encoding="utf-8", errors="ignore")
        for m in REF.finditer(text):
            path, ln, ln2 = m.group(1), m.group(2), m.group(3)
            if "/" in path:
                # 路径限定：精确解析，单文件严格判定（语义不变）
                hit = resolve(path)
                if hit is None:
                    if path.startswith(("src/", "tools/", "docs/")):
                        missing.append(f"{rel} → 找不到/歧义 `{path}`")
                    continue
                candidates = [hit]
            else:
                # 裸基名：取**全部候选**（我们 + Baritone 参照仓），候选都装不下才红
                candidates = _index_by_name().get(path, [])
                if not candidates:
                    missing.append(f"{rel} → 找不到/歧义 `{path}`")
                    continue
            if not ln:
                continue
            checked += 1
            worst = max(int(ln), int(ln2) if ln2 else 0)
            problem = overflows(rel, path, worst, candidates)
            if problem:
                hard.append(problem)

        # 第二类：`Class.method:行`（见文件头 §两类引用）。基名唯一才认，歧义/找不到一律跳过。
        for m in REF_METHOD.finditer(text):
            cls, ln, ln2 = m.group(1), m.group(3), m.group(4)
            hits = _index_by_name().get(cls + ".java", [])
            if not hits:
                continue   # 找不到（历史/提案）⇒ 与第一类同口径，不硬红
            checked += 1
            checked_method += 1
            worst = max(int(ln), int(ln2) if ln2 else 0)
            problem = overflows(rel, cls + "." + m.group(2), worst, hits)
            if problem:
                hard.append(problem)

    for line in hard:
        print(f"[R·引用过期] {line}")
    for line in missing[:5]:
        print(f"[R·提示（不失败）] {line}")
    if len(missing) > 5:
        print(f"[R·提示（不失败）] …另有 {len(missing) - 5} 处指向不存在文件的引用（历史记录/提案，不阻塞）")

    ok = not hard
    print(f"REF_INTEGRITY_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"校验了 {checked} 处带行号的引用（其中类限定 `Class.method:行` {checked_method} 处）/ "
          f"行号超界={len(hard)} / 文件不存在={len(missing)}（仅提示）"
          f"（R-P1 = 指向存在文件的 `文件:行` 不得超出该文件长度）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

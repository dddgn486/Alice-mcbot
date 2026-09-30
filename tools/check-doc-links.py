#!/usr/bin/env python3
"""`{@link}` **符号存在性**门禁（`D-512` ⓒ 的建议；刀 5 落地）。

## 为什么需要它（一条活了两个月的假断言）

`bot/ManualTestLock.java:16` 的 javadoc 逐字写着「**唯一**的绕过口是
`{@link BotManager#assignManualTestJob}`」—— 而那个符号**全仓 0 命中**（唯一的命中就是这行 javadoc
自己）。也就是说：**那句"唯一绕过口"的断言，整整一段时间没有任何载体**，而没有任何门禁发现它。

根因是**门禁盲区**，⛔ 不是"有人忘了改"：
`tools/check-ref-integrity.sh` 只校验**文档里的 `文件:行` 行号有没有越界**，
它**不解析 `{@link}`**，也**不检查被链接的符号存不存在**。
⇒ 本脚本补上那一半（`D-512` ⓒ 自己就建议过"另立一条"，一直没立）。

## 口径（刻意保守：**只报"能确定是坏的"**，宁漏不误）

1. 扫描 `src/main/java/**/*.java` 的 `{@link …}`（含 `{@linkplain …}`）。
2. 取链接目标（`#` 前是类、`#` 后是成员；空格后的部分是显示名，丢掉）。
3. **类部分解析到本仓文件**才检查（解析不到 = JDK/Forge/第三方类 ⇒ **跳过**，⛔ 不误报）。
   · 带点的（`Outer.Inner`）取**最后一段**当简单名；
   · 简单名优先在同包内找，找不到再全仓找；**全仓有多个同名类 ⇒ 跳过**（宁可漏，也不误报）。
4. 判据：`#成员` 的成员名必须在**那个文件**里出现过（词边界）。
   · 成员名取自 `#` 后、`(` 前的部分；
   · ⛔ **不做**"是不是真的可访问/签名对不对"的深判（那要类型系统，误报率会失控）。
5. **裸 `#成员`**（没有类部分）⇒ 在**本文件**里找；找不到时**再看一次继承链上的本仓类**
   （`extends`/`implements` 的简单名，深度 ≤ 3）—— 覆盖"链接到父类成员"的常见合法写法。

⚠️ **它挡的是"符号根本不存在"这一类**（`assignManualTestJob` 那种），
⛔ 挡不住"符号存在但语义已漂"（那要靠 `docs/` 的行号引用 ＋ 人的复核）。

用法：`python3 tools/check-doc-links.py`（已挂在 `tools/check-all.sh`）。
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"

LINK_RE = re.compile(r"\{@link(?:plain)?\s+([^}]*)\}")
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
#: 类/接口/枚举/记录/注解 的声明（含嵌套）—— 只用来建"简单名 → 文件"索引。
TYPE_DECL_RE = re.compile(
    r"^\s*(?:public|protected|private|static|final|abstract|sealed|non-sealed|\s)*"
    r"(?:class|interface|enum|record|@interface)\s+([A-Z]\w*)", re.M)
MEMBER_DECL_RE = re.compile(r"\b([A-Za-z_$]\w*)\s*(?:\(|;|=|,|\))")
EXTENDS_RE = re.compile(r"\b(?:extends|implements)\s+([^{]+)\{")

MIN_LINKS = 900      # 人口下限（实测 1100+；掉到 900 以下 ⇒ 扫描/正则坏了）
MIN_RESOLVED = 300   # 实测能解析到本仓的链接数下限（防"解析器静默返回空集"）


def java_files() -> list[pathlib.Path]:
    out = sorted(SRC.rglob("*.java"))
    if len(out) < 400:
        raise SystemExit(f"空集！只扫到 {len(out)} 个 .java ⇒ 扫描根坏了")
    return out


def build_index(files: list[pathlib.Path]) -> dict[str, list[pathlib.Path]]:
    """`简单类名 → 文件`（一个名字可能在多个包里）。"""
    index: dict[str, list[pathlib.Path]] = {}
    for path in files:
        text = path.read_text(encoding="utf-8")
        for name in set(TYPE_DECL_RE.findall(text)):
            index.setdefault(name, []).append(path)
    if not index:
        raise SystemExit("空集！一个类名都没索引到 ⇒ 解析器坏了")
    return index


def package_of(path: pathlib.Path, cache: dict[pathlib.Path, str]) -> str:
    if path not in cache:
        m = PACKAGE_RE.search(path.read_text(encoding="utf-8"))
        cache[path] = m.group(1) if m else ""
    return cache[path]


def resolve_class(name: str, here: pathlib.Path, index: dict[str, list[pathlib.Path]],
                  pkg_cache: dict[pathlib.Path, str]) -> pathlib.Path | None:
    """把链接里的类部分解析成本仓文件；**解析不到或歧义 ⇒ None（跳过，不误报）**。"""
    simple = name.split(".")[-1]
    if not simple or not simple[0].isupper():
        return None
    cands = index.get(simple)
    if not cands:
        return None
    if len(cands) == 1:
        return cands[0]
    same_pkg = [p for p in cands if package_of(p, pkg_cache) == package_of(here, pkg_cache)]
    if len(same_pkg) == 1:
        return same_pkg[0]
    return None   # 歧义 ⇒ 跳过（宁可漏，不误报）


def member_present(path: pathlib.Path, member: str, text_cache: dict[pathlib.Path, str]) -> bool:
    if path not in text_cache:
        text_cache[path] = path.read_text(encoding="utf-8")
    body = re.sub(r"^import .*$", "", text_cache[path], flags=re.M)
    return re.search(r"\b" + re.escape(member) + r"\b", body) is not None


def inherited_candidates(path: pathlib.Path, text_cache: dict[pathlib.Path, str],
                         index: dict[str, list[pathlib.Path]],
                         pkg_cache: dict[pathlib.Path, str], depth: int = 3) -> list[pathlib.Path]:
    """本仓里这条继承链上的父类/接口文件（深度 ≤ depth）—— 用来合法地解析**裸 `#成员`**。"""
    seen: list[pathlib.Path] = []
    frontier = [path]
    for _ in range(depth):
        nxt: list[pathlib.Path] = []
        for p in frontier:
            if p not in text_cache:
                text_cache[p] = p.read_text(encoding="utf-8")
            for group in EXTENDS_RE.findall(text_cache[p]):
                for raw in group.split(","):
                    name = raw.strip().split("<")[0].strip()
                    if not name or not name[0].isupper():
                        continue
                    parent = resolve_class(name, p, index, pkg_cache)
                    if parent and parent not in seen and parent != path:
                        seen.append(parent)
                        nxt.append(parent)
        frontier = nxt
    return seen


def main() -> int:
    files = java_files()
    index = build_index(files)
    pkg_cache: dict[pathlib.Path, str] = {}
    text_cache: dict[pathlib.Path, str] = {}

    problems: list[str] = []
    links = 0
    resolved = 0
    for path in files:
        text = text_cache.setdefault(path, path.read_text(encoding="utf-8"))
        for m in LINK_RE.finditer(text):
            links += 1
            target = m.group(1).strip().split()[0] if m.group(1).strip() else ""
            if not target:
                continue
            line = text.count("\n", 0, m.start()) + 1
            cls, _, member = target.partition("#")
            member = member.split("(")[0].strip()
            if cls:
                owner = resolve_class(cls, path, index, pkg_cache)
                if owner is None:
                    continue                      # 外部类/歧义 ⇒ 跳过
                resolved += 1
                if member and not member_present(owner, member, text_cache):
                    problems.append(
                        f"{path.relative_to(SRC)}:{line} `{{@link {target}}}` 指向的 "
                        f"`{cls}#{member}` 在 `{owner.relative_to(SRC)}` 里**不存在**")
            else:
                # 裸 `#成员`：先本文件，再看继承链
                resolved += 1
                if not member:
                    continue
                if member_present(path, member, text_cache):
                    continue
                if any(member_present(p, member, text_cache)
                       for p in inherited_candidates(path, text_cache, index, pkg_cache)):
                    continue
                problems.append(
                    f"{path.relative_to(SRC)}:{line} `{{@link {target}}}` 的 `#{member}` "
                    f"在本类及其本仓父类里都**不存在**")

    if links < MIN_LINKS:
        problems.append(f"只扫到 {links} 个 `{{@link}}`（下限 {MIN_LINKS}）⇒ 正则或扫描根坏了")
    if resolved < MIN_RESOLVED:
        problems.append(f"只解析到 {resolved} 个本仓链接（下限 {MIN_RESOLVED}）⇒ 类名索引坏了")

    if problems:
        print("DOC_LINKS_RESULT FAIL: `{@link}` 指向不存在的符号")
        for p in problems:
            print(f"  ✗ {p}")
        print("  ⚠️ 修法：改成现名，或删掉那句断言（⛔ 别把坏链接留在文档里当「有载体」）")
        return 1
    print(f"DOC_LINKS_RESULT PASS: `{{@link}}` {links} 个（其中本仓可解析 {resolved} 个）"
          f"全部指向存在的符号 · 扫描 {len(files)} 个 .java")
    return 0


if __name__ == "__main__":
    sys.exit(main())

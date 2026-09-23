#!/usr/bin/env python3
"""`B2`：「**底线不许进配置面**」+「可选/底线分界」门禁（只读；`survey/30 §4.3-1/2`，用户 2026-09-23 裁定）。

## 它守的是什么

`RiskSwitches` 里的开关是**玩家可写**的（`/alice risk …`）。已定结论是：
* **可选**开关可以暴露给玩家（前提：默认值 = 现状，且**只会收紧、不会放宽**安全）；
* **底线**（寻路纯通行 / 服务端真相 / 未知模组只读）**永不允许**出现在任何玩家可写入口 —— 否则
  玩家（或误配）能把红线关掉。

## 唯一真源 + 双向（对照 `tools/machine-map.py` 的模子）

真源 = `pathing/risk/RiskSwitches.java` 里的 `KNOWN` / `OPTIONAL` / `BOTTOM_LINES` 三张表。
本脚本**解析**它们（常量名要能解析到字符串字面量，**解析不到即红**），再断言：

| # | 断言 | 为什么 |
|---|---|---|
| ① | `KNOWN == OPTIONAL.keySet()`（双向） | 加开关必须同时登记"为什么它可选"；反之不许有幽灵条目 |
| ② | `KNOWN ∩ BOTTOM_LINES = ∅`（且 `OPTIONAL ∩ BOTTOM_LINES = ∅`） | **底线不许进配置面**（本门禁存在的理由） |
| ③ | 三张表**都非空**，且每条 OPTIONAL 的理由长度 ≥ 20 字 | **防空集真**（`Z4` 的教训：空集断言在人口为 0 时恒真且不报错） |
| ④ | 命令面上 `riskSwitch(..., "<name>", ...)` 的每个名字 ∈ `KNOWN`；且 `command/` 里**不许**出现底线名 | 命令是今天唯一的玩家可写入口 |
| ⑤ | `src/` 里**不许**出现 `ForgeConfigSpec`/`ModConfigSpec`（真配置面）；若将来出现，则要求它不含底线名 | 今天 Alice 没有配置面 —— 这条把这个**事实**钉住，而不是假设它 |

用法：`python3 tools/risk-surface.py`（`--check` 同义；构建前用）。
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALICE = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"
SWITCHES = ALICE / "pathing" / "risk" / "RiskSwitches.java"
COMMAND_DIR = ALICE / "command"
MIN_RATIONALE = 20
PLACEHOLDERS = {"pathing_pure_traversal", "server_authoritative_state", "unknown_mod_read_only"}


def strip_comments(text: str) -> str:
    """去 `//` 与 `/* … */`（保留换行，便于报行号）。"""
    out, i, n, in_block = [], 0, len(text), False
    while i < n:
        if in_block:
            if text.startswith("*/", i):
                in_block, i = False, i + 2
            else:
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            continue
        if text.startswith("/*", i):
            in_block, i = True, i + 2
            continue
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def slice_block(code: str, marker: str, end: str = ");") -> str:
    idx = code.find(marker)
    if idx < 0:
        return ""
    j = code.find(end, idx)
    return code[idx:j] if j > 0 else code[idx:]


def resolve_constants(code: str) -> dict[str, str]:
    return dict(re.findall(r'public static final String ([A-Za-z_0-9]+)\s*=\s*"([^"]+)"', code))


def parse_switches():
    code = strip_comments(SWITCHES.read_text(encoding="utf-8"))
    consts = resolve_constants(code)
    problems: list[str] = []

    def resolve(token: str) -> str | None:
        if token in consts:
            return consts[token]
        if token.startswith('"') and token.endswith('"'):
            return token[1:-1]
        return None

    # KNOWN = Set.of(A, B, C)
    known_block = slice_block(code, "Set<String> KNOWN =", ";")
    known: list[str] = []
    for tok in re.findall(r"[A-Za-z_][A-Za-z_0-9]*", known_block.split("=", 1)[-1]):
        if tok in {"java", "util", "Set", "of"}:
            continue
        val = resolve(tok)
        if val is None:
            problems.append(f"KNOWN 里的 `{tok}` **解析不到**字符串字面量（常量表里没有）"
                            f"⇒ 门禁拒绝用猜测继续（machine-map 的规矩：解析不到即红）")
        else:
            known.append(val)

    # OPTIONAL = Map.of(K1, "why", K2, "why", …)
    opt_block = slice_block(code, "Map<String, String> OPTIONAL =")
    optional: dict[str, str] = {}
    for key_tok, why in re.findall(r'([A-Za-z_][A-Za-z_0-9]*)\s*,\s*"([^"]*)"', opt_block):
        val = resolve(key_tok)
        if val is None:
            problems.append(f"OPTIONAL 的键 `{key_tok}` 解析不到字面量")
        else:
            optional[val] = why

    # BOTTOM_LINES = Map.of("name", "pointer", …)
    bot_block = slice_block(code, "Map<String, String> BOTTOM_LINES =")
    bottom = dict(re.findall(r'"([^"]+)"\s*,\s*"([^"]*)"', bot_block))
    return known, optional, bottom, problems


def command_surface() -> tuple[set[str], list[str]]:
    """命令面上真正被 `riskSwitch(...)` 写的开关名 + 命令目录里出现的底线名。"""
    names: set[str] = set()
    hits: list[str] = []
    for path in sorted(COMMAND_DIR.rglob("*.java")):
        code = strip_comments(path.read_text(encoding="utf-8"))
        names |= set(re.findall(r'riskSwitch\s*\(\s*[^,]+,\s*"([a-z_0-9]+)"', code))
        for line in code.split("\n"):
            for b in PLACEHOLDERS:
                if f'"{b}"' in line:
                    hits.append(f"{path.name}: {line.strip()[:90]}")
    return names, hits


def config_surface() -> list[str]:
    hits = []
    for path in sorted(ALICE.rglob("*.java")):
        code = strip_comments(path.read_text(encoding="utf-8"))
        for m in re.finditer(r"(ForgeConfigSpec|ModConfigSpec)", code):
            line_no = code[:m.start()].count("\n") + 1
            hits.append(f"{path.relative_to(ROOT)}:{line_no} 出现真配置面 `{m.group(1)}`")
    return hits


def main() -> int:
    if not SWITCHES.exists():
        print(f"RISK_SURFACE_CHECK_RESULT FAIL: 找不到唯一真源 {SWITCHES}")
        return 1
    known, optional, bottom, problems = parse_switches()

    # ① 双向一致
    missing = sorted(set(known) - set(optional))
    phantom = sorted(set(optional) - set(known))
    for name in missing:
        problems.append(f"开关 `{name}` 在 KNOWN 里但**没有分类**（OPTIONAL 缺行）"
                        f"⇒ 新开关必须写清「为什么它可选」")
    for name in phantom:
        problems.append(f"OPTIONAL 里有 `{name}`，但 KNOWN 里没有 ⇒ 幽灵条目（删掉或补进 KNOWN）")
    # ② 底线不许出现在可写面
    for name in sorted(set(known) & set(bottom)):
        problems.append(f"⭐ **底线进了配置面**：`{name}` 同时在 KNOWN 与 BOTTOM_LINES 里"
                        f"⇒ 玩家能把红线关掉（本门禁存在的唯一理由）")
    for name in sorted(set(optional) & set(bottom)):
        problems.append(f"⭐ 底线 `{name}` 被登记成 OPTIONAL ⇒ 分类自相矛盾")
    # ③ 人口（防空集真）
    if not known:
        problems.append("KNOWN 解析出来是**空集** ⇒ 门禁退化成空集真（要么解析坏了，要么开关全没了）")
    if not optional:
        problems.append("OPTIONAL 解析出来是**空集** ⇒ 同上")
    if not bottom:
        problems.append("BOTTOM_LINES 解析出来是**空集** ⇒ 「底线不许进配置面」变成恒真断言")
    for name, why in sorted(optional.items()):
        if len(why) < MIN_RATIONALE:
            problems.append(f"`{name}` 的「为什么可选」只有 {len(why)} 字（< {MIN_RATIONALE}）"
                            f"⇒ 分类等于没写：{why!r}")
    # ④ 命令面
    exposed, bl_hits = command_surface()
    for name in sorted(exposed - set(known)):
        problems.append(f"命令面暴露了 `{name}`，但它不在 KNOWN 里 ⇒ 未经分类的玩家可写开关")
    problems.extend(f"命令面出现底线名：{h}" for h in bl_hits)
    # ⑤ 配置面
    cfg = config_surface()
    cfg_note = "无（Alice 今天不读玩家可写配置文件）"
    if cfg:
        names_in_cfg = [c for c in cfg if any(b in c for b in bottom)]
        problems.extend(f"配置面里出现底线：{c}" for c in names_in_cfg)
        cfg_note = f"**存在** {len(cfg)} 处（已断言不含底线名）"

    print(f"[B2] 真源 = {SWITCHES.relative_to(ROOT)}")
    print(f"[B2] 可选开关 = {sorted(known)} ｜ 命令面暴露 = {sorted(exposed)} ｜ 底线 = {sorted(bottom)}")
    print(f"[B2] 配置面 = {cfg_note}")
    for p in problems:
        print(f"[B2·底线不许进配置面] {p}")
    print(f"RISK_SURFACE_CHECK_RESULT {'PASS' if not problems else 'FAIL'}: "
          f"可选={len(known)} / 命令面={len(exposed)} / 底线={len(bottom)} / 问题={len(problems)}")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())

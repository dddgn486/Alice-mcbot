"""SH-P1（2026-09-17）：**`single:<步名>` 的引用不得指向不存在的步**。

**为什么有这条规则**（本轮实测踩到）：按步跑电池时把步名写成 `permission_contract`
（真名是 `permission_gate`）⇒ 服务端起跑、白跑 200 tick，最后只报 `battery_never_ran`
⇒ **看起来像"电池坏了"，其实是名字打错**。修法有两层：
① 运行时快速失败（`HeadlessBattery` 拿 `RegressionBatteryTask.knownStepNames()` 起跑前就判，
   并给出相近候选）—— 已落地；
② **本规则**：静态扫描**文档 / 技能 / 脚本**里出现的 `single:<步名>`，逐个与电池的步名表比对
   ⇒ 打错字**在门禁阶段**就红，连服务端都不用起。

步名的唯一出处 = `RegressionBatteryTask.CURATION`（该表构造期自校验与实际步骤表一一对应）。
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BATTERY = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task"
           / "RegressionBatteryTask.java")
# 扫这些地方（说明性文本最可能写步名）
SCAN_DIRS = [ROOT / "docs", ROOT / "AGENTS.md", ROOT / ".alice-supervision" / "skills", ROOT / "tools"]
# ⭐ `A2′`（2026-09-24）：`single:` 支持**逗号点名多步**（`single:<步名1>,<步名2>`）⇒ 引用必须**逐个**校验；
# 旧正则只吃第一个名字 ⇒ 清单里后面写错的步名会被静默漏掉（"判据太弱"，同 `Z2` 教训）。
REF = re.compile(r"single:([A-Za-z_][A-Za-z0-9_]*(?:\s*[,，]\s*[A-Za-z_][A-Za-z0-9_]*)*)")
# `single:<step>` 这种占位写法（文档里讲用法）不算引用
PLACEHOLDER = {"step", "name", "步名", "X", "Y", "NAME", "STEP", "S"}


def known_steps() -> set[str]:
    text = BATTERY.read_text(encoding="utf-8")
    start = text.index("private static final Map<String, Profile> CURATION")
    # 归属表以单独一行的 `);` 结束（表内每个条目都是 Map.entry(...)，不会有裸 `);`）
    end = text.index("\n    );", start) if "\n    );" in text[start:] else len(text)
    return set(re.findall(r'Map\.entry\("([A-Za-z0-9_]+)",\s*Profile\.', text[start:end]))


def scan_files() -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for target in SCAN_DIRS:
        if target.is_file():
            files.append(target)
        elif target.is_dir():
            files.extend(sorted(p for p in target.rglob("*") if p.suffix in {".md", ".sh", ".py", ".java", ".txt"}))
    return files


def main() -> int:
    known = known_steps()
    if not known:
        print("STEP_NAME_CHECK_RESULT FAIL: 没能从 RegressionBatteryTask.CURATION 解析出任何步名（规则需同步）")
        return 1
    problems: list[str] = []
    checked = 0
    for path in scan_files():
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for match in REF.finditer(text):
            for name in [t for t in re.split(r"[,\s，]+", match.group(1)) if t]:
                if name in PLACEHOLDER:
                    continue
                checked += 1
                if name in known:
                    continue
                close = sorted(k for k in known if name[:4] in k or k[:4] in name)[:4]
                problems.append(f"{path.relative_to(ROOT)} 引用了不存在的步 `single:{name}`"
                                + (f"（相近：{close}）" if close else ""))
    for line in problems:
        print(f"[SH·步名引用] {line}")
    if problems:
        print(f"STEP_NAME_CHECK_RESULT FAIL: 引用 {checked} 处，其中 {len(problems)} 处指向不存在的步"
              f"（已知 {len(known)} 步）")
        return 1
    print(f"STEP_NAME_CHECK_RESULT PASS: 引用 {checked} 处全部命中已知步（已知 {len(known)} 步）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
Windows 脚本编码门禁 —— 让"漏 BOM / 错行尾"变成**构建红**，而不是靠人记得。

为什么需要它（2026-10-01 两次实伤，都不是猜的）：
  · ⭐ `alice-doctor.ps1` 第一版写成**无 BOM 的 UTF-8** ⇒ PowerShell 5.1 按 **ANSI/GBK** 读
    ⇒ 中文被解成乱字节、把字符串解析搞坏 ⇒ 报 `UnexpectedToken` / `MissingEndCurlyBrace`，
    **报错行号还指向别处**（parser 的行/列超出该行真实长度 = 典型的"前面有未闭合引号"信号）。
    同一个坑 `codespace-tunnel.ps1` 早踩过一次（`docs/CLOUD_MIGRATION.md §12` 第 1 条），
    但那次只写进了文档 ⇒ **文档拦不住第二次**。
  · `.cmd` 用 **LF 行尾 + 中文注释** ⇒ `cmd.exe` 按 GBK 误读、LF 断错行 ⇒ 命令行被切碎
    （症状：报错里出现被吃头的 `espace-tunnel.cmd`）。所以 `.cmd` 必须 **CRLF + 纯 ASCII**。

判据（都能红）：
  ① 每个 `.ps1`：含非 ASCII ⇒ **必须有 UTF-8 BOM**（`EF BB BF`）；纯 ASCII 则免检（BOM 可有可无）
  ② 每个 `.cmd`/`.bat`：行尾必须 **CRLF**（出现裸 LF 即红）
  ③ 每个 `.cmd`/`.bat`：**不得含非 ASCII 字节**（cmd.exe 按 GBK 读，中文注释会切碎命令行）
  ④ 人口下限：扫到的 `.ps1`/`.cmd` 数不得为 0（⛔ 防"路径写错 ⇒ 空集静默通过"）
  ⑤ 顺手：`.ps1` 参数名不得用 PowerShell 自动变量（会解析坏；2026-10-01 实伤其一）

⚠️ 与 PowerShell 解析器的分工：本门禁管**编码/行尾**这类"读之前就坏了"的问题；
   真正的**语法**问题应由解析器抓（需要 Windows 上的 powershell.exe，CI 里没有 ⇒ 不在这道门禁里）。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BOM = b"\xef\xbb\xbf"

#: PowerShell 自动变量（拿它们当参数名会让解析器坏掉，实测：`$Script`）
RESERVED_PS_PARAMS = {
    "Script", "Args", "Input", "PSItem", "MyInvocation", "PSScriptRoot", "PSCommandPath",
    "Error", "Host", "Home", "PWD", "ExecutionContext", "Matches", "LASTEXITCODE", "true", "false", "null",
}

SKIP_DIRS = {".git", "build", "node_modules", ".gradle", "run"}

#: ⛔ 上游/生成物：不按本仓规矩要求（`gradlew.bat` 是 Gradle 官方 wrapper 生成的，LF 行尾，
#:    实测在用，不许我们改它 —— 改了下次 `gradle wrapper` 又变回去）
SKIP_FILES = {"gradlew.bat"}


def walk(suffixes):
    for p in ROOT.rglob("*"):
        if p.suffix.lower() not in suffixes:
            continue
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        if p.name in SKIP_FILES:
            continue
        if p.is_file():
            yield p


def main() -> int:
    problems = []
    ps1 = list(walk({".ps1"}))
    cmd = list(walk({".cmd", ".bat"}))

    for p in ps1:
        raw = p.read_bytes()
        rel = p.relative_to(ROOT)
        has_bom = raw.startswith(BOM)
        body = raw[len(BOM):] if has_bom else raw
        try:
            text = body.decode("utf-8")
        except UnicodeDecodeError as e:
            problems.append(f"{rel}: 不是合法 UTF-8（{e}）")
            continue
        non_ascii = any(ord(c) > 127 for c in text)
        if non_ascii and not has_bom:
            problems.append(
                f"{rel}: 含非 ASCII 但**没有 UTF-8 BOM** ⇒ PS 5.1 会按 GBK 读，字符串解析会坏"
                f"（实测两次）。修法：printf '\\xEF\\xBB\\xBF' | cat - 文件 > 新文件 && mv 新文件 文件"
            )
        for i, line in enumerate(text.splitlines(), 1):
            s = line.strip()
            if s.startswith("param(") or s.startswith("["):
                for name in RESERVED_PS_PARAMS:
                    if f"${name}" in s and ("param" in s or "] $" in s):
                        problems.append(f"{rel}:{i}: 参数名疑似用了 PowerShell 自动变量 ${name} ⇒ 会解析坏")

    for p in cmd:
        raw = p.read_bytes()
        rel = p.relative_to(ROOT)
        # 裸 LF（前面不是 CR）
        body = raw[3:] if raw.startswith(BOM) else raw
        bare_lf = body.replace(b"\r\n", b"").count(b"\n")
        if bare_lf:
            problems.append(f"{rel}: 有 {bare_lf} 处 **裸 LF** 行尾 ⇒ cmd.exe 会把命令行切碎。修法：转 CRLF")
        bad = [b for b in body if b > 127]
        if bad:
            problems.append(f"{rel}: 含 {len(bad)} 个**非 ASCII 字节** ⇒ cmd.exe 按 GBK 误读。修法：注释改纯 ASCII")

    # ④ 人口下限（空集不许静默通过）
    if not ps1:
        problems.append("扫到 0 个 .ps1 ⇒ 路径解析坏了（⛔ 不许静默通过）")
    if not cmd:
        problems.append("扫到 0 个 .cmd ⇒ 路径解析坏了（⛔ 不许静默通过）")

    if problems:
        print("WINDOWS_SCRIPT_ENCODING_RESULT FAIL")
        for x in problems:
            print("  ✗ " + x)
        return 1
    print(
        f"WINDOWS_SCRIPT_ENCODING_RESULT PASS: .ps1 {len(ps1)} 个（含非 ASCII 的全部带 BOM）· "
        f".cmd {len(cmd)} 个（CRLF + 纯 ASCII）"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

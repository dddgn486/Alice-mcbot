#!/usr/bin/env python3
"""合成主工作流 preset 的 `agent.cordis.yml`（= DSH 内置 standard 骨架 + 本项目 persona）。

为什么要有这个脚本：
  preset 的 persona 是**手写的**（`persona.md`，可评审、可 diff），而工具组合骨架来自
  npm 公开包 `@deepseek-ai/dsh-agent-presets`。两者都不该被手抄进一个 YAML —— 抄了就会静默过期。

  ⛔ **不要把仓库文档全文抄进 persona**（2026-10-02 的观点修正）：
     正文一旦被复制进来，就成了一份无人维护的副本；而更新它的代价是"重建 preset"⇒
     结果要么过期，要么让人不想改。⇒ persona 只放**稳定准则**，⛔ **不写路径/编号/排期**
     （项目处于整改期、文档随时会变 ⇒ 指针会烂；用户 2026-10-02 明确裁定「不要写指针」）。

用法：`python3 tools/make-agent-preset.py [--check]`
  --check 只比对、不写盘（自检用；不一致时退出码 1）
"""
from __future__ import annotations

import argparse
import pathlib
import sys
import tarfile
import tempfile
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
SRC = HERE / "agent-presets" / "alice-forge-assistant" / "persona.md"
DEST = HERE / "agent-presets" / "alice-forge-assistant" / "agent.cordis.yml"

PRESET_ID = "alice-forge-assistant"
BUNDLE_VERSION = "0.1.5-rc.3"          # 与用户环境实测的 dsh 版本一致
BUNDLE_URL = (
    "https://registry.npmjs.org/@deepseek-ai/dsh-agent-presets/-/"
    f"dsh-agent-presets-{BUNDLE_VERSION}.tgz"
)
SKELETON = "package/presets/standard/agent.cordis.yml"

HEADER = """- id: persona
  name: '@deepseek-ai/dsh-persona'
  config:
    suffix: Your working directory is {{cwd}}.
    prefix: |-
"""


def fetch_skeleton() -> str:
    with tempfile.TemporaryDirectory() as tmp:
        tgz = pathlib.Path(tmp) / "ap.tgz"
        urllib.request.urlretrieve(BUNDLE_URL, tgz)
        with tarfile.open(tgz) as t:
            return t.extractfile(SKELETON).read().decode("utf-8")


def build() -> str:
    persona = SRC.read_text(encoding="utf-8").rstrip("\n")
    if "{{" in persona:
        raise SystemExit(f"✗ {SRC.name} 里出现 {{{{ }}}}，会被 persona 模板替换吃掉")

    yml = fetch_skeleton()
    i = yml.index("- id: persona\n")
    j = yml.index("\n- id: ", i + 1)

    # 正文统一缩进 6 空格；空行留空（YAML 块标量里保持空行，避免并入上一行）
    body = "\n".join(("      " + l).rstrip() if l.strip() else "" for l in persona.split("\n"))
    return yml[:i] + HEADER + body + "\n" + yml[j:]


def install(dest: pathlib.Path) -> int:
    """把 preset 的两份文件放进 DSH 的 preset 目录（preset 是**目录**，不会被自动发现）。"""
    dest.mkdir(parents=True, exist_ok=True)
    for name in ("preset.yml", "agent.cordis.yml"):
        src = SRC.parent / name
        if not src.is_file():
            print(f"✗ 缺 {src}（先不带 --install 跑一次生成）")
            return 2
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", dir=dest, delete=False, suffix=".tmp"
        ) as f:
            f.write(src.read_text(encoding="utf-8"))
            tmp = pathlib.Path(f.name)
        tmp.replace(dest / name)
    print(f"→ 已装入 {dest}")
    print("  ⚠️ DSH 只在启动时发现 preset ⇒ 装完必须重启 `dsh web` 才会生效")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只比对，不写盘")
    ap.add_argument(
        "--install",
        action="store_true",
        help="生成后装到 $DSH_HOME/.agent-presets/<id>/（DSH_HOME 默认 ~/.dsh）",
    )
    args = ap.parse_args()

    if not SRC.is_file():
        print(f"✗ 找不到 {SRC}")
        return 2

    new = build()
    old = DEST.read_text(encoding="utf-8") if DEST.is_file() else None
    rel = DEST.relative_to(REPO)

    if args.check:
        if old == new:
            print(f"✓ {rel} 与 {SRC.name} 一致")
            return 0
        print(f"✗ 过期：{rel} 与 {SRC.name} 不一致 ⇒ 重跑本脚本")
        return 1

    if old == new:
        print(f"→ 已是最新（{len(new)} 字节），未改动")
    else:
        DEST.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", dir=DEST.parent, delete=False, suffix=".tmp"
        ) as f:
            f.write(new)
            tmp = pathlib.Path(f.name)
        tmp.replace(DEST)          # 原子替换（⛔ 不要 open(dest,'w') 直接截断）
        n = len(SRC.read_text(encoding="utf-8").splitlines())
        print(f"→ 已写入 {rel}（{len(new)} 字节；persona 源 {n} 行）")

    if args.install:
        import os

        dsh_home = pathlib.Path(os.environ.get("DSH_HOME") or pathlib.Path.home() / ".dsh")
        return install(dsh_home / ".agent-presets" / PRESET_ID)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

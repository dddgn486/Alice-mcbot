#!/usr/bin/env bash
# G3（2026-09-21）：`AGENTS.md` §不可悄悄改变的架构边界 的每条红线，必须带**可执行门禁指针**
# 或带**复核触发**的「未门禁」标记 —— 否则「没有东西会在它被破坏时变红」这件事本身没人发现。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/redline-gates.py

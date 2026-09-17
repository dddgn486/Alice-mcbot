#!/usr/bin/env bash
# SH-P1（2026-09-17）：文档/脚本里写的 `single:<步名>` 必须真的是电池里的步。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/step-names.py

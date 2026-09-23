#!/usr/bin/env bash
# `B2`（2026-09-23）：「底线不许进配置面」+ 可选/底线分界门禁。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/risk-surface.py

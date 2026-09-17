#!/usr/bin/env bash
# R-P1（队列第⑦项）：文档里的 `文件:行` 引用不得过期（行号超界）。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/ref-integrity.py

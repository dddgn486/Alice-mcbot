#!/usr/bin/env bash
# 工件内容摘要（jar 不可字节复现 ⇒ 用内容摘要判"是否同一版代码"）。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/jar-content-hash.py "$@"

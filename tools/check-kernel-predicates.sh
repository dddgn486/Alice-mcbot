#!/usr/bin/env bash
# 内核 §2 的两条可执行规则（K-4 规划/执行谓词统一、K-5 死状态）；违例即非零退出
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/kernel-predicates.py"

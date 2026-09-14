#!/usr/bin/env bash
# 生成写入策略表视图（docs/authz/POLICY_MATRIX.csv）
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/policy-map.py"

#!/usr/bin/env bash
# 生成授权/审批框架总览（单一出处 docs/authz/AUTHZ_REGISTRY.csv）
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/authz-map.py" "$@"

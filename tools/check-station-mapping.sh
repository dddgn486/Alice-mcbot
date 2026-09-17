#!/usr/bin/env bash
# S-P1（队列第①项）：站点映射（RecipeDump ↔ recipe-graph.py）必须一致。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/station-mapping.py

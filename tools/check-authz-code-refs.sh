#!/usr/bin/env bash
# 门禁：**授权登记 CSV 的 `code_ref` 必须指得到真东西**（`docs/authz/AUTHZ_REGISTRY.csv` · `POLICY_MATRIX.csv`）。
#
# ⭐ 为什么有它：这两份 CSV **两道门禁都在、都绿**，但它们查的全是**枚举覆盖面**
#   （`check-authz-registry` 逐字「扫描文件=67 · 拒绝码=119 · MovementType=10 · WriteReason=15」）
#   ⇒ ⛔ **没有一个字查引用**；而 `check-ref-integrity` 的扫描范围是 `docs/**/*.md` ＋ `AGENTS.md`
#   ⇒ ⛔ **不含 `.csv`**。实测后果：路径引用 48 个里 **19 个指不到东西**，而**没人会知道**。
#
# ⏰ 有时效：`tools/policy-map.py` 的源路径**写死了 `write/WritePolicyMatrix.java`**，
#   而 `write/` 正是 `D-563`/`O116` 里"排在最后要拆解搬家"的那个
#   ⇒ ⭐ **那刀一开，`check-policy-matrix` 会变成永远绿的空门禁** ⇒ 本门禁顺手保护它。
#
# 判据三态：① 路径对 ⇒ 过；② 名字在、路径错 ⇒ 红（**并指出实际在哪**，可机械修）；
#   ③ `src/` 零命中 ⇒ 红（类已删/改名）· ④ 名字撞车 ⇒ ⚠️ 只警告（⛔ 不判红）。
# 人口下限 `REF_FLOOR` · 5 臂注入自证。
# ⚠️ 它**判不了行号**（只报个数）· ⛔ **也不改任何东西** —— `code_ref` 的真源可能在 Java 源码里。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/check-authz-code-refs.py

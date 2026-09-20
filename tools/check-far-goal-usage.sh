#!/usr/bin/env bash
# `D-350`（`⑥` 的处置，2026-09-20）：**裸粗目标不许进生产** —— 红线 `D-132`（内核从不加载区块）的门禁。
#
# 为什么（`D-337` 实测，不是推测）：`GoalNearXZ` 是"粗目标（只认 XZ 列 + 半径）"，它的到达判断**纯算术、不读世界**
# ⇒ 内核那条"目标未加载就拒"的前置守卫**对它不生效** ⇒ **A* 扩展时会读未加载区块的方块，把它们同步加载进来**。
# `far_path_bench` 的沿路采样实证（8 个点）：
#   采样前：192=true 224=false 256=false 288=false 320=false 352=false 384=false
#   采样后：192=true 224=true  256=true  288=true  320=true  352=true  384=true   ← 被同步加载了
# ⇒ 远距离的**正确形状**（`D-337` 附注二）= **一跳一跳逼近**：`FarTravelHop` 先用 `hasChunkAt` 采样已加载前沿，
#   把目标**夹到边界内侧**再按 `GoalNearXZ` 规划（新区块由 bot 自己的移动自然加载 = 合法的世界推进）。
#
# 规则（可失败）：
#   ① `GoalNearXZ.around(` 只许出现在两个地方：**`pathing/core/search/FarTravelHop.java`**（合法的"夹到边界"层）
#      与**夹具**（`*CheckTask.java` / `*Bench*.java`，用来演示缺陷 / 做 A/B）；
#   ② 出现在任何**生产**文件里 ⇒ 红，并给出理由与正确做法。
#
# 注：这是**红线守卫**（不是风格检查）——它挡的是"接一根会把区块同步加载进来的线"。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/src/main/java"
NEEDLE='GoalNearXZ.around('
ALLOW_FILES=(
  "pathing/core/search/FarTravelHop.java"
  "pathing/core/search/GoalNearXZ.java"
)

[[ -d "$SRC" ]] || { echo "FAR_GOAL_CHECK_RESULT FAIL: 找不到 $SRC"; exit 1; }

violations=""
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  file="${line%%:*}"
  rel="${file#"$SRC"/}"
  # 夹具/门禁白名单：文件名带 CheckTask / Bench
  case "$(basename "$file")" in
    *CheckTask.java|*Bench*.java) continue ;;
  esac
  allowed=0
  for a in "${ALLOW_FILES[@]}"; do
    # 允许项写成**路径后缀** ⇒ 不依赖 `$SRC` 的绝对前缀（首版按全等比较 ⇒ 误报，别改回去）
    [[ "$rel" == *"$a" ]] && allowed=1 && break
  done
  [[ "$allowed" == "1" ]] && continue
  violations="${violations}${rel}:${line#*:}; "
done < <(grep -rn --include=*.java -F "$NEEDLE" "$SRC" || true)

if [[ -n "$violations" ]]; then
  echo "FAR_GOAL_CHECK_RESULT FAIL: 生产代码里出现**裸粗目标** ⇒ 会让内核搜索读未加载区块（红线 D-132）：${violations}"
  echo "  正确做法：远距离走 FarTravelHop（一跳一跳，先 hasChunkAt 夹到已加载边界内侧）—— 见 D-337 附注二。"
  exit 1
fi

count="$(grep -rn --include=*.java -F "$NEEDLE" "$SRC" | wc -l | tr -d ' ')"
echo "FAR_GOAL_CHECK_RESULT PASS: 裸粗目标 ${count} 处**全在允许层**（FarTravelHop 夹边界层 / 夹具）"

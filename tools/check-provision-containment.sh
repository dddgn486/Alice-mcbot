#!/usr/bin/env bash
# 红线② 门禁（T1 / R-2，2026-09-14）：**生产决策路径不得凭空发料**。
#
# 背景（三路审计 §3.1 R-2 实证）：`BotManager.assignJob` 是**决策层唯一的生产入口**
# （`decision/GoalDirector` 起 Job 就走它），而它一路调到 `item/FixtureToolKit`，
# 把**钻石镐 / 钻石斧 / 12 圆石凭空塞进快捷栏**，快捷栏满时还会**强制覆盖**已有物品
# ⇒ **LLM 起的每个 Job 都白得一套钻石工具**，与 `CASE CRAFT` 里自己写的
# "不许凭空给物品（同族铁律）" 直接冲突。
#
# 修法（同步落地）：`JobLauncher.provision` / `BotManager.assignJob` 现在**必须显式声明**
# `fixtureProvision`。生产传 `false` ⇒ 只走 `ToolSupply.promoteFromMain`（**只搬运已有的**），
# 没有就如实留缺、由 Job 报 `tool_missing`；夹具传 `true` ⇒ 保留造物（测试世界是白板）。
#
# ⚠️ **本门禁只断言"生产决策路径"，不断言"夹具不许造物"** —— 后者本来就是夹具的职责
#     （`*CheckTask` / `*DiagnosticTask` / `*Regression*` / `*Battery*` / 游戏内测试物品大量调用
#     `FixtureToolKit.ensure*`，**这是对的**）。第一版把范围写成"只有 JobLauncher 能造物"，
#     结果列出 80+ 行夹具命中、毫无信号 —— 断言写宽和写松一样是废的。
#
# 三条断言：
#   ① **生产决策包 `decision/` 里零命中 `FixtureToolKit`**（它连类型名都不该出现）；
#   ② `JobLauncher` 的**非夹具分支**（`provisionFromExisting`）零命中 `FixtureToolKit`；
#   ③ **反向断言（gate is live）**：夹具分支确实还在造物、生产确实还在传 `false`、夹具入口确实还在传 `true`
#      —— 否则一次重命名/改默认值就能让本门禁静默变绿。
#
# 用法：bash tools/check-provision-containment.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JOB_LAUNCHER="src/main/java/com/dddgn/alice/job/JobLauncher.java"
PRODUCTION_DIR="src/main/java/com/dddgn/alice/decision"
FIXTURE_ENTRY_DIR="src/main/java/com/dddgn/alice/item"

fail=0
note() { printf '  %s\n' "$1" >&2; }

for f in "$JOB_LAUNCHER" "$PRODUCTION_DIR"; do
    if [ ! -e "$f" ]; then
        echo "PROVISION_CONTAINMENT_RESULT FAIL：缺关键路径 $f" >&2
        exit 1
    fi
done

# ---------- ① 生产决策包不许出现发料工具 ----------
prod_hits=$(grep -rn "FixtureToolKit" "$PRODUCTION_DIR" --include=*.java || true)
if [ -n "$prod_hits" ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：生产决策包 decision/ 出现 FixtureToolKit（红线②）" >&2
    echo "$prod_hits" | sed 's/^/  /' >&2
    fail=1
fi

# ---------- ② JobLauncher 的非夹具分支不许调 FixtureToolKit ----------
nonfix_hits=$(awk '
    /private static boolean provisionFromExisting/ { inside = 1 }
    inside { print NR": "$0 }
    inside && /^    }$/ { inside = 0 }
' "$JOB_LAUNCHER" | grep "FixtureToolKit" || true)
if [ -n "$nonfix_hits" ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：JobLauncher 的**非夹具分支**里出现 FixtureToolKit（红线②）" >&2
    echo "$nonfix_hits" | sed 's/^/  /' >&2
    fail=1
fi

# ---------- ③ gate is live ----------
live_fixture=$(awk '/case LUMBER -> \{/,/^    \}/' "$JOB_LAUNCHER" | grep -c "FixtureToolKit" || true)
live_prod_false=$(grep -rhE "assignJob\(.*,[[:space:]]*false[[:space:]]*\)" "$PRODUCTION_DIR" --include=*.java | wc -l)
live_fixture_true=$(grep -rhE "assignJob\(.*,[[:space:]]*true[[:space:]]*\)" "$FIXTURE_ENTRY_DIR" --include=*.java | wc -l)
prod_loose=$(grep -rnE "assignJob\(" "$PRODUCTION_DIR" --include=*.java \
    | grep -vE "assignJob\(.*,[[:space:]]*false[[:space:]]*\)" || true)
if [ -n "$prod_loose" ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：生产包的 assignJob 调用没有显式 fixtureProvision=false" >&2
    echo "$prod_loose" | sed 's/^/  /' >&2
    fail=1
fi
if [ "$live_fixture" -lt 3 ] || [ "$live_prod_false" -lt 1 ] || [ "$live_fixture_true" -lt 1 ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：门禁失去靶子（夹具不再造物 / 无人传 false / 无人传 true）" >&2
    note "实测：JobLauncher 夹具分支造物=$live_fixture（期望 ≥3）  生产传 false=$live_prod_false（期望 ≥1）  夹具入口传 true=$live_fixture_true（期望 ≥1）"
    note "若确实改了机制，请同步更新本脚本的靶子，别让它静默变绿。"
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "PROVISION_CONTAINMENT_RESULT PASS: decision/ 零命中 FixtureToolKit；JobLauncher 非夹具分支零命中；夹具分支造物=$live_fixture；生产传 false=$live_prod_false；夹具入口传 true=$live_fixture_true"

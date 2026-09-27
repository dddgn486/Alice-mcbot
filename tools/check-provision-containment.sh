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
# 范围（`D-479` 的 `P6/A` 扩展，2026-09-27）：`decision/` + `job/`（除 `JobLauncher`）+ `bot/`（**带理由的豁免清单**）。
#   ⚠️ **不覆盖 `task/`**：那是**混装包**（生产任务与自检夹具同包），宽断言 = 54 行夹具命中 = 无信号
#      —— 这正是本脚本自己写下的教训。`task/` 的解法是**包分离**（登记为结构待办），**不是**收紧/放宽断言。
#   ⚠️ **匹配前必须去掉 javadoc 的 `{@link …}` 片段**：`task/MineTask:225` 只用
#      `{@link com.dddgn.alice.item.FixtureToolKit}` 做文档引用（**不是代码依赖**）⇒ 不去注释会把文档当依赖误报
#      （与 `check-phase-transition-outlet.py`「解析函数必须同源、必须 strip comments/strings」同一条教训）。
#
# 五条断言：
#   ① **生产决策包 `decision/` 里零命中 `FixtureToolKit`**（它连类型名都不该出现）；
#   ② `JobLauncher` 的**非夹具分支**（`provisionFromExisting`）零命中 `FixtureToolKit`；
#   ③ **反向断言（gate is live）**：夹具分支确实还在造物、生产确实还在传 `false`、夹具入口确实还在传 `true`
#      —— 否则一次重命名/改默认值就能让本门禁静默变绿；
#   ④ ⭐ **`job/` 包除 `JobLauncher` 外零命中**（`P6/A`）—— 今天 `job/` 只有 `JobLauncher` 一个命中者（它就是夹具口），
#      所以这条**今天是零误报的**，它挡的是**将来**任何人在 `job/` 里引入造物；
#   ⑤ ⭐ **`bot/` 包的命中必须在带理由的豁免清单里**（`P6/A`）—— 豁免**不是静默的**：
#      `BotManager` 是**已知缺口**（**12 处造物 / 6 个 legacy `assign*` 入口**，其中 `assignMineJob`、
#      `assignRestore`、`assignRegionLumber` **三条同时服务玩家命令** `BotCommand:825/:843/:1199`
#      ⇒ **不是纯夹具口**；且这些入口跳过 `provision`/`create`/`refusalReason`/`JobKindContract`/`ManualTestLock` 五道闸门）。
#      ⚠️ **豁免得手（不再命中）也要 FAIL** —— 强制在修好时同步删掉清单条目，别让白名单漂移成永久漏洞。
#
# 用法：bash tools/check-provision-containment.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JOB_LAUNCHER="src/main/java/com/dddgn/alice/job/JobLauncher.java"
PRODUCTION_DIR="src/main/java/com/dddgn/alice/decision"
JOB_DIR="src/main/java/com/dddgn/alice/job"
BOT_DIR="src/main/java/com/dddgn/alice/bot"
FIXTURE_ENTRY_DIR="src/main/java/com/dddgn/alice/item"

#: ⭐ `P6/A` ⑤：`bot/` 的**带理由豁免清单**（`<路径>|<理由>`）。新增条目 = 显式表态，不许静默放行。
BOT_EXEMPT=(
    "src/main/java/com/dddgn/alice/bot/BotManager.java|已知缺口（待独立刀）：本类把「发料」与「起任务」焊在 6 个 legacy assign* 入口里，共 12 处造物 —— assignLumberJob:602-604 / assignMineJob:686 / assignFishboneJob:715 / assignRestore:735 / assignRegionLumber:765-767,785 / 内部匿名类:2168,2182；其中 assignMineJob / assignRestore / assignRegionLumber 三条**同时服务玩家命令**（BotCommand:825 / :843 / :1199）⇒ **不是纯夹具口**，不能按「只服务测试物品」豁免；且这些入口跳过 provision / create / refusalReason / JobKindContract / ManualTestLock 五道入口闸门"
)

#: 去掉 javadoc 的 `{@link …}` 片段后再判命中（**文档引用不是代码依赖**，见头部注释）。
strip_doclinks() { sed 's/{@link[^}]*}//g'; }

#: 目录下**真实引用**（去掉 javadoc link 后仍有命中）的文件清单。
#  ⚠️ 这里**不能用 `grep -q`**：它一命中就退出 ⇒ 上游 `sed` 收 SIGPIPE ⇒ `set -o pipefail` 把
#  「命中」判成「未命中」（实测：`bot/BotManager` 被判成 0 命中，本门禁自己的 ⑥ 靶子断言当场抓到）。
#  ⇒ 必须把上游输出**读完**（命令替换），不让管道提前关闭。
files_with_real_refs() {
    local dir="$1" f
    for f in $(grep -rl "FixtureToolKit" "$dir" --include=*.java || true); do
        if [ -n "$(strip_doclinks < "$f" | grep "FixtureToolKit" || true)" ]; then
            echo "$f"
        fi
    done
}

#: 某文件里的真实引用行（`路径:行号: 文本`）。
refs_in() {
    strip_doclinks < "$1" | grep -n "FixtureToolKit" | sed "s|^|$1:|" || true
}

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

# ---------- ④ `job/` 包除 JobLauncher 外零命中（`P6/A`）----------
job_bad=0
for f in $(files_with_real_refs "$JOB_DIR"); do
    if [ "$f" != "$JOB_LAUNCHER" ]; then
        echo "PROVISION_CONTAINMENT_RESULT FAIL：job/ 包出现 FixtureToolKit（P6/A）：$f" >&2
        refs_in "$f" | sed 's/^/  /' >&2
        note "job/ 是生产包。要发料请走 JobLauncher.provision 的**夹具分支**（fixtureProvision=true），不要在别处造物。"
        job_bad=$((job_bad + 1))
    fi
done
[ "$job_bad" -ne 0 ] && fail=1

# ---------- ⑤ `bot/` 命中必须在带理由豁免清单里（`P6/A`）----------
bot_files=$(files_with_real_refs "$BOT_DIR")
for f in $bot_files; do
    matched=0
    for e in "${BOT_EXEMPT[@]}"; do
        if [ "${e%%|*}" = "$f" ]; then matched=1; break; fi
    done
    if [ "$matched" -eq 0 ]; then
        echo "PROVISION_CONTAINMENT_RESULT FAIL：bot/ 包出现 FixtureToolKit 且未登记豁免（P6/A）：$f" >&2
        refs_in "$f" | sed 's/^/  /' >&2
        note "要么改走 JobLauncher.provision / 夹具入口，要么在 BOT_EXEMPT 里**带理由**显式登记（不许静默放行）。"
        fail=1
    fi
done
# 豁免得手（不再命中）⇒ 也 FAIL：强制在修好时同步删条目，别让白名单漂移成永久漏洞。
for e in "${BOT_EXEMPT[@]}"; do
    ef="${e%%|*}"
    if ! printf '%s\n' "$bot_files" | grep -qx "$ef"; then
        echo "PROVISION_CONTAINMENT_RESULT FAIL：豁免已得手（$ef 不再命中 FixtureToolKit）⇒ 请删掉 BOT_EXEMPT 里这条（P6/A）" >&2
        note "理由（登记时写的）：${e#*|}"
        fail=1
    fi
done

# ---------- ⑥ gate is live（`P6/A` 新增靶子）----------
live_job_launcher=$(refs_in "$JOB_LAUNCHER" | wc -l)
live_bot_files=$(printf '%s\n' "$bot_files" | grep -c . || true)
live_bot_exempt=${#BOT_EXEMPT[@]}
if [ "$live_job_launcher" -lt 3 ] || [ "$live_bot_files" -lt 1 ] || [ "$live_bot_exempt" -lt 1 ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：门禁失去靶子（job/ 豁免对象不再造物 / bot/ 无人命中 / 豁免清单被清空）" >&2
    note "实测：JobLauncher 造物=$live_job_launcher（期望 ≥3）  bot/ 命中文件=$live_bot_files（期望 ≥1）  豁免条数=$live_bot_exempt（期望 ≥1）"
    note "若确实改了机制，请同步更新本脚本的靶子，别让它静默变绿。"
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "PROVISION_CONTAINMENT_RESULT PASS: decision/ 零命中 FixtureToolKit；JobLauncher 非夹具分支零命中；job/ 除 JobLauncher 外零命中（JobLauncher 造物=$live_job_launcher）；bot/ 命中=$live_bot_files 全部带理由豁免=$live_bot_exempt；夹具分支造物=$live_fixture；生产传 false=$live_prod_false；夹具入口传 true=$live_fixture_true"

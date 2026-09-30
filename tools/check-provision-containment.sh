#!/usr/bin/env bash
# 红线② 门禁（T1 / R-2，2026-09-14；**刀 2 重写**，2026-09-30）：**生产决策路径不得凭空发料**。
#
# ## 背景（三路审计 §3.1 R-2 实证）
#
# `BotManager.assignJob` 是**决策层唯一的生产入口**（`decision/GoalDirector` 起 Job 就走它），
# 而它一路调到 `item/FixtureToolKit`，把**钻石镐 / 钻石斧 / 12 圆石凭空塞进快捷栏**，
# 快捷栏满时还会**强制覆盖**已有物品 ⇒ **LLM 起的每个 Job 都白得一套钻石工具**，
# 与 `CASE CRAFT` 里自己写的"不许凭空给物品（同族铁律）"直接冲突。
#
# 2026-09-14 的第一版修法 = 加一个裸 `boolean fixtureProvision`（生产传 false / 夹具传 true）。
# ⚠️ **刀 2（`D-512`）换掉了这个载体**：用户逐字「⛔ **入口既不是夹具口也不是生产口**；
# ✅ **入口不分类，`fixtureProvision` 由调用方传**」⇒ 改成**具名策略** `tool/ToolProvision`：
#   · ✅ `ToolProvision.PROMOTE_ONLY`（**生产包** `tool/`）—— 只搬运已存在的工具，缺就如实留缺；
#   · ⚠️ `fixture/DevCreateProvision.INSTANCE`（**开发期包**）—— 凭空造。
# ⭐ 结构性收益：**生产包结构上没法表达"凭空造物"**（一写就反向依赖开发期包，见 `R3`），
#   所以 `bot/`／`job/`／`decision/` 现在**零开发期引用**（刀 2 清了 40 处 `fixture/` import
#   ＋ 6 处 `debug/` import ＋ 47 个构造点 ＋ 12 处 `FixtureToolKit`）。
#
# ## 与旧版的差别（逐条，⛔ 不是放宽）
#
# | # | 旧 | 新 |
# |---|---|---|
# | ① | `decision/` 零命中 `FixtureToolKit` | `decision/` 零命中 `FixtureToolKit`／`DevCreateProvision`，**且每条 `assignJob(` 必须显式传 `PROMOTE_ONLY`** |
# | ② | `JobLauncher` 的非夹具分支零命中 | `JobLauncher` **整个文件**零命中具体策略（它只调 `provisioning.provisionFor`） |
# | ③ | 靶子：夹具分支造物≥3 / 生产传 false≥1 / 夹具入口传 true≥1 | 靶子：`PROMOTE_ONLY` 实现 / `DEV_CREATE` 实现 / 生产传 `PROMOTE_ONLY` / 开发期入口传 `DEV_CREATE` 四项都 ≥1 |
# | ④ | `job/` 除 `JobLauncher` 外零命中 | `job/` **零命中**（JobLauncher 自己也不造物了 —— 它只剩一行转发） |
# | ⑤ | `bot/` 命中必须在 `BOT_EXEMPT` 白名单里（**豁免得手也 FAIL**） | ⭐ **`bot/` 零命中开发期物**（`FixtureToolKit`／`DevCreateProvision`／`com.dddgn.alice.(fixture\|debug).`）⇒ **`BOT_EXEMPT` 已删** |
# | ⑥ | 靶子：豁免清单非空等 | 靶子：`bot/` 人口下限 ＋ 两个策略文件都必须存在（防"把包搬空 ⇒ 假绿"） |
#
# ⚠️ **`BOT_EXEMPT` 为什么可以直接删、不需要新白名单**：那份白名单存在的唯一理由是
# 「`BotManager` 是**已知缺口**，12 处造物**暂时**搬不走」—— 而它自己写着**豁免得手也要 FAIL**，
# 那句纪律就是为今天写的。刀 2 把缺口填平 ⇒ 白名单使命结束（⛔ 不许换个名字复活）。
#
# ⚠️ **匹配前要去掉 javadoc 的 `{@link …}` 片段**（`task/MineTask:225` 的教训）：
# 文档引用**不是**代码依赖；本脚本沿用 `strip_doclinks`。
#
# 用法：bash tools/check-provision-containment.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JOB_LAUNCHER="src/main/java/com/dddgn/alice/job/JobLauncher.java"
PRODUCTION_DIR="src/main/java/com/dddgn/alice/decision"
JOB_DIR="src/main/java/com/dddgn/alice/job"
BOT_DIR="src/main/java/com/dddgn/alice/bot"
FIXTURE_ENTRY_DIR="src/main/java/com/dddgn/alice/item"
DEV_ENTRY_DIR="src/main/java/com/dddgn/alice/debug"
TOOL_PROVISION="src/main/java/com/dddgn/alice/tool/ToolProvision.java"
DEV_CREATE="src/main/java/com/dddgn/alice/fixture/DevCreateProvision.java"

#: 开发期物的**字面量形态**（`FixtureToolKit` / `DevCreateProvision`）。
DEV_PATTERN='FixtureToolKit|DevCreateProvision'
#: 开发期物的**行内 FQN 形态**（`D-557`/`O90` 的教训：只挡字面量会漏 import 型与 FQN 型）。
DEV_FQN_PATTERN='com\.dddgn\.alice\.(fixture|debug)\.'

#: 去掉 javadoc 的 `{@link …}` 片段后再判命中（**文档引用不是代码依赖**，见头部注释）。
strip_doclinks() { sed 's/{@link[^}]*}//g'; }

#: 目录下**真实引用**（去掉 javadoc link 后仍有命中）的文件清单。
#  ⚠️ 这里**不能用 `grep -q`**：它一命中就退出 ⇒ 上游 `sed` 收 SIGPIPE ⇒ `set -o pipefail` 把
#  「命中」判成「未命中」（实测：`bot/BotManager` 被判成 0 命中，旧版门禁的靶子断言当场抓到）。
#  ⇒ 必须把上游输出**读完**（命令替换），不让管道提前关闭。
files_with_real_refs() {
    local dir="$1" pat="$2" f
    for f in $(grep -rlE "$pat" "$dir" --include=*.java || true); do
        if [ -n "$(strip_doclinks < "$f" | grep -E "$pat" || true)" ]; then
            echo "$f"
        fi
    done
}

#: 某文件里的真实引用行（`路径:行号: 文本`）。
refs_in() {
    strip_doclinks < "$1" | grep -nE "$2" | sed "s|^|$1:|" || true
}

fail=0
note() { printf '  %s\n' "$1" >&2; }

for f in "$JOB_LAUNCHER" "$PRODUCTION_DIR" "$TOOL_PROVISION" "$DEV_CREATE"; do
    if [ ! -e "$f" ]; then
        echo "PROVISION_CONTAINMENT_RESULT FAIL：缺关键路径 $f（⛔ 改名/搬家必须同刀改本脚本）" >&2
        exit 1
    fi
done

# ---------- ① 生产决策包：零开发期发料，且每条 assignJob 显式传生产策略 ----------
prod_hits=$(grep -rnE "$DEV_PATTERN" "$PRODUCTION_DIR" --include=*.java || true)
if [ -n "$prod_hits" ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：生产决策包 decision/ 出现开发期发料物（红线②）" >&2
    echo "$prod_hits" | sed 's/^/  /' >&2
    fail=1
fi
# ⚠️ **必须跨行匹配**：调用点常常把策略写在续行上（实测 `GoalDirector` 两处就是）
# ⇒ 用 `grep -Pzo` 抓到「`assignJob(` 到第一个 `;`」的整段实参，再按 NUL 记录过滤。
prod_loose=$(grep -rPzo "assignJob\([^;]*;" "$PRODUCTION_DIR" --include=*.java \
    | awk 'BEGIN{RS="\0"} /assignJob\(/ && !/ToolProvision\.PROMOTE_ONLY/ {gsub(/\n/," "); print}' || true)
if [ -n "$prod_loose" ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：生产包的 assignJob 调用没有显式传 ToolProvision.PROMOTE_ONLY" >&2
    echo "$prod_loose" | sed 's/^/  /' >&2
    note "（\`D-512\`：入口不分类，**策略由调用方传** ⇒ 生产调用点必须自己写明白）"
    fail=1
fi

# ---------- ② JobLauncher 只认识接口，不认识任何具体策略 ----------
jl_hits=$(refs_in "$JOB_LAUNCHER" "$DEV_PATTERN")
if [ -n "$jl_hits" ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：JobLauncher 认识具体发料策略（应只调 provisioning.provisionFor）" >&2
    echo "$jl_hits" | sed 's/^/  /' >&2
    fail=1
fi

# ---------- ③ gate is live（两个实现都真的在做事，而且只在各自该在的侧） ----------
live_promote_impl=$(strip_doclinks < "$TOOL_PROVISION" | grep -c "ToolSupply.promoteFromMain" || true)
live_create_impl=$(strip_doclinks < "$DEV_CREATE" | grep -c "FixtureToolKit" || true)
live_prod_promote=$(grep -rh "ToolProvision.PROMOTE_ONLY" "$PRODUCTION_DIR" --include=*.java | wc -l)
live_dev_create=$(( $(grep -rh "DevCreateProvision.INSTANCE" "$FIXTURE_ENTRY_DIR" --include=*.java | wc -l) \
                  + $(grep -rh "DevCreateProvision.INSTANCE" "$DEV_ENTRY_DIR" --include=*.java | wc -l) ))
if [ "$live_promote_impl" -lt 1 ] || [ "$live_create_impl" -lt 3 ] \
   || [ "$live_prod_promote" -lt 1 ] || [ "$live_dev_create" -lt 1 ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：门禁失去靶子" >&2
    note "实测：PROMOTE_ONLY 实现调 promoteFromMain=$live_promote_impl（期望 ≥1）  DEV_CREATE 实现调 FixtureToolKit=$live_create_impl（期望 ≥3）  生产传 PROMOTE_ONLY=$live_prod_promote（期望 ≥1）  开发期入口传 DEV_CREATE=$live_dev_create（期望 ≥1）"
    note "若确实改了机制，请同步更新本脚本的靶子，别让它静默变绿。"
    fail=1
fi

# ---------- ④ `job/` 包零命中开发期物（`JobLauncher` 也不再豁免） ----------
job_bad=0
for f in $(files_with_real_refs "$JOB_DIR" "$DEV_PATTERN"); do
    echo "PROVISION_CONTAINMENT_RESULT FAIL：job/ 包出现开发期发料物：$f" >&2
    refs_in "$f" "$DEV_PATTERN" | sed 's/^/  /' >&2
    note "job/ 是生产包 ⇒ 发料只能由**调用方传进来的策略**做，⛔ 不许在这里认识具体实现。"
    job_bad=$((job_bad + 1))
done
[ "$job_bad" -ne 0 ] && fail=1

# ---------- ⑤ ⭐ 刀 2：`bot/` 包零命中开发期物（旧版的 `BOT_EXEMPT` 白名单已删） ----------
bot_hits=$( { files_with_real_refs "$BOT_DIR" "$DEV_PATTERN"; \
              files_with_real_refs "$BOT_DIR" "$DEV_FQN_PATTERN"; } | sort -u )
if [ -n "$bot_hits" ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：bot/ 包出现开发期物（fixture/／debug/／FixtureToolKit）" >&2
    for f in $bot_hits; do
        refs_in "$f" "$DEV_PATTERN" | sed 's/^/  /' >&2
        refs_in "$f" "$DEV_FQN_PATTERN" | sed 's/^/  /' >&2
    done
    note "⭐ 刀 2 已把 48 个开发期派发入口搬去 fixture/FixtureDispatch（走 bot/BotManager.beginIdleTask 桥）"
    note "⇒ ⛔ 不许再长出新的反向依赖；也⛔ 不许用新白名单「复活」（旧 BOT_EXEMPT 的回收条件就是今天）。"
    fail=1
fi

# ---------- ⑥ gate is live（人口下限：防"把包搬空 ⇒ 假绿"） ----------
MIN_BOT_FILES=10
live_bot_files=$(find "$BOT_DIR" -name '*.java' | wc -l)
if [ "$live_bot_files" -lt "$MIN_BOT_FILES" ]; then
    echo "PROVISION_CONTAINMENT_RESULT FAIL：bot/ 只扫到 $live_bot_files 个 .java（下限 $MIN_BOT_FILES）⇒ 扫描路径坏了" >&2
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "PROVISION_CONTAINMENT_RESULT PASS: decision/ 零命中开发期发料且 assignJob 全部显式传 PROMOTE_ONLY；JobLauncher 只调策略接口；job/ 零命中；⭐ bot/ 零命中（BOT_EXEMPT 已删，扫 $live_bot_files 个 .java）；靶子：PROMOTE_ONLY 实现=$live_promote_impl DEV_CREATE 实现=$live_create_impl 生产传 PROMOTE_ONLY=$live_prod_promote 开发期入口传 DEV_CREATE=$live_dev_create"

#!/usr/bin/env bash
# 红线① 门禁（D-216 / D-217）：**补电通道只许住在夹具里**，生产路径一个字都不许碰。
#
# 背景：S4 的闭环执行器 `task/craft/MachineCycle` 是**夹具与生产共用**的同一份实现。
# 它的电前提是"机器已有电 ⇒ 放行；没有 ⇒ 如实失败（`machine_no_energy`）"，
# 唯一例外是调用方注入的 `MachineCycle.EnergyTopUp`（"按前提补电"，测试前提）。
# 生产语义是"没电就如实失败"，**不许凭空造能量** —— 所以那条通道只允许夹具实现。
#
# 本脚本把"只许"变成机械断言（三条，缺一不可）：
#   ① **造能量的调用**（`precharge(` / `PRECHARGE` / `setEnergy(`）命中面只能是夹具文件；
#      ⚠️ `api_precharge` 这种**日志措辞**不算 —— 判据是"有没有调写入"，不是"有没有提过这个词"；
#   ② **注入点 `EnergyTopUp`** 的命中面只能是「执行器里的定义 + 夹具里的唯一实现」
#      ⇒ 生产调用方（`CraftJob`）连类型名都不出现，它是**位置传 `null`**；
#   ③ **反向断言（gate is live）**：夹具里那两类符号确实还在 —— 否则一次重命名就能让本门禁静默变绿。
#
# 用法：bash tools/check-precharge-containment.sh
set -euo pipefail
cd "$(dirname "$0")/.."

FIXTURE="src/main/java/com/dddgn/alice/task/MachineCycleCheckTask.java"
EXECUTOR="src/main/java/com/dddgn/alice/task/craft/MachineCycle.java"
PRODUCTION_JOB="src/main/java/com/dddgn/alice/job/craft/CraftJob.java"

WRITE_PATTERN='precharge[[:space:]]*\(|PRECHARGE|setEnergy[[:space:]]*\('
INJECT_PATTERN='EnergyTopUp'

fail=0
note() { printf '  %s\n' "$1" >&2; }

# ---------- ① 造能量的调用不许出现在夹具之外 ----------
if [ ! -f "$FIXTURE" ]; then
    echo "PRECHARGE_CONTAINMENT_RESULT FAIL：夹具文件不存在：$FIXTURE" >&2
    exit 1
fi
offenders=$(grep -rEn "$WRITE_PATTERN" src/main/java --include=*.java \
    | grep -v "^${FIXTURE}:" || true)
if [ -n "$offenders" ]; then
    echo "PRECHARGE_CONTAINMENT_RESULT FAIL：造能量的调用出现在夹具之外（红线①）" >&2
    echo "$offenders" | sed 's/^/  /' >&2
    fail=1
fi

# ---------- ② 注入点只有一个实现者（生产调用方连类型名都不出现） ----------
inject_hits=$(grep -rEl "$INJECT_PATTERN" src/main/java --include=*.java | sort || true)
inject_expected=$(printf '%s\n%s\n' "$EXECUTOR" "$FIXTURE" | sort)
if [ "$inject_hits" != "$inject_expected" ]; then
    echo "PRECHARGE_CONTAINMENT_RESULT FAIL：EnergyTopUp 的命中面不是「定义 + 夹具」" >&2
    note "期望："
    printf '%s\n' "$inject_expected" | sed 's/^/    /' >&2
    note "实际："
    printf '%s\n' "$inject_hits" | sed 's/^/    /' >&2
    fail=1
fi
if grep -qE "$INJECT_PATTERN|$WRITE_PATTERN" "$PRODUCTION_JOB"; then
    echo "PRECHARGE_CONTAINMENT_RESULT FAIL：生产 Job 里出现了补电相关符号（$PRODUCTION_JOB）" >&2
    grep -nE "$INJECT_PATTERN|$WRITE_PATTERN" "$PRODUCTION_JOB" | sed 's/^/  /' >&2
    fail=1
fi

# ---------- ③ gate is live：夹具里确实还有那两类符号 ----------
live_write=$(grep -cE "$WRITE_PATTERN" "$FIXTURE" || true)
live_inject=$(grep -cE "$INJECT_PATTERN" "$FIXTURE" || true)
if [ "$live_write" -lt 2 ] || [ "$live_inject" -lt 1 ]; then
    echo "PRECHARGE_CONTAINMENT_RESULT FAIL：门禁失去靶子（夹具里 precharge/setEnergy 或 EnergyTopUp 不见了）" >&2
    note "夹具命中：写=$live_write 注入=$live_inject（期望 写≥2 且 注入≥1）"
    note "若确实改成了别的机制，请同步更新本脚本的靶子，别让它静默变绿。"
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "PRECHARGE_CONTAINMENT_RESULT PASS: 补电只命中夹具（$(basename "$FIXTURE")：写=$live_write 注入=$live_inject）；生产路径 $PRODUCTION_JOB 零命中"

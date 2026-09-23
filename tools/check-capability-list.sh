#!/usr/bin/env bash
# `B3` / `Q-22`（2026-09-23）：**能力清单不许与代码分叉**。
#
# 断言（真正的牙齿是**跨出处**的双向，见 `tools/capability-list.py` 的 `ASSERTIONS`）：
#   · 文档不陈旧（重新生成 ⇒ 逐字节相同）；
#   · 步声明 ↔ `RegressionBatteryTask.CURATION`（双向，无豁免）；
#   · 模块 `CheckProfile` ↔ `CURATION` 档位；`CheckModules.ALL` ↔ 电池成员（缺席须带理由）；
#   · `JobRequest.Kind` ↔ `JobKindContract` 表（双向）；`MovementType` ↔ `changesWorld()`；
#   · **人口下限** + 每节的**独立计数**交叉核对（解析崩塌 ⇒ 响亮失败，不许少一行悄悄过）。
#
# 为什么要有它：`survey/29 §3.8⑥` 的原话是「把『它知道的自己』做成**生成的表**……⇒
# 『它以为的自己』和『真实的自己』**结构上不可能分叉**」。只写文档不做门禁 ⇒ 一定会分叉，
# 而且分叉时没人会发现（活体样本：`docs/BATTERY_CURATION.md` §2 的小节计数漂到 15/35/15，
# 真值是 15/26/52，靠人读才发现）。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/capability-list.py --check

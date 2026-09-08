# 实体测试物品选择容器 —— 用户申请受理记录

- 日期：2026-08-22
- 基线：`49be37efb57375f4e4c82ee6d6f4fb18dfb98d85`（A1.1 已监督二审 PASS → CLIENT_TEST_PENDING）
- 申请人：user
- 状态：`NEEDS_USER_DECISION + PLANNER_TASK_DISPATCHED`

## 用户申请

1. **申请 A**：为 A1.1 转移测试增加游戏内实体测试物品：右键选择转移目标（destination），Shift+右键选择源容器（source），或类似设计。
2. **申请 B**：今后凡测试工具需要定位等复杂参数，至少制作一个游戏内实体测试物品给测试员使用。

## 事实核查

- A1.1 active plan（`.alice-supervision/active-plan.md` 第 42 行）明确排除 `Binding tool, Shift/right-click handling` —— 申请 A 超出当前批准范围，需新工作包授权。
- 项目已有成熟的"手持测试物品 右键/Shift+右键"入口先例（HANDOVER 第 249-252 行）：
  - 钻石斧 TargetSelector：右键挖掘、Shift+右键放置；
  - 金斧 soft_move_selector：右键 NATIVE_TRAVEL、Shift+右键 SELF_MOVE；
  - 钻石锄道路蓝图：右键两端、Shift+右键重置；
  - `alice:interface_scanner`：右键只读扫描（C1 S1-S4 已 USER_ACCEPTED）。
- 监督协议「客户端测试入口规则」第 1 条把"手持测试物品右键/Shift+右键"列为优先入口；第 96 行允许在**专门批准的工作包**中增加仅开发/管理员可用的交互测试物品。

## 受理结论

- 申请 A：受理为新的测试入口工作包。先派 `Alice 实现规划员+` 只读产出最小实现线路，我审核后呈报用户批准，再写入 active plan 并派发开发。禁止范围：不得改变转移语义/ledger/primitive；物品仅测试/管理员用途；不得拦截原版箱子 GUI；不得接入普通任务链。
- 申请 B：属于流程规范，直接写入监督协议「客户端测试入口规则」并同步 Windows。
- 在用户明确批准前，不实现任何代码，A1.1 状态保持 `CLIENT_TEST_PENDING`。

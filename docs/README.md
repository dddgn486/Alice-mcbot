# Alice 文档索引

> 当前入口。旧监督工作流和旧实验材料已移入 `archive/`，不要把归档文档当作当前开发规则。

## 每次 AI 会话先读

**第一入口：[`START_HERE.md`](START_HERE.md)** —— 一句话接手口令和完整恢复流程

然后读取：

1. [`AI_PROJECT_STATE.md`](AI_PROJECT_STATE.md) —— 当前目标、已验证状态、失败和下一步
2. [`AI_DEVELOPMENT_PLAYBOOK.md`](AI_DEVELOPMENT_PLAYBOOK.md) —— 轻量 AI/Forge 开发流程、skills、Windows 测试习惯
3. [`AI_DECISIONS.md`](AI_DECISIONS.md) —— 稳定架构决策
4. 与当前任务直接相关的 skill：`.alice-supervision/skills/`；跨模块功能先读 [`minimal-implementation-planning.skill.md`](../.alice-supervision/skills/minimal-implementation-planning.skill.md)

## 当前规则和状态

| 文件 | 用途 |
|---|---|
| `AI_PROJECT_STATE.md` | 唯一的短当前状态；跨会话恢复优先读这里 |
| `AI_DECISIONS.md` | 不应在补丁中悄悄推翻的决策 |
| `AI_TEST_MATRIX.md` | 测试物品/入口、预期行为和用户实测结果 |
| `AI_CHANGELOG.md` | 实际改动、失败、根因假设和下一步 |
| `AI_DEVELOPMENT_PLAYBOOK.md` | 开发协作和 skills 使用手册 |

## 架构与实现设计

- `AI_PLAYER_DESIGN.md`：总体架构和三层产品方向
- `PRODUCT_ARCHITECTURE_ROADMAP.md`：长期产品路线和 C0-C5 能力分级
- `EXECUTION_FRAMEWORK.md`：任务、感知和执行层现状
- `PATHING_REFACTOR.md`：曲面寻路、通道规划和道路隔离边界
- `MOVEMENT_SYSTEM_ARCHITECTURE.md`：移动系统设计
- `MINING_SAFETY_AND_PLANNING.md`：挖掘安全与规划约束

## 当前测试入口

- `BOT_CONTROLLER_QUICK_TEST.md`：BotController 快速测试
- 游戏内优先使用已有测试物品：`target_selector`、`interface_scanner`、`road_planner`、`transfer_endpoint_selector` 等
- 客户端实测在 Windows `D:\JAVA_projects\alice\`，由真人通过游戏内物品和键盘鼠标操作完成

## 参考资料

`reference/` 仅存放外部项目、数学模型或模组语义参考，不是当前实现授权：

- Baritone 移动参考
- 道路数学模型
- Mekanism GUI 语义参考

## 历史归档

`archive/legacy-design/`：旧设计和调查材料。  
`archive/legacy-testing/`：旧测试说明和同步清单。  
`archive/legacy-workflow/`：已废弃的 HANDOVER、dsh-agent-bus 和监督模板。

归档内容用于追溯，不作为新会话当前规则；如果归档文档与当前状态冲突，以用户本轮决定、当前代码和真实客户端证据为准。

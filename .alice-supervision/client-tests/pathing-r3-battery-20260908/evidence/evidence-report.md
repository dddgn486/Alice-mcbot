# R3 规划内核 + 一键自检电池 客户端验收证据

- 日期：2026-09-08
- 工件 SHA-256：`522cd4040c8c6a30635f16c3e4dd980776763191878efe2de5dd601bb28bb200`
- 场景：`/function alice_test:pathing_course`（孤立长方体开阔场景，固定起点 `(0,64,46)`）
- 入口：`alice:pathing_battery` 右键任意方块（零参数）
- 日志：`evidence/r3-battery-key-lines.log`（AI 直接读取 Windows 客户端 `latest.log`）

## 结果（一次运行，8 项全通过）

```
[R3 Battery] anchor_to_start foot=0, 64, 46 actualFoot=0, 64, 46
[R3 Battery] plan_flat=REACHED(2) plan_up=REACHED(1) plan_budget=SEARCH_LIMIT
[R3 Battery] item=traverse result=PASS actualFoot=0, 64, 47
[R3 Battery] item=diagonal result=PASS actualFoot=-1, 64, 47
[R3 Battery] item=ascend   result=PASS actualFoot=1, 65, 46
[R3 Battery] item=descend  result=PASS actualFoot=0, 63, 45
[R3 Battery] item=chain2 seg=0 result=PASS actualFoot=0, 63, 45   (COLUMN 容差)
[R3 Battery] item=chain2 seg=1 result=PASS actualFoot=0, 62, 44   (EXACT 容差)
[R3 Battery] SUMMARY plan_flat=REACHED(2) plan_up=REACHED(1) plan_budget=SEARCH_LIMIT
             traverse=PASS diagonal=PASS ascend=PASS descend=PASS chain2=PASS
```

## 关键验证点

| 验证点 | 证据 | 等级 |
|---|---|---|
| 规划状态语义：可达 = `REACHED` | `plan_flat` / `plan_up` | WINDOWS_CLIENT |
| 预算耗尽 ≠ 不可达 | `plan_budget=SEARCH_LIMIT`（非 `UNREACHABLE`） | WINDOWS_CLIENT |
| 四个基础 Movement 执行正确 | 四项 PASS + 脚位精确 | WINDOWS_CLIENT |
| 上升必须跳跃（D-025，台阶 0.6） | `ascend` PASS `(1,65,46)` | WINDOWS_CLIENT |
| 链式下降不再断链（D-026 合法位置集 + `to.above()`） | `chain2` 两段连续 PASS | WINDOWS_CLIENT |
| COLUMN/EXACT 分段容差（D-027） | seg1=COLUMN、seg2=EXACT，均 PASS | WINDOWS_CLIENT |
| 摘要不被 PASS 掩盖失败 | 本轮全 PASS；上一轮失败已正确显示 | WINDOWS_CLIENT |

## 上一轮失败与修复（对照）

- 上一轮：`chain2` 段2 `DESCEND_INVALID_PRECONDITION`，实际脚位 `(0,63,44)`。
- 根因：COLUMN 容差让段1 带动量结束，bot 滑入"下一目标列正上方、下落中"的位置；
  合法起点集 `{from,to}` 不含该位置。
- 修复：对齐 Baritone `MovementDescend.calculateValidPositions()`，合法集扩为
  `{from, to, to.above()}`；摘要逻辑改为失败覆盖 PASS。

## 待用户确认（视觉，日志无法证明）

- `ascend` 是否看到明显跳跃动作；
- `chain2` 段间是否还有明显回冲（D-027 预期：段1 顺势落入第二级，无掉头倒退）。

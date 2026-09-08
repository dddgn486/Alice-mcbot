# R2-C Movement 客户端测试证据报告

## 测试信息

- **测试日期**: 2026-09-07（22:42 – 23:08）
- **测试人员**: 用户（真人 Windows 客户端操作）
- **测试环境**: Windows 固定客户端 `D:\JAVA_projects\worldedit-test\versions/1.20.1-Forge_47.4.10`
- **AI 证据来源**: WSL 直接读取 Windows `logs/latest.log` 及当日轮转日志 `2026-09-07-{1,2,3,4}.log.gz`

## 涉及工件（按测试轮次）

| 轮次 | 工件 SHA-256 | 说明 |
|---|---|---|
| Round 1 | `1b00ed0df3e52652eb81c8703df7f5474b06aa38eb7b5ad432de9b9b13e066d6` | R2-C 首版（Ascend 带跳跃、Descend v1） |
| Round 2 | `871f4d51d47465ddec7a2d99c7636fe755e321a6071dd0b8f47ba862f5a2a6df` | Ascend 移除跳跃 + settling 放宽 |
| Round 3 | `89c38637e37d8b5f9bda7ab82fe390214a0fd0b5e1f59c73dbf9f7fe46c476db` | Descend 诊断探针版（已回收探针） |
| Round 4 | `804c5eb897a5337a07ff11286332805b1f2a4227d41bf1cdd4e5622982ed98cf` | Descend blockPosition 修复版（**验收版**） |
| 最终清洁版 | `9c0388aeafa77f9d7a32129dab5d0270d764a48f271e56cbee4ed048a2b8e8d9` | 与验收版代码相同，仅移除诊断探针（当前运行时） |

## 测试入口

```
/alice pathing diagonal  <northeast|northwest|southeast|southwest>
/alice pathing ascend    <north|south|east|west>
/alice pathing descend   <north|south|east|west>
```

## 测试轮次与结果

### Round 1（artifact `1b00ed0d`，日志: round1-*.txt）

| Movement | 结果 | 明细 |
|---|---|---|
| Diagonal | ✅ 4/4 completed | 全部 8 ticks，actualFoot 与目标一致，support=true，onGround=true，controllerActive=false |
| Ascend | ❌ 4 failed + 1 rejected | 均为 `ASCEND_SETTLING_TIMEOUT`（约 23-25 ticks）；rejected 为用户故意构造的前置条件失败测试（`ASCEND_INVALID_PRECONDITION`，符合预期） |
| Descend | ❌ 4 failed | 均为 `DESCEND_SETTLING_TIMEOUT`，actualFoot 未变，Bot 完全未移动 |

用户观察（Ascend）：Bot 没有弹跳，像普通走上一格台阶。
用户观察（Descend）：Bot 动都没动。

### Round 2（artifact `871f4d51`，日志: round2-*.txt）

| Movement | 结果 | 明细 |
|---|---|---|
| Ascend | ✅ 2 completed / 1 failed / 1 rejected | completed 两条 actualFoot 到达目标层（0,65,2 / 0,65,3）；failed 一条 actualFoot=(0,65,2) 而目标为 (0,65,1)——Bot 上了台阶但水平位置停在原列，settling 超时 |
| Descend | ❌ 4 failed | 仍全部 `DESCEND_SETTLING_TIMEOUT`，Bot 未移动 |

### Round 3（诊断探针轮，artifact `89c38637`，日志: round3-*.txt）

单次 Descend 探针输出（关键证据）：

```
[R2-C Descend] started ... from=-1, 65, 2 to=0, 64, 2
[R2-C Descend] EXECUTING phase entered, bot Y=65.0 fromY=65
[R2-C Descend] Y check passed, entering SETTLING      ← 第 1 tick 即进入 SETTLING
[R2-C Descend] failed ... reason=DESCEND_SETTLING_TIMEOUT
```

**根因确认**: `bot.getY() < fromFoot.getY() + 0.5D` 中 Bot 站在 Y=65 支撑面上时 `getY()==65.0`，条件恒真，EXECUTING 从未执行 `driveTowardTarget()`。此为实心证据，非推测。

**探针回收**: 根因确认后，Round 4 前该探针仍在代码中；验收后已全部删除（见"探针回收记录"）。

### Round 4（验收轮，artifact `804c5eb8`，日志: round4-*.txt + latest.log）

修复：`bot.blockPosition().getY() < spec.fromFoot().getY()`（检测方块脚位真实下降）。

| 方向 | from → to | 结果 | actualFoot | ticks | reason |
|---|---|---|---|---|---|
| east | (-1,65,2) → (0,64,2) | ✅ completed | (0,64,2) | 11 | - |
| north | (-1,65,2) → (-1,64,1) | ⚠️ failed | (-1,64,0) | 30 | DESCEND_SETTLING_TIMEOUT |
| south | (-1,65,2) → (-1,64,3) | ⚠️ failed | (-1,64,4) | 27 | DESCEND_SETTLING_TIMEOUT |
| west | (0,65,3) → (-1,64,3) | ⚠️ failed | (-2,65,3) | 28 | DESCEND_SETTLING_TIMEOUT |

**用户观察与裁定**: Bot 均成功下降一级并向目标方向移动；失败 3 例均为 Bot 下落到底后碰到相邻方块边缘、被 Minecraft 自动踩台阶机制再次站上（过冲一格），导致精确落点后置条件超时。
**用户明确表示**: 该过冲行为暂时不解决，不影响本轮验收。

## 最终验收状态

| Movement | IMPLEMENTED | COMPILES | SERVER_TESTED | WINDOWS_CLIENT | USER_ACCEPTED |
|---|---|---|---|---|---|
| Diagonal | ✅ | ✅ | ✅ | ✅ 4/4 完成 | ✅ |
| Ascend | ✅ | ✅ | ✅ | ✅ 完成为主，已知水平位置偶差 | ✅ |
| Descend | ✅ | ✅ | ✅ | ✅ 下降动作成立，落点过冲已知 | ✅（过冲暂不处理） |

## 已知限制与遗留问题（用户已裁定暂不处理）

1. **Descend 落点过冲**: 下降到底后可能被自动踩台阶机制带上相邻方块边缘，actualFoot 偏离目标一格，后置条件报 `DESCEND_SETTLING_TIMEOUT`。后续可参考 Baritone MovementDescend 对落点区/边缘滑动块（sliding block）的预处理来收紧。
2. **Ascend 水平位置偶差**: 自动踩台阶依赖 Bot 与台阶的接触相位，个别情况下 Bot 上层后水平位置仍停在原列，导致 settling 超时。
3. 两者都只在"失败重试一次"的自然使用模式下可恢复，不影响独立验证链目的。

## 探针回收记录

- Round 3 添加的临时探针（EXECUTING 进入日志、SETTLING 进入日志、每 10 tick 状态日志）已在验收确认后**全部删除**。
- 清洁版工件 `9c0388ae` 重新构建并同步到运行时 mods，SHA-256 校验一致。
- 生产代码中仅保留 `started` / `completed` / `failed` / `rejected` 终态日志。

## 证据文件清单

- `latest.log` / `debug.log`: 最终验收轮完整客户端日志
- `round1-diag-ok-ascend-descend-fail-r2c-logs.txt`: Round 1 全部 R2-C 日志
- `round2-ascend-partial-descend-fail-r2c-logs.txt`: Round 2 全部 R2-C 日志
- `round3-diagnostic-probe-r2c-logs.txt`: Round 3 根因探针日志
- `round4-final-descend-r2c-logs.txt`: Round 4 验收轮日志
- `evidence-report.md`: 本报告
- `test-case.md`: 测试用例说明

## 架构边界确认

- ✅ R2-C 为独立验证链，未接入 MineTask
- ✅ 未修改 Legacy PathExecutor
- ✅ 符合 R2-A 纯数据契约（MovementSpec / MovementExecutionFactory / MovementExecution）
- ✅ 终态清理 Controller 输入（全部日志 controllerActive=false）
- ✅ 日志前缀 `[R2-C Diagonal|Ascend|Descend]`

---

**归档日期**: 2026-09-08
**归档人**: Alice Forge Assistant（AI 直接采集 Windows 日志，非用户提供）

# Alice A1.1 Windows 客户端测试记录（精简版 C1-C4）

- 关联提交：`f655be2`（Windows overlay，含 370ff343 + 49be37e A1.1 实现）
- 计划 ID：`20260822-a1-1-focused-fixture-expansion-v1`
- 监督审核：`.alice-supervision/reviews/20260822-49be37e-a1-1-review.md`（原始二审）+ focused fixture 扩展审核（待补充）
- 测试人：`user`
- 状态：`NOT_STARTED`
- 测试根目录：`.alice-supervision/client-tests/f655be2-a1-1-client/`
- 场景证据目录：`.alice-supervision/client-tests/f655be2-a1-1-client/evidence/`
- 说明：A1/A2/A3/A4/A8 已由服务端 focused fixture 覆盖（见 pending 包），本客户端测试只保留必须实测的 C1-C4。

## 使用方式

一次完整客户端会话连续测试 C1-C4；不为每个场景重启客户端或复制日志：

```text
.alice-supervision/client-tests/f655be2-a1-1-client/evidence/
  evidence-report.md
  latest.log
  debug.log
  C1-external-mutation/
    evidence-report.md
    C1-result.png
  C2-hazard-interrupt/
    evidence-report.md
  C3-restart-takeover/
    evidence-report.md
  C4-vanilla-gui/
    evidence-report.md
    C4-gui.png
```

根目录 `evidence-report.md`、`latest.log`、`debug.log` 是本轮唯一的总表单与完整日志。总表单（测试日期、Windows Git SHA、环境、request UUID、item/count、总体结果、用户总体决定）只填写一次；场景目录只填写本场景"是/否"观察并放置专用截图；不同场景截图不得混放。开始测试前，用真实截图直接覆盖同名的零字节 `.png` 占位文件；审核时空占位文件不算证据。

## 精简场景矩阵（C1-C4）

| ID | 场景目录 | 最少动作 | 核心观察 | 最少证据 | 不可替代原因 |
|---|---|---|---|---|---|
| C1 | `evidence/C1-external-mutation/` | Bot 移动中人工修改目标箱 | `simulation_conflict` 或 discrepancy；不自动重试 | 场景表单、目标/Bot结果截图 | 真实时序竞争、人工干预 |
| C2 | `evidence/C2-hazard-interrupt/` | 转移移动阶段触发危险 | `SUSPENDED`、稳定 survival code、正确 location；不自动恢复 | 场景表单 | 真实生存模拟环境 |
| C3 | `evidence/C3-restart-takeover/` | 在途时停止并重启服务端 | `SUSPENDED/manual_takeover_required`；不自动继续写入 | 场景表单；共用完整日志 | 真实重启与持久化 |
| C4 | `evidence/C4-vanilla-gui/` | 打开普通原版箱子 GUI | GUI 保持原版，无 Alice 拦截或额外写入 | 场景表单、GUI 截图 | 客户端渲染层 |

**注**：A1 权限与命令、A2 正常转移、A3 在途可见性、A4 容量不足、A8 Abort 已由服务端 focused fixture 覆盖，无需客户端重复测试。

## 共用停止条件

出现无法解释的物品数量变化、自动 retry/resume/finish-insert、受保护任务被替换、错误端点写入、GUI 拦截、崩溃或卡死时立即停止。保留该场景目录、完整日志和世界副本，不重试已发生变异的请求。

## 填写位置

所有需要填写的字段（测试日期、Windows Git SHA、环境、item/count、request UUID、各场景结果汇总、异常、用户总体决定）只填写在 `evidence/evidence-report.md`，不在此文件重复填写。

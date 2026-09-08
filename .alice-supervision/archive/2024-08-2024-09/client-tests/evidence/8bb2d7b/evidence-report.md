# Alice C1 扫描器客户端测试报告

> 使用方法：直接在本文件填写 `[填写]` 内容；把同目录的预留文件名替换为实际证据文件。测试完成后无需在会话中逐项汇报。

## 基本信息

- 测试日期时间：[填写]
- Windows Git SHA：[填写]
- 世界类型（新世界/旧存档）：[填写]
- 是否正常进入地图并获得控制：[填写]
- 总体结果（四选一）：`USER_ACCEPTED` / `NEEDS_FIX` / `NEEDS_REPLAN` / `NEEDS_DISCUSSION`

## 最小文件清单

必须保留并使用以下文件名：

- `evidence-report.md`：本报告，填写后保留
- `latest.log`：本次 Windows 客户端运行产生的完整日志
- `debug.log`：本次 Windows 客户端运行产生的完整日志
- `S1-item.png`：场景 1 物品栏/手持截图
- `S2-scan.png`：场景 2 箱子扫描后截图
- `S3-no-block-entity.png`：场景 3 石头或泥土扫描后截图
- `S4-vanilla-shovel.png`：场景 4 原版钻石铲行为截图

发生卡死或崩溃时，额外放入：

- `hang-client-thread-dump.txt`（如能取得）
- `hang-server-thread-dump.txt`（如能取得）

## 场景结果

### S1 物品身份

- 结果（PASS/FAIL）：[填写]
- 观察：名称是否为 `接口扫描器` / `Interface Scanner`，是否为钻石铲外观，是否没有原版铲行为：[填写]
- 证据：`S1-item.png`

### S2 箱子或机器只读扫描

- 结果（PASS/FAIL）：[填写]
- 观察：是否不打开 GUI、是否显示扫描提示、箱子/机器内容是否未改变：[填写]
- 日志关键字是否出现：`接口扫描(alice:interface_scanner)`、`schema=v1`、`status=OK`：[填写]
- 是否出现通用输入/输出角色推断：[填写]
- 证据：`S2-scan.png`、`latest.log`、`debug.log`

### S3 非方块实体

- 结果（PASS/FAIL）：[填写]
- 观察：石头/泥土是否未改变、是否未造土径、是否无异常或任务启动：[填写]
- 日志是否出现 `status=NO_BLOCK_ENTITY`：[填写]
- 证据：`S3-no-block-entity.png`、`latest.log`、`debug.log`

### S4 原版钻石铲隔离

- 结果（PASS/FAIL）：[填写]
- 观察：泥土造土径是否正常；箱子/机器交互是否正常；是否没有 Alice 扫描消息：[填写]
- 证据：`S4-vanilla-shovel.png`、`latest.log`、`debug.log`

## 异常与停止

- 是否发生卡死/崩溃/异常/世界修改/重复事件/bot 任务：[填写]
- 发生时的场景：[填写]
- 说明：[填写]
- 若发生卡死，是否已停止重试并保存线程转储：[填写]

## 监督员使用区

- 审核状态：待审核
- 审核备注：[留空]

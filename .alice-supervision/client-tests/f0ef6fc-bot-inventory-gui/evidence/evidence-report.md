# Alice Bot Inventory GUI 完整测试报告（精简版 G1-G10）

> 关联提交：`f0ef6fc`（feat: add bot inventory GUI with task read-only guard）
> 计划 ID：`20260823-bot-inventory-gui-v1`
> 本文件只填写一次，覆盖同一客户端会话内连续完成的 G1-G10。`latest.log` 和 `debug.log` 是本轮唯一的完整日志；各场景目录只填写场景观察并放置专用截图。

## 用户决策（必填）

- **总体结果**：`PASS` / `FAIL` / `UNCLEAR`
- **用户总体决定**：`USER_ACCEPTED` / `NEEDS_FIX` / `NEEDS_REPLAN` / `NEEDS_DISCUSSION`
- **备注**（可选）：[对整体测试的补充说明]

## 场景结果汇总（必填）

| 场景 | 结果 | 备注 |
|---|---|---|
| G1 入口与授权（op 打开/非 op 拒绝） | PASS / FAIL / UNCLEAR | |
| G2 36 普通槽显示 | PASS / FAIL / UNCLEAR | |
| G3 4 装备槽显示 | PASS / FAIL / UNCLEAR | |
| G4 主手槽与 equipment 渲染对齐 | PASS / FAIL / UNCLEAR | |
| G5 空槽/堆叠数量 | PASS / FAIL / UNCLEAR | |
| G6 GUI 隔离（不影响原版箱子/背包） | PASS / FAIL / UNCLEAR | |
| G7 bot 不在场 → bot_unavailable | PASS / FAIL / UNCLEAR | |
| G8 **任务保护（Bot 执行任务时只读可看禁写）** | PASS / FAIL / UNCLEAR | |
| G9 空闲交互（拿取/放入/Shift 正常） | PASS / FAIL / UNCLEAR | |
| G10 客户端无服务端崩溃 | PASS / FAIL / UNCLEAR | |

## 异常与停止（必填）

- 是否出现 GUI 崩溃、类加载错误、inventory 异常变更、任务被破坏？`是 / 否`
- 发生场景：[填写，若无则填"不适用"]
- 是否已停止该变异请求，且未重试？`是 / 否 / 不适用`
- 说明：[填写，若无则填"无"]

---

## 自动从日志提取的信息（监督员核对，用户不填）

以下信息由监督员从 `latest.log` 提取，用户无需填写：

- 测试日期时间：[监督员从日志首行时间戳提取]
- Windows Git SHA：[监督员从测试包目录名提取：`f0ef6fc`]
- 客户端/服务端 Forge 与 Mod 版本：[监督员从日志 ModLauncher 行提取]
- Bot 名称/UUID：[监督员从日志 `bot=` / 假人登录行提取]
- 世界/存档条件：[监督员从日志 `ServerLevel[...]` 提取]

## 监督员使用区

- 审核状态：待审核
- 审核备注：[留空]

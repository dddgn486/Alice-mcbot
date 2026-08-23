# Alice 客户端测试总表单（精简版模板）

> 本文件只填写一次，覆盖同一客户端会话内连续完成的全部场景。`latest.log` 和 `debug.log` 是本轮唯一的完整日志；各场景目录只填写场景观察并放置专用截图。

## 用户决策（必填）

- **总体结果**：`PASS` / `FAIL` / `UNCLEAR`
- **用户总体决定**：`USER_ACCEPTED` / `NEEDS_FIX` / `NEEDS_REPLAN` / `NEEDS_DISCUSSION`
- **备注**（可选）：[对整体测试的补充说明，如遇到的非验收范围现象]

## 场景结果汇总（必填）

| 场景 | 结果 | request UUID（如适用） | 备注 |
|---|---|---|---|
| C1 ... | PASS / FAIL / UNCLEAR | | |
| C2 ... | PASS / FAIL / UNCLEAR | | |
| C3 ... | PASS / FAIL / UNCLEAR | | |
| C4 ... | PASS / FAIL / UNCLEAR | | |

## 异常与停止（必填）

- 是否出现无法解释的物品/世界变化、自动 retry/resume/finish-insert、任务替换、错误端点写入、GUI 拦截、崩溃或卡死？`是 / 否`
- 发生场景和 request UUID：[填写，若无则填"不适用"]
- 是否已停止该变异请求，且未重试？`是 / 否 / 不适用`
- 说明：[填写，若无则填"无"]

---

## 自动从日志提取的信息（监督员核对，用户不填）

以下信息由监督员从 `latest.log` 提取，用户无需填写：

- 测试日期时间：[监督员从日志首行时间戳提取]
- Windows Git SHA：[监督员从 `.alice-supervision/client-tests/<sha>-*/` 目录名提取]
- 客户端/服务端 Forge 与 Mod 版本：[监督员从日志 ModLauncher 行提取]
- Bot 名称/UUID：[监督员从日志 `bot=` / 假人登录行提取]
- 世界/存档条件：[监督员从日志 `ServerLevel[...]` 提取]
- item/count：[监督员从日志 `transfer request=` 与场景表单对应]

## 监督员使用区

- 审核状态：待审核
- 审核备注：[留空]

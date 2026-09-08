# 5fa33ab 右键修复 Windows 回归测试报告

> 本文件只填写一次。R2/R3/R4/R9/R10 尽量同一会话连续完成，R10 仅重启服务器/世界；`latest.log`、`debug.log` 为本轮唯一完整日志。

## 基本信息
- 测试日期时间：[填写]
- Windows Git SHA：[填写，应为 `5fa33ab36730eda59a95e9beda182842b0ff430d`]
- 世界/存档：[填写]
- Forge/Mod 版本：[填写]
- Bot 名称/UUID：[填写]
- 总体结果：`PASS / FAIL / UNCLEAR`
- 用户总体决定：`USER_ACCEPTED / NEEDS_FIX / NEEDS_REPLAN / NEEDS_DISCUSSION`

## 场景汇总
| 场景 | 结果 | 日志时间/关键字 |
|---|---|---|
| R2 Shift+右键 source | PASS / FAIL / UNCLEAR | |
| R3 普通右键 destination + GUI | PASS / FAIL / UNCLEAR | |
| R4 非容器拒绝 | PASS / FAIL / UNCLEAR | |
| R9 status/clear/失败保留 | PASS / FAIL / UNCLEAR | |
| R10 重启失效 | PASS / FAIL / UNCLEAR | |

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 是否停止且未重试已变异 request？`是 / 否 / 不适用`
- 场景、request UUID、现象：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]

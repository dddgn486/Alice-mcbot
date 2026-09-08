# legacy 上升兼容（D-030）客户端验收证据

- 日期：2026-09-08
- 工件 SHA-256（验证时）：`9e58a99fa5205d12485e8750290ed5f1857a832bb0836249ebadf691cd0f8a33`
- 最终工件（含 `/alice come` 摆位命令）：`8a88bcd92d8b4793826b5e9646312089dcb435098b5e2f7e3006a285f806e02f`
- 场景：`/function alice_test:legacy_ascend`（孤立长方体开阔场景）
- 入口：`/alice follow on` + 玩家走上 1 格台阶 / 走向 2 格高墙
- 日志：`evidence/legacy-ascend-key-lines.log`

## 结果（用户确认"测试和预期相同"）

| 观察点 | 结果 |
|---|---|
| 1 格台阶 | bot 跟随并**爬上去**（日志 `pos=9,65,66` / `pos=7,65,67`，y=65 为台阶层，支撑 y=64） |
| 2 格高墙 | bot **没有爬上去**（对照组） |

关键推断：`maxUpStep=0.6` 下从 y=64 到 y=65 必须跳跃，日志中 bot 确实到达 y=65 → D-030 的条件跳跃生效。

## 观察到的 legacy 原有行为（非回归）

- `follow_target_not_on_safe_surface`：玩家站在墙顶/不安全面时，`FollowTask` 拒绝跟随（`FollowTask:137-141` 既有检查）。
- `follow_no_path`：第一次测试时 bot 仍留在上一个场景（课程底部 `0,62,44`），与玩家距离过远无路径；
  已补零参数命令 `/alice come`（把最近 bot 传送到玩家身边，使用带头部同步的传送重载）解决该测试摩擦。

## 验证等级

- 1 格台阶上升：`WINDOWS_CLIENT`（服务端日志实证）+ `USER_ACCEPTED`（用户确认符合预期）
- 2 格高墙不攀爬：`USER_ACCEPTED`（对照组）

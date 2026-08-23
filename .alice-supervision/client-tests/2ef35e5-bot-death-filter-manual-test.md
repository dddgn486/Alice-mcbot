# Bot 死亡过滤修复手动测试指南

- 关联提交：`2ef35e5`（fix: filter dead bot from restoration to prevent system failure）
- 计划 ID：`20260823-bot-death-filter-fix-v1`
- 调研报告：`.alice-supervision/research/bot-death-resurrection-mechanism-20260823.md`
- 测试人：`user`
- 状态：`NOT_STARTED`

## 修复说明

**问题**：Bot 死亡后被保存到存档，下次加载时恢复死亡状态导致 Alice 系统失效（事件系统卡死、selftest 无响应）。

**根因**：
1. `restoreFromWorld()` 未检查存档中 Bot 的健康状态
2. `onServerStopping()` 无条件保存所有 Bot（包括死亡 Bot）

**修复**：
1. `saveToWorld()` 新增保存 `Health` 字段
2. `restoreFromWorld()` 检查 `savedHealth <= 0.0f`，跳过恢复并清除存档
3. `onServerStopping()` 只保存健康的 Bot（`getHealth() > 0.0f`）

**服务端验证**：compileJava PASS、既有 fixture 保持 PASS（00:57:53）。

---

## 测试方法 1：手动修改存档（推荐）

**目标**：验证 `restoreFromWorld()` 的死亡 Bot 过滤逻辑。

### 步骤

1. **启动服务器，生成 Bot**：
   ```
   /alice spawn 14 -60 53 tango
   ```

2. **退出存档到主菜单**（不要杀死 Bot）

3. **手动修改存档文件**：
   - 打开 `saves/<存档名>/data/alice_bot.dat`（用 NBT 编辑器，如 NBTExplorer）
   - 找到 `Health` 字段，修改为 `0.0f`
   - 保存文件

4. **重新进入存档**

5. **观察日志**（`run/logs/latest.log`）：
   - ✅ **预期**：`[alice] 存档假人已死亡 (health=0.0),跳过恢复并清除存档`
   - ❌ **失败**：Bot 仍然出现，或系统失效（selftest 无响应）

6. **验证系统正常**：
   - Tab 列表无 Bot
   - `/alice selftest` 能正常执行（日志输出 `SELFTEST 启动: mode=smoke`）
   - 能重新生成 Bot（`/alice spawn`）

---

## 测试方法 2：自然死亡场景（可选）

**目标**：验证 Bot 死亡后重启的完整流程。

### 步骤

1. **启动服务器，生成 Bot**：
   ```
   /alice spawn 14 -60 53 tango
   ```

2. **杀死 Bot**（用剑或 `/kill tango`）

3. **观察**：
   - Bot 倒地红色动画（这是预期的，短期修复不解决动画问题）
   - 删除 `alice_bot.dat` 后系统恢复正常（已验证，不需重复）

4. **不删除存档，直接重启客户端**

5. **重新进入存档**

6. **观察日志**：
   - ✅ **预期**：`[alice] 存档假人已死亡 (health=0.0),跳过恢复并清除存档`
   - ✅ **预期**：Tab 列表无 Bot，系统正常

7. **验证系统正常**：
   - `/alice selftest` 能正常执行
   - 能重新生成 Bot

---

## 关键观察点

**修复前**：
- 死亡 Bot 被保存到 `alice_bot.dat`
- 重启后 Bot 仍在，但处于不可交互状态（红色倒地）
- Alice 事件系统失效（selftest 卡住、攻击无反应）

**修复后（预期）**：
- 死亡 Bot 不会被保存（`onServerStopping` 过滤）
- 即使存档中有死亡 Bot（手动修改或竞态残留），重启时也会被过滤并清除
- 日志输出 `存档假人已死亡 (health=0.0),跳过恢复并清除存档`
- 系统正常（selftest 能执行、能重新生成 Bot）

---

## 停止条件

如果出现以下情况，立即停止并告诉我：
- 修改存档 `Health=0.0f` 后重启，Bot 仍然出现
- 日志未输出 `跳过恢复并清除存档` 消息
- 系统仍失效（selftest 无响应）
- 崩溃或其他异常

---

## 下一步

测试完成后：
1. 告诉我"Bot 死亡过滤测试完毕"
2. 如果 PASS → 继续测试 Bot 手持物品渲染（V1-V3，之前暂停的）
3. 如果你希望完整死亡流程（掉落物品、3 秒复活）→ 实施长期方案 C

---

## 长期方案 C（可选，用户期望）

**目标**：Bot 像正常生物死亡（掉落物品、完整动画、3 秒后自动复活）

**实施范围**：
- 重写 `BotPlayer.die()` 方法（允许掉落物品、设置复活计时器）
- 实现 `BotPlayer.tick()` 复活逻辑（3 秒后传送回家、恢复满血）
- 任务中断并标记 `failed:bot_died`

**时间成本**：30-40 分钟实施 + 20-30 分钟客户端验证

**你的选择**：
- 现在就实施方案 C？
- 先测试完 Bot 手持物品渲染，再决定是否实施方案 C？

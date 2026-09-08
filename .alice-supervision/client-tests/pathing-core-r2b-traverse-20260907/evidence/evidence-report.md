# R2-B Traverse 客户端测试证据报告

## 测试信息

- **测试日期**: 2026-09-07
- **测试人员**: 用户 (真人 Windows 客户端)
- **工件**: alice-1.0.0-1.20.1.jar
- **工件 SHA-256**: `fc0ad051208422882b5d8c1060bb4afe822552a1ef753ac5e0964b8e09e0fbbe`
- **Git HEAD**: 3dbe84c (diag(phys): add disableable M3 M5 packet observer)
- **测试环境**: Windows 固定客户端 `D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10`

## 测试目标

验证 R2-B Traverse 执行器在真实客户端环境中的基础移动能力：
- 同高度四向相邻移动（north/south/east/west）
- 前置条件检查（支撑、onGround、空间）
- 终态报告（actualFoot、支撑、onGround、controllerActive）
- 超时保护和清理机制

## 测试入口

**命令**: `/alice pathing traverse <north|south|east|west>`
- 自动从 Bot 当前位置派生目标
- 严格几何约束：只接受同高度四向相邻
- 100 tick 执行预算

## 测试执行

### Test 1: East (东)
```
Command: /alice pathing traverse east
Bot: tango
From: (1, 64, 2)
To: (2, 64, 2)
```

**日志**:
```
[21:54:40.064] [R2-B Traverse] started session=r2b-traverse-5f4fa7f1-f058-48a0-bb37-00dd6f196706 bot=tango from=1, 64, 2 to=2, 64, 2 support=true onGround=true
[21:54:40.311] [R2-B Traverse] completed session=r2b-traverse-5f4fa7f1-f058-48a0-bb37-00dd6f196706 from=1, 64, 2 to=2, 64, 2 actualFoot=2, 64, 2 support=true onGround=true controllerActive=false ticks=6 reason=-
```

**结果**: ✅ **COMPLETED**
- 实际到达目标脚位 (2, 64, 2)
- 保持支撑和 onGround
- Controller 正确清理
- 耗时 6 ticks

---

### Test 2: North (北)
```
Command: /alice pathing traverse north
Bot: tango
From: (2, 64, 2)
To: (2, 64, 1)
```

**日志**:
```
[21:54:47.461] [R2-B Traverse] started session=r2b-traverse-e8591da9-43c3-4c41-889d-05c2065b763f bot=tango from=2, 64, 2 to=2, 64, 1 support=true onGround=true
[21:54:47.712] [R2-B Traverse] completed session=r2b-traverse-e8591da9-43c3-4c41-889d-05c2065b763f from=2, 64, 2 to=2, 64, 1 actualFoot=2, 64, 1 support=true onGround=true controllerActive=false ticks=6 reason=-
```

**结果**: ✅ **COMPLETED**
- 实际到达目标脚位 (2, 64, 1)
- 保持支撑和 onGround
- Controller 正确清理
- 耗时 6 ticks

---

### Test 3: South (南)
```
Command: /alice pathing traverse south
Bot: tango
From: (2, 64, 1)
To: (2, 64, 2)
```

**日志**:
```
[21:54:51.161] [R2-B Traverse] started session=r2b-traverse-48556dab-461a-4266-8a9b-fcfb76100843 bot=tango from=2, 64, 1 to=2, 64, 2 support=true onGround=true
[21:54:51.411] [R2-B Traverse] completed session=r2b-traverse-48556dab-461a-4266-8a9b-fcfb76100843 from=2, 64, 1 to=2, 64, 2 actualFoot=2, 64, 2 support=true onGround=true controllerActive=false ticks=6 reason=-
```

**结果**: ✅ **COMPLETED**
- 实际到达目标脚位 (2, 64, 2)
- 保持支撑和 onGround
- Controller 正确清理
- 耗时 6 ticks

---

### Test 4: West (西)
```
Command: /alice pathing traverse west
Bot: tango
From: (2, 64, 2)
To: (1, 64, 2)
```

**日志**:
```
[21:54:58.161] [R2-B Traverse] started session=r2b-traverse-26a543f8-7c84-4b4a-ac5f-fba0312a8baf bot=tango from=2, 64, 2 to=1, 64, 2 support=true onGround=true
[21:54:58.412] [R2-B Traverse] completed session=r2b-traverse-26a543f8-7c84-4b4a-ac5f-fba0312a8baf from=2, 64, 2 to=1, 64, 2 actualFoot=1, 64, 2 support=true onGround=true controllerActive=false ticks=6 reason=-
```

**结果**: ✅ **COMPLETED**
- 实际到达目标脚位 (1, 64, 2)
- 保持支撑和 onGround
- Controller 正确清理
- 耗时 6 ticks

---

## 测试总结

### 通过项
✅ **四向移动**: East/North/South/West 全部成功  
✅ **精确到达**: 所有测试的 actualFoot 与目标一致  
✅ **物理状态**: 所有测试保持 support=true, onGround=true  
✅ **清理机制**: 所有测试终态 controllerActive=false  
✅ **性能表现**: 所有测试均在 6 ticks 内完成，远低于 100 tick 预算  
✅ **日志前缀**: 所有日志正确使用 `[R2-B Traverse]` 前缀  

### 关键特性验证
- **严格几何约束**: 命令只接受四向参数
- **自动目标派生**: 从当前位置自动计算相邻目标
- **前置条件检查**: started 日志确认 support/onGround
- **终态报告**: completed 日志包含完整状态
- **超时保护**: 虽未触发，但框架已实现 100 tick 预算

### 用户反馈
**验收状态**: `USER_ACCEPTED`  
用户在真实 Windows 客户端环境中确认测试通过。

## 证据文件清单

- `latest.log`: 完整客户端日志
- `debug.log`: 详细调试日志
- `r2b-traverse-logs.txt`: 提取的 R2-B Traverse 专项日志
- `evidence-report.md`: 本报告

**注**: 本次测试未提供截图，日志证据已充分验证功能正确性。

## 架构边界确认

✅ **独立验证链**: R2-B 未接入生产 MineTask  
✅ **不修改 Legacy**: 旧 PathExecutor 保持不变  
✅ **契约遵守**: 实现符合 R2-A 纯数据契约  
✅ **终态清理**: 所有路径正确清理 BotController 输入  

---

**归档日期**: 2026-09-07  
**归档人**: Alice Forge Assistant

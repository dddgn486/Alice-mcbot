---
name: alice-inventory-transaction-conservation
description: 让 Alice 物品搬运以服务端守恒、阶段记录和可恢复失败为核心。
---
# Alice Inventory Transaction Conservation

## 何时使用
新增或修改箱子、Bot 背包、capability、机器输入输出、转移、重启或中断恢复时使用。

## 核心原则
每次写入前后都要能回答物品在哪里、数量是多少、是否仍属于这次事务。客户端 GUI 显示不是库存事实。

## 最小阶段

```text
planned -> admitted -> extracted -> carried -> inserted -> verified
```

失败时进入 `FAILED` 或 `MANUAL_REVIEW`，不要靠重复执行掩盖未知状态。

## 检查重点

- 服务端验证权限、维度、端点存在和数量；
- 优先 `simulate`，再重新读取后执行实际写入；
- post 条件检查数量/物品身份/NBT 边界；
- 同一 requestId 不重复执行；
- 客户端界面只用于观察和发起请求，不能直接成为真相。

## 轻量验证

一个正常单物品案例 + 一个容量不足或中断案例即可作为早期证据。记录短日志：`requestId/state/source/count/destination/result`。

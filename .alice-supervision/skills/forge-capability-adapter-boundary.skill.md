---
name: forge-capability-adapter-boundary
description: 区分 Forge capability 的事实读取与模组业务语义，防止未知机器被错误写入。
---
# Forge Capability and Adapter Boundary

## 何时使用
扫描机器、读取库存/能源/流体、设计模组适配器或准备写入未知方块时使用。

## 核心原则
Capability 证明“这里暴露了某种接口”，不自动证明槽位含义、配方、模式、权限或安全写入方式。

## 能力分级

```text
C0 识别 ID/标签
C1 只读 capability 事实
C2 标准接口的受限操作
C3 玩家/整合包配置驱动语义
C4 版本化模组原生适配
C5 经客户端和服务端验证的完整技能
```

未知或版本不匹配时默认降级为 C0/C1，只读报告或请求用户配置。不要让 LLM 猜槽位、猜配方或生成任意网络包。

## 轻量验证

先用独立扫描物品显示 `blockId/interface/type/values/status`。只有当事实和语义都明确时，才增加一个最小写操作，并记录前后差值；不要求一次覆盖所有整合包机器。

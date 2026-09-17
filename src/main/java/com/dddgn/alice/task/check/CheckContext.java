package com.dddgn.alice.task.check;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 模块构建自检步时能用到的**外部依赖**（由电池/未来的编排器提供）。
 *
 * <p>模块**只能**通过它拿 bot / 观察者 / 作用域 —— 这样模块本身**不持有编排状态**，
 * 才能保证"一个模块可以单独跑"（R-2 的验收目标 ✓）。
 */
public interface CheckContext {

    /** 被检 bot（会话里的那个）。 */
    BotPlayer bot();

    /** 观察者玩家（可空：无头环境下可能没有；用到它的步必须自判前提）。 */
    ServerPlayer observer();

    /** 本步的账本/写入预算作用域缓冲。 */
    ScopeBuffer scope();
}

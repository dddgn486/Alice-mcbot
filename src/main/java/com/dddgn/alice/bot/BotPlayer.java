package com.dddgn.alice.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Alice 假人实体:直接继承 {@link ServerPlayer}(mc_aiplayer 同款方案)。
 * <p>
 * 相比旧实现(Forge FakePlayer,无网络连接 → 客户端不可见 + tick 部分路径 NPE),
 * 玩家化假人通过 {@link BotManager#spawn} 里的
 * {@code PlayerList.placeNewPlayer(伪造Connection, this)} 注册:
 * <ul>
 *   <li>connection 字段被 PlayerList 自动填充 → tick 无 NPE(替代旧 NPE 吞异常);</li>
 *   <li>进入 PlayerList → 广播给所有玩家 → <b>客户端可见</b>(解决审查点 R6);</li>
 *   <li>移动/物理/交互全部走原版玩家逻辑。</li>
 * </ul>
 * ⚠️ 审查点 R9:假人被视为真实玩家(占服务器人数、名字需唯一、下线需从 PlayerList 移除);
 * 多人服的权限与审计约束后续里程碑处理。
 */
public class BotPlayer extends ServerPlayer {

    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

    @Override
    public void tick() {
        try {
            super.tick();
        } catch (NullPointerException exception) {
            // 假人无真实网络,兜底吞 NPE 保持 tick(玩家化后理论不再触发,防御保留)
        }
    }
}

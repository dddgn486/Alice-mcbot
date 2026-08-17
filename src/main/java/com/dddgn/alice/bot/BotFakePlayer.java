package com.dddgn.alice.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;

/**
 * 假人实体:在 Forge FakePlayer 基础上加 tick NPE 防护。
 * <p>
 * ⚠️ 审查点 4:假人没有 networkHandler(无真实网络连接),ServerPlayer.tick 的部分代码路径
 * 可能对 null 网络处理器解引用;此处沿用 mc_aiplayer 的做法吞掉 NPE 保持运行,
 * M0 实测确认是否需要更精细的防护。</p>
 */
public class BotFakePlayer extends FakePlayer {

    public BotFakePlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Override
    public void tick() {
        try {
            super.tick();
        } catch (NullPointerException exception) {
            // 假人无网络连接,吞掉 NPE 保持 tick 循环(与 mc_aiplayer AIPlayerEntity 一致)
        }
    }
}

package com.dddgn.aibot.bot;

import com.dddgn.aibot.action.BotMiner;
import com.dddgn.aibot.action.BotWalker;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 假人管理:tick 驱动、创建、当前动作分配。
 * <p>
 * ⚠️ 审查点 1:tick 采用「全局 ServerTickEvent 驱动」而非实体自身 tick 内嵌动作逻辑,
 * 与 mc_aiplayer 的 ActionPack.onUpdate 一致——好处是动作逻辑与实体解耦、易测。</p>
 */
public final class BotManager {

    /** 所有在线假人。 */
    private static final Map<UUID, BotSession> BOTS = new HashMap<>();

    private BotManager() {
    }

    /** 在指定位置生成假人并加入世界。 */
    public static FakePlayer spawn(ServerLevel level, BlockPos pos, String name) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        BotFakePlayer bot = new BotFakePlayer(level, profile);
        bot.setPos(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        // M0 简化:直接给主手一把钻石镐——空手挖石头约15秒会撞上挖掘超时,
        // 且给镐可顺便验证「原版工具速度机制在假人身上生效」(getDestroyProgress 读属性)
        bot.getInventory().setItem(bot.getInventory().selected, new ItemStack(Items.DIAMOND_PICKAXE));
        level.addFreshEntity(bot);
        BOTS.put(bot.getUUID(), new BotSession(bot));
        return bot;
    }

    public static void remove(FakePlayer bot) {
        BOTS.remove(bot.getUUID());
    }

    /** 取一个假人(无则生成,用于测试命令)。 */
    public static FakePlayer firstOrSpawn(ServerLevel level, BlockPos pos) {
        for (BotSession session : BOTS.values()) {
            if (session.bot().level() == level) {
                return session.bot();
            }
        }
        // 出生在目标上方(而非目标方块内部,避免卡进方块窒息)
        return spawn(level, pos.above(), "AI-Bot");
    }

    /** 给假人分配「挖掘指定方块」动作。 */
    public static void assignMine(FakePlayer bot, BlockPos target) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session != null) {
            session.assignMine(target);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (BotSession session : BOTS.values()) {
            session.tick();
        }
    }

    /** 单个假人的会话:持有当前动作。 */
    public static final class BotSession {
        private final FakePlayer bot;
        private BotMiner miner;

        private BotSession(FakePlayer bot) {
            this.bot = bot;
        }

        public FakePlayer bot() {
            return bot;
        }

        public void assignMine(BlockPos target) {
            this.miner = new BotMiner(bot, target);
        }

        private void tick() {
            if (miner == null) {
                return;
            }
            BotMiner.Status status = miner.tick();
            switch (status) {
                case DONE -> {
                    miner = null;
                }
                case FAILED -> {
                    miner = null;
                }
                default -> {
                    // 进行中,保持
                }
            }
        }
    }
}

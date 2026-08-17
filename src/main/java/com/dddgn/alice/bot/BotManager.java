package com.dddgn.alice.bot;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.action.BotWalker;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.List;
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
        BotLog.info("假人已生成: name={} pos={}", name, pos.toShortString());
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
        return spawn(level, pos.above(), "Alice");
    }

    /** 给假人分配「挖掘指定方块」动作。 */
    public static void assignMine(FakePlayer bot, BlockPos target) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session != null) {
            session.assignMine(target);
            BotLog.info("分配挖掘任务: bot={} target={}",
                    bot.getName().getString(), target.toShortString());
        }
    }

    /** 假人当前是否在执行动作(供自动化验收轮询)。 */
    public static boolean isBusy(FakePlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        return session != null && session.miner != null;
    }

    /** 取最近一次任务的结果文本("done" 或 "failed:原因"),供自动化验收断言。 */
    public static String lastTaskResult(FakePlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        return session == null ? "" : session.lastTaskResult;
    }

    /** 取最近一次挖掘任务「开始挖掘时的 bot 位置」(隔空挖断言用;从未开始挖则为 null)。 */
    public static BlockPos lastMineStartPos(FakePlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        return session == null ? null : session.lastMineStartPos;
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

    /** 单个假人的会话:持有当前动作与感知作用域。 */
    public static final class BotSession {
        private final FakePlayer bot;
        private BotMiner miner;
        private final ScopeBuffer scope = new ScopeBuffer();
        private String lastTaskResult = "";
        private BlockPos lastMineStartPos;

        private BotSession(FakePlayer bot) {
            this.bot = bot;
        }

        public FakePlayer bot() {
            return bot;
        }

        public ScopeBuffer scope() {
            return scope;
        }

        /** 最近一次任务结果("done" / "failed:原因")。 */
        public String lastTaskResult() {
            return lastTaskResult;
        }

        /** 当前挖掘动作(供自检读取 mineStartPos 等,无则为 null)。 */
        public BotMiner currentMiner() {
            return miner;
        }

        public void assignMine(BlockPos target) {
            this.miner = new BotMiner(bot, target);
            // M1:任务启动即开启作用域,监听掉落物与方块变化(设计文档 §3.2)
            this.scope.begin(target, 8);
        }

        private void tick() {
            if (miner == null) {
                return;
            }
            BotMiner.Status status = miner.tick();
            switch (status) {
                case DONE -> {
                    lastTaskResult = "done";
                    lastMineStartPos = miner.mineStartPos();
                    reportItems();
                    scope.end();
                    miner = null;
                }
                case FAILED -> {
                    lastTaskResult = "failed:" + miner.failureReason();
                    lastMineStartPos = miner.mineStartPos();
                    reportItems();
                    scope.end();
                    miner = null;
                }
                default -> {
                    // 进行中,保持
                }
            }
        }

        /** M1 验收:任务结束时汇报作用域内仍存活的掉落物(实时坐标可得)。 */
        private void reportItems() {
            List<ItemEntity> items = scope.liveItems();
            if (items.isEmpty()) {
                BotLog.info("任务结束: 作用域内无存活掉落物");
                return;
            }
            for (ItemEntity item : items) {
                BotLog.info("任务结束掉落物: {} x{} y{} z{}",
                        item.getItem().getItem(), item.getBlockX(), item.getBlockY(), item.getBlockZ());
            }
        }
    }
}

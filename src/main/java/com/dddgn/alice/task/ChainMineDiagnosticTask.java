package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.compat.ChainMining;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;


/**
 * 模组兼容诊断：Ore Excavation（连锁挖掘）的掉落物捕获与收集。
 *
 * <p>为什么用反射：该模组的连锁由**客户端按键 + mineMode**触发
 * （`EventHandler.onBlockBreak` → `PacketExcavation` 下发客户端 → 客户端回传形状 → `ExcPacketHandler.acceptServer`），
 * bot 没有客户端，因此直接调用它的**服务端入口**
 * `MiningScheduler.INSTANCE.startMining(ServerPlayer, BlockPos, BlockState, ExcavateShape, Direction, Direction)`。
 * 这是软依赖：模组不存在时任务如实报 `chain_mod=absent` 并失败，不做任何猜测。
 *
 * <p>兼容性要点（已用字节码核对，模组版本 1.13.174）：
 * <ul>
 *   <li>连锁破坏走 {@code player.gameMode.destroyBlock(pos)}——与本项目 {@code BlockBreakSession} 同一调用，
 *       因此**会触发** {@code BlockEvent.BreakEvent}，我们的作用域配对链成立；</li>
 *   <li>连锁期间模组把 ItemEntity/XP 生成**取消并缓冲**（`EventHandler.onEntitySpawn` + `captureAgent`），
 *       结束前清空 `captureAgent`，再在 `dropEverything()` 里**一次性**生成——全部堆在**同一格**
 *       （`autoPickup ? 玩家位置 : 连锁起点`）；</li>
 *   <li>因此掉落物可能晚于配对时间窗，`ScopeBuffer` 已加"破坏点位置回退"配对；</li>
 *   <li>`autoPickup=true` 时物品直接落到 bot 脚下并被自然拾取 → 本任务同时统计**背包增量**，
 *       两种情况都算收集成功。</li>
 * </ul>
 *
 * <p>断言：`mined >= 2`（连锁真的发生）+（`collected > 0` 或 `inventoryGain > 0`）。
 */
public final class ChainMineDiagnosticTask implements Task {

    /** 自检起点（物品入口用；场景函数注释与之对齐）。 */
    public static final BlockPos START_FOOT = new BlockPos(23, 64, 152);
    /** 连锁种子：3x3 矿脉的北面中心格。 */
    public static final BlockPos SEED = new BlockPos(23, 64, 154);

    /** 连锁总超时（tick）。 */
    private static final int EXCAVATE_TIMEOUT_TICKS = 200;
    /** 连锁结束后等待掉落物生成的 tick 数（模组在结束时一次性生成）。 */
    private static final int SETTLE_TICKS = 20;
    /** 任务总超时（tick）。 */
    private static final int TOTAL_TIMEOUT_TICKS = 800;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    private int phase;
    private int ticks;
    private int excavateTicks;
    private int settleTicks;
    private String failure = "";
    private boolean modPresent;
    private int ironBefore;
    private int minedPeak;
    private int brokenCount;
    private int dropsSeen;
    private int inventoryGain;
    private int collected;
    private String collectResult = "-";
    private String settings = "-";
    private CollectDropsTask collector;

    public ChainMineDiagnosticTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(SEED);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Status tick() {
        if (++ticks > TOTAL_TIMEOUT_TICKS) {
            failure = "CHAIN_TIMEOUT";
            return finish();
        }
        switch (phase) {
            case 0 -> prepare();
            case 1 -> {
                if (!triggerChain()) {
                    return finish();
                }
                phase = 2;
            }
            case 2 -> {
                if (!pollChain()) {
                    return finish();
                }
            }
            case 3 -> {
                if (++settleTicks < SETTLE_TICKS) {
                    return Status.RUNNING;
                }
                sampleAfterChain();
                phase = 4;
            }
            case 4 -> {
                Status status = collector.tick();
                if (status == Status.RUNNING) {
                    return Status.RUNNING;
                }
                collected = collector.collected();
                collectResult = status == Status.DONE ? "PASS" : "FAIL";
                return finish();
            }
            default -> {
                return finish();
            }
        }
        return Status.RUNNING;
    }

    // ---- 阶段 ----

    private void prepare() {
        ensurePickaxe();
        teleport(START_FOOT);
        // 与 MineTask 一致：半径 16 覆盖连锁范围，只登记 bot 自己造成的破坏
        scope.begin(SEED, 16, bot.getUUID());
        ironBefore = countIron();
        settings = ChainMining.settingsSummary();
        BotLog.info("[ChainMine] 准备 start={} seed={} settings={} ironBefore={}",
                START_FOOT.toShortString(), SEED.toShortString(), settings, ironBefore);
        phase = 1;
    }

    private boolean triggerChain() {
        modPresent = ChainMining.available();
        ChainMining.StartResult result = ChainMining.start(bot, SEED);
        BotLog.info("[ChainMine] 触发连锁 mod={} seed={} result={}",
                modPresent ? "present" : "absent", SEED.toShortString(), result);
        if (result == ChainMining.StartResult.OK) {
            return true;
        }
        failure = result == ChainMining.StartResult.MOD_ABSENT ? "MOD_ABSENT" : result.name();
        return false;
    }

    /** 返回 false 表示连锁已结束或超时（需要继续走采样/收集阶段）。 */
    private boolean pollChain() {
        if (++excavateTicks > EXCAVATE_TIMEOUT_TICKS) {
            BotLog.warn("[ChainMine] 连锁超时 ticks={} minedPeak={}", excavateTicks, minedPeak);
            ChainMining.stop(bot);
            phase = 3;
            return true;
        }
        if (!ChainMining.isRunning(bot)) {
            BotLog.info("[ChainMine] 连锁结束 ticks={} minedPeak={}", excavateTicks, minedPeak);
            phase = 3;
            return true;
        }
        int mined = ChainMining.minedCount(bot);
        if (mined != minedPeak) {
            minedPeak = mined;
            BotLog.info("[ChainMine] 连锁进度 mined={} tick={}", mined, excavateTicks);
        }
        return true;
    }

    private void sampleAfterChain() {
        brokenCount = scope.brokenBlocks().size();
        dropsSeen = scope.liveDrops().size();
        inventoryGain = countIron() - ironBefore;
        BotLog.info("[ChainMine] 连锁采样 mined={} broken={} drops={} inventoryGain={}",
                minedPeak, brokenCount, dropsSeen, inventoryGain);
        collector = new CollectDropsTask(bot, SEED, scope, java.util.List.of(), false);
        phase = 4;
    }

    // ---- 收尾 ----

    private Status finish() {
        boolean chainPass = minedPeak >= 2;
        boolean collectPass = collected > 0 || inventoryGain > 0;
        boolean pass = modPresent && chainPass && collectPass && failure.isEmpty();
        String summary = "chain_mod=" + (modPresent ? "present" : "absent")
                + " settings(" + settings + ")"
                + " mined=" + minedPeak
                + " broken=" + brokenCount
                + " drops=" + dropsSeen
                + " collected=" + collected
                + " inventory_gain=" + inventoryGain
                + " chain=" + (chainPass ? "PASS" : "FAIL")
                + " collect=" + collectResult
                + (failure.isEmpty() ? "" : " reason=" + failure);
        BotLog.info("[ChainMine] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component
                    .literal("[alice] 模组连锁兼容 " + summary)
                    .withStyle(pass ? net.minecraft.ChatFormatting.GREEN
                            : net.minecraft.ChatFormatting.RED));
        }
        if (!pass) {
            if (failure.isEmpty()) {
                failure = "CHAIN_COURSE_FAILED";
            }
            return Status.FAILED;
        }
        return Status.DONE;
    }

    // ---- 夹具 ----

    /**
     * 模组 {@code canDestroy} 检查的是**主手**物品（{@code ItemStack.isCorrectToolForDrops}），
     * 因此必须写进主手槽位，不能只丢进背包空格。
     */
    private void ensurePickaxe() {
        ItemStack main = bot.getMainHandItem();
        if (!main.isEmpty() && main.isCorrectToolForDrops(bot.serverLevel().getBlockState(SEED))) {
            return;
        }
        bot.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(Items.DIAMOND_PICKAXE));
    }

    private int countIron() {
        var inventory = bot.getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.RAW_IRON) || stack.is(Items.IRON_ORE)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }
}

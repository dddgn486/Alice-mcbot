package com.dddgn.alice.task;

import com.dddgn.alice.action.BlockBreakSession;
import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ `RC3`（`docs/plans/2026-09-22-回收方案.md` §4.1 **C 类 `CANNOT_RECLAIM`**）：
 * **"内容拿不回来"必须如实记账，而且不许把不可逆说成"没写过世界"**。
 *
 * <h2>为什么必须单独一个夹具</h2>
 * 裁定是"容器内容/流体/方块实体**明确不做**逐 item 还原，但**必须可查计数/日志**"。
 * 而今天 Alice **真的会**破这些方块：`BlockBreakSafety.clearingRefusal` 只在**清障**策略下拒
 * `hasBlockEntity()`（`D-095`），而**明确目标**策略（`EXPECTED_TARGET` / `DESCEND_FOOT` / `BULK_EDIT`）
 * 照挖不误 ⇒ 没有记账时，"箱子连同里面的东西一起没了"是**完全静默**的。
 *
 * <h2>三条臂（都能变红）</h2>
 * <ol>
 *   <li>**分类表**：箱子 ⇒ `container_contents`、告示牌 ⇒ `block_entity`、水 ⇒ `fluid`、
 *       石头 ⇒ `null`（顺序即优先级：容器比"带方块实体"更具体）；</li>
 *   <li>**记账 + 不静默**：走**生产路径**（`BlockInteraction.beginBreak` + `BlockBreakSession`）
 *       真的破掉一个**装着 3 颗钻石的箱子**与一个告示牌 ⇒ 账本计数 +2、明细里两族各自点名、
 *       `[Ledger] cannot_reclaim` 逐条留痕、并且**结论性证据**：内容物**没有**被还原到任何背包；</li>
 *   <li>⭐ **不许把不可逆读成"没写过世界"**：这两次破坏**不产生任何回收义务**
 *       （`recorded=0`、账本为空）⇒ `Closure.anythingHappened()` 必须为真、
 *       `describe()` 必须印出 `lossy=+2`。判据就是这一条：把 `anythingHappened()` 里的
 *       `lossyWritesSince` 去掉 ⇒ 本夹具红（"破了箱子却报本窗口没写过世界"）。</li>
 * </ol>
 *
 * <h2>负例（守卫确实在）</h2>
 * 同一格里，**清障**策略（`PATH_ACCESS`）必须拒掉那个箱子（`block_entity`）⇒
 * "记账为 0"才不等于"没在看"。
 */
public final class LossyWriteAccountedCheckTask implements Task {

    /** 孤立原点（避开 `C2` 的 4000/2600 与 3000/3600/3700/3800/3900 段）。 */
    private static final BlockPos ORIGIN = new BlockPos(4100, 100, 2600);
    private static final int FLOOR_Y = 98;
    /** 真破的箱子（`container_contents`）：里面放 3 颗钻石 —— 内容物就是那条裁定的对象。 */
    private static final BlockPos CHEST_POS = new BlockPos(ORIGIN.getX(), FLOOR_Y + 1, ORIGIN.getZ());
    /** 真破的告示牌（`block_entity`）。 */
    private static final BlockPos SIGN_POS = new BlockPos(ORIGIN.getX() + 2, FLOOR_Y + 1, ORIGIN.getZ());
    /** 负例用：同一个箱子几何，只换**策略**（清障）⇒ 必须被拒。 */
    private static final BlockPos GUARD_CHEST_POS =
            new BlockPos(ORIGIN.getX() + 4, FLOOR_Y + 1, ORIGIN.getZ());
    private static final int DIAMONDS = 3;
    private static final int BUDGET_TICKS = 900;

    private enum Phase { SETUP, BREAK_CHEST, BREAK_SIGN, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int totalTicks;
    private int checks;
    private String scopeId = "-";
    private FixtureZone.Handle zone;
    private WorldModLedger.Population baseline;
    private WriteGrant grant;
    private BlockBreakSession session;
    private String chestStatus = "-";
    private String signStatus = "-";
    private String classify = "-";
    private String clearingRefusal = "-";
    private int lossyWrites = -1;
    private String details = "-";
    private String closureLine = "-";
    private boolean closureAnything;
    private int closureRecorded = -1;
    private int closureLossy = -1;
    private int inventoryDiamonds = -1;
    /**
     * ⭐ **夹具卫生（2026-09-24 由 `A2′` 的组合点名实测抓出）**：进场景**之前** bot 的脚位。
     * 收尾必须回**这里**，不许回"自己的场景原点" —— 场景方块已还原成原始地形，原点常常是**空中**
     * ⇒ bot 掉下去，后一步继承到一个坑里。
     */
    private BlockPos entryFoot;
    private boolean reported;

    public LossyWriteAccountedCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "LossyWriteAccountedCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(CHEST_POS);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "passed" : "failed";
    }

    private void check(String what, boolean ok) {
        checks++;
        if (ok) {
            findings.add(what + " ✓");
        } else {
            failures.add(what + " ✗");
            BotLog.warn("[RC3] FAIL {}", what);
        }
    }

    @Override
    public Task.Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        totalTicks++;
        phaseTicks++;
        if (totalTicks > BUDGET_TICKS) {
            failures.add("超时 " + BUDGET_TICKS + " tick（phase=" + phase + "）");
            return finish(level);
        }
        switch (phase) {
            case SETUP -> {
                if (phaseTicks == 1) {
                    setup(level);
                }
                if (phaseTicks >= 3) {
                    phase = Phase.BREAK_CHEST;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case BREAK_CHEST -> {
                if (runBreak(level, CHEST_POS)) {
                    chestStatus = terminalStatus(level, CHEST_POS);
                    phase = Phase.BREAK_SIGN;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case BREAK_SIGN -> {
                if (runBreak(level, SIGN_POS)) {
                    signStatus = terminalStatus(level, SIGN_POS);
                    phase = Phase.DONE;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case DONE -> {
                return finish(level);
            }
            default -> {
                return Task.Status.FAILED;
            }
        }
    }

    /** 推动一次破坏会话；返回"这一格已经结束"。 */
    private boolean runBreak(ServerLevel level, BlockPos pos) {
        if (session == null) {
            session = BlockInteraction.beginBreak(bot, level, pos, grant);
            if (session == null) {
                failures.add("FIXTURE_PREMISE_FAILED beginBreak 被拒 pos=" + pos.toShortString());
                return true;
            }
        }
        BlockBreakSession.Status status = session.tick();
        if (status == BlockBreakSession.Status.IN_PROGRESS) {
            return false;
        }
        session = null;
        return true;
    }

    private String terminalStatus(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() ? "broken" : ("still=" + blockIdAt(level, pos));
    }

    private static String blockIdAt(ServerLevel level, BlockPos pos) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock()));
    }

    private Task.Status finish(ServerLevel level) {
        if (reported) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        reported = true;
        // 读数（**当场取**）：窗口内的不可逆写入数（差值）+ 最近两条明细
        lossyWrites = WorldModLedger.lossyWriteCount(level.getServer())
                - (baseline == null ? 0 : baseline.lossyWrites());
        List<String> dl = WorldModLedger.lossyDetails(level.getServer());
        details = dl.isEmpty() ? "-" : String.join(" | ",
                dl.subList(Math.max(0, dl.size() - 2), dl.size()));
        // ① 分类表（唯一判据入口：容器 → 方块实体 → 流体）
        WorldModLedger.Lossy chestKind = WorldModLedger.lossyOf(Blocks.CHEST.defaultBlockState());
        WorldModLedger.Lossy signKind = WorldModLedger.lossyOf(Blocks.OAK_SIGN.defaultBlockState());
        WorldModLedger.Lossy waterKind = WorldModLedger.lossyOf(Blocks.WATER.defaultBlockState());
        WorldModLedger.Lossy stoneKind = WorldModLedger.lossyOf(Blocks.STONE.defaultBlockState());
        classify = chestKind + "/" + signKind + "/" + waterKind + "/" + stoneKind;
        check("⭐ 臂① 分类表：箱子=" + chestKind + " 告示牌=" + signKind + " 水=" + waterKind
                        + " 石头=" + stoneKind,
                chestKind == WorldModLedger.Lossy.CONTAINER_CONTENTS
                        && signKind == WorldModLedger.Lossy.BLOCK_ENTITY
                        && waterKind == WorldModLedger.Lossy.FLUID
                        && stoneKind == null);
        // ② 负例：清障策略必须拒掉同一个箱子（`block_entity`）—— "0 次"才不等于"没在看"
        check("⭐ 负例 清障策略必须拒掉箱子（实测 clearingRefusal=" + clearingRefusal + "）",
                "block_entity".equals(clearingRefusal));
        // ③ 世界事实：两格真的被破掉（记账的前提 = 破坏真的发生）
        check("⭐ 两格必须真的被破掉（实测 箱子=" + chestStatus + " 告示牌=" + signStatus + "）",
                "broken".equals(chestStatus) && "broken".equals(signStatus));
        // ④ 记账：窗口内 +2（走的是生产路径：beginBreak + BlockBreakSession）
        check("⭐ 臂② 账本必须记下这 2 次不可逆写入（实测 lossyWrites=+" + lossyWrites + "）",
                lossyWrites == 2);
        // ⑤ 不静默 + 分族点名（明细 = 日志同一份字符串）
        check("⭐ 臂② 明细必须分族点名（实测 " + details + "）",
                details.contains(CHEST_POS.toShortString() + " ")
                        && details.contains(WorldModLedger.Lossy.CONTAINER_CONTENTS.code())
                        && details.contains(SIGN_POS.toShortString() + " ")
                        && details.contains(WorldModLedger.Lossy.BLOCK_ENTITY.code()));
        // ⑥ ⭐ 本夹具的核心：破了东西 ⇒ 账本空，但**绝不许**说成"本窗口没写过世界"
        WorldModLedger.Closure closure = WorldModLedger.closure(level, scopeId, baseline);
        closureLine = closure.describe();
        closureAnything = closure.anythingHappened();
        closureRecorded = closure.recordedSince();
        closureLossy = closure.lossyWritesSince();
        check("⭐ 臂③ 账本里 0 条义务（recorded=+" + closureRecorded + "）但**发生过** "
                        + closureLossy + " 次不可逆写入 ⇒ anythingHappened 必须为真（实测 "
                        + closureAnything + "；人口行=" + closureLine + "）",
                closureRecorded == 0 && closureLossy == 2 && closureAnything
                        && closureLine.contains("lossy=+2"));
        // ⑦ 裁定本身的证据：**内容物没有被逐 item 还原**（不在背包里）
        inventoryDiamonds = 0;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.is(Items.DIAMOND)) {
                inventoryDiamonds += stack.getCount();
            }
        }
        check("⭐ 裁定实证：箱子里的 " + DIAMONDS + " 颗钻石**没有被还原**（实测背包里钻石="
                        + inventoryDiamonds + "）—— 记账存在的意义就是别把它讲成「已还原」",
                inventoryDiamonds == 0);
        cleanup(level);
        BotLog.info("[RC3] SUMMARY checks={} failures={} classify={} clearingRefusal={}"
                        + " chest={} sign={} lossyWrites={} closure={} inventoryDiamonds={} ticks={} → {}",
                checks, failures.size(), classify, clearingRefusal, chestStatus, signStatus,
                lossyWrites, closureLine, inventoryDiamonds, totalTicks,
                failures.isEmpty() ? "PASS" : "FAIL");
        for (String line : findings) {
            BotLog.info("[RC3]   {}", line);
        }
        for (String line : failures) {
            BotLog.warn("[RC3]   {}", line);
        }
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 不可逆写入记账夹具（RC3）："
                    + (failures.isEmpty() ? "PASS" : "FAIL") + " checks=" + checks
                    + " failures=" + failures.size() + "（lossyWrites=+" + lossyWrites + "）"));
        }
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 场景与账本 ====================

    private void setup(ServerLevel level) {
        // ⭐ 先记下"进来时站在哪"（收尾回这里；见 `entryFoot` 的注释）
        if (entryFoot == null) {
            entryFoot = com.dddgn.alice.pathing.MovementHelper.footCell(level, bot).immutable();
        }
        // 地板（3 格宽，覆盖三个目标格 —— 摔/落都有底）
        for (int dx = -1; dx <= 6; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(level, new BlockPos(ORIGIN.getX() + dx, FLOOR_Y, ORIGIN.getZ() + dz), Blocks.STONE);
            }
        }
        // 真破的箱子（**里面有东西** —— 内容物就是"拿不回来"的那部分）
        place(level, CHEST_POS, Blocks.CHEST);
        if (level.getBlockEntity(CHEST_POS) instanceof ChestBlockEntity chest) {
            chest.setItem(0, new ItemStack(Items.DIAMOND, DIAMONDS));
        } else {
            failures.add("FIXTURE_PREMISE_FAILED 箱子方块实体建不出来 " + CHEST_POS.toShortString());
        }
        place(level, SIGN_POS, Blocks.OAK_SIGN);
        place(level, GUARD_CHEST_POS, Blocks.CHEST);
        bot.teleportTo(level, CHEST_POS.getX() + 0.5D, CHEST_POS.getY(), CHEST_POS.getZ() + 0.5D, 0.0F, 0.0F);
        bot.controller().stopMovement();
        FixtureToolKit.resetInventory(bot);
        FixtureToolKit.ensureAxe(bot);       // 箱子/告示牌都是斧类，别让夹具测"空手拆木器要多久"
        // ⭐ 前提：保护区 + L2 任务区封套（`D-398`：明文目标策略也要过区域授权）——
        // 顺序必须是"先开作用域、再声明任务区"（见 `RestoreUnderfootSafetyCheckTask.startRestore` 的 A4 教训）。
        scopeId = WorldModLedger.openScope(level.getServer(), bot.getUUID(), "rc3_lossy");
        zone = FixtureZone.protect(level, bot.getUUID(),
                new BlockPos(ORIGIN.getX() - 2, FLOOR_Y, ORIGIN.getZ() - 2),
                new BlockPos(ORIGIN.getX() + 6, FLOOR_Y + 4, ORIGIN.getZ() + 2), "region_lumber");
        if (!zone.ok()) {
            failures.add("FIXTURE_ZONE_PREMISE_FAILED " + zone.describe());
            BotLog.warn("[RC3] 前提未成立：{}", zone.describe());
            return;
        }
        baseline = WorldModLedger.populationBaseline(level.getServer());
        grant = WriteGrant.of("check:lossy-write", WriteReason.EXPECTED_TARGET);
        WriteGrant clearing = WriteGrant.of("check:lossy-write", WriteReason.PATH_ACCESS);
        clearingRefusal = BlockInteraction.breakRefusal(bot, level, GUARD_CHEST_POS, clearing);
        BotLog.info("[RC3] SETUP 箱子={}（{} 颗钻石）告示牌={} 负例箱={} 清障拒绝码={} scope={}",
                CHEST_POS.toShortString(), DIAMONDS, SIGN_POS.toShortString(),
                GUARD_CHEST_POS.toShortString(), clearingRefusal, scopeId);
    }

    private void place(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        touched.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, block.defaultBlockState(), 3);
    }

    private void cleanup(ServerLevel level) {
        if (session != null) {
            session.abort();
            session = null;
        }
        // 掉落物清场（破箱子会掉出内容物；夹具纪律：结束复位，别给后面的步留垃圾）
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class,
                new AABB(ORIGIN.getX() - 4, FLOOR_Y - 2, ORIGIN.getZ() - 4,
                        ORIGIN.getX() + 10, FLOOR_Y + 6, ORIGIN.getZ() + 4))) {
            drop.discard();
        }
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        if (zone != null) {
            zone.release();
        }
        WorldModLedger.closeScope(level.getServer(), bot.getUUID());
        BlockPos home = new BlockPos(ORIGIN.getX(), FLOOR_Y + 1, ORIGIN.getZ());
        // ⭐ 夹具卫生：回**进来时**的脚位（不是自己的场景原点 —— 那里多半已经是空中）
        BlockPos back = entryFoot != null ? entryFoot : home;
        bot.teleportTo(level, back.getX() + 0.5D, back.getY(), back.getZ() + 0.5D, 0.0F, 0.0F);
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}

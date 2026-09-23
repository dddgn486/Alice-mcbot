package com.dddgn.alice.task;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.protection.ProtectionZones;
import com.dddgn.alice.protection.SafeZoneData;
import com.dddgn.alice.protection.TaskZoneRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ `Z1` 的判据（`D-398` 落地）：**账本与恢复的地理范围 = "保护区及其子区域"**。
 *
 * <h2>被验的那句话（用户 2026-09-22 逐字）</h2>
 * 「保护区及其子区域以外的任何无主区域，绝对不需要负任何修改的责任…在保护区内的修改一定记账，
 * 不一定马上恢复，保护区外的区域一定不恢复，无限制修改。」
 *
 * <h2>四条臂（每条都能被"去掉实现"弄红）</h2>
 * <ol>
 *   <li><b>区外放置 ⇒ 账本无条目</b>：真的用动作层（{@code BlockInteraction.placeAt}）在**未认领**处放一块，
 *       断言世界**真的变了**、`outsideSkipCount` **真的 +1**（防"根本没干活"的空跑假绿）、
 *       而账本里 `at(pos) == null`。注入 = 去掉 `recordPlacement` 的区外过滤 ⇒ 红；</li>
 *   <li><b>区外不恢复</b>：同一格上跑 `RestoreScopeTask` ⇒ 终态 `nothing_to_restore` 且**方块还在**。
 *       注入 = 去掉 `RestoreScopeTask` 的区内取件 ⇒ 它会去拆无主区域 ⇒ 红；</li>
 *   <li><b>区内放置 ⇒ 必有条目，且回收真的发生</b>：认领区块 + 声明任务区（见下"为什么要借 L2 封套"）
 *       ⇒ 放置后断言账本有 `TEMP` 条目；`RestoreScopeTask` 跑完 ⇒ 方块变空气、条目被销。
 *       注入 = 把过滤反向（只记区外）⇒ 红；</li>
 *   <li><b>先记账、后取消认领 ⇒ 义务随之消失</b>（`dropStale` 的区外销账臂）：区内放置 → `unclaim` →
 *       `dropStale` ⇒ 条目被销、**方块留在原地**（那片地已变回无主区域 ⇒ 谁都不去拆它）。
 *       注入 = 去掉 `dropStale` 的区外销账 ⇒ 条目留着、`remaining` 永远非零 ⇒ 红。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么要"借"一个 `L2` 任务区封套（诚实标注）</h2>
 * 保护区里的写入要过 `ZoneAuthority`，而**自检夹具的定位是 `DIAGNOSTIC` ⇒ `L0`（只读）**
 * （`D-338` 附注七③：未登记任务与自检夹具**不该**拿到区内写入权）。于是"在区内写字"这件事，
 * 夹具**必须**自己摆出前提：本步按 `TaskZoneCheckTask` 的同一做法声明一个
 * `region_lumber`（`LUMBER ⇒ L2 工作面`）+ `playerDriven=true` 的封套 —— 它**不是**在验权限阶梯
 * （那是 `task_zone` 的活），而是**让闸门放行**，否则根本测不到"区内记账 / 回收"这半边。
 * 反过来这也顺带证明了那条阶梯是活的：**不声明封套 ⇒ 区内放置被拒 `protected_area`**
 * （本步在区外臂之前会断言这一点，见 {@code assertZoneGateAlive}）。
 *
 * <h2>场景</h2>
 * 自建孤立场景（x=4200 / z=2600 一带，避开 3400/3600/3700/3800/3900/4000 段）：地板 y=98、
 * 三个目标格都在**同一个区块**里（4199/4200/4201 ⇒ chunk 262,162）⇒ 一次 claim 就够。
 * 收尾把地形与认领状态**全部还原**（认领状态按"进来时是不是已认领"还原）。
 */
public final class LedgerZoneScopeCheckTask implements Task {

    /** 孤立原点（避开其它夹具的坐标段）。 */
    private static final BlockPos ORIGIN = new BlockPos(4200, 100, 2600);
    /** 地板顶面（bot 站在 y=99）。 */
    private static final int FLOOR_Y = 98;
    private static final int BUDGET_TICKS = 1800;

    /** 区外臂的目标格（未认领时）。 */
    private static final BlockPos WILD_TARGET = new BlockPos(ORIGIN.getX() + 1, FLOOR_Y + 1, ORIGIN.getZ());
    /** 区内臂的目标格（认领后；会被回收）。 */
    private static final BlockPos ZONE_TARGET = new BlockPos(ORIGIN.getX() - 1, FLOOR_Y + 1, ORIGIN.getZ());
    /** 销账臂的目标格（区内记账 → unclaim → 应被销掉、方块留下）。 */
    private static final BlockPos PURGE_TARGET = new BlockPos(ORIGIN.getX(), FLOOR_Y + 1, ORIGIN.getZ() + 1);

    private enum Phase { SETUP, WILD_PLACE, WILD_RESTORE, ZONE_PREP, ZONE_PLACE, ZONE_RESTORE, PURGE, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int phaseTicks;
    private ScopeBuffer scope;
    private String scopeId;
    private boolean ownsScope;
    /** ⭐ 夹具自摆的"保护区 + L2 任务区"前提（结束复位）。 */
    private FixtureZone.Handle zone;
    private RestoreScopeTask restore;
    private String wildTerminal = "-";
    private String zoneTerminal = "-";
    private String zoneNotes = "-";
    private int outsideSkipsBefore;
    /** ⭐ `Z2`：本夹具窗口起点的**人口基线**（记账次数 + 区外跳过次数）—— 收尾读数的差值基准。 */
    private WorldModLedger.Population populationBaseline = WorldModLedger.Population.ZERO;
    private int outsideSkipsAfter;
    private int purgeDropped;
    private boolean reported;

    public LedgerZoneScopeCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "LedgerZoneScopeCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN);
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
        if (ok) {
            findings.add(what + " ✓");
        } else {
            failures.add(what + " ✗");
            BotLog.warn("[Z1] FAIL {}", what);
        }
    }

    // ==================== 主循环 ====================

    @Override
    public Task.Status tick() {
        ServerLevel level = bot.serverLevel();
        ticks++;
        phaseTicks++;
        if (ticks > BUDGET_TICKS) {
            failures.add("超时 " + BUDGET_TICKS + " tick（phase=" + phase + "）");
            return finish(level);
        }
        switch (phase) {
            case SETUP -> setup(level);
            case WILD_PLACE -> wildPlace(level);
            case WILD_RESTORE -> wildRestore(level);
            case ZONE_PREP -> zonePrep(level);
            case ZONE_PLACE -> zonePlace(level);
            case ZONE_RESTORE -> zoneRestore(level);
            case PURGE -> purge(level);
            case DONE -> {
                return finish(level);
            }
        }
        return Task.Status.RUNNING;
    }

    // ==================== 相位 ====================

    private void setup(ServerLevel level) {
        if (phaseTicks == 1) {
            buildScene(level);
            FixtureToolKit.resetInventory(bot);
            FixtureToolKit.ensurePickaxe(bot);
            FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE),
                    stack -> stack.is(Items.COBBLESTONE), 16, "cobblestone");
            bot.controller().stopMovement();
            // 作用域：优先用编排器/电池已经开好的那个（本步的 scope），没有才自己开
            scopeId = WorldModLedger.currentScope(level.getServer(), bot.getUUID());
            if (scopeId == null) {
                scopeId = WorldModLedger.openScope(level.getServer(), bot.getUUID(), "ledger_zone_scope");
                ownsScope = true;
            }
            scope = new ScopeBuffer();
            BotLog.info("[Z1] SETUP 原点={} 地板 y={} 目标 区外={} 区内={} 销账={} scope={}",
                    ORIGIN.toShortString(), FLOOR_Y, WILD_TARGET.toShortString(),
                    ZONE_TARGET.toShortString(), PURGE_TARGET.toShortString(), scopeId);
        }
        if (phaseTicks < 3) {
            return;
        }
        // 前提：三个目标格**都在同一个区块**（一次 claim 覆盖全部）+ 起点确实是"无主区域"
        check("前提：三格同区块（一次认领即覆盖，实测 wild="
                        + chunkOf(WILD_TARGET) + " zone=" + chunkOf(ZONE_TARGET)
                        + " purge=" + chunkOf(PURGE_TARGET) + "）",
                chunkOf(WILD_TARGET) == chunkOf(ZONE_TARGET) && chunkOf(ZONE_TARGET) == chunkOf(PURGE_TARGET));
        check("前提：起点未认领（否则「区外」这一半无从谈起）", ProtectionZones.isWild(level, WILD_TARGET));
        if (!ProtectionZones.isWild(level, ZONE_TARGET)) {
            failures.add("前提被破坏：目标格已在保护区内（夹具无法测「区外」臂）");
            phase = Phase.DONE;
            return;
        }
        check("前提：动作层能放置（快捷栏有" + Blocks.COBBLESTONE.getName().getString() + "）",
                BlockInteraction.findSlotForBlock(bot, Blocks.COBBLESTONE) >= 0);
        outsideSkipsBefore = WorldModLedger.outsideSkipCount(level.getServer());
        populationBaseline = WorldModLedger.populationBaseline(level.getServer());
        phase = Phase.WILD_PLACE;
        phaseTicks = 0;
    }

    /** 臂 ①：**区外放置 ⇒ 账本无条目**。 */
    private void wildPlace(ServerLevel level) {
        if (phaseTicks == 1) {
            // 站位复位（夹具纪律：不依赖上一步把 bot 留在哪）
            standAtHome(level);
            BlockInteraction.PlaceResult result = BlockInteraction.placeAt(bot, level, WILD_TARGET, false,
                    grant(), Blocks.COBBLESTONE);
            outsideSkipsAfter = WorldModLedger.outsideSkipCount(level.getServer());
            boolean inWorld = level.getBlockState(WILD_TARGET).is(Blocks.COBBLESTONE);
            check("臂① 区外放置**真的发生了**（result=" + result + "，世界事实=" + shortId(level, WILD_TARGET)
                            + "）—— 否则下面的「无条目」是空跑假绿",
                    result == BlockInteraction.PlaceResult.PLACED && inWorld);
            check("臂① 区外放置被**跳过记账**（遥测 outsideSkips " + outsideSkipsBefore + " → "
                            + outsideSkipsAfter + "）",
                    outsideSkipsAfter == outsideSkipsBefore + 1);
            check("臂① 区外放置后**账本无条目**（`D-398` R1/R2）",
                    WorldModLedger.at(level.getServer(), WILD_TARGET) == null);
        }
        if (phaseTicks >= 3) {
            restore = new RestoreScopeTask(bot, scope, scopeId);
            phase = Phase.WILD_RESTORE;
            phaseTicks = 0;
        }
    }

    /** 臂 ②：**区外不恢复**（回收任务在区外什么都不该做）。 */
    private void wildRestore(ServerLevel level) {
        Task.Status status = restore.tick();
        if (status == Task.Status.RUNNING) {
            return;
        }
        wildTerminal = String.valueOf(restore.terminalReason());
        boolean stillThere = level.getBlockState(WILD_TARGET).is(Blocks.COBBLESTONE);
        check("臂② 区外不恢复：终态=" + wildTerminal + "（期望 nothing_to_restore）",
                "nothing_to_restore".equals(wildTerminal));
        check("臂② 区外方块**留在原地**（世界事实=" + shortId(level, WILD_TARGET) + "）", stillThere);
        restore = null;
        phase = Phase.ZONE_PREP;
        phaseTicks = 0;
    }

    /** 臂 ③ 前提：证明"没有封套 ⇒ 区内写入被拒"，再摆出保护区 + L2 任务区封套。 */
    private void zonePrep(ServerLevel level) {
        if (phaseTicks != 1) {
            if (phaseTicks >= 2) {
                phase = Phase.ZONE_PLACE;
                phaseTicks = 0;
            }
            return;
        }
        // **门禁是活的**：还没有认领/封套时，先证明这道闸门不是恒假（"未认领 ⇒ 不拦"也是其中一半）
        check("臂③ 前提：起点未认领 ⇒ 闸门不拦（NOT_GATED）",
                ProtectionZones.isWild(level, ZONE_TARGET)
                        && com.dddgn.alice.protection.ZoneAuthority.placeRefusal(level, bot.getUUID(),
                                ZONE_TARGET, WriteReason.STEP_PLACEMENT) == null);
        // 夹具自己摆前提：认领区块 + 声明 L2 任务区（夹具自己的任务是 DIAGNOSTIC ⇒ L0，见类注释）
        zone = FixtureZone.protect(level, bot.getUUID(),
                ORIGIN.offset(-3, -2, -3), ORIGIN.offset(3, 2, 3), "region_lumber");
        check("臂③ 前提：保护区 + 任务区前提成立（" + zone.describe() + "）", zone.ok());
        if (!zone.ok()) {
            phase = Phase.DONE;
            return;
        }
        check("臂③ 前提：现在被判为保护区内（`ProtectionZones.isProtected`）",
                ProtectionZones.isProtected(level, ZONE_TARGET));
        check("臂③ 前提：封套等级 = L2 工作面（拆/放都放行）",
                zone.declaredZone() != null && zone.declaredZone().level()
                        == com.dddgn.alice.action.WritePolicyMatrix.Level.L2_WORKFACE);
    }

    /** 臂 ③：**区内放置 ⇒ 必有条目 + 回收真的发生**。 */
    private void zonePlace(ServerLevel level) {
        if (phaseTicks == 1) {
            BlockInteraction.PlaceResult result = BlockInteraction.placeAt(bot, level, ZONE_TARGET, false,
                    grant(), Blocks.COBBLESTONE);
            WorldModLedger.Entry entry = WorldModLedger.at(level.getServer(), ZONE_TARGET);
            check("臂③ 区内放置成功（result=" + result + "，世界事实=" + shortId(level, ZONE_TARGET) + "）",
                    result == BlockInteraction.PlaceResult.PLACED
                            && level.getBlockState(ZONE_TARGET).is(Blocks.COBBLESTONE));
            check("臂③ 区内放置**必有账本条目**（`D-398` R3：一定记账）"
                            + "（条目=" + (entry == null ? "null" : entry.describe()) + "）",
                    entry != null && entry.policy() == WorldModLedger.Policy.TEMP);
            check("臂③ 条目落在**本步作用域**上（scope=" + (entry == null ? "-" : entry.scopeId()) + "）",
                    entry != null && entry.scopeId().equals(scopeId));
        }
        if (phaseTicks >= 3) {
            restore = new RestoreScopeTask(bot, scope, scopeId);
            phase = Phase.ZONE_RESTORE;
            phaseTicks = 0;
        }
    }

    /** 臂 ③ 结论：回收真的把区内那格拆了（`D-403`：保留自动拆除）。 */
    private void zoneRestore(ServerLevel level) {
        Task.Status status = restore.tick();
        if (status == Task.Status.RUNNING) {
            return;
        }
        zoneTerminal = String.valueOf(restore.terminalReason());
        List<String> notes = restore.notes();
        zoneNotes = notes.isEmpty() ? "-" : String.join(" | ", notes);
        check("臂③ 区内回收：方块已被拆掉（世界事实=" + shortId(level, ZONE_TARGET)
                        + "，终态=" + zoneTerminal + " notes=" + zoneNotes + "）",
                level.getBlockState(ZONE_TARGET).isAir());
        check("臂③ 区内回收：账本条目已被销（" + (WorldModLedger.at(level.getServer(), ZONE_TARGET) == null)
                + "）", WorldModLedger.at(level.getServer(), ZONE_TARGET) == null);
        restore = null;
        phase = Phase.PURGE;
        phaseTicks = 0;
    }

    /** 臂 ④：**先记账、后取消认领 ⇒ 义务随之消失**（条目被销、方块留下）。 */
    private void purge(ServerLevel level) {
        if (phaseTicks == 1) {
            // 站位复位：**实测坑**（`module:ledger` 连跑）—— 臂③ 的回收收尾会去追掉落物，
            // 把 bot 带离场景 ⇒ 不再"够得到"目标格 ⇒ `placeAt` 返回 `NO_OPTION`（夹具假红）。
            standAtHome(level);
            FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE),
                    stack -> stack.is(Items.COBBLESTONE), 8, "cobblestone");
            BlockInteraction.PlaceResult result = BlockInteraction.placeAt(bot, level, PURGE_TARGET, false,
                    grant(), Blocks.COBBLESTONE);
            check("臂④ 区内放置记了账（result=" + result + "，世界事实=" + shortId(level, PURGE_TARGET) + "）",
                    result == BlockInteraction.PlaceResult.PLACED
                            && WorldModLedger.at(level.getServer(), PURGE_TARGET) != null);
            // 取消认领 ⇒ 这片地变回无主区域 ⇒ 按 `D-398` 不再有恢复义务
            boolean removed = SafeZoneData.get(level.getServer())
                    .unclaim(level, PURGE_TARGET.getX() >> 4, PURGE_TARGET.getZ() >> 4);   // 模拟"玩家取消认领"
            check("臂④ 取消认领成功（chunk=" + chunkOf(PURGE_TARGET) + "）", removed);
            check("臂④ 取消后该格不再是保护区内", ProtectionZones.isWild(level, PURGE_TARGET));
            purgeDropped = WorldModLedger.dropStale(level);
            check("臂④ `dropStale` 把区外条目销掉（本次共销 " + purgeDropped + " 条）",
                    purgeDropped >= 1 && WorldModLedger.at(level.getServer(), PURGE_TARGET) == null);
            check("臂④ 方块**留在原地**（那片地已无主 ⇒ 谁都不去拆它，世界事实="
                            + shortId(level, PURGE_TARGET) + "）",
                    level.getBlockState(PURGE_TARGET).is(Blocks.COBBLESTONE));
            phase = Phase.DONE;
            phaseTicks = 0;
        }
    }

    // ==================== 收尾 ====================

    private Task.Status finish(ServerLevel level) {
        if (reported) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        reported = true;
        // 本步自己的作用域里**保护区内**不许留条目（`Z2`：与电池 `endStep` 的 J6 口径一致 ——
        // 只认区内；区外条目按 `D-398` R1/R2 不算义务）。读数带上人口，便于分辨"空"的两义。
        var closure = scopeId == null
                ? new WorldModLedger.Closure(0, 0, 0, 0)
                : WorldModLedger.closure(level, scopeId, populationBaseline);
        check("收尾 本步作用域内**无区内遗留条目**（实测 " + closure.inZone() + " 条）"
                + closure.describe(), closure.inZone() == 0);
        cleanup(level);
        BotLog.info("[Z1] SUMMARY checks={} failures={} outsideSkips={}→{} wildTerminal={} "
                        + "zoneTerminal={} zoneNotes={} purgeDropped={} ticks={} → {}",
                findings.size() + failures.size(), failures.size(), outsideSkipsBefore, outsideSkipsAfter,
                wildTerminal, zoneTerminal, zoneNotes, purgeDropped, ticks,
                failures.isEmpty() ? "PASS" : "FAIL");
        for (String line : findings) {
            BotLog.info("[Z1]   {}", line);
        }
        for (String line : failures) {
            BotLog.warn("[Z1]   {}", line);
        }
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 账本/回收地理范围夹具（Z1）："
                    + (failures.isEmpty() ? "PASS" : "FAIL") + " failures=" + failures.size()
                    + "（区外账本条目=" + (WorldModLedger.at(level.getServer(), WILD_TARGET) == null ? "无" : "有")
                    + "）"));
        }
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 场景 ====================

    private void buildScene(ServerLevel level) {
        // 地板（5×5，y=98）—— 与 C2 夹具同形：先给一块站得住的地方
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                put(level, new BlockPos(ORIGIN.getX() + dx, FLOOR_Y, ORIGIN.getZ() + dz), Blocks.STONE);
            }
        }
        // 把地板上方两层清成空气（自建孤立场景；收尾按原状态还原）
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 1; dy <= 3; dy++) {
                    put(level, new BlockPos(ORIGIN.getX() + dx, FLOOR_Y + dy, ORIGIN.getZ() + dz),
                            Blocks.AIR);
                }
            }
        }
        BlockPos foot = new BlockPos(ORIGIN.getX(), FLOOR_Y + 1, ORIGIN.getZ());
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D, 0.0F, 0.0F);
        bot.controller().stopMovement();
    }

    private void put(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        touched.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, block.defaultBlockState(), 3);
    }

    private void cleanup(ServerLevel level) {
        // ① 还地形
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        // ②③ 还认领状态 + 解任务区封套（`FixtureZone` 只还**它自己加的**区块，不乱动别人的区）
        if (zone != null) {
            zone.release();
        }
        // ④ 还作用域（只关自己开的那个）
        if (ownsScope) {
            WorldModLedger.closeScope(level.getServer(), bot.getUUID());
        }
        // ⑤ 场景已还原 ⇒ 把可能残留的条目按世界事实销掉（不为下一步留垃圾）
        WorldModLedger.dropStale(level);
        bot.teleportTo(level, ORIGIN.getX() + 0.5D, FLOOR_Y + 2, ORIGIN.getZ() + 0.5D, 0.0F, 0.0F);
        bot.controller().stopMovement();
    }

    // ==================== 小工具 ====================

    /**
     * 写入授权。requester **必须含派生前缀**（`check/probe/dump/diagnostic/regression/battery/demo`
     * —— 见 `tools/policy-map.py`），否则 `check-policy-matrix` 会报"未登记的 requester 字面量"。
     */
    private static WriteGrant grant() {
        return WriteGrant.of("check:ledger-zone", WriteReason.STEP_PLACEMENT);
    }

    /** 把 bot 放回场景起点（每次放置前调用 ⇒ 不依赖上一步把它留在哪）。 */
    private void standAtHome(ServerLevel level) {
        BlockPos foot = new BlockPos(ORIGIN.getX(), FLOOR_Y + 1, ORIGIN.getZ());
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D, 0.0F, 0.0F);
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private static long chunkOf(BlockPos pos) {
        return net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static String shortId(ServerLevel level, BlockPos pos) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock()));
    }
}

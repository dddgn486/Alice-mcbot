package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.DropPolicy;
import com.dddgn.alice.decision.PickupGate;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ `A3`（`survey/29 §2.1` ⇒ `survey/30` 的 `P0`、台账 §11「A3」）：**击杀产物的归属**。
 *
 * <h2>缺口（勘测侧读码推断，本夹具负责**先复现**）</h2>
 * `ScopeBuffer.pendingEntry` 只按「破坏事件配对」解析归属（直接 10 tick/3 格 → 松窗 60 tick/4 格），
 * 而**击杀**不产生 `BlockEvent.BreakEvent` ⇒ 击杀产物没有任何归属来源 ⇒
 * {@link DropPolicy#effectiveProvenance} 落到 `FOREIGN` ⇒ `PickupGate` 在**被动路径**上直接拦下
 * （`drop.foreign = ASK`）⇒ **"自己杀的牛，肉捡不起来，而且只有一行节流日志"**。
 * 而攻击/猎杀能力一旦上线，第一天就是这个形状（`survey/29` 的口径：**归属先行**）。
 *
 * <h2>两条臂（都必须能变红）</h2>
 * <ol>
 *   <li>**臂①（正向 = `A3` 判据）**：bot 亲手打死一头牛（`playerAttack(bot)`）⇒ 产物必须是
 *       `OURS_KILL`，且**被动拾取必须放行**，并**端到端**真的进背包（站到落点上等自动吸附）；</li>
 *   <li>**臂②（反向对照 = "闸门还在"）**：同一片区里再杀一头牛，但**没有任何玩家归因**
 *       （`genericKill()` ⇒ killer=null）⇒ 产物**必须仍是 `FOREIGN`**、被动拾取**必须被拦**、
 *       东西**必须留在地上**。没有这条臂，"把所有掉落物一律登记成我方的"也能让臂①变绿（假绿）。</li>
 * </ol>
 *
 * <h2>先红后绿（本夹具的证据链）</h2>
 * 实现落地**之前**的实跑（提交 `4c00fa5`，日志
 * `run/headless-logs/20260924-105859-single_kill_drop_provenance.log`）：
 * `FAIL checks=11 failures=3 ticks=72` · `provA=[FOREIGN, FOREIGN]` · `passiveA=false` ·
 * `pickedA=0`（真缺口复现：自己杀的牛，皮革/牛肉落成 `FOREIGN` ⇒ 被动闸门直接拦下）；
 * 当时臂①的断言只有"不许是 `FOREIGN`"，实现落地后**收紧成"必须是 `OURS_KILL`"**（同一条臂）。
 *
 * <h2>⭐ 夹具教训（2026-09-24，第一版**偶发红**换来的）：别用固定 tick 数赌实体的 tick 起点</h2>
 * 第一版用"固定等 30 tick 再断言拾取"，实测**同代码两次判决不同**（`pickedA=0` 与 `pickedA=4`）。
 * 探针（临时加、定位后删除）打出的原始读数解释了原因：掉落物生成后 **`Age=0`/`PickupDelay=10`
 * 被冻住约 10 tick**（`pt=1..10` 全是 `age=0 delay=10`，`pt=15` 才 `age=3 delay=7`）——
 * 也就是"生成 → 开始被 tick"本身有一段延迟，再叠加 `pickupDelay`≈10 tick ⇒
 * `击杀 → 能捡`实测要 **≈40 tick**，而 30 tick 的窗口正好卡在边界上。
 * ⇒ 本夹具改成 **"等条件 + 上限"**：拾取臂等"背包真的多了"（上限 60 tick）、
 * 反向臂等"闸门真的被撞到"（上限 60 tick）⇒ 既不赌时长，也不会无限等。
 */
public final class KillDropProvenanceCheckTask implements Task {

    /** 孤立原点（避开 `C2` 的 4000、`RC3` 的 4100、`Z1` 的 4200 段，均在同一条 2600 线上）。 */
    private static final BlockPos ORIGIN = new BlockPos(4300, 100, 2600);
    private static final int FLOOR_Y = 98;
    /** bot 站位（地板中央）。 */
    private static final BlockPos BOT_FOOT = new BlockPos(ORIGIN.getX(), FLOOR_Y + 1, ORIGIN.getZ());
    /** 臂①（我方击杀）的落点：bot 东侧 2 格 —— 死亡产物不会自动进包（拾取要碰撞）。 */
    private static final BlockPos KILL_A_POS = new BlockPos(ORIGIN.getX() + 2, FLOOR_Y + 1, ORIGIN.getZ());
    /** 臂②（无归因击杀）的落点：bot 西侧 2 格（与臂① 相距 4 格，两批产物不会混）。 */
    private static final BlockPos KILL_B_POS = new BlockPos(ORIGIN.getX() - 2, FLOOR_Y + 1, ORIGIN.getZ());
    private static final int SCOPE_RADIUS = 16;
    /** 产物**出现**的上限（死亡当 tick 就掉物；`D-348` 的生成登记可能被推迟若干 tick）。 */
    private static final int MAX_WAIT_DROPS = 20;
    /** 拾取臂的上限（实测 `击杀 → 能捡` ≈40 tick，见类注释的教训）。 */
    private static final int MAX_WAIT_PICKUP = 60;
    /** 反向臂：至少等这么久才认"闸门没反应"（防"还没被撞到就宣布拦住了"）。 */
    private static final int MIN_PICKUP_TICKS = 20;
    private static final int BUDGET_TICKS = 900;

    private enum Phase { SETUP, KILL_A, ASSERT_A, PICKUP_A, KILL_B, ASSERT_B, PICKUP_B, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int totalTicks;
    private int checks;
    private ScopeBuffer scope;
    private BlockPos entryFoot;
    private boolean reported;

    private final List<DropPolicy.Provenance> provA = new ArrayList<>();
    private final List<DropPolicy.Provenance> provB = new ArrayList<>();
    private String itemsA = "-";
    private String itemsB = "-";
    private String dropsAtPickupA = "-";
    private String dropsAtPickupB = "-";
    private boolean passiveA;
    private boolean passiveB;
    private int pickedA = -1;
    private int pickupATicks = -1;
    private int pickupBTicks = -1;
    private int invBeforeA = -1;
    private int invBeforeB = -1;
    private int remainingB = -1;
    private long blockedBefore = -1L;
    private long blockedAfterB = -1L;
    private String mobLoot = "-";

    public KillDropProvenanceCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "KillDropProvenanceCheck";
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
        checks++;
        if (ok) {
            findings.add(what + " ✓");
        } else {
            failures.add(what + " ✗");
            BotLog.warn("[A3] FAIL {}", what);
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
                    phase = Phase.KILL_A;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case KILL_A -> {
                if (phaseTicks == 1) {
                    // 前提（**独立方法**：R4 —— 不许与 teleportTo 同处一个方法里判"站在地上"）
                    premiseOnGround();
                    kill(level, KILL_A_POS, true);
                }
                if (dropsNear(level, KILL_A_POS, 3.0D) > 0 || phaseTicks >= MAX_WAIT_DROPS) {
                    phase = Phase.ASSERT_A;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case ASSERT_A -> {
                assertProvenance(level, KILL_A_POS, true);
                phase = Phase.PICKUP_A;
                phaseTicks = 0;
                return Task.Status.RUNNING;
            }
            case PICKUP_A -> {
                if (phaseTicks == 1) {
                    invBeforeA = itemCount();
                    standOn(level, KILL_A_POS);
                }
                boolean picked = invBeforeA >= 0 && itemCount() > invBeforeA;
                if (picked || phaseTicks >= MAX_WAIT_PICKUP) {
                    pickedA = itemCount() - invBeforeA;
                    pickupATicks = phaseTicks;
                    dropsAtPickupA = describeDrops(level, KILL_A_POS, 3.0D);
                    check("⭐ 臂① 端到端：站到落点上等自动吸附 ⇒ 必须真的进背包（实测 +" + pickedA
                                    + " 件，等了 " + pickupATicks + " tick；地上剩 " + dropsAtPickupA + "）",
                            pickedA > 0);
                    phase = Phase.KILL_B;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case KILL_B -> {
                if (phaseTicks == 1) {
                    invBeforeB = itemCount();
                    blockedBefore = PickupGate.blockedTotal();
                    kill(level, KILL_B_POS, false);
                }
                if (dropsNear(level, KILL_B_POS, 3.0D) > 0 || phaseTicks >= MAX_WAIT_DROPS) {
                    phase = Phase.ASSERT_B;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case ASSERT_B -> {
                assertProvenance(level, KILL_B_POS, false);
                phase = Phase.PICKUP_B;
                phaseTicks = 0;
                return Task.Status.RUNNING;
            }
            case PICKUP_B -> {
                if (phaseTicks == 1) {
                    standOn(level, KILL_B_POS);
                }
                blockedAfterB = PickupGate.blockedTotal();
                // ⭐ **等条件**：闸门真的被撞到（且至少等够 MIN_PICKUP_TICKS）⇒ 才算"拦截确实发生过"；
                // 否则退化成"还没轮到它，就宣布拦住了"
                boolean gateReached = blockedAfterB > blockedBefore && phaseTicks >= MIN_PICKUP_TICKS;
                if (gateReached || phaseTicks >= MAX_WAIT_PICKUP) {
                    pickupBTicks = phaseTicks;
                    remainingB = dropsNear(level, KILL_B_POS, 3.0D);
                    dropsAtPickupB = describeDrops(level, KILL_B_POS, 3.0D);
                    int gained = itemCount() - invBeforeB;
                    check("⭐ 臂② 反向对照：无归因的击杀产物**必须留在地上**（实测 剩 " + remainingB
                                    + "/" + provB.size() + " 堆 · 背包 +" + gained + " 件 · 等了 "
                                    + pickupBTicks + " tick）",
                            remainingB == provB.size() && gained == 0);
                    check("⭐ 臂② 闸门确实点名拦了（`[Pickup] blocked` 计数 +"
                                    + (blockedAfterB - blockedBefore) + "）",
                            blockedAfterB > blockedBefore);
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

    // ==================== 场景 ====================

    private void setup(ServerLevel level) {
        // ⭐ 先记"进来时站在哪"（收尾回这里；见类注释的夹具卫生）
        if (entryFoot == null) {
            entryFoot = MovementHelper.footCell(level, bot).immutable();
        }
        mobLoot = String.valueOf(level.getServer().getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(level, new BlockPos(ORIGIN.getX() + dx, FLOOR_Y, ORIGIN.getZ() + dz), Blocks.STONE);
            }
        }
        standOn(level, BOT_FOOT);
        var session = BotManager.sessionOf(bot);
        if (session == null) {
            failures.add("FIXTURE_PREMISE_FAILED 会话不存在（scope 无从登记 ⇒ 归属必然是 FOREIGN）");
            return;
        }
        scope = session.scope();
        scope.begin(ORIGIN, SCOPE_RADIUS, bot.getUUID());
        BotLog.info("[A3] SETUP 自建地板 y={} 落点A={} 落点B={} scope({}r, owner={}) doMobLoot={}",
                FLOOR_Y, KILL_A_POS.toShortString(), KILL_B_POS.toShortString(), SCOPE_RADIUS,
                bot.getUUID(), mobLoot);
    }

    /** 前提：站在自建地板上（**与 `teleportTo` 分开的方法** —— `fixture-hygiene` 的 R4）。 */
    private void premiseOnGround() {
        boolean onGround = FixturePremise.onGround(bot).ok();
        check("夹具前提：bot 站在自建地板上（实测 onGround=" + onGround + "）", onGround);
        check("夹具前提：`doMobLoot` 必须为真（否则**根本不会有产物** —— 本夹具会对着空集断言）",
                "true".equals(mobLoot));
    }

    /**
     * 生成一头牛并打死它。
     *
     * @param ours {@code true} = **我方击杀**（`playerAttack(bot)` ⇒ 死亡事件里的 killer 是 bot）；
     *             {@code false} = **无归因击杀**（`genericKill()` ⇒ killer 为 null，反向对照臂）
     */
    private void kill(ServerLevel level, BlockPos pos, boolean ours) {
        Cow cow = EntityType.COW.create(level);
        if (cow == null) {
            failures.add("FIXTURE_PREMISE_FAILED 生成不了牛（EntityType.COW.create 返回 null）");
            return;
        }
        cow.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        cow.setNoAi(true);                       // 别让 AI 把它走出判定几何
        cow.setPersistenceRequired();
        level.addFreshEntity(cow);
        var source = ours ? bot.damageSources().playerAttack(bot) : cow.damageSources().genericKill();
        boolean died = cow.hurt(source, 1000.0F);
        BotLog.info("[A3] {} 落点={} 击杀={}（killer={}）died={} hp={}",
                ours ? "臂① 生成牛 + 我方击杀" : "臂② 生成牛 + 无归因击杀", pos.toShortString(),
                ours ? "playerAttack(bot)" : "genericKill", ours ? bot.getUUID() : "null", died,
                cow.getHealth());
        if (!died) {
            failures.add("FIXTURE_PREMISE_FAILED 造物失败：牛没被打死 " + pos.toShortString());
        }
    }

    /** 读这一批产物的归属（走 `DropPolicy.effectiveProvenance` —— **唯一入口**）。 */
    private void assertProvenance(ServerLevel level, BlockPos pos, boolean ours) {
        List<ItemEntity> drops = drops(level, pos, 3.0D);
        List<DropPolicy.Provenance> provs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ItemEntity drop : drops) {
            provs.add(DropPolicy.effectiveProvenance(bot, drop));
            names.add(String.valueOf(BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()))
                    + "x" + drop.getItem().getCount());
        }
        if (ours) {
            provA.addAll(provs);
            itemsA = names.isEmpty() ? "-" : String.join(",", names);
            passiveA = !provs.isEmpty() && provs.stream()
                    .allMatch(p -> DropPolicy.mayPickUpPassively(bot, p));
            check("⭐ 臂① 前提：我方击杀**产生了产物**（实测 " + provs.size() + " 堆：" + itemsA + "）",
                    !provs.isEmpty());
            // ⭐ `A3` 判据本体：**必须**是 `OURS_KILL`（红线版只判 `!= FOREIGN`，实现落地后收紧 ——
            // 先红证据见提交 `4c00fa5`：同一条臂当时读到 `provA=[FOREIGN, FOREIGN]`）
            check("⭐ 臂①（`A3` 判据）我方击杀的产物必须是 `OURS_KILL`（实测 " + provs + "）",
                    !provs.isEmpty() && provs.stream()
                            .allMatch(p -> p == DropPolicy.Provenance.OURS_KILL));
            check("⭐ 臂① 被动拾取必须放行（`mayPickUpPassively`，实测 " + passiveA + "）", passiveA);
        } else {
            provB.addAll(provs);
            itemsB = names.isEmpty() ? "-" : String.join(",", names);
            passiveB = !provs.isEmpty() && provs.stream()
                    .allMatch(p -> DropPolicy.mayPickUpPassively(bot, p));
            check("⭐ 臂② 前提：无归因击杀也**产生了产物**（实测 " + provs.size() + " 堆：" + itemsB + "）",
                    !provs.isEmpty());
            check("⭐ 臂② 反向对照：无归因的产物**必须仍是 FOREIGN**（实测 " + provs + "）",
                    !provs.isEmpty() && provs.stream()
                            .allMatch(p -> p == DropPolicy.Provenance.FOREIGN));
            check("⭐ 臂② 反向对照：被动拾取**必须被拦**（实测 mayPickUpPassively=" + passiveB + "）",
                    !provs.isEmpty() && !passiveB);
        }
    }

    private Task.Status finish(ServerLevel level) {
        if (reported) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        reported = true;
        cleanup(level);
        BotLog.info("[A3] SUMMARY checks={} failures={} provA={} itemsA={} passiveA={} pickedA={}"
                        + " pickupATicks={} remainA={} provB={} itemsB={} passiveB={} remainingB={}"
                        + " pickupBTicks={} blocked=+{} doMobLoot={} ticks={} verdict={}",
                checks, failures.size(), provA, itemsA, passiveA, pickedA, pickupATicks, dropsAtPickupA,
                provB, itemsB, passiveB, remainingB, pickupBTicks,
                blockedAfterB < 0 || blockedBefore < 0 ? "-" : (blockedAfterB - blockedBefore),
                mobLoot, totalTicks, failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[A3] 地上残留读数：臂①={} 臂②={}", dropsAtPickupA, dropsAtPickupB);
        for (String line : findings) {
            BotLog.info("[A3]   {}", line);
        }
        for (String line : failures) {
            BotLog.warn("[A3]   {}", line);
        }
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 击杀产物归属夹具（A3）："
                    + (failures.isEmpty() ? "PASS" : "FAIL") + " checks=" + checks
                    + " failures=" + failures.size()));
        }
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 工具 ====================

    private List<ItemEntity> drops(ServerLevel level, BlockPos pos, double radius) {
        return level.getEntitiesOfClass(ItemEntity.class,
                new AABB(pos).inflate(radius), ItemEntity::isAlive);
    }

    private int dropsNear(ServerLevel level, BlockPos pos, double radius) {
        return drops(level, pos, radius).size();
    }

    /**
     * 落物的**原始读数**（坐标 + `PickupDelay` + `Age` + 距离）：只在**断言与终态行**里用。
     * 为什么留着它：拾取这条路径的失败原因全在这三个数上（"还没开始被 tick"/"还有延迟"/"不在范围内"），
     * 而 `pickedA=0` 本身说不出是哪一种（第一版就是靠它才定位到"`Age=0` 冻住约 10 tick"）。
     */
    private String describeDrops(ServerLevel level, BlockPos pos, double radius) {
        List<String> rows = new ArrayList<>();
        for (ItemEntity drop : drops(level, pos, radius)) {
            var nbt = drop.saveWithoutId(new net.minecraft.nbt.CompoundTag());
            rows.add(String.valueOf(BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()))
                    + "x" + drop.getItem().getCount()
                    + "@" + String.format("%.2f/%.2f/%.2f", drop.getX(), drop.getY(), drop.getZ())
                    + "(delay=" + nbt.getShort("PickupDelay") + ",age=" + nbt.getShort("Age")
                    + ",d=" + String.format("%.2f", Math.sqrt(drop.distanceToSqr(bot))) + ")");
        }
        return rows.isEmpty() ? "-" : String.join(" ", rows);
    }

    private int itemCount() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 站到某一格上（**只传送，不做任何判据** —— 判据在后续相位里读）。 */
    private void standOn(ServerLevel level, BlockPos foot) {
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D, 0.0F, 0.0F);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private void place(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        touched.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, block.defaultBlockState(), 3);
    }

    private void cleanup(ServerLevel level) {
        // 产物 + 牛清场（夹具纪律：结束复位，别给后面的步留垃圾）
        AABB area = new AABB(ORIGIN.getX() - 6, FLOOR_Y - 2, ORIGIN.getZ() - 4,
                ORIGIN.getX() + 6, FLOOR_Y + 6, ORIGIN.getZ() + 4);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, area)) {
            drop.discard();
        }
        for (Cow cow : level.getEntitiesOfClass(Cow.class, area)) {
            cow.discard();
        }
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        // ⭐ 夹具卫生：回**进来时**的脚位（不是自己的场景原点 —— 那里多半已经是空中）
        BlockPos back = entryFoot != null ? entryFoot : BOT_FOOT;
        standOn(level, back);
    }
}

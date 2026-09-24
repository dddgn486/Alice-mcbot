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
 *   <li>**臂①（正向 = `A3` 判据）**：bot 亲手打死一头牛（`playerAttack(bot)`）⇒ 产物**不许**是
 *       `FOREIGN`，且**被动拾取必须放行**，并**端到端**真的进背包（走到落点上等着捡）；</li>
 *   <li>**臂②（反向对照 = "闸门还在"）**：同一片区里再杀一头牛，但**没有任何玩家归因**
 *       （`genericKill()` ⇒ killer=null）⇒ 产物**必须仍是 `FOREIGN`**、被动拾取**必须被拦**、
 *       东西**必须留在地上**。没有这条臂，"把所有掉落物一律登记成我方的"也能让臂①变绿（假绿）。</li>
 * </ol>
 *
 * <h2>为什么用"等 tick + 站在落点上"而不是调用收集任务</h2>
 * 本条判据是**归属**，不是路径/收集能力（后者已有 `pickup_gate` / `collect_job` 等步覆盖）。
 * 所以这里只做两件事：读 `DropPolicy.effectiveProvenance`，然后**站着等自动吸附**（原版那条路）
 * —— 那正是真机上"自己杀的牛捡不起来"的同一段代码路径。
 *
 * <h2>夹具卫生（照 `A2′` 抓出来的纪律）</h2>
 * 收尾回**进来时的脚位**（`entryFoot`），不回自己的场景原点（那时自建地板已还原 ⇒ 多半是空中）。
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
    /** 死亡当 tick 就掉物；等 2 tick 让"生成 → 入队 → tick 末登记"走完（`D-348` 的宽限窗口）。 */
    private static final int WAIT_DROPS = 3;
    /** 掉落物 `pickupDelay` 默认 10 tick ⇒ 站着至少等 30 tick 才能断言"捡到了/没捡到"。 */
    private static final int WAIT_PICKUP = 30;
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
    private boolean passiveA;
    private boolean passiveB;
    private int pickedA = -1;
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
                if (phaseTicks >= WAIT_DROPS) {
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
                if (phaseTicks >= WAIT_PICKUP) {
                    pickedA = itemCount() - invBeforeA;
                    check("⭐ 臂① 端到端：站着等自动吸附 ⇒ 必须真的进背包（实测 +" + pickedA + " 件）",
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
                if (phaseTicks >= WAIT_DROPS) {
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
                if (phaseTicks >= WAIT_PICKUP) {
                    remainingB = dropsNear(level, KILL_B_POS, 3.0D);
                    blockedAfterB = PickupGate.blockedTotal();
                    int gained = itemCount() - invBeforeB;
                    check("⭐ 臂② 反向对照：无归因的击杀产物**必须留在地上**（实测 剩 " + remainingB
                                    + " 堆 / 背包 +" + gained + " 件）",
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
        boolean onGround = com.dddgn.alice.task.FixturePremise.onGround(bot).ok();
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
            // ⭐ `A3` 判据本体（红线版；实现落地后收紧为 `== OURS_KILL`）
            check("⭐ 臂①（`A3` 判据）我方击杀的产物**不许**是 FOREIGN（实测 " + provs + "）",
                    !provs.isEmpty() && provs.stream()
                            .noneMatch(p -> p == DropPolicy.Provenance.FOREIGN));
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
                        + " provB={} itemsB={} passiveB={} remainingB={} blocked=+{} doMobLoot={} ticks={}"
                        + " verdict={}",
                checks, failures.size(), provA, itemsA, passiveA, pickedA,
                provB, itemsB, passiveB, remainingB,
                blockedAfterB < 0 || blockedBefore < 0 ? "-" : (blockedAfterB - blockedBefore),
                mobLoot, totalTicks, failures.isEmpty() ? "PASS" : "FAIL");
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

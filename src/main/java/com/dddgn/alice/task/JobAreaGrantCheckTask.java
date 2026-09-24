package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.CollectGrants;
import com.dddgn.alice.decision.DropPolicy;
import com.dddgn.alice.decision.PickupGate;
import com.dddgn.alice.job.mine.MineProductFilter;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ `P3`（台账 §11「B. 能力线」/ `D-394` §五的待办）：**掉落物「本作业范围内」收集授权**
 * （用户 2026-09-22 裁定的方案）。
 *
 * <h2>要解决的问题</h2>
 * mine 作业的落物今天只能靠"破坏事件配对"（`OURS_DIRECT` / 松窗 `OURS_INDIRECT`）拿到归属；
 * 一旦**没配上**（连锁模组缓冲后重放、生成登记被推迟过窗口、`D-348` 那类延迟），
 * 那个落物就是 `FOREIGN` ⇒ 被动闸门拦下、收集阶段也看不见它 ⇒ **自己挖出来的东西捡不起来**。
 * 裁定的出口 = 给作业一条**「本作业声明范围内」**的收集授权（`provenance=GRANTED_AREA`）。
 *
 * <h2>⭐ 关键约束：授权必须**带产物过滤**（否则就是权限面偷偷放宽）</h2>
 * 玩家授权的语义是"这片地上的东西我都准你捡"（`GRANTED_AREA` ⇒ 策略 `AUTO`，**被动吸附也放行**）。
 * 作业想要的只是"**我自己挖出来的产物**别丢" ⇒ 直接按片授权会把范围里**玩家丢的东西**一起吸走。
 * 所以本夹具的四条臂里，**臂②③ 才是承重墙**：
 * <ol>
 *   <li>**臂①** 范围内 + **目标产物** ⇒ `GRANTED_AREA`（主动与被动都放行，端到端真的进背包）；</li>
 *   <li>**臂②** 范围内 + **非目标产物**（玩家丢的钻石）⇒ **仍是 `FOREIGN`**、被动闸门照旧拦、东西留在地上；</li>
 *   <li>**臂③** **范围外** + 目标产物 ⇒ **仍是 `FOREIGN`**、留在地上（作业授权不出圈）；</li>
 *   <li>**臂④** ⭐ **撤销即关**：作业级授权撤销后，同一个位置的目标产物**立刻**回到 `FOREIGN`
 *       ⇒ 「权限窗口 = 作业时长」这条不许退化成一个宽限期；</li>
 * </ol>
 *
 * <h2>夹具纪律（照 `A3` 的两条教训）</h2>
 * ① 进场景先记 `entryFoot`，收尾回**那里**（回自己的场景原点常常是空中）；
 * ② "等世界发生某事"一律用**等条件 + 上限**（不用固定 tick 数赌实体什么时候开始被 tick）；
 * ③ 收尾**必须撤销夹具自己签的作业级授权** —— 授权是全局内存表，留着会漏给后面的步（比方块残留更隐蔽）。
 */
public final class JobAreaGrantCheckTask implements Task {

    /** 孤立原点（`C2`=4000 / `RC3`=4100 / `Z1`=4200 / `A3`=4300 段，本夹具接在 4400）。 */
    private static final BlockPos ORIGIN = new BlockPos(4400, 100, 2600);
    private static final int FLOOR_Y = 98;
    /** 作业声明半径（水平，沿用 `CollectGrants` 的区域语义）。 */
    private static final int AREA_RADIUS = 4;
    private static final BlockPos BOT_FOOT = new BlockPos(ORIGIN.getX(), FLOOR_Y + 1, ORIGIN.getZ());
    /** 臂①：范围内 + 目标产物（铁原矿 = 假想作业的产物）。 */
    private static final BlockPos IN_PRODUCT = new BlockPos(ORIGIN.getX() + 2, FLOOR_Y + 1, ORIGIN.getZ());
    /** 臂②：**范围内**但**不是**目标产物（= 玩家丢的钻石）。与其它落点相距 ≥4 格（见 `dropsAt` 的注释）。 */
    private static final BlockPos IN_FOREIGN = new BlockPos(ORIGIN.getX(), FLOOR_Y + 1, ORIGIN.getZ() + 4);
    /** 臂③：**范围外**（半径 4 ⇒ 8 格在外）但是目标产物。 */
    private static final BlockPos OUT_PRODUCT = new BlockPos(ORIGIN.getX() + 8, FLOOR_Y + 1, ORIGIN.getZ());
    /** 臂④：撤销之后用的落点（与臂① 分开，免得"东西已经被捡走了"混淆结论）。 */
    private static final BlockPos IN_PRODUCT_AFTER = new BlockPos(ORIGIN.getX() - 2, FLOOR_Y + 1, ORIGIN.getZ());
    private static final int SCOPE_RADIUS = 24;
    /** 落物「可查」的上限（新加载区块里实体登记可能被推迟 —— `D-345` 实测 19 tick）。 */
    private static final int MAX_WAIT_DROPS = 40;
    private static final int MAX_WAIT_PICKUP = 60;
    private static final int MIN_PICKUP_TICKS = 20;
    private static final int BUDGET_TICKS = 900;

    private enum Phase { SETUP, WAIT_DROPS, ASSERT, PICKUP_PRODUCT, PICKUP_FOREIGN, REVOKE,
                          ASSERT_AFTER_REVOKE, PICKUP_AFTER, DONE }

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
    private CollectGrants.JobGrant grant;
    private MineProductFilter filter;

    private DropPolicy.Provenance provInProduct;
    private DropPolicy.Provenance provInForeign;
    private DropPolicy.Provenance provOut;
    private DropPolicy.Provenance provAfterRevoke;
    private boolean grantVisible;
    private boolean passiveInProduct;
    private boolean passiveInForeign;
    private boolean passiveOut;
    private boolean passiveAfterRevoke;
    private int pickedProduct = -1;
    private int pickupTicks = -1;
    private int blockedBefore = -1;
    private int blockedAfter = -1;
    private boolean foreignAlive;
    private boolean outAlive;
    private boolean afterRevokeAlive;
    private int gainedForeign = -1;
    private int itemsBeforeForeign = -1;
    private java.util.UUID uuidInProduct;
    private java.util.UUID uuidInForeign;
    private java.util.UUID uuidOut;
    private java.util.UUID uuidAfterRevoke;
    private String dropsAtEnd = "-";

    public JobAreaGrantCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "JobAreaGrantCheck";
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
            BotLog.warn("[P3] FAIL {}", what);
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
                if (phaseTicks >= 2) {
                    phase = Phase.WAIT_DROPS;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case WAIT_DROPS -> {
                // ⭐ **等条件 + 上限**（`A3` 的教训）：刚 forceload 的区块里实体的登记可能被推迟
                // （`D-345` 实测 summon 19 tick；这里是「新建方块 + 生成实体」同一族场景）
                // ⇒ 不许用固定 tick 数赌「落物此刻已经可查」（第一版固定 3 tick ⇒ 三条落点全报「没有落物」）。
                boolean allPresent = !dropsAt(level, IN_PRODUCT).isEmpty()
                        && !dropsAt(level, IN_FOREIGN).isEmpty()
                        && !dropsAt(level, OUT_PRODUCT).isEmpty();
                if (allPresent || phaseTicks >= MAX_WAIT_DROPS) {
                    BotLog.info("[P3] 落物就绪读数（等了 {} tick）：{}", phaseTicks, describeDrops(level));
                    phase = Phase.ASSERT;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case ASSERT -> {
                grantVisible = !CollectGrants.activeJobScoped(level.getServer()).isEmpty();
                readProvenance(level);
                check("夹具前提：作业级授权已签发且可见（实测 visible=" + grantVisible + "；"
                                + (grant == null ? "-" : grant.describe()) + "）",
                        grant != null && grantVisible);
                check("夹具前提：产物口径来自**生产过滤器**（`MineProductFilter`，实测 "
                                + (filter == null ? "-" : filter.describe()) + "）",
                        filter != null && filter.matches(new ItemStack(Items.RAW_IRON))
                                && !filter.matches(new ItemStack(Items.DIAMOND)));
                check("⭐ 臂① 范围内 + 目标产物 ⇒ `GRANTED_AREA`（实测 " + provInProduct + "）",
                        provInProduct == DropPolicy.Provenance.GRANTED_AREA);
                check("⭐ 臂① 被动拾取放行（实测 mayPickUpPassively=" + passiveInProduct + "）",
                        passiveInProduct);
                check("⭐ 臂② 范围内 + **非目标产物**（玩家丢的）⇒ 必须仍是 `FOREIGN`（实测 "
                                + provInForeign + "）",
                        provInForeign == DropPolicy.Provenance.FOREIGN);
                check("⭐ 臂② 非目标产物被动拾取必须被拦（实测 mayPickUpPassively=" + passiveInForeign + "）",
                        !passiveInForeign);
                check("⭐ 臂③ **范围外** + 目标产物 ⇒ 必须仍是 `FOREIGN`（实测 " + provOut + "）",
                        provOut == DropPolicy.Provenance.FOREIGN);
                check("⭐ 臂③ 范围外被动拾取必须被拦（实测 mayPickUpPassively=" + passiveOut + "）",
                        !passiveOut);
                phase = Phase.PICKUP_PRODUCT;
                phaseTicks = 0;
                return Task.Status.RUNNING;
            }
            case PICKUP_PRODUCT -> {
                if (phaseTicks == 1) {
                    standOn(level, IN_PRODUCT);
                }
                if (itemCount() > itemsAtSetup || phaseTicks >= MAX_WAIT_PICKUP) {
                    pickedProduct = itemCount() - itemsAtSetup;
                    pickupTicks = phaseTicks;
                    check("⭐ 臂① 端到端：范围内的目标产物必须**真的进背包**（实测 +" + pickedProduct
                                    + " 件，等了 " + pickupTicks + " tick）",
                            pickedProduct > 0);
                    phase = Phase.PICKUP_FOREIGN;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case PICKUP_FOREIGN -> {
                if (phaseTicks == 1) {
                    blockedBefore = (int) PickupGate.blockedTotal();
                    itemsBeforeForeign = itemCount();
                    standOn(level, IN_FOREIGN);
                }
                blockedAfter = (int) PickupGate.blockedTotal();
                boolean gateReached = blockedAfter > blockedBefore && phaseTicks >= MIN_PICKUP_TICKS;
                if (gateReached || phaseTicks >= MAX_WAIT_PICKUP) {
                    // ⭐ **按身份（UUID）判「东西还在不在」**，不按格子/半径：落物会被玩家的 AABB
                    // **推开**（ItemEntity 是可推动实体）⇒ 站在它上面 20 tick 后它可能已经不在原来那格里
                    // （第一版按"格子里还有没有"判 ⇒ 同代码两次判决不同：剩 1 堆 / 剩 0 堆）。
                    foreignAlive = entityAlive(level, uuidInForeign);
                    outAlive = entityAlive(level, uuidOut);
                    gainedForeign = itemCount() - itemsBeforeForeign;
                    check("⭐ 臂② 玩家丢的东西**仍在世界里且没进背包**（实测 alive=" + foreignAlive
                                    + "、背包 +" + gainedForeign + "、闸门拦了 "
                                    + (blockedAfter - blockedBefore) + " 次；落物实测 "
                                    + describeDrops(level) + "）",
                            foreignAlive && gainedForeign == 0 && blockedAfter > blockedBefore);
                    check("⭐ 臂③ 范围外的目标产物也仍在世界里（实测 alive=" + outAlive + "）", outAlive);
                    phase = Phase.REVOKE;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case REVOKE -> {
                if (phaseTicks == 1) {
                    // ⭐ 臂④：撤销（模拟作业终态）—— 权限窗口必须**立刻**关
                    boolean revoked = CollectGrants.revokeJobScoped(grant == null ? null : grant.id());
                    check("⭐ 臂④ `revokeJobScoped` 必须真的撤掉（实测 revoked=" + revoked + "）", revoked);
                    standOn(level, BOT_FOOT);
                    uuidAfterRevoke = spawn(level, IN_PRODUCT_AFTER, new ItemStack(Items.RAW_IRON, 1)).getUUID();
                }
                if (phaseTicks >= 3) {
                    phase = Phase.ASSERT_AFTER_REVOKE;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case ASSERT_AFTER_REVOKE -> {
                provAfterRevoke = provenanceOf(level, IN_PRODUCT_AFTER);
                passiveAfterRevoke = DropPolicy.mayPickUpPassively(bot, provAfterRevoke);
                check("⭐ 臂④ 撤销后同一位置的目标产物**立刻**回到 `FOREIGN`（实测 " + provAfterRevoke + "）",
                        provAfterRevoke == DropPolicy.Provenance.FOREIGN);
                check("⭐ 臂④ 撤销后被动拾取必须被拦（实测 mayPickUpPassively=" + passiveAfterRevoke + "）",
                        !passiveAfterRevoke);
                phase = Phase.PICKUP_AFTER;
                phaseTicks = 0;
                return Task.Status.RUNNING;
            }
            case PICKUP_AFTER -> {
                if (phaseTicks == 1) {
                    blockedBefore = (int) PickupGate.blockedTotal();
                    standOn(level, IN_PRODUCT_AFTER);
                }
                blockedAfter = (int) PickupGate.blockedTotal();
                boolean gateReached = blockedAfter > blockedBefore && phaseTicks >= MIN_PICKUP_TICKS;
                if (gateReached || phaseTicks >= MAX_WAIT_PICKUP) {
                    afterRevokeAlive = entityAlive(level, uuidAfterRevoke);
                    int gainedAfter = itemCount() - itemsBeforeForeign - gainedForeign;
                    check("⭐ 臂④ 端到端：撤销后站在上面也捡不起来（实测 alive=" + afterRevokeAlive
                                    + "、背包 +" + gainedAfter + "、闸门拦了 "
                                    + (blockedAfter - blockedBefore) + " 次）",
                            afterRevokeAlive && gainedAfter == 0 && blockedAfter > blockedBefore);
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

    private int itemsAtSetup = -1;

    private void setup(ServerLevel level) {
        if (entryFoot == null) {
            entryFoot = MovementHelper.footCell(level, bot).immutable();
        }
        for (int dx = -4; dx <= 10; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                place(level, new BlockPos(ORIGIN.getX() + dx, FLOOR_Y, ORIGIN.getZ() + dz), Blocks.STONE);
            }
        }
        standOn(level, BOT_FOOT);
        var session = BotManager.sessionOf(bot);
        if (session == null) {
            failures.add("FIXTURE_PREMISE_FAILED 会话不存在（scope 无从登记）");
            return;
        }
        scope = session.scope();
        scope.begin(ORIGIN, SCOPE_RADIUS, bot.getUUID());
        // ⭐ 走**生产过滤器**（同一个类、同一个入口：`MineProductFilter.forTag` + `matches`）
        filter = MineProductFilter.forTag(Tags.Items.RAW_MATERIALS_IRON);
        grant = CollectGrants.addJobScoped(level.getServer(), ORIGIN, AREA_RADIUS, filter.describe(),
                filter::matches, "fixture:job_area_grant", 20 * 600);
        uuidInProduct = spawn(level, IN_PRODUCT, new ItemStack(Items.RAW_IRON, 2)).getUUID();
        uuidInForeign = spawn(level, IN_FOREIGN, new ItemStack(Items.DIAMOND, 1)).getUUID();
        uuidOut = spawn(level, OUT_PRODUCT, new ItemStack(Items.RAW_IRON, 2)).getUUID();
        itemsAtSetup = itemCount();
        BotLog.info("[P3] SETUP 地板 y={} 作业区 center={} r={} 落点：内产物={} 内非产物={} 外产物={}"
                        + " 背包={}",
                FLOOR_Y, ORIGIN.toShortString(), AREA_RADIUS, IN_PRODUCT.toShortString(),
                IN_FOREIGN.toShortString(), OUT_PRODUCT.toShortString(), itemsAtSetup);
    }

    /** 读三条（范围内产物 / 范围内非产物 / 范围外产物）的归属与两条路径的判定。 */
    private void readProvenance(ServerLevel level) {
        provInProduct = provenanceOf(level, IN_PRODUCT);
        provInForeign = provenanceOf(level, IN_FOREIGN);
        provOut = provenanceOf(level, OUT_PRODUCT);
        passiveInProduct = DropPolicy.mayPickUpPassively(bot, provInProduct);
        passiveInForeign = DropPolicy.mayPickUpPassively(bot, provInForeign);
        passiveOut = DropPolicy.mayPickUpPassively(bot, provOut);
    }

    private DropPolicy.Provenance provenanceOf(ServerLevel level, BlockPos pos) {
        for (ItemEntity drop : dropsAt(level, pos)) {
            return DropPolicy.effectiveProvenance(bot, drop);
        }
        failures.add("FIXTURE_PREMISE_FAILED 落点上没有落物（" + pos.toShortString() + "）");
        return DropPolicy.Provenance.FOREIGN;
    }

    private Task.Status finish(ServerLevel level) {
        if (reported) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        reported = true;
        dropsAtEnd = describeDrops(level);
        cleanup(level);
        BotLog.info("[P3] SUMMARY checks={} failures={} provInProduct={} passiveInProduct={} picked={}"
                        + " provInForeign={} passiveInForeign={} provOut={} passiveOut={}"
                        + " provAfterRevoke={} passiveAfterRevoke={} foreignAlive={} outAlive={}"
                        + " afterRevokeAlive={} blocked={} ticks={} verdict={}",
                checks, failures.size(), provInProduct, passiveInProduct, pickedProduct,
                provInForeign, passiveInForeign, provOut, passiveOut,
                provAfterRevoke, passiveAfterRevoke, foreignAlive, outAlive, afterRevokeAlive,
                blockedAfter - Math.max(0, blockedBefore),
                totalTicks, failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[P3] 收尾读数：grant={} 末尾落物={}", grant == null ? "-" : grant.describe(), dropsAtEnd);
        for (String line : findings) {
            BotLog.info("[P3]   {}", line);
        }
        for (String line : failures) {
            BotLog.warn("[P3]   {}", line);
        }
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 作业范围收集授权夹具（P3）："
                    + (failures.isEmpty() ? "PASS" : "FAIL") + " checks=" + checks
                    + " failures=" + failures.size()));
        }
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 工具 ====================

    /** 生成一堆落物并返回它（夹具按 **UUID 身份**跟踪；见 `entityAlive` 的注释）。 */
    private ItemEntity spawn(ServerLevel level, BlockPos pos, ItemStack stack) {
        ItemEntity drop = new ItemEntity(level, pos.getX() + 0.5D, pos.getY() + 0.2D,
                pos.getZ() + 0.5D, stack);
        drop.setDeltaMovement(Vec3.ZERO);
        level.addFreshEntity(drop);
        return drop;
    }

    /** 那一堆**还是不是它**（按 UUID）—— 「东西还在吗」只能用身份判，不能用格子/半径近似（会被推开）。 */
    private boolean entityAlive(ServerLevel level, java.util.UUID id) {
        if (id == null) {
            return false;
        }
        var entity = level.getEntity(id);
        return entity != null && !entity.isRemoved();
    }

    private List<ItemEntity> drops(ServerLevel level, BlockPos pos, double radius) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(radius), ItemEntity::isAlive);
    }

    /**
     * ⭐ **本格的**落物（`blockPosition()` **相等**）。
     *
     * <p>⚠️ 夹具教训（`P3` 第一版实测踩到）：`new AABB(pos).inflate(r)` 的**半边长是 `r + 0.5`**
     * （`AABB(pos)` 本身是 1×1×1 的方块盒）⇒ 我写 1.5 想要"只框住自己那格"，实际覆盖 x/z 各 ±2 格，
     * 于是**相距 2 格**的两个落点互相进入对方的框 ⇒ 臂②（范围内玩家丢的钻石）读到的是**邻格的铁**
     * （读数 `provInForeign=GRANTED_AREA`，而闸门日志明明是 `diamond … FOREIGN`）。
     * 修法 = 判据改成"**这一格**"（`blockPosition()` 相等），不再依赖 AABB 的半径语义；
     * 落点之间也拉开到 ≥4 格。⇒ 凡"这一格上有什么"的读数，都别用 inflate 半径去近似。
     */
    private List<ItemEntity> dropsAt(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0D),
                drop -> drop.isAlive() && drop.blockPosition().equals(pos));
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

    /** 落物的原始读数（坐标 + `PickupDelay` + `Age` + 距离）—— 失败时用来说明"卡在哪一步"。 */
    private String describeDrops(ServerLevel level) {
        List<String> rows = new ArrayList<>();
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        for (BlockPos pos : List.of(IN_PRODUCT, IN_FOREIGN, OUT_PRODUCT, IN_PRODUCT_AFTER)) {
            for (ItemEntity drop : dropsAt(level, pos)) {
                if (!seen.add(drop.getUUID())) {
                    continue;
                }
                var nbt = drop.saveWithoutId(new net.minecraft.nbt.CompoundTag());
                // ⚠️ 印**落物自己的**格（不是「我在哪个框里找到的」）—— 第一版印框坐标，
                // 框重叠时读出来的位置是错的（诊断价值归零）。
                rows.add(String.valueOf(BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()))
                        + "@" + drop.blockPosition().toShortString() + "(框=" + pos.toShortString()
                        + ",delay=" + nbt.getShort("PickupDelay") + ",age=" + nbt.getShort("Age") + ")");
            }
        }
        return rows.isEmpty() ? "-" : String.join(" ", rows);
    }

    /** 站到某一格上（只传送；判据在后续相位里读）。 */
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
        // ⭐ 夹具自己签的**作业级授权**必须收掉：它是全局内存表，留着会漏给后面的步（比方块残留更隐蔽）
        if (grant != null) {
            CollectGrants.revokeJobScoped(grant.id());
        }
        AABB area = new AABB(ORIGIN.getX() - 6, FLOOR_Y - 2, ORIGIN.getZ() - 4,
                ORIGIN.getX() + 12, FLOOR_Y + 6, ORIGIN.getZ() + 5);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, area)) {
            drop.discard();
        }
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        BlockPos back = entryFoot != null ? entryFoot : BOT_FOOT;
        standOn(level, back);
    }
}

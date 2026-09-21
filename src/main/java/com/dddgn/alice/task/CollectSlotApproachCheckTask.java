package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ⭐ **掉落物「够得着的可站格」取证夹具**（电池步 `collect_slot_approach`；`D-375` 的落地判据）。
 *
 * <h2>它钉住的是什么（一句话）</h2>
 * <b>「掉落物在一格高夹缝里」= 它自己那格站不住 —— 此时必须走到**够得着的可站邻格**去捡，
 * 绝不许把那个站不住的格当寻路目标（那等于让寻路挖穿天花板）。</b>
 *
 * <h2>当时那条缺陷链（2026-09-21 第六轮真机，四个环节缺一不可）</h2>
 * <ol>
 *   <li>{@code CollectDropsTask.pickupGoalFor} 的"够得着吗"是**粗判**（逐轴 1.2），而真实拾取盒的
 *       逐轴上界是 `0.125 + 0.3 + 1.0 = 1.425` ⇒ 存在一段"**真够得着、却被规划期否掉**"的位置；</li>
 *   <li>真机上那件落物正好落在这段里（可站邻格 `433,87,205` 距物品中心 1.3~1.4）⇒ 搜索一圈
 *       **一格都没找到**；</li>
 *   <li>于是静默走 `best == null → return itemCell` 的**兜底**——把站不住的"物品自身格"当目标
 *       （**这一行日志都没有**）；</li>
 *   <li>走到一个"脚位可通行、头位被挡"的格**只能**靠 {@code BREAK_AND_ENTER}（`D-374` 刚补上的那条边）
 *       ⇒ 搜索被迫给出唯一可行路线：<b>19 段计划 / 破 6 格 / 约 10 秒</b> ⇒ 烧满 200 tick 簇预算
 *       ⇒ 最坏 `collected=0/13`。</li>
 * </ol>
 *
 * <h2>四个案例（两个判据 + 各自的反证）</h2>
 * <table border="1">
 *   <tr><th>案例</th><th>场景</th><th>期望</th><th>`D-375` 修复前</th></tr>
 *   <tr><td>{@code SLOT_REACH}</td><td>物品在 1 格高夹缝（头位被挡），邻格可站、真够得着（中心距 1.3 &gt; 旧粗判 1.2）</td>
 *       <td>进包 1 件 + **世界零改动**</td><td>❌ 红（把物品自身格当目标 ⇒ 破天花板 1 格）</td></tr>
 *   <tr><td>{@code UNREACHABLE}</td><td>物品所在格 + 8 邻格全是 1 格高 ⇒ **没有**可站且够得着的格</td>
 *       <td>进包 0 件 + 世界零改动 + `no_approach=1`（如实退休，物品留在世界）</td>
 *       <td>❌ 红（挖穿两格天花板进去捡）</td></tr>
 *   <tr><td>{@code SEALED_ROOM}（反证：**该改世界时照样改**）</td><td>目标格可站，但四面封死；世界修改已授权</td>
 *       <td>进包 1 件 + 世界**确实**被改 + 决策层收到 `PICKUP_DETOUR`</td>
 *       <td>✅ 本来就绿（钉住"修复没有把合法的破墙也一起禁掉"）</td></tr>
 *   <tr><td>{@code PICKUP_DELAY}（反证：**慢也要被看见**）</td><td>到位却永远捡不起来（`PickupDelay` 永久）</td>
 *       <td>进包 0 件 + 簇耗时超阈值 ⇒ 决策层收到 `PICKUP_SLOW`</td>
 *       <td>✅ 本来就绿（钉住第二条决策层信号）</td></tr>
 * </table>
 *
 * <h2>它断言哪一层（技能 §6.9.1 ③）</h2>
 * <b>动作层</b>——真世界里一个真的 {@code CollectDropsTask}，被本夹具**每 tick 驱动一 tick**
 * （与 `mine_far_drop` / `region_sweep_e2e` 同一个手法），且**世界修改授权 = `true`**
 * （= 挖掘/伐木/回收作业的真实调用口径，`D-372`）⇒ `SLOT_REACH` 的"零改动"是**真的行为约束**，
 * 不是"没授权所以改不了"。
 *
 * <h2>怎么保证"可规划即可执行"（判据复用同一定义）</h2>
 * 前提里的"够得着吗"**直接调生产代码那一个谓词**（{@code CollectDropsTask.withinPickupReach}，包可见），
 * 不自己复写一份近似判据 —— 夹具若用另一套判据，就会自己骗自己。
 *
 * <h2>几何前提（§6.9.1 ①：盒子以谁为中心、多大，全部写下来并自断言）</h2>
 * <ul>
 *   <li>场景 = 空中平台 {@link #ORIGIN}（y=100，x/z 在 3200 一带、其它夹具不用 z=2600）：
 *       地板 {@code y=100}、脚位/物品层 {@code y=101}、天花/围墙层 {@code y=102}，上方留空气
 *       ⇒ 与其它场景**不相连**（技能 §2 孤立长方体）；</li>
 *   <li>建之前先断言整块**本来就是空气**（不是就记硬前提失败，绝不覆盖别人的东西）；</li>
 *   <li>四个案例各自**自己传送 bot 到起点**、**自己复位**（§5.0d），案例之间互不干扰（每案例重开作用域）。</li>
 * </ul>
 *
 * <h2>副作用边界（§6.9.2）</h2>
 * 不调 {@code assign*}/{@code beginTask} ⇒ 自己持有并 tick 一个 {@code CollectDropsTask}；
 * 造出来的地形收尾清回空气、撤销 forceload、造出来的落物全部 discard、bot 回传送前的位置。
 */
public final class CollectSlotApproachCheckTask implements Task {

    /** 场景原点（**远离所有场景**：y=100 的空中，其它夹具不在这片 z 上）。 */
    private static final BlockPos ORIGIN = new BlockPos(3200, 100, 2600);

    /** 地板范围（相对 ORIGIN）：x ∈ [-7, +7]、z ∈ [-2, +6]。 */
    private static final int FLOOR_HALF_X = 7;
    private static final int FLOOR_MIN_Z = -2;
    private static final int FLOOR_MAX_Z = 6;

    /** 脚位/物品层与天花/围墙层（相对 ORIGIN 的 y 偏移）。 */
    private static final int FOOT_DY = 1;
    private static final int CEIL_DY = 2;
    /** 地板之上留的空气层数（围墙 2 高，再留 1 层空气免得贴顶）。 */
    private static final int HEADROOM = 3;

    /** 案例 A：物品格 (+4, 0)；天花板 x ∈ {+3,+4}、z ∈ {-1..+1}；bot 起点 x=+7。 */
    private static final int A_ITEM_DX = 4;
    private static final int A_BOT_DX = 7;
    /**
     * 案例 A 的**物品在格内的 x 偏移**（格内 0..1）。
     *
     * <p>⭐ 这就是整个夹具的**场景形状**：它决定"可站邻格 (+5,0) 到物品中心的距离"。
     * 取 `0.2` ⇒ 距离 `1.5 - 0.2 = 1.3`：**大于旧粗判 1.2、小于真实上界 1.425**
     * —— 正是真机被打中的那段区间（`1.2 &lt; 1.3 &lt; 1.425`）。
     * 若哪天挪出这段区间（比如改成 0.5 ⇒ 距离 1.0），本夹具就**测不到**旧缺陷了
     * ⇒ 前提自断言会红（保护夹具自己，与 `mine_far_drop` 的 `FAR_WINDOW` 同一个手法）。
     */
    private static final double A_ITEM_OFFSET_X = 0.2D;
    /** 案例 A 的物品在格内的 z 偏移（居中）。 */
    private static final double A_ITEM_OFFSET_Z = 0.5D;

    /** 案例 B：物品格 (-4, 0)；天花板 = 以它为中心的 3×3；bot 起点 x=-7。 */
    private static final int B_ITEM_DX = -4;
    private static final int B_BOT_DX = -7;

    /** 案例 C：物品格 (+4, +5)，四面（8 格 × 2 层）封死；bot 起点 (+4, +3)。 */
    private static final int C_ITEM_DX = 4;
    private static final int C_ITEM_DZ = 5;
    private static final int C_BOT_DZ = 3;

    /**
     * 案例 D：物品格 (+2, -2)（开阔地，可站），`PickupDelay` 永久；bot 起点 (+2, +1)。
     *
     * <p>⚠️ x 取 `+2` 而**不是** `+4`：案例 A 的天花板覆盖 x ∈ {+3,+4}、z ∈ {-1..+1}
     * ⇒ 若起点落在 (+4, +1)，bot 的**头位就在自家石板里** —— 自检实测：传送当 tick 触发
     * `survival_suffocating`，把整个电池步打断成"无判决"（`exit=5`，不是红也不是绿）✗。
     */
    private static final int D_ITEM_DX = 2;
    private static final int D_ITEM_DZ = -2;
    private static final int D_BOT_DZ = 1;

    /** 旧粗判的逐轴上界（= `D-375` 修复前 `withinPickupReach` 里的 1.2）—— 只用来刻画场景形状。 */
    private static final double OLD_ROUGH_BOUND = 1.2D;

    /**
     * 产物物品：**本夹具独有**（`D-375`）。
     *
     * <p>刻意不用 `raw_iron`（`mine_far_drop` / 挖矿作业都用它）：本夹具会把捡到的产物**留在背包里**
     * 到收尾才清 ⇒ 用同一个物品会污染 `mine_*` 那几步的"背包增量"口径。
     */
    private static final String PRODUCT = "minecraft:amethyst_shard";
    /** `PickupDelay` 永久值（原版的"永不拾取"哨兵值）。 */
    private static final String PICKUP_NEVER = "32767s";

    /** 收集器自带的总预算（`CollectDropsTask.DEFAULT_TOTAL_BUDGET_TICKS`，私有常量 ⇒ 照抄值）。 */
    private static final int COLLECTOR_BUDGET_TICKS = 600;
    /** 作用域半径（覆盖整个场景：最远对角 √(7²+6²) ≈ 9.2）。 */
    private static final int SCOPE_RADIUS = 12;

    /** 本步预算（四个案例串行 + 收集器自带预算 ⇒ 护栏要高一点）。 */
    private static final int BUDGET_TICKS = 900;
    /** 每案例给"等落物可见/落地"的 tick 上限。 */
    private static final int SUMMON_CAP = 60;
    /** 每案例给收集器跑的 tick 上限（收集器自己 600 tick，这里是"它该早就结束了"的护栏）。 */
    private static final int CASE_RUN_CAP = 260;

    /** 案例种类（前提判据各不相同）。 */
    private enum Kind { SLOT_REACH, UNREACHABLE, SEALED_ROOM, PICKUP_DELAY }

    private enum Phase { SETUP, SPAWN, SETTLE, RUN, ASSERT, DONE }

    /** 一个案例的完整期望（`D-375`：每个案例都是"判据 + 反证"的一对）。 */
    private record Expect(Kind kind, BlockPos itemCell, BlockPos botStart, double itemOffsetX,
                          double itemOffsetZ, boolean pickupNever, int collected, boolean worldMustChange,
                          int noApproach, int minDetour, int minSlow, String why) {
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    private Phase phase = Phase.SETUP;
    private int ticks;
    /** 案例表（SETUP 时按 ORIGIN 生成）。 */
    private List<Expect> cases = List.of();
    private int caseIndex = -1;
    private Expect current;

    private final List<String> failures = new ArrayList<>();
    private int checks;
    private String setupFailure = "";

    // ---- 场景（建/拆对称）----
    /** 本夹具**亲手写过**的每个位置（收尾要清回空气，且它是"世界改动"计数的探针集合）。 */
    private final Map<BlockPos, BlockState> probe = new LinkedHashMap<>();
    private BlockPos entryFoot;
    private int builtBlocks;
    private int preExistingNonAir = -1;

    // ---- 当前案例的状态 ----
    private ItemEntity item;
    private UUID itemId;
    private boolean itemVisible;
    private int settleTicks;
    private CollectDropsTask collector;
    private Task.Status collectorStatus = Task.Status.DONE;
    private int runTicks;
    private int inventoryBefore;
    private String itemReason = "";
    private Map<BlockPos, BlockState> worldBefore = Map.of();
    private int worldChanges;
    private int collectedDelta;
    private boolean itemStillOnGround;
    // ---- `D-375`：**跑之前**取的世界前提（跑完再取就不是前提了）----
    private boolean premiseItemCellStandable;
    private boolean premiseGoalStandable;
    private boolean premiseGoalReachable;
    private double premiseGoalDistance = -1;
    private int premiseRingStandable;
    private int premiseRingOpenings;
    private boolean premiseNearestOutOfReach;

    public CollectSlotApproachCheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "CollectSlotApproachCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN.above(FOOT_DY));
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "passed" : "failed";
    }

    @Override
    public Task.Status tick() {
        if (++ticks > BUDGET_TICKS && phase != Phase.ASSERT && phase != Phase.DONE) {
            check("夹具护栏：" + BUDGET_TICKS + " tick 内必须跑完（实际 " + ticks + "，阶段=" + phase
                    + "，案例=" + caseName() + "）", false);
            phase = Phase.ASSERT;
        }
        return switch (phase) {
            case SETUP -> setup();
            case SPAWN -> spawn();
            case SETTLE -> settle();
            case RUN -> run();
            case ASSERT -> assertCase();
            case DONE -> failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        };
    }

    // ==================== SETUP ====================

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        // ① forceload 先做：**未加载区块里写方块是静默无效的**（本项目踩过两次：D-244 / FarBench 注释）
        forceload(level, true);

        // ② 建之前先证明"这块本来就是空气"（不是就**不许**覆盖：那是别人的场景）
        preExistingNonAir = 0;
        for (int x = -FLOOR_HALF_X; x <= FLOOR_HALF_X; x++) {
            for (int z = FLOOR_MIN_Z; z <= FLOOR_MAX_Z; z++) {
                for (int dy = 0; dy <= CEIL_DY; dy++) {
                    if (!level.getBlockState(ORIGIN.offset(x, dy, z)).isAir()) {
                        preExistingNonAir++;
                    }
                }
            }
        }
        if (preExistingNonAir != 0) {
            // **绝不许覆盖别人的东西**：这块位置本该是空的（空中）⇒ 当场收手，连地形都不建
            // （⇒ `probe` 为空 ⇒ 收尾不会清掉任何东西）
            setupFailure = "SCENE_NOT_AIR(空中平台位置本来有 " + preExistingNonAir + " 个非空气方块)";
            check("硬前提失败：" + setupFailure + " ⇒ 本步的其余判据都不成立", false);
            phase = Phase.DONE;
            return Task.Status.RUNNING;
        }

        // ③ 地板 + 上方空气
        for (int x = -FLOOR_HALF_X; x <= FLOOR_HALF_X; x++) {
            for (int z = FLOOR_MIN_Z; z <= FLOOR_MAX_Z; z++) {
                set(level, x, 0, z, Blocks.STONE);
                for (int dy = 1; dy <= HEADROOM; dy++) {
                    set(level, x, dy, z, Blocks.AIR);
                }
            }
        }
        // ④ 案例 A 的天花板（1 格高夹缝）：x ∈ {+3,+4}、z ∈ {-1..+1}
        for (int x = A_ITEM_DX - 1; x <= A_ITEM_DX; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(level, x, CEIL_DY, dz, Blocks.STONE);
            }
        }
        // ⑤ 案例 B 的天花板：以物品格为中心的 3×3
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(level, B_ITEM_DX + dx, CEIL_DY, dz, Blocks.STONE);
            }
        }
        // ⑥ 案例 C 的封闭石室：物品格周围 8 格 × 2 层（脚位 + 头位）全实心 ⇒ 只能破墙进去
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;      // 物品格本身留空（且它**可站**：下面是地板、头位是空气）
                }
                set(level, C_ITEM_DX + dx, FOOT_DY, C_ITEM_DZ + dz, Blocks.STONE);
                set(level, C_ITEM_DX + dx, CEIL_DY, C_ITEM_DZ + dz, Blocks.STONE);
            }
        }
        builtBlocks = probe.size();

        // ⑦ 几何前提自断言（没落地就不许把"收集失败"当缺陷证据）
        boolean floorOk = level.getBlockState(ORIGIN.offset(0, 0, 0)).is(Blocks.STONE)
                && level.getBlockState(ORIGIN.offset(A_ITEM_DX, 0, 0)).is(Blocks.STONE)
                && level.getBlockState(ORIGIN.offset(B_ITEM_DX, 0, 0)).is(Blocks.STONE)
                && level.getBlockState(ORIGIN.offset(C_ITEM_DX, 0, C_ITEM_DZ)).is(Blocks.STONE);
        if (!floorOk && setupFailure.isEmpty()) {
            setupFailure = "TERRAIN_NOT_BUILT(空中平台没落地)";
        }

        // ⑧ 传送（玩家 ticket 会同步加载区块）+ 记下入口脚位（收尾复位用）
        entryFoot = bot.blockPosition();
        teleport(level, ORIGIN.offset(0, FOOT_DY, 0));

        // ⑨ 清背包（`resetInventory` 会把背包里的东西掉在脚下 ⇒ 必须在清场之前做）
        FixtureToolKit.resetInventory(bot);
        // ⭐ 给一把镐：案例 C 的"合法破墙"是真破石墙 —— 没工具的话那不是"决策层看得见"而是"挖不动"
        FixtureToolKit.ensurePickaxe(bot);
        // 前提：清过背包 ⇒ 本步开始前**没有**本夹具的产物（否则背包增量口径被上一步污染）
        check("前提：清背包后没有本夹具的产物（实际 " + countProductInInventory() + "）",
                countProductInInventory() == 0);

        cases = List.of(
                new Expect(Kind.SLOT_REACH, cell(A_ITEM_DX, 0), cell(A_BOT_DX, 0),
                        A_ITEM_OFFSET_X, A_ITEM_OFFSET_Z, false,
                        1, false, 0, 0, 0,
                        "1 格高夹缝：物品所在格站不住、邻格可站且**真够得着**（中心距 1.3 ∈ (旧粗判1.2, 真实1.425]）"
                                + " ⇒ 必须走过去捡到，且**一格都不许挖**"),
                new Expect(Kind.UNREACHABLE, cell(B_ITEM_DX, 0), cell(B_BOT_DX, 0),
                        0.5D, 0.5D, false,
                        0, false, 1, 0, 0,
                        "物品所在格 + 8 邻格全是 1 格高 ⇒ **没有**可站且够得着的格 ⇒ 如实退休"
                                + "（`no_standable_approach`）、物品留在世界、一格都不许挖"),
                new Expect(Kind.SEALED_ROOM, cell(C_ITEM_DX, C_ITEM_DZ), cell(C_ITEM_DX, C_BOT_DZ),
                        0.5D, 0.5D, false,
                        1, true, 0, 1, 0,
                        "目标格可站但四面封死（世界修改**已授权**）⇒ 合法破墙进去拿到；反证："
                                + "修复不许把「该改世界时」也一起禁掉，且决策层必须收到 `PICKUP_DETOUR`"),
                new Expect(Kind.PICKUP_DELAY, cell(D_ITEM_DX, D_ITEM_DZ), cell(D_ITEM_DX, D_BOT_DZ),
                        0.5D, 0.5D, true,
                        0, false, 0, 0, 1,
                        "到位却永远捡不起来（`PickupDelay` 永久）⇒ 簇耗时超阈值 ⇒ 决策层必须收到"
                                + " `PICKUP_SLOW`（真机那条 10 秒绕远当时对决策层完全不可见）"));

        BotLog.info("[CollectSlot] CHECK setup origin={} 地板方块={} 原本非空气={} 案例数={} setupFailure={}",
                ORIGIN.toShortString(), builtBlocks, preExistingNonAir, cases.size(),
                setupFailure.isEmpty() ? "-" : setupFailure);
        if (!setupFailure.isEmpty()) {
            phase = Phase.ASSERT;      // 硬前提失败 ⇒ 跑下去没有意义（ASSERT 里如实判红）
            return Task.Status.RUNNING;
        }
        phase = Phase.SPAWN;
        return Task.Status.RUNNING;
    }

    // ==================== 每案例：SPAWN → SETTLE → RUN → ASSERT ====================

    private Task.Status spawn() {
        if (!startNextCase()) {
            phase = Phase.DONE;
            return Task.Status.RUNNING;
        }
        ServerLevel level = bot.serverLevel();
        // ① 复位 bot 到本案例起点（夹具自己传送，§5.0d）
        teleport(level, current.botStart());
        settleTicks = 0;
        runTicks = 0;
        itemVisible = false;
        itemStillOnGround = false;
        worldBefore = Map.of();

        // ② 每案例重开作用域（清掉上一案例的登记 ⇒ 案例之间互不干扰）
        scope.begin(ORIGIN, SCOPE_RADIUS, bot.getUUID());

        // ③ 造落物（`/summon`：命令返回值必须看 —— 陷阱 #5 静默失败）
        // ⭐ 起点必须**可站**（脚位/头位都不能被挡）：自检实测踩到过"起点头顶是本夹具自己的
        // 石板 ⇒ 传送当 tick 窒息 ⇒ 整个电池步打断成无判决"（`exit=5`）——那次是案例 D 的 x 选错。
        if (!canStand(level, current.botStart())) {
            check("硬前提失败：案例 " + current.kind() + " 的起点 " + current.botStart().toShortString()
                    + " **站不住**（头位被挡？）⇒ 本案例不做判据（防窒息打断整个电池步）", false);
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        String pickupDelay = current.pickupNever() ? PICKUP_NEVER : "0s";
        boolean spawned = summonProduct(level, current.itemCell(), current.itemOffsetX(),
                current.itemOffsetZ(), pickupDelay);
        inventoryBefore = countProductInInventory();
        BotLog.info("[CollectSlot] CHECK case={} spawn item={} itemCell={} botStart={} pickupDelay={} 背包起始产物={}",
                current.kind(), spawned, current.itemCell().toShortString(),
                current.botStart().toShortString(), pickupDelay, inventoryBefore);
        if (!spawned) {
            check("硬前提失败：案例 " + current.kind() + " 的落物没造出来（`/summon` 返回 0）", false);
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        phase = Phase.SETTLE;
        return Task.Status.RUNNING;
    }

    private Task.Status settle() {
        ServerLevel level = bot.serverLevel();
        settleTicks++;
        boolean onGround = FixturePremise.settledOnGround(bot, settleTicks);
        item = visibleItem(level);
        itemVisible = item != null;
        if ((itemVisible && onGround) || settleTicks > SUMMON_CAP) {
            if (item == null) {
                check("硬前提失败：案例 " + current.kind() + " 的落物等了 " + settleTicks
                        + " tick 仍不可见（区块/实体 tick 未就绪）", false);
                phase = Phase.ASSERT;
                return Task.Status.RUNNING;
            }
            itemId = item.getUUID();
            // 登记成"我方产物"（`OURS_DIRECT`，与生产"破坏事件配对"同一状态）⇒ `DropPolicy` 一律放行
            boolean ours = scope.registerAsOurs(item, com.dddgn.alice.decision.DropPolicy.Provenance.OURS_DIRECT,
                    item.blockPosition());
            if (!ours) {
                check("硬前提失败：案例 " + current.kind() + " 的落物没能登记成我方（`registerAsOurs` 返回 false）",
                        false);
                phase = Phase.ASSERT;
                return Task.Status.RUNNING;
            }
            worldBefore = snapshot(level);      // ⭐ 走位**之前**的世界快照（判"改造地形"的基线）
            capturePremises(level);             // ⭐ 世界前提也必须在走位**之前**取（见 capturePremises）
            BotLog.info("[CollectSlot] CHECK case={} 落物就位 item={} itemPos={} itemY={} settled={} tick={}",
                    current.kind(), itemId, item.blockPosition().toShortString(),
                    String.format(java.util.Locale.ROOT, "%.3f", item.getY()), onGround, settleTicks);
            phase = Phase.RUN;
        }
        return Task.Status.RUNNING;
    }

    private Task.Status run() {
        runTicks++;
        if (collector == null) {
            // 世界修改授权 = `true`（= 生产里挖掘/伐木/回收作业的真实口径，`D-372`）
            collector = new CollectDropsTask(bot, ORIGIN, scope, List.of(), true,
                    COLLECTOR_BUDGET_TICKS, com.dddgn.alice.task.mining.MiningProfile.STANDABLE_ONLY,
                    this::liveItem);
        }
        collectorStatus = collector.tick();
        if (collectorStatus != Task.Status.RUNNING) {
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        if (runTicks > CASE_RUN_CAP) {
            check("夹具护栏：案例 " + current.kind() + " 的收集器跑了 " + runTicks + " tick 还没结束", false);
            phase = Phase.ASSERT;
        }
        return Task.Status.RUNNING;
    }

    // ==================== ASSERT ====================

    private Task.Status assertCase() {
        if (!setupFailure.isEmpty()) {
            check("硬前提失败：" + setupFailure + " ⇒ 本步的其余判据都不成立", false);
            cleanup();
            phase = Phase.DONE;
            return Task.Status.RUNNING;
        }
        if (caseIndex < 0) {           // SETUP 失败进来的：没有案例可断言
            cleanup();
            phase = Phase.DONE;
            return Task.Status.RUNNING;
        }
        ServerLevel level = bot.serverLevel();

        // ---- 世界事实（**在 dispose 之前读**）----
        worldChanges = countChanges(level, worldBefore);
        int inventoryAfter = countProductInInventory();
        collectedDelta = inventoryAfter - inventoryBefore;
        itemStillOnGround = itemId != null && entityAlive(level, itemId);
        itemReason = collector == null ? "" : collector.terminalReason();

        // ---- 前提（红了说明夹具坏，不是缺陷证据）----
        checkPremises(level);

        // ---- ⭐ 判据 ----
        switch (current.kind()) {
            case SLOT_REACH -> {
                check("⭐ 期望：**物品所在格站不住**（头位被挡）—— 本案例的全部意义所在（实际可站="
                        + canStand(level, current.itemCell()) + "）", !canStand(level, current.itemCell()));
                check("⭐ 期望：进包 " + current.collected() + " 件（实际 " + collectedDelta + "）——"
                        + "够得着的可站邻格就在旁边，必须真的走过去捡到", collectedDelta == current.collected());
                check("⭐ 期望：**世界零改动**（实际 " + worldChanges + " 格）——"
                        + "`D-375` 修复前这里会把**站不住的物品自身格**当寻路目标 ⇒ 破天花板挖进去"
                        + "（真机同族症状：19 段 / 破 6 格 / 烧满 200 tick）", worldChanges == 0);
                check("⭐ 期望：没有「没有可站格」的退休（实际 no_approach="
                        + (collector == null ? -1 : collector.noApproachRetired()) + "）",
                        collector != null && collector.noApproachRetired() == 0);
                // ⭐ **本案例唯一能抓"目标不可站"的判据**：只看世界状态会漏 —— 修复前收集器挑的是
                // **站不住的物品自身格**（计划里带一条破格边），而物品常常在破格**之前**就被原版
                // 拾取范围捞走（bot 走到够得着的邻格时已进入范围）⇒ 世界零改动、看起来全绿 ✗
                // （2026-09-21 注入复现实测：正是这样静默绿了一次）。
                BlockPos goalFoot = collector == null ? null : collector.lastGoalFoot();
                check("⭐ 期望：收集器挑的目标格就是**够得着的可站邻格** " + current.itemCell().east().toShortString()
                                + "（实际 " + (goalFoot == null ? "-" : goalFoot.toShortString()) + "）——"
                                + "`D-375` 修复前它挑的是**物品自身格**（站不住）",
                        goalFoot != null && goalFoot.equals(current.itemCell().east()));
            }
            case UNREACHABLE -> {
                check("⭐ 期望：进包 0 件（实际 " + collectedDelta + "）—— 物品所在格与 8 邻格全是 1 格高，"
                        + "**没有**可站且够得着的格 ⇒ 够不着就是够不着", collectedDelta == 0);
                check("⭐ 期望：**世界零改动**（实际 " + worldChanges + " 格）——"
                        + "`D-375` 修复前会挖穿两格天花板钻进去捡（那正是真机那 19 段计划的同族行为）",
                        worldChanges == 0);
                check("⭐ 期望：如实记为 `no_standable_approach` 退休 " + current.noApproach()
                                + " 件（实际 " + (collector == null ? -1 : collector.noApproachRetired()) + "）"
                                + "—— 不是静默丢弃，也不是混进 `unreachable`",
                        collector != null && collector.noApproachRetired() == current.noApproach());
                check("⭐ 期望：物品**留在世界里**（实际还在=" + itemStillOnGround + "）—— "
                        + "收集是 best-effort，够不着就如实留着，不许消失", itemStillOnGround);
                BlockPos goalFoot = collector == null ? null : collector.lastGoalFoot();
                check("⭐ 期望：**根本没规划过**（从未挑目标格，实际 "
                                + (goalFoot == null ? "-" : goalFoot.toShortString()) + "）——"
                                + "没有可站且够得着的格就不许规划；修复前它挑物品自身格 ⇒ 挖穿天花板钻进去",
                        collector != null && goalFoot == null);
            }
            case SEALED_ROOM -> {
                check("⭐ 期望：进包 1 件（实际 " + collectedDelta + "）—— 目标格**可站**、世界修改已授权，"
                        + "破墙进去是**合法**手段（反证：修复不许把合法的破墙也一起禁掉）",
                        collectedDelta == 1);
                check("⭐ 期望：世界**确实**被改过（实际 " + worldChanges + " 格 &gt;= 1）——"
                        + "否则「决策层看见改造地形」这条判据没被真正触发", worldChanges >= 1);
                check("⭐ 期望：决策层收到 `PICKUP_DETOUR` ≥ " + current.minDetour() + " 次（实际 "
                                + (collector == null ? -1 : collector.detourEventEmits()) + "）——"
                                + "真机上那 16 次 `collect-drops:attempt0:PATH_ACCESS` 对决策层完全不可见",
                        collector != null && collector.detourEventEmits() >= current.minDetour());
                check("⭐ 期望：**没有**误报 `PICKUP_SLOW`（实际 "
                                + (collector == null ? -1 : collector.slowEventEmits()) + "）——"
                                + "本案例应当很快结束（反证：别用阈值把正常簇也报成病态）",
                        collector != null && collector.slowEventEmits() == 0);
            }
            case PICKUP_DELAY -> {
                check("⭐ 期望：进包 0 件（实际 " + collectedDelta + "）—— 物品 `PickupDelay` 永久，"
                        + "到位也捡不起来", collectedDelta == 0);
                check("⭐ 期望：决策层收到 `PICKUP_SLOW` ≥ " + current.minSlow() + " 次（实际 "
                                + (collector == null ? -1 : collector.slowEventEmits()) + "）——"
                                + "簇耗时超阈值必须上报（真机那条 10 秒绕远当时它还在说「继续观察」）",
                        collector != null && collector.slowEventEmits() >= current.minSlow());
                check("⭐ 期望：**没有**误报 `PICKUP_DETOUR`（实际 "
                                + (collector == null ? -1 : collector.detourEventEmits()) + "）——"
                                + "本案例没有执行过破坏/放置（反证：两条信号不许互相冒充）",
                        collector != null && collector.detourEventEmits() == 0);
                check("⭐ 期望：世界零改动（实际 " + worldChanges + " 格）", worldChanges == 0);
            }
            default -> throw new IllegalStateException("未知案例 " + current.kind());
        }

        BotLog.info("[CollectSlot] CASE {} collected={} worldChanges={} no_approach={} slow={} detour={}"
                        + " status={} reason={} itemLeft={} runTicks={}",
                current.kind(), collectedDelta, worldChanges,
                collector == null ? -1 : collector.noApproachRetired(),
                collector == null ? -1 : collector.slowEventEmits(),
                collector == null ? -1 : collector.detourEventEmits(),
                collectorStatus, itemReason.isEmpty() ? "-" : itemReason, itemStillOnGround, runTicks);

        // ---- 本案例收尾（**失败路径也走**，§6.9.2）----
        discardItem(level);
        collector = null;
        phase = Phase.SPAWN;
        return Task.Status.RUNNING;
    }

    /** 前提自断言（红了 = **夹具坏**，不是缺陷证据）。 */
    /**
     * ⭐ **世界前提必须在"跑之前"取**（跑完再取就不是前提了）。
     *
     * <p>自检实测（2026-09-21 第二跑）：`SEALED_ROOM` 的"目标格四面封死"读到了 **1 个通道** ——
     * 因为收集器已经把墙**破开**了，跑完之后再去问"封死了吗"必然是否定的 ⇒ 前提假红。
     * 同一条也适用于 `SLOT_REACH` 的"物品格站不住"（修好之前，那格的天花板正是被挖掉的那一格）。
     */
    private void capturePremises(ServerLevel level) {
        premiseItemCellStandable = canStand(level, current.itemCell());
        premiseGoalReachable = false;
        premiseGoalDistance = -1;
        premiseGoalStandable = false;
        premiseRingStandable = 0;
        premiseRingOpenings = 0;
        premiseNearestOutOfReach = false;
        switch (current.kind()) {
            case SLOT_REACH -> {
                BlockPos goal = current.itemCell().east();
                premiseGoalStandable = canStand(level, goal);
                premiseGoalReachable = CollectDropsTask.withinPickupReach(goal, item);
                premiseGoalDistance = Math.abs(goal.getX() + 0.5D - item.getX());
            }
            case UNREACHABLE -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (canStand(level, current.itemCell().offset(dx, 0, dz))) {
                            premiseRingStandable++;
                        }
                    }
                }
                premiseNearestOutOfReach = !CollectDropsTask.withinPickupReach(current.itemCell().west(2), item);
            }
            case SEALED_ROOM -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        if (com.dddgn.alice.pathing.MovementHelper
                                .bodyPassable(level, current.itemCell().offset(dx, 0, dz))) {
                            premiseRingOpenings++;
                        }
                    }
                }
            }
            case PICKUP_DELAY -> {
                // 只有 NBT 可以跑完再读（它不是世界状态）
            }
        }
    }

    private void checkPremises(ServerLevel level) {
        check("前提：落物真的落在预期格（期望 " + current.itemCell().toShortString()
                        + "，实际 " + (item == null ? "-" : item.blockPosition().toShortString()) + "）",
                item != null && item.blockPosition().equals(current.itemCell()));
        check("前提：起点**不在**拾取范围内（否则「必须走过去」不成立）",
                item != null && !CollectDropsTask.withinPickupReach(current.botStart(), item));

        switch (current.kind()) {
            case SLOT_REACH -> {
                BlockPos goal = current.itemCell().east();
                check("前提：物品格**站不住**（头位被挡）—— 见判据", !premiseItemCellStandable);
                check("前提：可站邻格 " + goal.toShortString() + " **真的可站**", premiseGoalStandable);
                check("前提：⭐ 该邻格**真够得着**（复用生产谓词 `withinPickupReach`）", premiseGoalReachable);
                check("前提：⭐ 该邻格落在**旧粗判否掉、真实谓词接受**的那段区间（逐轴距离 "
                                + fmt(premiseGoalDistance) + " ∈ (" + OLD_ROUGH_BOUND + ", 1.425]）——"
                                + "本夹具正是靠这段区间抓缺陷；若它落回 1.2 以内，本夹具就测不到旧缺陷了",
                        premiseGoalDistance > OLD_ROUGH_BOUND && premiseGoalDistance <= 1.425D);
            }
            case UNREACHABLE -> {
                check("前提：物品格与其 8 邻格**全都站不住**（实际可站 " + premiseRingStandable + " 个）—— "
                        + "这是「真够不到」的定义", premiseRingStandable == 0);
                check("前提：最近的站格也**够不着**（复用生产谓词）—— 否则本案例不是「真够不到」",
                        premiseNearestOutOfReach);
            }
            case SEALED_ROOM -> {
                check("前提：目标格**可站**（否则本案例退化成正例 2）", premiseItemCellStandable);
                check("前提：目标格四面**封死**（跑之前 8 邻格无一整体通行，实际 " + premiseRingOpenings
                        + " 个通道）—— 否则可能绕行而不是破墙", premiseRingOpenings == 0);
            }
            case PICKUP_DELAY -> {
                check("前提：目标格**可站**（否则会走成「没有可站格」而不是「捡不起来」）",
                        premiseItemCellStandable);
                // ⚠️ 不许用 `hasPickupDelay()` 判：它把 `32767`（永不放行）当成"没有延迟"。
                // 直接读 NBT 里的那个数（就是 `/summon` 写进去的字段），才是本前提的真判据。
                check("前提：物品**带永久 `PickupDelay`=32767**（否则它会正常入包；实际 "
                                + pickupDelayNbt() + "）", pickupDelayNbt() == 32767);
            }
        }
    }

    // ==================== 收尾 ====================

    private void cleanup() {
        ServerLevel level = bot.serverLevel();
        discardItem(level);
        clearScene(level);
        forceload(level, false);
        bot.controller().stopMovement();
        if (entryFoot != null) {
            teleport(level, entryFoot);
        }
        // 收尾：把捡到的产物也还回去（`resetInventory` 会把背包里的东西掉在脚下 ⇒ 再回收一遍）
        FixtureToolKit.resetInventory(bot);
        discardNearbyProduct(level);
        int left = countSceneItems(level);
        check("收尾：夹具造出来的落物全部收回（场景内剩余=" + left + "）", left == 0);
        check("收尾：场景地形清回空气（残留非空气=" + remainingNonAir(level) + "）", remainingNonAir(level) == 0);

        boolean pass = failures.isEmpty();
        BotLog.info("[CollectSlot] SUMMARY checks={} failures={} cases={} 地板方块={} 原本非空气={}"
                        + " → {}｜失败项：{}",
                checks, failures.size(), cases.size(), builtBlocks, preExistingNonAir,
                pass ? "PASS" : "FAIL", failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 掉落物「够得着的可站格」取证 "
                    + (pass ? "PASS" : "FAIL") + "（案例 " + cases.size() + " 个，详见日志 [CollectSlot]）"));
        }
        phase = Phase.DONE;
    }

    // ==================== 案例驱动 ====================

    /** 推进到下一个案例；没有下一个 ⇒ `false`（收尾）。 */
    private boolean startNextCase() {
        caseIndex++;
        if (caseIndex >= cases.size()) {
            cleanup();
            return false;
        }
        current = cases.get(caseIndex);
        return true;
    }

    private String caseName() {
        return current == null ? "-" : current.kind().name();
    }

    // ==================== 世界读写与观测 ====================

    /** 以 ORIGIN 为参照的格（脚位层）。 */
    private BlockPos cell(int dx, int dz) {
        return ORIGIN.offset(dx, FOOT_DY, dz);
    }

    private void set(ServerLevel level, int dx, int dy, int dz, net.minecraft.world.level.block.Block block) {
        BlockPos pos = ORIGIN.offset(dx, dy, dz);
        level.setBlock(pos, block.defaultBlockState(), 3);
        probe.put(pos, level.getBlockState(pos));
    }

    private Map<BlockPos, BlockState> snapshot(ServerLevel level) {
        Map<BlockPos, BlockState> snapshot = new LinkedHashMap<>();
        for (BlockPos pos : probe.keySet()) {
            snapshot.put(pos, level.getBlockState(pos));
        }
        return snapshot;
    }

    /** 相对快照**变了几格**（破坏与放置都算：都是为了捡一件东西在改造地形）。 */
    private int countChanges(ServerLevel level, Map<BlockPos, BlockState> before) {
        if (before.isEmpty()) {
            return -1;      // 快照没建（硬前提失败）⇒ 不假装是 0
        }
        int changed = 0;
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                changed++;
            }
        }
        return changed;
    }

    private boolean canStand(ServerLevel level, BlockPos pos) {
        return com.dddgn.alice.pathing.MovementHelper.canStandCentered(level, pos);
    }

    private void forceload(ServerLevel level, boolean on) {
        int minZ = ORIGIN.getZ() + FLOOR_MIN_Z;
        int maxZ = ORIGIN.getZ() + FLOOR_MAX_Z;
        for (int cx = (ORIGIN.getX() - FLOOR_HALF_X) >> 4; cx <= (ORIGIN.getX() + FLOOR_HALF_X) >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private AABB box() {
        return new AABB(ORIGIN.getX() - FLOOR_HALF_X, ORIGIN.getY(),
                ORIGIN.getZ() + FLOOR_MIN_Z,
                ORIGIN.getX() + FLOOR_HALF_X + 1, ORIGIN.getY() + HEADROOM + 1,
                ORIGIN.getZ() + FLOOR_MAX_Z + 1);
    }

    private int countSceneItems(ServerLevel level) {
        int total = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box())) {
            total += entity.getItem().getCount();
        }
        return total;
    }

    private int countProductInInventory() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.AMETHYST_SHARD)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * ⭐ **候选来源必须每 tick 现查**（`D-344` 的既有钩子）。
     *
     * <p>⚠️ 自检实测（2026-09-21 首跑）：夹具若递一个"建好就不再变"的快照，物品被原版捡走后
     * 收集器**仍然以为它在地上** ⇒ 空等到 `pickup_timeout` + 记一条 `MISMATCH`
     *（`delta=1 expected=0`）⇒ 两个案例的簇耗时被凭空拉到 90~145 tick（还会误触发 `PICKUP_SLOW`）。
     * 生产侧 `scope.liveDrops()` 是**活的**，夹具必须同口径。
     */
    private List<ItemEntity> liveItem() {
        if (item == null || item.isRemoved() || !entityAlive(bot.serverLevel(), item.getUUID())) {
            return List.of();
        }
        return List.of(item);
    }

    /** 场景内的产物落物（"可见" = 真的进了世界的实体表）。 */
    private ItemEntity visibleItem(ServerLevel level) {
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box())) {
            if (entity.getItem().is(Items.AMETHYST_SHARD)) {
                return entity;
            }
        }
        return null;
    }

    /** 落物 NBT 里的 `PickupDelay`（`-1` = 读不到）。 */
    private int pickupDelayNbt() {
        if (item == null) {
            return -1;
        }
        return item.saveWithoutId(new net.minecraft.nbt.CompoundTag()).getShort("PickupDelay");
    }

    private boolean entityAlive(ServerLevel level, UUID id) {
        var entity = level.getEntity(id);
        return entity != null && !entity.isRemoved();
    }

    /**
     * 清掉场景内的**所有**落物（不只产物）。
     *
     * <p>为什么要全清：案例 C 破墙会掉**圆石**，而圆石会被作用域登记成 `OURS_DIRECT`
     * ⇒ 留着它既会污染下一案例的作用域继承，也会让收尾判据（场景内剩余落物=0）红。
     */
    private void discardItem(ServerLevel level) {
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box())) {
            entity.discard();
        }
        item = null;
        itemId = null;
    }

    /** 清掉 bot 脚下几格内的本夹具产物（收尾用：`resetInventory` 会把它们掉在脚下）。 */
    private void discardNearbyProduct(ServerLevel level) {
        AABB around = bot.getBoundingBox().inflate(3.0D);
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, around)) {
            if (entity.getItem().is(Items.AMETHYST_SHARD)) {
                entity.discard();
            }
        }
    }

    /** 场景清回空气（该处原状就是空中 ⇒ 这就是"还原原状"）。 */
    private void clearScene(ServerLevel level) {
        for (BlockPos pos : probe.keySet()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /** 收尾校验：探针位置上还有没有非空气方块。 */
    private int remainingNonAir(ServerLevel level) {
        int left = 0;
        for (BlockPos pos : probe.keySet()) {
            if (!level.getBlockState(pos).isAir()) {
                left++;
            }
        }
        return left;
    }

    private boolean summonProduct(ServerLevel level, BlockPos cell, double offsetX, double offsetZ,
                                  String pickupDelay) {
        int commands = level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withSuppressedOutput(),
                "summon minecraft:item " + (cell.getX() + offsetX) + " " + (cell.getY() + 0.2D) + " "
                        + (cell.getZ() + offsetZ) + " {Item:{id:\"" + PRODUCT
                        + "\",Count:1b},PickupDelay:" + pickupDelay + "}");
        // 陷阱 #5：`/summon` 在抑制输出的命令源下静默失败 ⇒ **必须看返回值**
        return commands > 0;
    }

    private void teleport(ServerLevel level, BlockPos foot) {
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}

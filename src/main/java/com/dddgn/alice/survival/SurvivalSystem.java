package com.dddgn.alice.survival;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 所有 bot 共用的维生监控入口。第一版只观察并给出硬中断信号，不主动逃生或改动世界。
 * 这样挖矿、拾取、道路施工可以共享同一套生命安全底线。
 */
public final class SurvivalSystem {
    private static final Map<UUID, Monitor> MONITORS = new HashMap<>();

    private SurvivalSystem() {
    }

    public static HazardState tick(ServerPlayer bot) {
        Monitor monitor = MONITORS.computeIfAbsent(bot.getUUID(), ignored -> new Monitor());
        long gameTime = bot.level().getGameTime();
        if (monitor.lastTick == gameTime && monitor.lastState != null) {
            return monitor.lastState;
        }
        HazardState state = monitor.observe(bot);
        monitor.lastTick = gameTime;
        monitor.lastState = state;
        if (state.type() != HazardType.NONE && monitor.shouldLog(state)) {
            BotLog.warn("维生监测: bot={} hazard={} duration={} air={} health={} pos={}",
                    bot.getName().getString(), state.type(), state.durationTicks(), state.airSupply(),
                    state.health(), state.position().toShortString());
        }
        return state;
    }

    public static HazardState current(ServerPlayer bot) {
        Monitor monitor = MONITORS.get(bot.getUUID());
        return monitor == null || monitor.lastState == null
                ? new HazardState(HazardType.NONE, 0, bot.getAirSupply(),
                bot.getHealth(), bot.getHealth(), bot.blockPosition()) : monitor.lastState;
    }

    /**
     * **测试/夹具用**（`D-325`）：把监视器的「上一拍血量」拨到**当前血量** —— 也就是掉血检测
     * （{@code HazardState.healthLost()}）下次比较用的那一边。
     *
     * <p><b>为什么非要有这个接缝</b>（2026-09-18 CORE 假红的根因，存档日志实证）：`tick()` 每 tick
     * **至多观察一次**，而夹具是**在同一 tick 内**改血量的（`normalizeVitals()` 抬到满血 → `hurt()` 打掉）
     * ⇒ 那一拍的观察看到的还是**改血前**的值（实测 `health=19.0`，着火相位余波），于是监视器的
     * `previousHealth` 停留在 **19**；下一拍回血 +1 把血量也带到 **19** ⇒ `19 < 19` 为假 ⇒
     * **掉血边缘被"夹具自己抬血那一步"吃掉**，一条事件都不发（旧日志：注入后完全没有
     * `[Threshold] 掉血 DANGER` 行，11 秒后才出现 FREEZING 的另一笔）⇒ `D-312` 的两条判据同时红。
     *
     * <p>⚠️ 所以 `D-312` 的"净掉 ≥2 就结构上不再偶发"**不成立**：决定成败的不是缺口大小，而是
     * **比较基准**（旧值 19 ≠ 抬血后的 20）。夹具在 `normalizeVitals()` 之后调用本方法即可让
     * 边缘基准 = 满血 ⇒ `hurt()` 之后的任何拍（18 或回血后的 19）都严格小于基准 ⇒ **确定性**。
     */
    public static void resetHealthBaseline(ServerPlayer bot) {
        monitor(bot).previousHealth = bot.getHealth();
    }

    /** **测试/夹具用**：当前边缘基准（下次比较用的 `previousHealth`），供夹具把前提写成判据。 */
    public static float healthBaseline(ServerPlayer bot) {
        return monitor(bot).previousHealth;
    }

    // ==================== 维生决策（S-5，2026-09-15）====================

    /**
     * **软危险的宽限期**（tick）：溺水/着火必须**先持续这么久**才可能行使否决权。
     *
     * <p>为什么需要它（两个独立理由，都不是凭感觉定的数）：
     * <ol>
     *   <li>**不抖动**：`ON_FIRE`/`LOW_AIR` 可以是单 tick 的现象（一脚踩进火里、头刚没入水面）。
     *       否决一次 = **中断一整个长作业**，代价远大于等 0.5 秒看它是否自解；</li>
     *   <li>**查询成本**：软危险的判据要问"有没有出口"（{@link #nearestSafeRefuge} 是 17³ 体积查询），
     *       不该每个 tick 都跑。</li>
     * </ol>
     *
     * <p>为什么是 **10**：溺水伤害发生在 `airSupply` 归零后**再 20 tick**（原版把 air 压到 −20 才结算），
     * 所以 10 tick 的宽限**仍然赶在第一次掉血之前**；着火伤害每 20 tick 一次，晚 0.5 秒同样安全。
     */
    public static final int SOFT_HAZARD_GRACE_TICKS = 10;

    /**
     * **维生决策**（纯查询，无副作用）：调用方（`BotSession`）据此决定"否决 / 不否决 / 如实登记不否决"。
     *
     * <p>为什么要有第三个值而不是布尔：**软危险"没有出口"时正确答案是"不否决"**（见
     * {@link #decide}），而这件事**必须留下痕迹** —— 否则日志上只看到"bot 在淹死/在烧，什么都没发生"，
     * 无法区分"判据没生效"和"判据生效了、判断是继续跑"。{@code HOLD_NO_EXIT} 就是"生效了但选择不动手"。
     */
    public enum Verdict {
        /** 不是危险，或还没到该动手的程度（宽限期内）——什么都不做。 */
        IGNORE,
        /** 行使否决权：中断当前任务，并由 `SurvivalExitTask` 走去逃生落点。 */
        INTERRUPT,
        /** **软危险 + 半径内无出口 ⇒ 不否决**（登记一次后让任务继续）。 */
        HOLD_NO_EXIT,
        /**
         * **溺水 + 半径内无出口 ⇒ 放弃当前任务**（D-236，2026-09-15）。
         *
         * <p>为什么溺水单独一档：`ON_FIRE`（会自己烧完）与 `FREEZING`（离开细雪就恢复）在没有出口时
         * **仍可能自愈** ⇒ "不否决、让任务继续"是合理的取舍（D-226/D-229 已验收）。而
         * **`LOW_AIR` 在水里没有出口时不动手就一定死**（原版：不按跳跃键只会缓慢下沉，空气归零后
         * 20 tick 开始每 20 tick 掉血）⇒ 继续跑 = 死在作业中途（任务半途而废、现场留着临时方块/账面）。
         * **放弃 = 干净收尾 + 大声登记**，不是"救活"（Alice 今天没有游泳/上浮能力，见台账 §5.10 的登记）。
         */
        ABANDON_NO_EXIT,
        /**
         * **溺水 + 无落点，但"浮得上去" ⇒ 先自救：按住跳跃把头露出水面**（D-237，2026-09-15，用户批准的"乙"）。
         *
         * <p>为什么值得单独一档：`ABANDON_NO_EXIT` 是"连自救都免谈"的终局；而开阔水面里 bot 只是**没按跳跃键**
         * 才下沉（原版：水里按住跳跃即上浮）⇒ 一次**纯输入**的自救就能把"必死"变成"能呼吸、能被救"，
         * 不需要任何新 Movement（内核今天规划不出含水路线，见 D-236）。
         * 浮不上去（头顶被实体方块封住，如封闭水牢）或**已经浮过一次仍失败** ⇒ 回到 `ABANDON_NO_EXIT`。
         */
        FLOAT_UP
    }

    /** **硬危险**：继续做任何事都只会更糟 ⇒ 无条件否决（不要求有出口）。 */
    public static boolean hardHazard(HazardType type) {
        return type == HazardType.LAVA_CONTACT || type == HazardType.SUFFOCATING;
    }

    /** **软危险**：溺水 / 着火 / 冻结 —— 需要"出口 + 宽限"才值得否决（见 {@link #decide}）。 */
    public static boolean softHazard(HazardType type) {
        return type == HazardType.LOW_AIR || type == HazardType.ON_FIRE || type == HazardType.FREEZING;
    }

    /**
     * **冻结警示阈值**（`ticksFrozen`，D-229，2026-09-15）。
     *
     * <p>原版事实（字节码核对）：`isFullyFrozen()` = `ticksFrozen >= getTicksRequiredToFreeze()`（**140**），
     * 完全冻住后才开始掉血（`LivingEntity.baseTick()`：`tickCount % 40 == 0 && isFullyFrozen() && canFreeze()`
     * ⇒ **每 2 秒 1 点**）；而 `ticksFrozen` 的累积在 **`LivingEntity.aiStep()`** 里
     * （假人由 Alice 手动调 `aiStep()` ⇒ **会累积**），皮靴等 `FREEZE_IMMUNE_WEARABLES` 免疫也在那条路上
     * ⇒ 用 `ticksFrozen` 当信号天然尊重免疫，不用自己判靴子。
     *
     * <p>取 **60**（≈ 全冻前 4 秒）的原因：给"走出细雪"留够余量（细雪里移动很慢），
     * 又能让"只是路过一小片细雪"（通常 &lt; 1 秒）不触发否决。**可调**，调它要连带看
     * 电池步 `survival_exit` 的细雪相位（它按这个常量等累积）。
     */
    public static final int FREEZE_WARN_TICKS = 60;

    /**
     * **唯一的维生决策入口**（S-5，2026-09-15）。
     *
     * <p>判据（按顺序）：
     * <ol>
     *   <li>硬危险（岩浆/窒息）⇒ {@link Verdict#INTERRUPT} —— 与 S-1 的行为完全一致，**本轮没改**；</li>
     *   <li>软危险（溺水/着火）且已过 {@link #SOFT_HAZARD_GRACE_TICKS}：**有出口才否决**；
     *       没出口 ⇒ {@link Verdict#HOLD_NO_EXIT}（**不否决**）。</li>
     * </ol>
     *
     * <p>为什么软危险"没出口就不否决"（本轮新增的判据，S-5）：否决的动作是"中断任务 + 走去落点"，
     * 而没落点时 `startSurvivalExit` 只能**如实登记"无出口"**并把 bot 留在原地 —— 对溺水/着火来说，
     * "停在原地挨着"**严格劣于**"让任务继续"（任务至少可能在往水面/安全处走）。硬危险不适用这条：
     * 岩浆里停不停都在烧，中断不会更糟（而且这是已实测过的既有行为，不在这轮改）。
     *
     * <p>配置读取：判据只读当前状态；结果由调用方决定**怎么登记**（软危险无出口的登记要求"一次"，
     * 见 `BotSession.tick(HazardState)` 的 `durationTicks == SOFT_HAZARD_GRACE_TICKS`）。
     */
    public static Verdict decide(ServerPlayer bot, HazardState state) {
        return decide(bot, state, false);
    }

    /** 带"逃生准备金是否可用"的判决（D-241：只有拿到写信封的任务才允许它为 true）。 */
    public static Verdict decide(ServerPlayer bot, HazardState state, boolean allowWrites) {
        if (hardHazard(state.type())) {
            return Verdict.INTERRUPT;
        }
        if (!softHazard(state.type())) {
            return Verdict.IGNORE;
        }
        if (state.durationTicks() < SOFT_HAZARD_GRACE_TICKS) {
            return Verdict.IGNORE;
        }
        // D-238：出口必须是**可规划**的（几何存在 ⇒ 规划不可达时按"没有出口"处理）。
        if (plannableRefuge(bot, state.type(), allowWrites) != null) {
            return Verdict.INTERRUPT;
        }
        // 无出口：着火/冻结仍可能自愈 ⇒ 不否决（让任务继续）。
        if (state.type() != HazardType.LOW_AIR) {
            return Verdict.HOLD_NO_EXIT;
        }
        // 溺水：先看能不能自救（浮上去呼吸）；浮不了 / 已经浮败过一次 ⇒ 放弃任务（D-236/D-237）。
        long now = bot.level().getGameTime();
        return canFloatUp(bot) && now >= monitor(bot).floatBlockedUntil
                ? Verdict.FLOAT_UP
                : Verdict.ABANDON_NO_EXIT;
    }

    /** 否决是否成立（保留旧名，语义 = {@link #decide} 是否给出 `INTERRUPT`）。 */
    public static boolean shouldInterrupt(ServerPlayer bot, HazardState state) {
        return decide(bot, state) == Verdict.INTERRUPT;
    }

    /** 半径 {@link #REFUGE_RADIUS} 内**有没有**出口（**排除 bot 当前格**：站在原地不算出口）。 */
    public static boolean hasRefuge(ServerPlayer bot) {
        return nearestSafeRefuge(bot, REFUGE_RADIUS, footCell(bot)) != null;
    }

    /** 逃生准备金的预算上限（D-241，用户 Q3 定案）：8 破坏 / 8 放置 / 0 容器写入。 */
    private static final int ESCAPE_MAX_BREAKS = 8;
    private static final int ESCAPE_MAX_PLACES = 8;

    /** 出口预检的搜索预算：小空间能在预算内穷尽（判 `UNREACHABLE`），又不至于在正常场景里拖慢 tick。 */
    private static final int PRECHECK_MAX_NODES = 600;
    private static final long PRECHECK_MAX_MILLIS = 20L;

    /**
     * **可规划的出口**（D-238，2026-09-15）：几何上"有落点"还不够 —— **出逃生之前先跑一次真规划预检**。
     *
     * <p>为什么必须补这一层：2026-09-15 实测到一个真实案例 —— 几何落点成立（`refuge=240,105,306`，干、可站、
     * 距 4 格），但**规划不可达**（`WalkToTask … PLAN_UNREACHABLE … walk_no_path`）⇒ 结果是"为一个走不到的落点
     * 把当前任务杀掉、再起一个 1 tick 就失败的逃生"。判据不该只看"有没有那个格子"，要看"**去不去得了**"。
     *
     * <p>口径（沿用既有 `SEARCH_LIMIT ≠ UNREACHABLE`）：只有 `UNREACHABLE`（搜索空间穷尽）才算"没有出口"；
     * `SEARCH_LIMIT`（预算耗尽、可达性未知）按"未知 ⇒ 允许尝试"处理，避免因为预算太紧而误判。
     *
     * <p>**成本**：预检要跑一次规划 ⇒ 结果按"危险类型 + 脚位"缓存在 monitor 里（一场危险最多一次）。
     */
    public static BlockPos plannableRefuge(ServerPlayer bot, HazardType hazardType) {
        return plannableRefuge(bot, hazardType, false);
    }

    /**
     * **出口预检（带逃生准备金那一档）**（D-241）：`allowWrites=true` 时，纯通行规划不可达的落点会再用
     * `PathRequest.survivalEscape`（放置 + 破坏 + PILLAR）试一次；成功则**顺手把逃生准备金的预算上限**
     * （8 破坏 / 8 放置，用户 Q3 定案）装到本 bot 的作用域上 —— 规划期的写边闸门看的就是它。
     * 预算上限在**下一个任务开始时**由 `BotSession.beginTask` 清掉（不会泄漏到后续任务）。
     */
    public static BlockPos plannableRefuge(ServerPlayer bot, HazardType hazardType, boolean allowWrites) {
        Monitor monitor = monitor(bot);
        BlockPos foot = footCell(bot);
        if (monitor.refugeCacheValid && monitor.refugeCheckedFor == hazardType
                && monitor.refugeCheckedAt != null && foot.equals(monitor.refugeCheckedAt)
                && monitor.refugeCacheWrites == allowWrites) {
            return monitor.cachedRefuge;
        }
        BlockPos refuge = nearestSafeRefuge(bot, REFUGE_RADIUS, foot);
        BlockPos result = null;
        if (refuge != null) {
            if (isPlannable(bot, refuge)) {
                result = refuge;                       // 第一档：纯通行（今天的行为，不改世界）
            } else if (allowWrites && escapeWithReserve(bot, foot, refuge)) {
                monitor.escapeNeedsWrites = true;      // 第二档：只对**信封里有写权**的任务开放
                result = refuge;
            }
        }
        return finishRefugeCheck(monitor, hazardType, foot, result, allowWrites);
    }

    /**
     * **动用逃生准备金再试一次**（D-241，用户 Q2/Q3 定案）：装上限（8 破坏 / 8 放置）后，用
     * `PathRequest.survivalEscape`（放置 + 破坏 + PILLAR，不含 `DOWNWARD`/`FALL`）规划到同一个落点。
     *
     * <p>只有 `UNREACHABLE` 才算失败（与 D-238 同一口径）。装上限的副作用**不回收**：
     * 下一个任务开始时由 `BotSession.beginTask` 清掉（逃生本身会把当前任务结束掉）。
     */
    private static boolean escapeWithReserve(ServerPlayer bot, BlockPos foot, BlockPos refuge) {
        com.dddgn.alice.action.WriteBudget.capForEscape(
                com.dddgn.alice.action.WriteBudget.scopeOf(bot), ESCAPE_MAX_BREAKS, ESCAPE_MAX_PLACES);
        var request = com.dddgn.alice.pathing.core.search.PathRequest
                .survivalEscape(bot.getUUID().toString(), foot, refuge, "survival-escape");
        var plan = new com.dddgn.alice.pathing.core.search.CorePathPlanner()
                .plan(bot, bot.serverLevel(), request);
        BotLog.warn("[Survival] 纯通行去不了 {} ⇒ 改用**逃生准备金**（放置+破坏+PILLAR，上限 {} 破坏/{} 放置）"
                        + "重试：status={}", refuge.toShortString(), ESCAPE_MAX_BREAKS, ESCAPE_MAX_PLACES,
                plan.status());
        return plan.status() != com.dddgn.alice.pathing.core.search.PlanningStatus.UNREACHABLE;
    }

    /** 纯通行预检：这个落点**规划得到**吗（只有 `UNREACHABLE` 才算不可达）。 */
    private static boolean isPlannable(ServerPlayer bot, BlockPos refuge) {
        var request = com.dddgn.alice.pathing.core.search.PathRequest
                .of(bot.getUUID().toString(), footCell(bot), refuge, "survival-precheck")
                .withBudget(com.dddgn.alice.pathing.core.search.SearchBudget
                        .of(PRECHECK_MAX_NODES, PRECHECK_MAX_MILLIS));
        var plan = new com.dddgn.alice.pathing.core.search.CorePathPlanner()
                .plan(bot, bot.serverLevel(), request);
        if (plan.status() == com.dddgn.alice.pathing.core.search.PlanningStatus.UNREACHABLE) {
            BotLog.warn("[Survival] 落点 {} 几何上成立，但**规划不可达**（status={}）⇒ 按"
                            + "「没有出口」处理（别为一个走不到的落点杀任务）",
                    refuge.toShortString(), plan.status());
            return false;
        }
        return true;
    }

    /** 上一次预检的落点是否**只有动用逃生准备金才到得了**（D-241；逃生任务据此选受限请求）。 */
    public static boolean escapeNeedsWrites(ServerPlayer bot) {
        return monitor(bot).escapeNeedsWrites;
    }

    /** 预检结果入缓存（键 = 危险类型 + 脚位 + 是否允许写）。 */
    private static BlockPos finishRefugeCheck(Monitor monitor, HazardType hazardType, BlockPos foot,
                                              BlockPos result, boolean allowWrites) {
        monitor.refugeCacheValid = true;
        monitor.refugeCheckedFor = hazardType;
        monitor.refugeCheckedAt = foot.immutable();
        monitor.refugeCacheWrites = allowWrites;
        monitor.cachedRefuge = result;
        return result;
    }

    /**
     * **bot 真正站着的那一格**（S-5 修正，2026-09-15）—— 全类只此一份定义。
     *
     * <p>为什么不能用 `bot.blockPosition()`：那是"脚**所在**格"，而本类的落点判据
     * （{@code isRefuge} → `canWalkOn`）用的是**规划层口径**"支撑格的上一格"（`MovementHelper.footCell`，
     * D-105）。贴地时实体的 y 会落在方块顶面**略下方**，于是 `blockPosition()` 退回**支撑格**
     * —— 那一格是实体方块、永远不会是落点。用它当"排除自己"等于**没排除**：
     * bot 自己站的那格会被算成"有出口"，否决完 `SurvivalExitTask` 走到原地、0 步 COMPLETED，
     * 而 bot **一格没动**（2026-09-15 由新电池步 `survival_exit` 实测抓到：同 tick 里
     * `nearestSafeRefuge(bot, 8, blockPosition())` 非空、`nearestSafeRefuge(bot, 8, footCell())` 为空）。
     */
    public static BlockPos footCell(ServerPlayer bot) {
        return com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
    }

    /** 否决理由码（落进 `task_execution_terminal` 的 `code=` 词汇表，供台账引用）。 */
    public static String interruptionReason(HazardState state) {
        return switch (state.type()) {
            case LAVA_CONTACT -> "survival_lava_contact";
            case SUFFOCATING -> "survival_suffocating";
            case LOW_AIR -> "survival_low_air";
            case ON_FIRE -> "survival_on_fire";
            case FREEZING -> "survival_freezing";
            default -> "";
        };
    }

    /** 上浮扫描的上限（水柱再深也不该扫全高）。 */
    private static final int FLOAT_SCAN_MAX = 48;

    /** "浮过一次仍失败"的封禁时长（D-237）：这段时间内同一场溺水不再反复试，直接放弃任务。 */
    private static final int FLOAT_RETRY_BLOCK_TICKS = 1200;

    /**
     * **浮得上去吗**（D-237）：从脚位往上找，先撞到"能穿过的非流体格"（空气等）⇒ 能浮上去；
     * 先撞到实体方块 ⇒ 浮到顶也还在水里 ⇒ 浮不上去（封闭水牢就是这一种）。
     * 顺带排除**上方是岩浆**的情形：那不是上浮，是把自己送进岩浆。
     */
    public static boolean canFloatUp(ServerPlayer bot) {
        ServerLevel level = bot.serverLevel();
        BlockPos foot = footCell(bot);
        if (!bot.isInWater() && !com.dddgn.alice.pathing.MovementHelper.isWater(level, foot)) {
            return false;
        }
        for (int dy = 0; dy <= FLOAT_SCAN_MAX; dy++) {
            BlockPos pos = foot.above(dy);
            if (!level.hasChunkAt(pos)) {
                return false;
            }
            if (level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA)) {
                return false;                       // 头顶是岩浆：别浮
            }
            if (!level.getFluidState(pos).isEmpty()) {
                continue;                           // 还在水里，继续往上找水面
            }
            return com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, pos);
        }
        return false;
    }

    /** **上浮自救失败**的登记（D-237）：由 {@code SurvivalFloatTask} 在失败时调用。 */
    public static void markFloatFailed(ServerPlayer bot) {
        Monitor monitor = monitor(bot);
        monitor.floatBlockedUntil = bot.level().getGameTime() + FLOAT_RETRY_BLOCK_TICKS;
        BotLog.warn("[Survival] 上浮自救失败 ⇒ 接下来 {} tick 内同一场溺水不再尝试上浮（改为放弃任务）",
                FLOAT_RETRY_BLOCK_TICKS);
    }

    /** 清掉"上浮失败"的封禁（夹具收尾用：别把封禁留给后续步骤）。 */
    public static void clearFloatFailures(ServerPlayer bot) {
        monitor(bot).floatBlockedUntil = 0L;
    }

    /**
     * **放弃任务**时用的判定码（D-236）：与 {@link #interruptionReason} 分开 —— 后者是"起了逃生任务"的
     * 理由（有出口），这里是"连逃都没地方逃、只好收手"的理由，读日志时不该混为一谈。
     */
    public static String abandonReason(HazardState state) {
        return state.type() == HazardType.LOW_AIR ? "survival_drowning_no_exit" : "";
    }

    public static void forget(ServerPlayer bot) {
        MONITORS.remove(bot.getUUID());
    }

    // ==================== S-1（P1-C）：否决必须带出口 ====================

    /** 逃生搜索半径（格）：够"离开脚下这一格危险"，又不至于把整片区域扫一遍。 */
    public static final int REFUGE_RADIUS = 8;

    /**
     * **只回答"哪个落点算安全"，不规划路径**（S-1 / P1-C，2026-09-12）。
     *
     * <p>为什么要有它：维生系统行使否决权之后，bot 原先**停在原地继续被烧** —— 这是全项目唯一的
     * "拒绝没有出口"反例。用户定的规矩是"**谁否决，都得说清那该去哪**"，所以这里只做**纯查询**，
     * 由 `BotSession` 发起 {@code SurvivalExitTask}（= 已验收的 `WalkToTask`）走过去。
     * 三方职责因此保持干净：**维生给落点、寻路给路径、维生仍然不找路**（本类 javadoc 的承诺不破）。
     *
     * <p>判据（保守，宁可少给也不给错）：脚位与头位**都可穿过**（不窒息）、**脚下有真支撑**
     * （`MovementHelper.canWalkOn`）、脚位/头位**都不是流体**（岩浆会烧、水会淹）、
     * **所在区块已加载**（S-2：绝不为逃生去同步加载区块）。
     *
     * @return 最近的安全脚位；半径内没有 ⇒ {@code null}（**如实返回 null**，由调用方登记"无出口"）
     */
    public static BlockPos nearestSafeRefuge(ServerPlayer bot, int radius) {
        return nearestSafeRefuge(bot, radius, null);
    }

    /** 同 {@link #nearestSafeRefuge(ServerPlayer, int)}，但可**排除一格**（通常是 bot 当前所在格）。 */
    public static BlockPos nearestSafeRefuge(ServerPlayer bot, int radius, BlockPos exclude) {
        if (!(bot.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        // 搜索原点/距离基准 = **脚位格**（见 {@link #footCell}）：与 isRefuge 的规划层口径一致。
        BlockPos origin = footCell(bot);
        int r = Math.max(1, radius);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            BlockPos candidate = pos.immutable();   // betweenClosed 复用同一个可变实例（见 BlockPos 可变性 skill）
            if (exclude != null && candidate.equals(exclude)) {
                continue;
            }
            if (!isRefuge(level, candidate)) {
                continue;
            }
            double distance = candidate.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isRefuge(net.minecraft.server.level.ServerLevel level, BlockPos foot) {
        if (!level.hasChunkAt(foot)) {
            return false;   // S-2：不朝未加载区块逃避生（读那一格会同步加载区块）
        }
        if (!level.getFluidState(foot).isEmpty() || !level.getFluidState(foot.above()).isEmpty()) {
            return false;   // 脚/头位是流体（岩浆会烧、水会淹）
        }
        if (!com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, foot)
                || !com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, foot.above())) {
            return false;   // 身体/头部被挡 ⇒ 会窒息
        }
        return com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, foot);
    }

    private static Monitor monitor(ServerPlayer bot) {
        return MONITORS.computeIfAbsent(bot.getUUID(), ignored -> new Monitor());
    }

    private static final class Monitor {
        private HazardType previous = HazardType.NONE;
        private int duration;
        private float previousHealth;
        private int logCooldown;
        private HazardState lastState;
        private long lastTick = Long.MIN_VALUE;
        /** D-237：在这个 gameTime 之前，同一场溺水不再尝试上浮（浮过了但没成功）。 */
        private long floatBlockedUntil;
        /** D-238：出口预检的缓存（按"危险类型 + 脚位"键）——预检跑一次真规划，不能每 tick 都跑。 */
        private boolean refugeCacheValid;
        private HazardType refugeCheckedFor;
        private BlockPos refugeCheckedAt;
        private BlockPos cachedRefuge;
        /** D-241：缓存键要带上"是否允许写"（否则纯通行的结论会顶掉带准备金的结论）。 */
        private boolean refugeCacheWrites;
        /** D-241：本次预检的落点**只有动用逃生准备金才到得了**（逃生任务据此选受限请求）。 */
        private boolean escapeNeedsWrites;

        private HazardState observe(ServerPlayer bot) {
            HazardType current = classify(bot);
            if (current == previous) {
                duration++;
            } else {
                previous = current;
                duration = current == HazardType.NONE ? 0 : 1;
            }
            float health = bot.getHealth();
            HazardState state = new HazardState(current, duration, bot.getAirSupply(), health,
                    previousHealth, bot.blockPosition());
            previousHealth = health;
            if (logCooldown > 0) logCooldown--;
            return state;
        }

        private boolean shouldLog(HazardState state) {
            if (logCooldown > 0) return false;
            logCooldown = state.type() == HazardType.NONE ? 0 : 20;
            return true;
        }

        private static HazardType classify(ServerPlayer bot) {
            if (bot.isInLava() || containsFluid(bot, Blocks.LAVA)) {
                return HazardType.LAVA_CONTACT;
            }
            if (bot.isInWall()) {
                return HazardType.SUFFOCATING;
            }
            if (bot.getAirSupply() <= 0) {
                return HazardType.LOW_AIR;
            }
            if (bot.isOnFire()) {
                return HazardType.ON_FIRE;
            }
            // 冻结排在着火之后、涉水之前：全冻（140 tick）后每 40 tick 掉 1 点，是**真的会致死**的
            // 慢危险；`ticksFrozen` 的累积在 `LivingEntity.aiStep()`（假人手动调得到 ⇒ 会累积）。
            if (bot.getTicksFrozen() >= FREEZE_WARN_TICKS) {
                return HazardType.FREEZING;
            }
            if (bot.isInWater() || containsWater(bot)) {
                return HazardType.WATER_CONTACT;
            }
            return HazardType.NONE;
        }

        private static boolean containsWater(ServerPlayer bot) {
            return com.dddgn.alice.pathing.MovementHelper.isWater(bot.level(), bot.blockPosition())
                    || com.dddgn.alice.pathing.MovementHelper.isWater(bot.level(), bot.blockPosition().above());
        }

        private static boolean containsFluid(ServerPlayer bot, net.minecraft.world.level.block.Block block) {
            BlockPos pos = bot.blockPosition();
            return bot.level().getBlockState(pos).is(block)
                    || bot.level().getBlockState(pos.above()).is(block);
        }
    }
}

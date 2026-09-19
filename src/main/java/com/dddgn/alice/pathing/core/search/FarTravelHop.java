package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

/**
 * **远距离旅行的"一跳"**（`D-337 附注二`）：把远目标**先夹进已加载区域**，再按 `GoalNearXZ` 规划。
 *
 * <p><b>为什么需要它（实测动机）</b>：粗目标（{@link GoalNearXZ}）解决了"目标区还没加载就硬拒"的死结，
 * 但**目标区在加载半径外 ⇒ 永远到不了** ⇒ A\* 必须把**已加载区整片**展开完才收手。2026-09-19 实测：
 * 400 格外的粗目标 = `nodes=20000`（= 预算上限）/ 147~163 ms；而**可达**的 640 格 = `641 节点 / 8~14 ms`。
 * ⇒ 分段 hop 若直接用"远目标"，**每跳都付一次整片洪泛**。
 *
 * <p><b>修法</b>：每一跳的目标不是远方，而是"**朝目标方向、仍在已加载区内的最远点**"：
 * 沿直线用 {@code hasChunkAt} 采样出可达距离（只问加载状态 ⇒ **自身零副作用**，红线 `D-132`），
 * 减去半径与余量得到一跳长度，再以该点为心造 {@link GoalNearXZ}（**半径版**：真实地形里那一点可能
 * 不可站/在水里，给搜索一个小区域去挑落脚点 —— 对照 Baritone `GoalNear`）。
 * 搜索因此**总是可达** ⇒ 回归线性（≈ 一跳长度个节点）、`REACHED`（不是 `PARTIAL`）、且碰不到加载边界。
 *
 * <p><b>与"粗目标"的关系</b>：两者都留着 —— 粗目标是**目标语义**（进半径即算到达），
 * 本类是**目标选择策略**（这一跳该往哪儿走）。远距离旅行 = 反复"本类算一跳 → 走完 → 再算一跳"。
 *
 * <p><b>分层</b>：放在 `core/search` 是因为它只做"**目标词汇**"（不动世界、不依赖任务/Job），
 * 与 {@link GoalSpec}/{@link GoalNearXZ} 同层；真正的"反复跳直到到达"属于任务层（`task/`）。
 */
public final class FarTravelHop {

    /** 沿直线采样步长（格）：区块是 16 对齐的，8 足够精确且只需 ~24 次 `hasChunkAt`。 */
    public static final int SWEEP_STEP = 8;

    /** 安全余量（格）：一跳终点离"最后一个已加载采样点"至少留一格区块，防采样粒度/边界抖动。 */
    public static final int MARGIN = 16;

    /** 一跳目标区域的半径（格）：真实地形里给搜索一个小区域挑可站格（对照 Baritone `GoalNear`）。 */
    public static final int DEFAULT_RADIUS = 8;

    /** 一跳的最小有效长度（格）：比这更短就不值得走（调用方应等加载推进或报不可行）。 */
    public static final int MIN_REACH = 8;

    private FarTravelHop() {
    }

    /**
     * 一跳的目标区域。
     *
     * @param center      目标区域中心（已夹到已加载区内；`clamped=false` 时就是原目标）
     * @param radius      区域半径（格）
     * @param reachBlocks 本跳长度（格）：中心到起点的水平距离
     * @param clamped     是否被夹过（`false` = 原目标本身就在已加载区内 ⇒ 一跳直达）
     */
    public record Hop(BlockPos center, int radius, int reachBlocks, boolean clamped) {
        /** 本跳能不能走：夹过的话长度必须够；没夹过（目标就在已加载区内）恒可行。 */
        public boolean feasible() {
            return !clamped || reachBlocks >= MIN_REACH;
        }

        public String describe() {
            return "hop center=" + center.toShortString() + " r=" + radius + " reach=" + reachBlocks
                    + " clamped=" + clamped + " feasible=" + feasible();
        }
    }

    /**
     * 算出"朝 {@code target} 走一跳"的目标区域：沿直线采样已加载范围，夹到边界内侧。
     *
     * <p>**只读加载状态**（`hasChunkAt`）⇒ 绝不加载/生成区块（红线 `D-132`）。
     * 采样遇到第一个未加载点就停（非凸加载集也**保守**收手，绝不跳过空洞）。
     */
    public static Hop compute(ServerLevel level, BlockPos fromFoot, BlockPos target, int radius,
                              int margin) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(fromFoot, "fromFoot");
        Objects.requireNonNull(target, "target");
        int dx = target.getX() - fromFoot.getX();
        int dz = target.getZ() - fromFoot.getZ();
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (dist < 1.0D) {
            // 已经在目标上（或几乎）：不夹、零长度，交给调用方判"到达"
            return new Hop(target.immutable(), radius, 0, false);
        }
        // ① 已加载可达距离：沿直线以 SWEEP_STEP 采样（`hasChunkAt` 不加载）
        int lastLoaded = 0;
        boolean reachedEnd = true;   // 整条线**全部**已加载 ⇒ 目标就在已加载区内，不夹
        int lastProbe = (int) Math.ceil(dist);
        for (int d = SWEEP_STEP; d <= lastProbe; d += SWEEP_STEP) {
            double t = d / dist;
            BlockPos probe = new BlockPos(
                    fromFoot.getX() + (int) Math.round(dx * t),
                    fromFoot.getY(),
                    fromFoot.getZ() + (int) Math.round(dz * t));
            if (level.hasChunkAt(probe)) {
                lastLoaded = d;
            } else {
                reachedEnd = false;
                break;
            }
        }
        // ② 一跳长度 = 可达距离 − 区域半径 − 余量；目标本身更近就用目标。
        // ⚠️ `reachedEnd` 这一条是**必须的**（2026-09-19 实测踩到）：剩余距离很短时（< 半径+余量），
        // 采样点全都已加载，但"最后一个采样点 − 半径 − 余量"会算成 ≤0 ⇒ 会把**就在脚边的目标**误判成
        // "边界在脚下、不可行"（实测：剩 28 格时 `far_walk_no_reach`）。
        int safe = reachedEnd ? Integer.MAX_VALUE : lastLoaded - radius - margin;
        int wanted = (int) Math.floor(dist);
        if (safe >= wanted) {
            return new Hop(target.immutable(), radius, wanted, false);
        }
        if (safe <= 0) {
            // 边界就在脚下（视野极小 / 刚好站在加载边缘）：交回零长度，调用方按不可行处理
            return new Hop(fromFoot.immutable(), radius, Math.max(0, safe), true);
        }
        double t = safe / dist;
        BlockPos center = new BlockPos(
                fromFoot.getX() + (int) Math.round(dx * t),
                fromFoot.getY(),
                fromFoot.getZ() + (int) Math.round(dz * t));
        return new Hop(center.immutable(), radius, safe, true);
    }

    /**
     * 用一跳造规划请求：**纯通行**（沿用 {@link PathRequest#of} 的动作集合与预算 ⇒ 不新造名单、不会漂移）
     * + 粗目标（{@link GoalNearXZ}）。
     *
     * <p>⚠️ 刻意**不**在 {@link PathRequest} 里新增工厂：策略矩阵门禁要求"每个工厂都被 `MovementGrant`
     * 认领"，而本入口提供的是**同一种纯通行授权**（只是换目标形状）⇒ 复用既有集合，不增加授权面。
     */
    public static PathRequest request(String botId, BlockPos fromFoot, Hop hop, String requester) {
        PathRequest pure = PathRequest.of(botId, fromFoot, hop.center(), requester);
        return new PathRequest(botId, fromFoot, GoalNearXZ.around(hop.center(), hop.radius()),
                pure.allowedMovementTypes(), pure.budget(), requester);
    }
}

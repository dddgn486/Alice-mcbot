package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.TwistingVinesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 移动判定(移植自 Baritone MovementHelper 的核心判定逻辑,服务端直读版)。
 */
public final class MovementHelper {

    private MovementHelper() {
    }

    /**
     * 该脚位能否站立（混合判定，D-041）。
     *
     * <p>对照 Baritone {@code canWalkOnBlockState:387-426}（整格白名单）与
     * {@code isBlockNormalCube:755-771}（{@code Block.isShapeFullBlock}）：
     * <ol>
     *   <li>整格碰撞形状 → 可站（含模组整格方块），但**蜂蜜块除外**（Baritone:389 显式排除）；</li>
     *   <li>否则按"已知有碰撞但不能站"的薄/半高面排除（栅栏/栅栏门/墙/铁栏杆与玻璃板/门/滴水石/
     *       紫水晶簇/末地棒/避雷针/可可/花盆）；</li>
     *   <li>其余（台阶/半砖/箱子/模组半格方块…）**保持可站**——这是 Alice 对模组方块的兼容选择，
     *       与 Baritone 的严格白名单不同，已在 D-041 登记。</li>
     * </ol>
     * <p>脚下是无碰撞层（草/花/薄雪等）时，看再下一层是否有支撑。
     */
    public static boolean canWalkOn(ServerLevel level, BlockPos footPos) {
        BlockPos belowPos = footPos.below();
        BlockState below = level.getBlockState(belowPos);
        // **水位例外**（切片 B，D-251；对照 Baritone `canWalkOnPosition:432-448`）：下面是水时，
        // **"我这一格也是水"才算支撑** —— 假人靠按住跳跃浮在水面（D-243），所以**有意收窄到只认"水面格"**
        // （`!isWater(footPos.above())`）：潜不到水里 ⇒ 合法位置集只放执行器真到得了的格子（K-4 可规划即可执行）。
        if (below.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            return isWater(level, footPos) && !isWater(level, footPos.above());
        }
        if (below.isAir() || below.getFluidState().isSource() || avoidWalkingInto(below)) {
            return false;
        }
        if (!below.getCollisionShape(level, belowPos).isEmpty()) {
            return isStandableSupport(level, belowPos, below);
        }
        // 脚下是无碰撞层(草/花/薄雪等可穿过方块):看再下一层是否有支撑
        // (真实玩家站在草丛里,脚位高度还是那层空气格)
        BlockPos below2Pos = belowPos.below();
        BlockState below2 = level.getBlockState(below2Pos);
        if (below2.isAir() || below2.getFluidState().isSource() || avoidWalkingInto(below2)) {
            return false;
        }
        return !below2.getCollisionShape(level, below2Pos).isEmpty()
                && isStandableSupport(level, below2Pos, below2);
    }

    /** 支撑面是否可站：整格形状优先，蜂蜜块与薄/半高面排除（见 {@link #canWalkOn}）。 */
    private static boolean isStandableSupport(ServerLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block != Blocks.HONEY_BLOCK
                && Block.isShapeFullBlock(state.getCollisionShape(level, pos))) {
            return true;
        }
        return !isKnownNonStandable(block);
    }

    /**
     * 已知"有碰撞但不能站"的方块（对照 Baritone canWalkOn 白名单的反面：碰撞面太薄或形状不完整）。
     * <p>注：活板门（TrapDoorBlock）**不在**此表——关闭的活板门是真实可站地面，Alice 保留该能力
     * （与 Baritone 的严格白名单不同，已在 D-041 登记）。
     */
    private static boolean isKnownNonStandable(Block block) {
        return block instanceof FenceBlock
                || block instanceof FenceGateBlock
                || block instanceof WallBlock
                || block instanceof IronBarsBlock
                || block instanceof DoorBlock
                || block instanceof HoneyBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof AmethystClusterBlock
                || block instanceof EndRodBlock
                || block instanceof LightningRodBlock
                || block instanceof CocoaBlock
                || block instanceof FlowerPotBlock;
    }

    /**
     * 底部半砖（FALL 落点拒绝，对照 Baritone `MovementDescend.dynamicFallCost:203-205`）：
     * 落到下半砖上会严重过冲并造成额外坠落伤害。
     */
    public static boolean isBottomSlab(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                && state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
    }

    /** 可攀爬方块（对照 Baritone isClimbable:573-580）：梯子/藤蔓/缠怨藤/垂泪藤。 */
    public static boolean isClimbable(BlockState state) {
        Block block = state.getBlock();
        return block instanceof LadderBlock
                || block instanceof VineBlock
                || block instanceof WeepingVinesBlock
                || block instanceof TwistingVinesBlock;
    }

    /**
     * **这格是不是水**（唯一定义，K-4 口径）：对照 Baritone {@code MovementHelper.isWater:698-712}。
     *
     * <p>口径是**流体状态**（含流动水、含水方块里的水），不是 Baritone 的"方块是 `LiquidBlock` 且流体为水"：
     * 差别只在**含水方块**（水半砖/水楼梯）那一档——那种格子在 Alice 里本来就被 `canWalkThrough` 判为不可穿，
     * 所有调用点都会先被挡下，所以两者在这里等价（D-244 已登记）。
     *
     * <p>用 {@code Level} 而不是 {@code ServerLevel}：`SurvivalSystem` 手上有的是 `bot.level()`。
     */
    public static boolean isWater(net.minecraft.world.level.Level level, BlockPos pos) {
        return level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER);
    }

    /** **浮着段**（切片 B）：目的格与它下面那格都是水 ⇒ 执行期拿不到 `onGround`（水里永远为假）。 */
    public static boolean isFloatingDestination(ServerLevel level, BlockPos footPos) {
        return isWater(level, footPos) && isWater(level, footPos.below());
    }

    /**
     * **浮着段的完成口径**（D-244 的 `PILLAR` 水柱口径推广到 TRAVERSE/DESCEND/ASCEND）：脚位到格即成功。
     *
     * <p>⚠️ **只对 {@link #isFloatingDestination} 生效**：蹚水（下面实心）时 bot 站在水底、`onGround` 成立，
     * 套上它会在格边界提前宣布完成 ⇒ 被水推回上一格 ⇒ 平白多出 STALE 重规划（D-248 实测踩到）。
     */
    public static boolean isAtFootCell(ServerLevel level, net.minecraft.world.entity.Entity entity,
                                       BlockPos footPos) {
        return footCell(level, entity).equals(footPos);
    }

    /**
     * **水里要不要按住跳跃**（D-243/D-251，唯一定义；对照 Baritone `MovementTraverse:243-248`：
     * `feet.getY() < dest.getY()` ⇒ `Input.JUMP`）。原版水里按住跳跃＝上浮；到高度就松开 ⇒ 不连跳。
     * 陆地那套"先对准再跳"的门控不适用于水（那是防斜跳落回原地的）。
     */
    public static boolean shouldHoldJumpInWater(ServerLevel level, net.minecraft.world.entity.Entity entity,
                                                BlockPos targetFoot) {
        BlockPos foot = footCell(level, entity);
        return isWater(level, foot) && foot.getY() < targetFoot.getY();
    }

    /** 该格能否穿过(身体格):空气或可穿过方块,且非危险。 */
    public static boolean canWalkThrough(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (avoidWalkingInto(state)) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * ⭐ **某个"脚位格"的整机通行性**（`D-374`，2026-09-21）：**脚位 + 头位都可穿过**。
     *
     * <p><b>为什么必须单独有一个名字</b>：`canWalkThrough(pos)` 只查**单格**（它的名字里没有这个信息），
     * 而玩家要占**两格**。把单格谓词放进"目的地能不能进"的闸门位置，就会**静默砍掉一整类边**——
     * 2026-09-21 真机实测：`appendBreakAndEnter` 的入口闸门只查躯干 ⇒ 「脚位可通行 + 头位被挡」
     * 的目的地**既不是 TRAVERSE（要 `canStandCentered`，含头位）也不是 BREAK_AND_ENTER（被提前 return）**
     * ⇒ 一格高夹缝在整张图里**没有任何入边**（代价：掉落物被瞬退）。
     *
     * <p><b>口径</b>：与 {@link #canStandCentered} 的"通行"那两半**逐字一致**（只是不含"脚下有支撑"）——
     * 需要"能站上去"用 `canStandCentered`，只需要"身体放得下"用本方法。
     */
    public static boolean bodyPassable(ServerLevel level, BlockPos foot) {
        return canWalkThrough(level, foot) && canWalkThrough(level, foot.above());
    }

    /**
     * 规划期"可站"世界前提（K-4 / D-167）：脚下有支撑 + 脚位可通行 + 头位可通行。
     *
     * <p><b>与执行期契约的关系</b>：运行期完成判定 {@link #isSettledAtFootPos}
     * （EXACT = 脚位格正确 + 已落地 + 水平距中心 ≤0.3）里，只有"世界前提"这部分能在规划期证明；
     * "已落地"与"带没带到位"是运行期事实。所以本方法是 EXACT 的**必要条件**，不是充分条件——
     * 规划期能证明的必须成立：支撑存在且非源流体（与 {@link #canWalkOn} 同口径）、
     * 脚位与头位无碰撞（碰撞形状空 ⇒ 以格中心摆放的 0.6×1.8 玩家包围盒必然放得下，
     * 因此"站得正"在规划期等价于这两条）。
     *
     * <p><b>唯一定义</b>（K-4 侦察结论）：本谓词原先在 {@code SurfaceMovementProvider} 复制 3 处、
     * {@code StandingPointSelector.isStandable} 复制 1 处，而**目标准入一处都没查**。
     * 现在一律调本方法；两条**故意更宽**的例外留在原处并注明理由（破坏类移动的落点
     * 只能证明"破坏之后可站"，见 `SurfaceMovementProvider` 的 BREAK_AND_ENTER / DOWNWARD）。
     */
    public static boolean canStandCentered(ServerLevel level, BlockPos foot) {
        return canWalkOn(level, foot)
                && canWalkThrough(level, foot)
                && canWalkThrough(level, foot.above());
    }

    /**
     * 危险方块(走入即受伤/致命或无法安全穿过)。
     * <p>对照 Baritone {@code MovementHelper.avoidWalkingInto:350-360}：熔岩、任意火(含灵魂火/营火)、
     * 岩浆块、仙人掌、甜浆果丛、末地传送门、蛛网、气泡柱。
     * <p>注意：Baritone 在此还会拒绝"任意流体"，但 Alice 的水面通行语义不同（见 D-025/D-036 差异登记），
     * 流体只在破坏路径上拒绝（{@code BlockBreakSafety}）。
     */
    public static boolean avoidWalkingInto(BlockState state) {
        return state.is(Blocks.LAVA)
                || state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.BUBBLE_COLUMN);
    }

    /**
     * 实体是否真正站在当前碰撞形状支撑顶面上。支持下半砖、台阶等非整格顶面；
     * 不用 blockPosition 的整数 Y 假装实体脚底高度。
     */
    public static boolean isStandingOnSupport(ServerLevel level, net.minecraft.world.entity.Entity entity) {
        final double epsilon = 0.08D;
        double footY = entity.getY();
        int supportY = net.minecraft.util.Mth.floor(footY - epsilon);
        BlockPos supportPos = BlockPos.containing(entity.getX(), supportY, entity.getZ());
        BlockState support = level.getBlockState(supportPos);
        if (support.getFluidState().isSource() || avoidWalkingInto(support)) {
            return false;
        }
        double topY = supportTopY(level, supportPos);
        return !Double.isNaN(topY) && Math.abs(footY - topY) <= epsilon;
    }

    /** 实体是否稳定落在指定的逻辑脚位段，兼容下半砖和台阶的非整格支撑顶面。 */
    public static boolean isStandingAtFootPos(ServerLevel level, net.minecraft.world.entity.Entity entity,
                                              BlockPos footPos) {
        final double epsilon = 0.08D;
        if (net.minecraft.util.Mth.floor(entity.getX()) != footPos.getX()
                || net.minecraft.util.Mth.floor(entity.getZ()) != footPos.getZ()) {
            return false;
        }
        BlockPos supportPos = footPos.below();
        BlockState support = level.getBlockState(supportPos);
        if (support.getFluidState().isSource() || avoidWalkingInto(support)) {
            return false;
        }
        double topY = supportTopY(level, supportPos);
        return !Double.isNaN(topY) && Math.abs(entity.getY() - topY) <= epsilon;
    }

    /**
     * R2-C 统一完成契约：脚位列正确 + 支撑稳定 + 已落地 + 水平距目标中心不超过 maxHorizontal。
     * <p>四个执行器共用，避免各自为政（D-026）。onGround 是必须项：否则残余动量会在
     * “判定成功”之后继续把 bot 带离目标列。
     */
    public static boolean isSettledAtFootPos(ServerLevel level, net.minecraft.world.entity.Entity entity,
                                             BlockPos footPos, double maxHorizontal) {
        if (!isStandingAtFootPos(level, entity, footPos)) {
            return false;
        }
        if (!entity.onGround()) {
            return false;
        }
        // ⭐ 与"朝格中心走"（{@link #faceCellCenter} / `DownwardExecution`）**共用同一个距离口径**：
        // 同一条几何被"判定"与"纠正"两处读 ⇒ 两处必须同源（`D-445` 裁定三的教训）。
        return horizontalDistanceToCenter(entity, footPos) <= maxHorizontal;
    }

    /**
     * **"被邻列方块的角托在洞沿上"** —— ③ 的**因果谓词**（`D-445` 裁定三）。
     *
     * <p>成立条件（缺一不可）：
     * <ol>
     *   <li>**本列脚下已经没有支撑**（`!canWalkOn(level, footCell)`）—— 也就是"该掉下去了"；</li>
     *   <li>把 AABB 按**一次重力步**往下探（{@link #FALL_PROBE}），它与**相邻某列**在脚下一层的
     *       实心方块**真的相交** ⇒ 这一 tick 的下落会被那一列的角挡住。</li>
     * </ol>
     * 第 2 条与真机读数**同形**：`delta=0.0000,-0.0784,0.0000` + `onGround=true` —— 每 tick 都想掉、
     * 每 tick 都被邻列的角托住 ⇒ 永远掉不下去（2026-09-25 两条 145 tick `segment_timeout`）。
     *
     * <p>⚠️ 为什么不用"离格中心 > 0.2"当判据：那确实是真机的**成因**，但**离心是正常状态**
     *（`COLUMN` 容差不要求居中），**无条件**给水平输入会改掉所有正常下落的落点。CORE 电池实测：
     * 伐木步按 `nearest` 选树 ⇒ 落点一动就换了一棵树 ⇒ `lumber_job=FAIL`。
     * ⇒ **只在真的被托住时才动**；正常下落**零水平输入**（对既有路径零扰动）。
     *
     * <p>⚠️ 垂直方向必须用"探一步之后"的盒子：静止时脚底与邻列方块顶面**恰好相切**，
     * 直接 `AABB.intersects` 会因零体积相交返回 `false`（夹具实测踩到）。
     */
    public static boolean supportedByNeighbourCorner(ServerLevel level, net.minecraft.world.entity.Entity entity,
                                                     BlockPos footCell) {
        if (canWalkOn(level, footCell)) {
            return false;
        }
        AABB probing = entity.getBoundingBox().move(0.0D, -FALL_PROBE, 0.0D);
        int y = footCell.getY() - 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos pos = new BlockPos(footCell.getX() + dx, y, footCell.getZ() + dz);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(pos)
                        .getCollisionShape(level, pos);
                if (!shape.isEmpty() && shape.bounds().move(pos).intersects(probing)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 探针步长：**一次重力步**（真机逐字读数 `delta=0.0000,-0.0784,0.0000`；= 0.08 × 0.98）。
     * ⚠️ 不许改成 0：静止时脚底与邻列方块顶面**恰好相切**，零体积相交判不出来（见上）。
     */
    private static final double FALL_PROBE = 0.0784D;

    /**
     * **到某格中心的水平距离**（= Baritone `MovementDownward:88` 的 `ab`）。
     *
     * <p>⚠️ 是**格 +0.5 的那个中心**，不是格边 —— Baritone 原话（`MovementFall:174`）：
     * "we are moving to the 0.5 center **not the edge**"。
     *
     * <p>⚠️ **0.2 是几何分界**：玩家 AABB 半宽 0.3、格半宽 0.5 ⇒ 离心 > 0.2 时 AABB 就压进了邻列；
     * 邻列只要有一块实心，就能把 bot **托在洞沿上**（`onGround=true`、重力每 tick 被碰撞清零）
     * ⇒ **永不下落**。2026-09-25 真机实测：`pos=-73.500,54.000,158.730`（离心 0.23）与
     * `pos=-73.503,54.000,175.205`（离心 0.295），两条各 145 tick `segment_timeout`、`input=forward 0.00`
     *（取证见 `docs/reviews/2026-09-25-真机第三轮-根因取证.md` §4）。
     */
    public static double horizontalDistanceToCenter(net.minecraft.world.entity.Entity entity, BlockPos foot) {
        double dx = entity.getX() - (foot.getX() + 0.5D);
        double dz = entity.getZ() - (foot.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 把**身体 yaw** 指向某格中心（Baritone `MovementHelper.moveTowards:639-646` 的转向那一半）。
     *
     * <p>⚠️ 只写 `yRot`/`yBodyRot`（对齐 `LookBehavior:99-100`），头/身交给原版 `tickHeadTurn`；
     * 只写 `yHeadRot` 而不同步 `yBodyRot` 会让客户端把头渲染成扭向一侧。
     * ⚠️ **只负责转向，不给前进量** —— 前进量由各执行器按场景给（走过去 1.0 / 原地微调 0.4）。
     */
    public static void faceCellCenter(net.minecraft.world.entity.LivingEntity entity, BlockPos foot) {
        double dx = foot.getX() + 0.5D - entity.getX();
        double dz = foot.getZ() + 0.5D - entity.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        entity.setYRot(yaw);
        entity.setYBodyRot(yaw);
    }

    /**
     * COLUMN 容差判定（D-027）：脚位所在格正确 + **已落地**。
     *
     * <p>`onGround` 是 D-026 统一完成契约的必须项：起跳/下落途中 `blockPosition()` 已经等于目标格，
     * 若不带 `onGround`，执行器会在空中提前宣布成功（实测 PILLAR 4 tick 假成功 → bot 落回原点 →
     * 下一段 STALE；ASCEND 同类）。
     *
     * <p>脚位格必须用 {@link #footCell} 取，不能用原版 `blockPosition()`（D-105）。
     */
    public static boolean isAtFootColumn(ServerLevel level, net.minecraft.world.entity.Entity entity,
                                         BlockPos footPos) {
        return entity.onGround()
                && footCell(level, entity).equals(footPos)
                && entity.getY() - footPos.getY() < 0.5D;
    }

    /** 支撑碰撞形状的世界坐标顶面；空形状返回 NaN。 */
    public static double supportTopY(ServerLevel level, BlockPos supportPos) {
        net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(supportPos)
                .getCollisionShape(level, supportPos);
        if (shape.isEmpty()) {
            return Double.NaN;
        }
        return supportPos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
    }

    /**
     * 运行期脚位格：**统一**"实体当前站在哪一格"的口径，与规划层 node 语义对齐（D-105）。
     *
     * <p>规划层的脚位格定义是"支撑格的上一格"（{@link #canWalkOn(ServerLevel, BlockPos)} 查的是
     * 脚位格**下方**那格），而原版 {@code entity.blockPosition()} 返回"脚**所在**格"。支撑是整格时
     * 两者恰好重合；支撑顶面不足一格时（箱子/灵魂沙 0.875、底半砖 0.5、模组半格方块）原版会把脚留在
     * 支撑自己那一格 —— "站在箱子上"在两套坐标里相差整整一格，于是
     * `footCell.equals(toFoot)` 之类的完成契约与合法位置集检查永远不成立。
     *
     * <p>实测（J6-b2，`clear_guard` 场景）：ASCEND 到箱顶后 bot 原地起跳 12 次（每次从箱顶 64.875
     * 跳到顶点 66.125 → 落回 64.875），三个出口全部锁死，只能等 `SEGMENT_TIMEOUT`（161 tick）。
     *
     * <p>判定规则：脚所在格有碰撞形状、**可站**、且顶面 ≥ 半格 → 脚位格取其上一格。
     * 与 Baritone `IPlayerContext.playerFeet()`（`y + 0.1251`，底半砖再上移一格）语义一致；
     * 这里按碰撞形状判定而非硬编码常量，因此对模组半格方块同样成立（D-041 的兼容立场）。
     */
    public static BlockPos footCell(ServerLevel level, net.minecraft.world.entity.Entity entity) {
        return footCell(level, entity.getX(), entity.getY(), entity.getZ());
    }

    /**
     * {@link #footCell(ServerLevel, net.minecraft.world.entity.Entity)} 的坐标版本：便于无头断言
     * （`PathingRegression` 直接喂坐标，不依赖 bot 物理）。
     */
    public static BlockPos footCell(ServerLevel level, double x, double y, double z) {
        BlockPos raw = BlockPos.containing(x, y, z);
        BlockState here = level.getBlockState(raw);
        if (here.isAir()) {
            return raw;
        }
        // 无碰撞形状（草/花/薄雪/地毯等）或顶面不足半格 → 脚就在这一格里
        double topY = supportTopY(level, raw);
        if (Double.isNaN(topY) || topY - raw.getY() < 0.5D) {
            return raw;
        }
        return isStandableSupport(level, raw, here) ? raw.above() : raw;
    }

    /** 平地移动(从 from 脚位水平走到 to 脚位)。 */
    public static boolean canTraverse(ServerLevel level, BlockPos from, BlockPos to) {
        // 目标脚位可站,目标身体格与头格可穿过（K-4/D-167：共用唯一定义）
        if (!canStandCentered(level, to)) {
            return false;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            // 对角线不能擦过任一侧方块；同时检查角落交叉格，避免两方块夹角被当成可走。
            BlockPos sideX = new BlockPos(from.getX() + dx, from.getY(), from.getZ());
            BlockPos sideZ = new BlockPos(from.getX(), from.getY(), from.getZ() + dz);
            if (!canWalkThrough(level, sideX) || !canWalkThrough(level, sideX.above())
                    || !canWalkThrough(level, sideZ) || !canWalkThrough(level, sideZ.above())) {
                return false;
            }
        }
        return canSweepPlayer(level, from, to);
    }

    /**
     * 按玩家尺寸检查脚位中心从 from 到 to 的连续扫掠空间。
     * 支撑方块只在脚底边界接触，不会被当作身体碰撞；侧向实体空间则必须完全无碰撞。
     */
    public static boolean canSweepPlayer(ServerLevel level, BlockPos from, BlockPos to) {
        final double halfWidth = 0.3D;
        final double height = 1.8D;
        double minX = Math.min(from.getX() + 0.5D, to.getX() + 0.5D) - halfWidth;
        double maxX = Math.max(from.getX() + 0.5D, to.getX() + 0.5D) + halfWidth;
        double minY = Math.min(from.getY(), to.getY());
        double maxY = Math.max(from.getY(), to.getY()) + height;
        double minZ = Math.min(from.getZ() + 0.5D, to.getZ() + 0.5D) - halfWidth;
        double maxZ = Math.max(from.getZ() + 0.5D, to.getZ() + 0.5D) + halfWidth;
        AABB sweep = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        int startX = (int) Math.floor(minX);
        int endX = (int) Math.floor(Math.nextDown(maxX));
        int startY = (int) Math.floor(minY);
        int endY = (int) Math.floor(Math.nextDown(maxY));
        int startZ = (int) Math.floor(minZ);
        int endZ = (int) Math.floor(Math.nextDown(maxZ));
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    // 起点/终点脚下支撑属于脚底接触，不是身体扫掠阻塞。
                    if (pos.equals(from.below()) || pos.equals(to.below())) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (avoidWalkingInto(state)) {
                        return false;
                    }
                    net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
                    if (!shape.isEmpty() && shape.bounds().move(x, y, z).intersects(sweep)) {
                        double top = y + shape.max(net.minecraft.core.Direction.Axis.Y);
                        if (top > minY + 1.0E-6D) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /** 上一阶台阶(从 from 脚位走到 to=from+1y 脚位)。
     * <p>跳跃语义(真实玩家跳高约 1.25 格):
     * <ul>
     *   <li>轨迹下层 mid 允许「1 格高的实心方块」(台阶/围墙可跳越,不再视为墙);</li>
     *   <li>正上方垂直爬升(mid==from)允许目标格悬空——跳跃弧线过渡,
     *       PathExecutor 为硬移动(无重力),bot 不会在过渡格掉落。</li>
     * </ul></p>
     */
    public static boolean canAscend(ServerLevel level, BlockPos from, BlockPos to) {
        if (to.getY() != from.getY() + 1) {
            return false;
        }
        // ⭐⭐ `D-394`（2026-09-22 真机：`ASCEND_NO_HEADROOM` ×75 + 无限重规划循环）：
        // **起跳需要 `from.above(2)` 那格也是空的**（跳到 1 格高时头顶要占两格 + 到第三格）。
        // 对照 Baritone `MovementAscend.java:42`：它的位置集就是 `{dest, src.above(2), dest.above()}`
        // （`Movement` 的 `positionsToPlace`/畅通集），即 **`src.above(2)` 是它的一等成员**。
        // 病灶：本方法（规划侧共享谓词）**漏了它**，而执行侧 `AscendExecutionFactory:59` 一直在查
        // ⇒ 规划出边、执行必拒 ⇒ `PathExecutor` 重规划又算出**同一条边**（确定性）⇒ 死循环
        // （真机 75 次拒绝、同一脚位 6 次 segment 重启：`from=10,71,219` 头位空、`from.up2=石头`）。
        // 收进**同一个谓词**（`D-374`/K-4 纪律：谓词只有一处出处），两侧同时生效。
        if (!canWalkThrough(level, from.above(2))) {
            return false;
        }
        BlockPos mid = new BlockPos(to.getX(), from.getY(), to.getZ());
        if (!canJumpThrough(level, mid)) {
            return false;
        }
        if (!canWalkThrough(level, to) || !canWalkThrough(level, to.above())) {
            return false;
        }
        if (mid.equals(from)) {
            // 正上方垂直爬升:目标格悬空也允许(跳跃过渡;硬移动不掉落)
            return true;
        }
        return canWalkOn(level, to);
    }

    /** 跳跃轨迹下层判定:空气/可穿过,或 1 格高的实心方块(跳跃高度可越过)。 */
    private static boolean canJumpThrough(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) {
            return true;
        }
        if (avoidWalkingInto(state)) {
            return false;
        }
        return state.getCollisionShape(level, pos).max(net.minecraft.core.Direction.Axis.Y) <= 1.0D;
    }

    /** 下一格台阶(从 from 脚位走到 to=from-1y 脚位)。 */
    public static boolean canDescend(ServerLevel level, BlockPos from, BlockPos to) {
        if (to.getY() != from.getY() - 1) {
            return false;
        }
        BlockPos mid = new BlockPos(to.getX(), from.getY(), to.getZ());
        // 从当前格能走到中继格(身体可穿过),目标格本身可穿过 + 可站，并检查下降过程的实体扫掠空间。
        return canWalkThrough(level, mid)
                && canWalkThrough(level, mid.above())
                && canWalkThrough(level, to)
                && canWalkOn(level, to)
                && canSweepPlayer(level, from, to);
    }
}

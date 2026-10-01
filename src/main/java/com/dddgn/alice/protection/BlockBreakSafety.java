package com.dddgn.alice.protection;

import com.dddgn.alice.write.WriteReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Alice 唯一的方块破坏安全入口。
 * <p>明确任务目标与执行器自行选择的清障方块使用不同策略：</p>
 * <ul>
 *   <li>明确目标：**保护区**（`protected_area`，交{@link ZoneAuthority} 区域授权面判）与不可破坏方块拒绝；
 *       黑曜石等高代价方块仍允许。</li>
 *   <li>清障目标：额外回避脚下承重块与高代价方块，优先换站位/路线。</li>
 * </ul>
 *
 * <p>⚠️ 本类**不是**"宝贵"的唯一出处：**区内（已认领区块）任何方块实体一律不可挖掘**这条**位置**规则
 * 在 {@link ZoneAuthority#authorize}（`D-565` ⑤ 刀 2，码 `protected_block_entity`）—— 它对
 * `EXPECTED_TARGET` 也生效，而本类的 {@code block_entity} 只管**清障**策略。
 */
public final class BlockBreakSafety {

    private BlockBreakSafety() {
    }

    /**
     * **按声明的写入理由派生策略**——破坏判定的唯一分发入口（D-082）。
     *
     * <p>在此之前，策略是由"调用哪个方法"隐式选择的（{@code breakable} vs {@code breakableExplicit}），
     * 授权语义因此藏在方法名里。现在理由成为数据，调用点必须显式声明。
     *
     * @param reason 声明的写入理由；null 视为最保守的清障策略
     * @return null = 允许；非 null = 拒绝原因
     */
    public static String refusal(ServerPlayer bot, BlockPos target, WriteReason reason) {
        if (reason != null && reason.policy() == WriteReason.Policy.EXPLICIT_TARGET) {
            return explicitTargetRefusal(bot, target, reason);
        }
        return clearingRefusal(bot, target, reason);
    }

    /** 明确指定目标的硬拒绝原因；返回 null 表示目标本身允许挖。 */
    public static String explicitTargetRefusal(ServerPlayer bot, BlockPos target) {
        return explicitTargetRefusal(bot, target, null);
    }

    /**
     * 明确指定目标的硬拒绝原因（带**写入理由** ⇒ 保护区那一层可以按区域级授权面判定）。
     *
     * <p>⭐ `D-338` 附注七③：**保护区认领**这一条从"一律拒"变成"问区域授权面"
     * —— 任务区覆盖 + 等级够 ⇒ 放行（其后仍受 `Attribution`/`Quota`/账本约束）。
     * ⚠️ **没有任务区时拒绝码逐字仍是 `protected_area`**（既有失败码/文档/夹具都按它写）。
     * ⚠️ 方块/标签黑名单（`protected_block`/`protected_tag`）**不参与**区域授权 ⇒ 原样拒。
     */
    public static String explicitTargetRefusal(ServerPlayer bot, BlockPos target, WriteReason reason) {
        ServerLevel level = (ServerLevel) bot.level();
        // 流体不可挖：对照 Baritone MovementHelper.getMiningDurationTicks:588-590（任何流体 → COST_INF）。
        // 否则岩浆会被当成可清障方块（estimateBreakTicks 给出有限代价），规划器可能选择"挖岩浆"。
        if (!level.getBlockState(target).getFluidState().isEmpty()) {
            return "fluid_block";
        }
        String worldProtection = AreaData.get(level.getServer()).protectionReason(level, target);
        String zoneRefusal = ZoneAuthority.regionRefusal(level, bot.getUUID(), target, worldProtection,
                reason, ZoneAuthority.Act.BREAK);
        if (zoneRefusal != null) {
            return zoneRefusal;
        }
        if (isUnbreakable(level, target)) {
            return "unbreakable_block";
        }
        // ⭐ `1-2` · `D1`：Baritone `avoidBreaking:68-82` 的**公共谓词化**（③冰 ④虫蚀 ⑤侧邻危险）。
        // 放在**这一层**（而不是只放清障层）⇒ 搜索（`SurfaceMovementProvider` 三处都先问
        // `BlockInteraction.breakable`）与执行（`BreakAnd*Execution` / `PathSession` 复检）**同一处**生效。
        // 对照 Baritone：同一个判据也在 `MineProcess:489`（**任务目标**那一侧）上跑 ⇒ 目标与清障同口径。
        return hazardRefusal(level, target);
    }

    /**
     * ⭐ `1-2` · `D1`：**破坏的危险邻接**（Baritone `MovementHelper.avoidBreaking:68-82` ＋
     * `avoidAdjacentBreaking:84-108` 的公共谓词化）。
     *
     * <p>逐条对照（Baritone → Alice 稳定码）：
     * <ul>
     *   <li>③ `b == Blocks.ICE`（冰会变水，把路弄乱）⇒ {@code ice_clearing_block}；</li>
     *   <li>④ `b instanceof InfestedBlock`（虫蚀方块，敲开会放虫）⇒ {@code infested_clearing_block}；</li>
     *   <li>⑤ 正上方 + 四个水平邻格的 {@code avoidAdjacentBreaking} ⇒ {@code liquid_above_neighbour}
     *       / {@code liquid_source_neighbour} / {@code liquid_neighbour} /
     *       {@code unsupported_falling_neighbour}（见 {@link #neighbourHazard}）。</li>
     * </ul>
     *
     * <p>⭐ **分工逐字**（Baritone `getMiningDurationTicks:600-605` 的 `includeFalling` 与
     * `avoidAdjacentBreaking:90-96` 的 `!directlyAbove` 两条合起来的语义）：
     * **正上方**的落体是**计价**问题（`BlockInteraction.estimateBreakTicks(bot, level, pos, true)`），
     * **侧邻**的未支撑落体是**禁止**问题（本方法的 {@code unsupported_falling_neighbour}）。
     * 两者不是同一条判据，别合并。
     *
     * <p>⚠️ **未镜像的两条**（如实登记，理由）：
     * <ol>
     *   <li>Baritone `:69-71` 的 {@code worldBorder.canPlaceAt} —— Alice 没有世界边界感知的规划面；</li>
     *   <li>Baritone 的两个设置项（`blocksToDisallowBreaking` / `avoidUpdatingFallingBlocks`）——
     *       Alice 无设置系统 ⇒ 等价于「名单为空 ＋ 开关恒开」（后者 Baritone 默认即开）。</li>
     * </ol>
     *
     * @return null = 允许；非 null = 拒绝原因（稳定码，供日志与夹具断言）
     */
    public static String hazardRefusal(ServerLevel level, BlockPos target) {
        BlockState state = level.getBlockState(target);
        if (state.is(Blocks.ICE)) {
            return "ice_clearing_block";
        }
        if (state.getBlock() instanceof InfestedBlock) {
            return "infested_clearing_block";
        }
        // 邻接顺序逐字照 Baritone `:77-81`：先正上方，再 +x / -x / +z / -z。
        String above = neighbourHazard(level, target.above(), true);
        if (above != null) {
            return above;
        }
        for (BlockPos neighbour : new BlockPos[] {
                target.east(), target.west(), target.north(), target.south() }) {
            String hazard = neighbourHazard(level, neighbour, false);
            if (hazard != null) {
                return hazard;
            }
        }
        return null;
    }

    /**
     * 单个邻格的危险性（Baritone `avoidAdjacentBreaking:84-108` 的逐句对照）。
     *
     * <p>⚠️ `directlyAbove` 这个参数就是 Baritone 的 `!directlyAbove` 开关：**正上方**时不看落体
     * （"拆一块、上面那块落体掉下来"是正常流程，代价另行计价），只把**液体**当危险；
     * **水平邻格**时，未支撑落体与液体的判定都生效。
     */
    private static String neighbourHazard(ServerLevel level, BlockPos pos, boolean directlyAbove) {
        BlockState state = level.getBlockState(pos);
        net.minecraft.world.level.block.Block block = state.getBlock();
        // Baritone `:90-96`：水平方向 + 是落体 + 它下面无支撑（会流/塌过来）⇒ 危险。
        if (!directlyAbove
                && block instanceof FallingBlock
                && FallingBlock.isFree(level.getBlockState(pos.below()))) {
            return "unsupported_falling_neighbour";
        }
        if (block instanceof LiquidBlock) {
            // Baritone `:99-108`：只认纯液体（waterlogged 方块有封闭侧面，不算）。
            if (directlyAbove) {
                return "liquid_above_neighbour";
            }
            if (state.getValue(LiquidBlock.LEVEL) == 0) {
                return "liquid_source_neighbour";   // 源头会向水平方向流
            }
            // 非源头 ⇒ 它会更愿意往下流；**下面仍是液体**时才当静态、放行。
            return level.getBlockState(pos.below()).getBlock() instanceof LiquidBlock
                    ? null : "liquid_neighbour";
        }
        return null;
    }

    /**
     * 清障方块的拒绝原因。清障是执行器擅自破坏，因此比明确目标更保守。
     * 返回非 null 时，上层应先尝试其他站位/路线，而不是立即破坏该方块。
     */
    public static String clearingRefusal(ServerPlayer bot, BlockPos target) {
        return clearingRefusal(bot, target, null);
    }

    /** 清障的拒绝原因（带理由 ⇒ 与明确目标走**同一套**区域级授权判定）。 */
    public static String clearingRefusal(ServerPlayer bot, BlockPos target, WriteReason reason) {
        if (isUnderfoot(bot, target)) {
            return "underfoot_block";
        }
        String hardRefusal = explicitTargetRefusal(bot, target, reason);
        if (hardRefusal != null) {
            return hardRefusal;
        }
        BlockState state = bot.level().getBlockState(target);
        if (isExpensiveToClear(state)) {
            return "expensive_clearing_block";
        }
        // 含方块实体的方块（箱子/熔炉/漏斗/告示牌/刷怪笼/**模组机器**）不得作为"清障"对象（D-095）。
        // 风险不是理论：箱子被拆物品会掉落（还好），但**模组机器可能内容物直接蒸发**。
        // 为什么放在"可破坏集合"里而不是单独加一段拒绝逻辑：`breakable` 会喂给搜索
        // （`SurfaceMovementProvider` 用它生成 BREAK_AND_* 候选）——**剔除之后规划器会自动绕开**，
        // 绕不开就如实 `found_but_unminable`。于是"绕路"是免费得到的，不需要新机制。
        // 只作用于清障策略（LINE_OF_SIGHT/STANDING_SPACE/PATH_ACCESS）；
        // **玩家明确指定的目标**（EXPECTED_TARGET）在这一层不受影响
        // ⚠️ 但**区内**另有更严的一档（`D-565` ⑤ 刀 2：已认领区块里的方块实体**一律不可挖掘**，
        // 码 `protected_block_entity`，见 `ZoneAuthority.authorize`）—— 那一条**对明确目标也生效**，
        // 所以区内的清障拒绝今天**归因到位置规则**，本码只剩"野外清障"这一面。
        if (state.hasBlockEntity()) {
            return "block_entity";
        }
        return null;
    }

    /**
     * ⭐ `D-472` 起：**"目标在脚下"这件事的唯一判据搬到了
     * {@code pathing/MovementHelper.underfootUnsafe(level, bot, target)}** ——
     * 本类原来的 {@code requiresReposition(bot, target)}（`D-097` 时期声明、
     * **全仓零调用点**、且用 {@code blockPosition().below()} 与 `D-399` C 的
     * {@code footCell(...).below()} 口径不一致）已删除。
     *
     * <p>为什么**不能**留在本类：判据要用 `footCell`/`canWalkOn`（内核层几何），
     * 而 `pathing → protection` 已经存在（`MovementHelper` 要问"可破坏吗"）
     * ⇒ 放这里会造出仓里第二个包级环。
     *
     * <p>⚠️ 口径**不是**"是否在脚下"，而是"脚下 **且** 拆完没有落脚面"
     * （站上去再向下拆是回收/向下挖的正常流程，`D-399` §一）。
     */
    private static boolean isUnderfoot(ServerPlayer bot, BlockPos target) {
        return target.equals(bot.blockPosition().below());
    }

    /** 原版负破坏速度表示生存模式不可破坏（如基岩）。 */
    public static boolean isUnbreakable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getDestroySpeed(level, pos) < 0.0F;
    }

    /**
     * 可作为明确目标、但不应被普通清障路线擅自消耗的高代价方块。
     * 后续可迁移到数据包标签；当前先集中在唯一策略类，避免散落硬编码。
     */
    public static boolean isExpensiveToClear(BlockState state) {
        return state.is(Blocks.OBSIDIAN)
                || state.is(Blocks.CRYING_OBSIDIAN)
                || state.is(Blocks.REINFORCED_DEEPSLATE);
    }
}

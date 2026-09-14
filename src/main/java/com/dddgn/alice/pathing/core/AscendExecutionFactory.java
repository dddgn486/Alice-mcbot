package com.dddgn.alice.pathing.core;

import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

/** R2-C Ascend 的执行工厂；只接受一级上升 MovementSpec。 */
public final class AscendExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.ascend.v1";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec != null
                && spec.movementType() == MovementType.ASCEND
                && KEY.equals(spec.executionFactoryKey());
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("ASCEND_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("ASCEND_MISSING_CONTEXT");
        
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        
        // 验证几何约束：dy=+1, 相邻方块
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int horizontalDist = Math.abs(dx) + Math.abs(dz);
        if (dy != 1 || horizontalDist != 1) {
            return ValidationResult.invalid("ASCEND_INVALID_GEOMETRY");
        }
        
        // D-026 合法位置集：接受 from 或 to（含"已在目标"的幂等情形）
        BlockPos feet = MovementHelper.footCell(context.level(), context.bot());
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("ASCEND_STALE_START");
        }
        
        // 验证目标方块可通行
        if (!MovementHelper.canWalkThrough(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())
                || !MovementHelper.canWalkOn(context.level(), to)) {
            return ValidationResult.invalid(describe("ASCEND_INVALID_PRECONDITION", context.level(),
                    "to", to, "to.up", to.above(), "to.down", to.below()));
        }

        // 验证起点头部空间（跳跃需要）
        if (!MovementHelper.canWalkThrough(context.level(), from.above(2))) {
            return ValidationResult.invalid(describe("ASCEND_NO_HEADROOM", context.level(),
                    "from", from, "from.up", from.above(), "from.up2", from.above(2),
                    "from.up3", from.above(3)));
        }

        // 对照 Baritone MovementAscend:96-108：源头上方 3 格的 FallingBlock 会砸到 bot（可能窒息）
        BlockState srcUp2 = context.level().getBlockState(from.above(2));
        BlockState srcUp3 = context.level().getBlockState(from.above(3));
        if (srcUp3.getBlock() instanceof FallingBlock
                && (MovementHelper.canWalkThrough(context.level(), from.above(1))
                    || !(srcUp2.getBlock() instanceof FallingBlock))) {
            return ValidationResult.invalid("ASCEND_FALLING_BLOCK_ABOVE");
        }

        // 对照 Baritone MovementAscend:115-117：站在可攀爬方块上无法起跳
        if (MovementHelper.isClimbable(context.level().getBlockState(from.below()))) {
            return ValidationResult.invalid("ASCEND_FROM_CLIMBABLE");
        }

        return ValidationResult.accepted();
    }

    /**
     * **失败码 + 可判读几何**（2026-09-14，用户批准的 (甲)）。
     *
     * <p>为什么要带几何：`ASCEND_NO_HEADROOM` 在 `place_course+wall` 上**单次**变红（此前 13 轮连续 PASS），
     * 而日志只有"码 + 起始脚位"，**分不出**三种可能：① 场景没建好（墙/台阶真在那一格）；
     * ② 我们自己的临时放置挡路（`PLACE_*` 段落留下）；③ 时序相位（同一几何、不同时刻的残留方块）。
     * 把**挡路的那一格到底是什么方块**写进失败码，这三种就能在**既有那一行日志**里区分。
     *
     * <p>**格式契约**：`<稳定码>@<label>=<x,y,z>:<block_id>,…` —— `@` **之前**是稳定码
     * （grep/比较仍按前缀用），`@` 之后是读数。**没有**任何代码按整串比较（已核对全仓）。
     * 这是**终态日志**的一部分，不是要回收的临时探针 ⇒ 不留"忘了删的探针"。
     */
    private static String describe(String stableCode, net.minecraft.server.level.ServerLevel level,
                                   Object... labelAndPos) {
        StringBuilder sb = new StringBuilder(stableCode).append('@');
        for (int i = 0; i + 1 < labelAndPos.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            BlockPos pos = (BlockPos) labelAndPos[i + 1];
            sb.append(labelAndPos[i]).append('=').append(pos.toShortString()).append(':')
                    .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(level.getBlockState(pos).getBlock()));
        }
        return sb.toString();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException("Cannot create AscendExecution: " + result.failureCode());
        }
        return new AscendExecution(spec, context);
    }
}

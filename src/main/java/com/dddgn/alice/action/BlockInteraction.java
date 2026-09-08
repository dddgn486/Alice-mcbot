package com.dddgn.alice.action;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 可复用的方块交互原语（对齐 Baritone）。
 *
 * <p>这是挖掘/放置语义的**唯一入口**：工具选择、触及距离、朝向、破坏进度、放置面选择。
 * 现有 legacy 的 {@code level.destroyBlock(...)} 瞬间销毁调用点应逐步改走本层。
 *
 * <p>参考 Baritone：
 * <ul>
 *   <li>工具选择 {@code MovementHelper.switchToBestToolFor:628-643}；</li>
 *   <li>放置面选择 {@code MovementHelper.attemptToPlaceABlock:791-843}
 *       （直放 → 扫 5 个邻面（水平+下，不含上）→ 算面中心 → 视线校验 → 使用物品）；</li>
 *   <li>破坏 {@code Movement.prepared:153-193} + 原版 {@code ServerPlayerGameMode} 进度。</li>
 * </ul>
 */
public final class BlockInteraction {

    /** 放置候选支撑面方向（Baritone：水平 + 下，不含上）。 */
    private static final List<Direction> SUPPORT_SIDES = List.of(
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN);

    /** 优先使用的"一次性"方块（Baritone selectThrowawayForLocation 的等价简化）。 */
    private static final List<net.minecraft.world.level.block.Block> THROWAWAY = List.of(
            net.minecraft.world.level.block.Blocks.DIRT,
            net.minecraft.world.level.block.Blocks.COBBLESTONE,
            net.minecraft.world.level.block.Blocks.STONE,
            net.minecraft.world.level.block.Blocks.NETHERRACK,
            net.minecraft.world.level.block.Blocks.ANDESITE,
            net.minecraft.world.level.block.Blocks.DIORITE,
            net.minecraft.world.level.block.Blocks.GRANITE);

    public enum PlaceResult { PLACED, NO_OPTION }

    private BlockInteraction() {
    }

    // ==================== 触及与朝向 ====================

    /** 方块触及距离（Forge 玩家默认 4.5）。 */
    public static double blockReach(ServerPlayer bot) {
        return bot.getBlockReach();
    }

    /** 是否在触及距离内（Baritone RotationUtils.reachable 的距离部分）。 */
    public static boolean reachable(ServerPlayer bot, BlockPos pos) {
        return bot.getEyePosition().distanceTo(pos.getCenter()) <= blockReach(bot) + 0.5D;
    }

    /** 朝向方块的面（Baritone 用 Direction.getNearest(eye→center)）。 */
    public static Direction faceToward(ServerPlayer bot, BlockPos pos) {
        Vec3 eye = bot.getEyePosition();
        return Direction.getNearest(
                pos.getX() + 0.5D - eye.x,
                pos.getY() + 0.5D - eye.y,
                pos.getZ() + 0.5D - eye.z);
    }

    /** 转身面向方块中心；头/身/俯仰一起写，避免头身不一致。 */
    public static void faceBlock(ServerPlayer bot, BlockPos pos) {
        faceTowards(bot, pos.getCenter());
    }

    /** 面向任意世界坐标点（放置面中心用）。 */
    public static void faceTowards(ServerPlayer bot, Vec3 target) {
        Vec3 eye = bot.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setXRot(pitch);
    }

    // ==================== 工具 ====================

    /** 快捷栏中对该方块破坏速度最快的槽位；无法破坏（速度 0）返回 -1。 */
    public static int findBestToolSlot(ServerPlayer bot, BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        Inventory inventory = bot.getInventory();
        int bestSlot = -1;
        float bestSpeed = 0.0F;
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            float speed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSpeed > 0.0F ? bestSlot : -1;
    }

    /** 切换到挖掘该方块的最佳工具（空手速度视为 1.0）。 */
    public static void switchToBestToolFor(ServerPlayer bot, BlockPos pos) {
        int slot = findBestToolSlot(bot, pos);
        if (slot >= 0) {
            bot.getInventory().selected = slot;
        }
    }

    // ==================== 放置 ====================

    /** 该方块能否作为放置支撑面（实心、非空气、非流体）。 */
    public static boolean isSolidForPlacement(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    /** 快捷栏中可放置的方块槽位：优先一次性方块，其次任意 BlockItem。 */
    public static int findPlaceableSlot(ServerPlayer bot) {
        Inventory inventory = bot.getInventory();
        int fallback = -1;
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (THROWAWAY.contains(blockItem.getBlock())) {
                return slot;
            }
            if (fallback < 0) {
                fallback = slot;
            }
        }
        return fallback;
    }

    /**
     * 在 {@code placeAt} 放置一个方块（对齐 Baritone attemptToPlaceABlock）。
     *
     * <p>流程：触及检查 → 选可放置方块 → 扫水平+下的邻面找支撑 → 计算面中心 →
     * 视线校验（命中该支撑块且面朝 placeAt）→ 转向 → 使用物品。
     *
     * @return {@link PlaceResult#PLACED} 表示已发起放置；{@link PlaceResult#NO_OPTION} 表示不可行
     */
    public static PlaceResult placeAt(ServerPlayer bot, ServerLevel level, BlockPos placeAt, boolean sneak) {
        if (!reachable(bot, placeAt)) {
            return PlaceResult.NO_OPTION;
        }
        int slot = findPlaceableSlot(bot);
        if (slot < 0) {
            return PlaceResult.NO_OPTION;
        }
        for (Direction supportSide : SUPPORT_SIDES) {
            BlockPos against = placeAt.relative(supportSide);
            if (!isSolidForPlacement(level, against)) {
                continue;
            }
            Direction clickFace = supportSide.getOpposite();
            Vec3 faceCenter = Vec3.atCenterOf(against)
                    .add(Vec3.atLowerCornerOf(clickFace.getNormal()).scale(0.5D));
            if (!faceVisible(bot, level, against, clickFace, faceCenter)) {
                continue;
            }
            faceTowards(bot, faceCenter);
            if (sneak) {
                bot.setShiftKeyDown(true);
            }
            bot.getInventory().selected = slot;
            ItemStack stack = bot.getInventory().getItem(slot);
            BlockHitResult hit = new BlockHitResult(faceCenter, clickFace, against, false);
            bot.gameMode.useItemOn(bot, level, stack, InteractionHand.MAIN_HAND, hit);
            bot.swing(InteractionHand.MAIN_HAND);
            return PlaceResult.PLACED;
        }
        return PlaceResult.NO_OPTION;
    }

    /** 从眼睛到面中心的光线是否命中该支撑块的那一面（Baritone 的 rayTraceTowards 校验）。 */
    private static boolean faceVisible(ServerPlayer bot, ServerLevel level, BlockPos against,
                                       Direction clickFace, Vec3 faceCenter) {
        HitResult hit = level.clip(new ClipContext(bot.getEyePosition(), faceCenter,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            return false;
        }
        return blockHit.getBlockPos().equals(against) && blockHit.getDirection() == clickFace;
    }

    // ==================== 安全传送（夹具/摆位） ====================

    /**
     * 夹具用安全传送：只有当 bot 当前位置到目标脚位的直线（脚位+头位）完全无方块阻挡，
     * 且目标脚位/头位为空时才会传送。
     *
     * <p>原版 {@code teleportTo} 不做碰撞检查（等同 {@code /tp}），会把 bot 穿过墙体；
     * 本方法保证**夹具不会把 bot 送穿方块**。被阻挡时返回 false，调用方应提示用户
     * 先把 bot 带到起点附近。
     */
    public static boolean teleportSafely(ServerPlayer bot, ServerLevel level, BlockPos targetFoot,
                                         float yRot, float xRot) {
        BlockPos from = bot.blockPosition();
        if (!positionFree(level, targetFoot)) {
            return false;
        }
        if (!lineClear(level, from, targetFoot)) {
            return false;
        }
        bot.teleportTo(level, targetFoot.getX() + 0.5D, targetFoot.getY(),
                targetFoot.getZ() + 0.5D, java.util.Set.of(), yRot, xRot);
        return true;
    }

    /** 脚位与头位都必须无碰撞。 */
    private static boolean positionFree(ServerLevel level, BlockPos foot) {
        return level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()
                && level.getBlockState(foot.above())
                        .getCollisionShape(level, foot.above()).isEmpty();
    }

    /** 两点之间按 0.25 步长采样脚位与头位，全部无碰撞才算直线通畅。 */
    private static boolean lineClear(ServerLevel level, BlockPos from, BlockPos to) {
        int steps = (int) Math.ceil(from.distSqr(to) == 0 ? 1.0D
                : Math.sqrt(from.distSqr(to)) / 0.25D);
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0.0D : (double) i / steps;
            double x = from.getX() + 0.5D + (to.getX() - from.getX()) * t;
            double y = from.getY() + (to.getY() - from.getY()) * t;
            double z = from.getZ() + 0.5D + (to.getZ() - from.getZ()) * t;
            BlockPos foot = BlockPos.containing(x, y, z);
            if (!positionFree(level, foot)) {
                return false;
            }
        }
        return true;
    }

    // ==================== 破坏 ====================

    /**
     * 破坏拒绝原因（null = 允许）。清障语义比"明确目标"更保守：
     * 脚下承重块、保护区、不可破坏方块、高代价方块都会被拒绝。
     */
    public static String breakRefusal(ServerPlayer bot, ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).isAir()) {
            return "already_air";
        }
        return com.dddgn.alice.protection.BlockBreakSafety.clearingRefusal(bot, pos);
    }

    /** 该方块能否被本 bot 破坏（清障语义）。 */
    public static boolean breakable(ServerPlayer bot, ServerLevel level, BlockPos pos) {
        return breakRefusal(bot, level, pos) == null;
    }

    /**
     * 破坏该方块的预计 tick 数（对照 Baritone {@code MovementHelper.getMiningDurationTicks}）。
     * 用于规划期成本，不修改世界。
     */
    public static double estimateBreakTicks(ServerPlayer bot, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return 0.0D;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return Double.POSITIVE_INFINITY;
        }
        if (hardness == 0.0F) {
            return 1.0D;
        }
        int slot = findBestToolSlot(bot, pos);
        ItemStack stack = slot >= 0 ? bot.getInventory().getItem(slot) : ItemStack.EMPTY;
        float speed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);
        boolean canHarvest = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        double seconds = canHarvest
                ? (double) hardness * 1.5D / Math.max(speed, 1.0E-4F)
                : (double) hardness * 5.0D / Math.max(speed, 1.0E-4F);
        return Math.max(1.0D, seconds * 20.0D);
    }

    /** 开启一个按 tick 推进的破坏会话（推荐路径）。 */
    public static BlockBreakSession beginBreak(ServerPlayer bot, ServerLevel level, BlockPos pos) {
        return BlockBreakSession.begin(bot, level, pos);
    }

    /**
     * 批量地形编辑用：立即销毁方块（道路施工等非寻路场景）。
     *
     * <p>语义与 {@code level.destroyBlock} 相同，但集中到本层以便统一审计：
     * 调用方必须先做权限/保护区检查（{@code BlockBreakSafety}）。
     */
    public static void breakForBulkEdit(ServerPlayer bot, ServerLevel level, BlockPos pos, boolean dropItems) {
        level.destroyBlock(pos, dropItems, bot);
    }
}

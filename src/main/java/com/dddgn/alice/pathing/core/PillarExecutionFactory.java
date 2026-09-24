package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

/**
 * {@link MovementType#PILLAR}（垂直上升 1 格）的工厂与校验。
 *
 * <p>对照 Baritone {@code MovementPillar.cost}（baritone-1.20.1 @8c55ad0
 * {@code movements/MovementPillar.java:62-124}）：
 * <ul>
 *   <li>放置格（{@code src}）必须可放置：脚下有支撑面 + 快捷栏有一次性方块；</li>
 *   <li>目标格与头顶格必须净空（Alice 最小闭环不做"破头顶方块"分支，见 D-055 偏离记录）；
 *       ⭐ 口径 = `MovementHelper.bodyPassable(to)`（与规划侧 `appendPillar` **同一个谓词**）；</li>
 *   <li>脚下支撑不能是底部半砖（Baritone 同款 COST_INF 条件）。</li>
 * </ul>
 *
 * <p>⭐ **起跳门控只对"脚位不是水"成立**（`P2` Pillar 片 / `D-427`）：Baritone 的
 * {@code MovementPillar.cost} **从不**查 {@code onGround}（它查的是 `fromDown` 是不是底部半砖/梯子、
 * 水柱那支直接给 `LADDER_UP_ONE_COST`）；Alice 这条守卫是自加的，只对陆地那支有意义 ——
 * 水里按住跳跃即上浮（`D-243`），水柱支更是全程 `onGround` 恒假（`D-244`）。
 */
public final class PillarExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.movement.pillar";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec.movementType() == MovementType.PILLAR;
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("PILLAR_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("PILLAR_MISSING_CONTEXT");

        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        if (to.getY() != from.getY() + 1 || to.getX() != from.getX() || to.getZ() != from.getZ()) {
            return ValidationResult.invalid("PILLAR_INVALID_GEOMETRY");
        }

        // D-026 合法位置集
        BlockPos feet = MovementHelper.footCell(context.level(), context.bot());
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("PILLAR_STALE_START");
        }

        // 起跳列净空（身体 + 头）—— 用**规划侧同一个谓词** `bodyPassable`（K-4/D-374）；
        // `appendPillar` 的准入就是 `bodyPassable(level, to)`，两侧别各自手搓一次 `canWalkThrough`
        // （手搓 = 只查脚位不查头位的那种漂移形态，D-374 的事故就是这么来的）。
        if (!MovementHelper.bodyPassable(context.level(), to)) {
            return ValidationResult.invalid("PILLAR_HEAD_BLOCKED");
        }
        if (!MovementHelper.canWalkThrough(context.level(), from)) {
            return ValidationResult.invalid("PILLAR_PLACE_OCCUPIED");
        }

        // 起跳门控：**只有"脚位不是水"时才要求站在地面**（`D-243`/`D-244`）。
        // 水里按住跳跃＝上浮，原版不查 `onGround`；`D-244` 的水柱支（from/to 都是水）更是全程
        // `onGround` 恒假 —— 这一点由执行侧自己写着（`PillarExecution.postconditionHolds` 水柱支：
        // 「水里没有 `onGround`、也没有支撑」，`preconditionsHold()` 因此**故意**不查它）。
        // ⚠️ 这条守卫原先是**无条件**的 ⇒ 灌水竖井只要 ≥3 格（= 需要 2 段以上 `PILLAR`），
        // **第 2 段起**全在**准入**处被 `PILLAR_NOT_ON_GROUND` 挡下 = 又一次「规划得到、执行不了」
        // （`D-242` 家族的残余）。真机证据 = `docs/reviews/2026-09-21-掉落物在洞里被瞬退.md:554`
        // 的 `PILLAR_NOT_ON_GROUND` ×11（现场：破掉脚下 → 落水 → 沉底）；`D-244` 的夹具
        // `FLOODED_SHAFT` 只有 **2 格水** = 恰好 **1 段**水柱 ⇒ 这条缝从来没被量到。
        // 口径按**脚位那一格**（与执行器 `tick()` 读的 `footCell` 同源）：水 ⇒ 不需要地面。
        if (!MovementHelper.isWater(context.level(), feet) && !context.bot().onGround()) {
            return ValidationResult.invalid("PILLAR_NOT_ON_GROUND");
        }

        // 放置资源与支撑面（Baritone：context.costOfPlacingAt + canPlaceAgainst）
        if (BlockInteraction.findPlaceableSlot(context.bot()) < 0) {
            return ValidationResult.invalid("PLACE_RESOURCE_UNAVAILABLE");
        }
        if (!BlockInteraction.hasPlacementFace(context.level(), from)) {
            return ValidationResult.invalid("PLACE_NO_VALID_FACE");
        }

        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException("Cannot create PillarExecution: " + result.failureCode());
        }
        return new PillarExecution(spec, context);
    }
}

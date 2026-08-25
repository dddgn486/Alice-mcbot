package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.SoftMovementPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * F1-F6 服务端物理断言 fixture（bot 物理失效定案前置，只收集证据不判定根因）。
 *
 * 覆盖：
 * - F1: bot.hurt(...) 后读 getDeltaMovement() —— hurt/knockback 链是否触发（generic + 真实生物攻击对照）
 * - F3: bot.push(...) 后读 delta+位移 —— push 写入路径；碰撞箱内实体推挤链（aiStep/pushEntities 运行佐证）
 * - F4: travel 前后 delta 摩擦衰减（约 0.91 缩放）+ 伴随 tick 链完整性（horizontalCollision/verticalCollisionBelow）
 * - F5: 同 tick vs 延迟 1 tick travel 时序对照
 * - F2: 击退后延迟 travel 位移保留（包含击退分量）
 *
 * fixture 内自清理：hurt 后恢复 health、push/travel 后归位与清 delta、zombie discard、不写档。
 * 日志字段按 evidence-collection-standard：corr/tick/delta_x/y/z/health/horizontalCollision/verticalCollisionBelow。
 * 本 fixture 只产出服务端证据，根因判定由监督员按区分表执行，不写根因结论。
 */
public final class BotPhysicsAssertionFixture {
    private static final double EPS = 0.001D;

    private BotPhysicsAssertionFixture() {
    }

    public static boolean runF1F6(ServerLevel level, BotPlayer bot) {
        String corr = java.util.UUID.randomUUID().toString().substring(0, 8);
        int tick = level.getServer() == null ? 0 : level.getServer().getTickCount();
        float health0 = bot.getHealth();
        Vec3 pos0 = bot.position();
        Vec3 delta0 = bot.getDeltaMovement();

        prepareGround(level, bot.blockPosition());

        boolean f1 = assertF1Hurt(level, bot, corr, tick, health0);
        boolean f3 = assertF3Push(level, bot, corr, tick);
        boolean f4 = assertF4Friction(level, bot, corr, tick);
        boolean tickChain = assertTickChain(level, bot, corr, tick);
        boolean f5 = assertF5Timing(level, bot, corr, tick);
        boolean f2 = assertF2DelayedTravel(level, bot, corr, tick);

        // fixture 自清理：恢复 health、归位、清 delta、不写档
        bot.setHealth(health0);
        bot.teleportTo(pos0.x, pos0.y, pos0.z);
        bot.setDeltaMovement(Vec3.ZERO);

        boolean pass = f1 && f3 && f4 && f5 && f2 && tickChain;
        BotLog.info("BOT_PHYSICS_ASSERTION_SUITE {} corr={} tick={} f1={} f3={} f4={} f5={} f2={} tickChain={}",
                pass ? "PASS" : "FAIL", corr, tick, f1, f3, f4, f5, f2, tickChain);
        return pass;
    }

    /** F1: hurt → deltaMovement（hurt/knockback 链是否触发；generic 与 mobAttack 双源对照）。 */
    private static boolean assertF1Hurt(ServerLevel level, BotPlayer bot, String corr, int tick, float health0) {
        // 源 1：generic 伤害（任务书主例）
        bot.setDeltaMovement(Vec3.ZERO);
        float h1 = bot.getHealth();
        boolean hurtOk = bot.hurt(level.damageSources().generic(), 1.0F);
        Vec3 d1 = bot.getDeltaMovement();
        float h2 = bot.getHealth();
        boolean triggered1 = hurtOk
                && (d1.horizontalDistance() > EPS || Math.abs(d1.y) > EPS);

        // 源 2：真实生物攻击（Zombie doHurtTarget 带 knockback 路径）
        bot.setDeltaMovement(Vec3.ZERO);
        bot.hurt(level.damageSources().generic(), 1.0F); // 复位伤害链无关项
        bot.setDeltaMovement(Vec3.ZERO);
        Zombie attacker = new Zombie(EntityType.ZOMBIE, level);
        attacker.setPos(bot.getX(), bot.getY() + 1.5D, bot.getZ());
        level.addFreshEntity(attacker);
        attacker.setTarget(bot);
        float h3 = bot.getHealth();
        boolean mobHurtOk = attacker.doHurtTarget(bot);
        Vec3 d2 = bot.getDeltaMovement();
        float h4 = bot.getHealth();
        attacker.discard();
        boolean triggered2 = mobHurtOk
                && (d2.horizontalDistance() > EPS || Math.abs(d2.y) > EPS);

        boolean result = triggered1 || triggered2;
        BotLog.info("BOT_PHYSICS_F1 corr={} tick={} result={} generic={} gHurtOk={} d1=({},{},{}) health={}->{} "
                        + "mob={} mHurtOk={} d2=({},{},{}) health={}->{}",
                corr, tick, result ? "PASS" : "FAIL", triggered1, hurtOk,
                fmt(d1.x), fmt(d1.y), fmt(d1.z), fmt(h1), fmt(h2),
                triggered2, mobHurtOk, fmt(d2.x), fmt(d2.y), fmt(d2.z), fmt(h3), fmt(h4));
        bot.setHealth(health0);
        bot.setDeltaMovement(Vec3.ZERO);
        return result;
    }

    /** F3: push 写入路径 + 碰撞箱实体推挤链（aiStep/pushEntities 运行佐证）。 */
    private static boolean assertF3Push(ServerLevel level, BotPlayer bot, String corr, int tick) {
        Vec3 anchor = bot.position();
        // 3a: 直接 push 写入 delta
        bot.setDeltaMovement(Vec3.ZERO);
        Vec3 before = bot.getDeltaMovement();
        bot.push(0.5D, 0.0D, 0.0D);
        Vec3 after = bot.getDeltaMovement();
        boolean pushWritten = after.x > before.x + 0.01D || Math.abs(after.z - before.z) > 0.01D;

        // 3b: 碰撞箱内放置 Zombie，观察实体推挤链（需 tick 链运行）
        bot.setDeltaMovement(Vec3.ZERO);
        Zombie pusher = new Zombie(EntityType.ZOMBIE, level);
        pusher.setPos(bot.getX(), bot.getY(), bot.getZ() + 0.5D);
        level.addFreshEntity(pusher);
        Vec3 d0 = bot.getDeltaMovement();
        Vec3 p0 = bot.position();
        bot.setDeltaMovement(Vec3.ZERO);
        bot.tick(); // 驱动实体 tick（aiStep → pushEntities，若 tick 链完整）
        Vec3 d1 = bot.getDeltaMovement();
        Vec3 p1 = bot.position();
        pusher.discard();
        boolean entityPushed = d1.horizontalDistance() > EPS
                || p1.distanceToSqr(p0) > EPS * EPS;

        boolean result = pushWritten && entityPushed;
        BotLog.info("BOT_PHYSICS_F3 corr={} tick={} result={} pushWritten={} "
                        + "pushD=({},{},{}) entityPushed={} botTick delta=({},{},{}) disp=({},{},{})",
                corr, tick, result ? "PASS" : "FAIL", pushWritten,
                fmt(after.x), fmt(after.y), fmt(after.z),
                entityPushed, fmt(d1.x), fmt(d1.y), fmt(d1.z),
                fmt(p1.x - p0.x), fmt(p1.y - p0.y), fmt(p1.z - p0.z));
        bot.teleportTo(anchor.x, anchor.y, anchor.z);
        bot.setDeltaMovement(Vec3.ZERO);
        return result;
    }

    /** F4: travel 前后 delta 摩擦衰减（travel/aiStep 链完整执行；归零属异常清零路径记录）。 */
    private static boolean assertF4Friction(ServerLevel level, BotPlayer bot, String corr, int tick) {
        Vec3 anchor = bot.position();
        bot.setDeltaMovement(new Vec3(0.3D, 0.0D, 0.0D));
        Vec3 dBefore = bot.getDeltaMovement();
        SoftPathProbeTask task = new SoftPathProbeTask(bot, bot.blockPosition().offset(5, 0, 0));
        runTaskTicks(task, 1);
        Vec3 dAfter = bot.getDeltaMovement();
        double ratioH = dBefore.horizontalDistance() > EPS
                ? dAfter.horizontalDistance() / dBefore.horizontalDistance() : 0.0D;
        // 链完整 = travel 后 delta 被处理（非原样非归零）
        boolean chainRan = dAfter.horizontalDistance() > EPS
                && Math.abs(dAfter.horizontalDistance() - dBefore.horizontalDistance()) > EPS;
        boolean result = chainRan;
        BotLog.info("BOT_PHYSICS_F4 corr={} tick={} result={} chainRan={} ratioH={} "
                        + "dBefore=({},{},{}) dAfter=({},{},{})",
                corr, tick, result ? "PASS" : "FAIL", chainRan, fmt(ratioH),
                fmt(dBefore.x), fmt(dBefore.y), fmt(dBefore.z),
                fmt(dAfter.x), fmt(dAfter.y), fmt(dAfter.z));
        bot.teleportTo(anchor.x, anchor.y, anchor.z);
        bot.setDeltaMovement(Vec3.ZERO);
        return result;
    }

    /** F4 伴随断言：墙体撞击后 horizontalCollision/verticalCollisionBelow 变化（tick 链完整性佐证）。 */
    private static boolean assertTickChain(ServerLevel level, BotPlayer bot, String corr, int tick) {
        Vec3 anchor = bot.position();
        BlockPos wallBase = bot.blockPosition().offset(3, 0, 0);
        level.setBlock(wallBase, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(wallBase.above(), Blocks.STONE.defaultBlockState(), 3);
        bot.setYRot(0.0F); // 面向 +X 墙
        bot.setYHeadRot(0.0F);
        boolean hBefore = bot.horizontalCollision;
        boolean vBefore = bot.verticalCollisionBelow;
        bot.setDeltaMovement(Vec3.ZERO);
        bot.push(0.5D, 0.0D, 0.0D);
        bot.tick(); // 撞击墙：若 tick/travel 链完整则 horizontalCollision=true
        boolean hAfter = bot.horizontalCollision;
        boolean vAfter = bot.verticalCollisionBelow;

        level.setBlock(wallBase, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(wallBase.above(), Blocks.AIR.defaultBlockState(), 3);
        boolean result = hAfter != hBefore || vAfter != vBefore;
        BotLog.info("BOT_PHYSICS_TICKCHAIN corr={} tick={} result={} "
                        + "horizontalCollision={}->{} verticalCollisionBelow={}->{}",
                corr, tick, result ? "PASS" : "FAIL",
                hBefore, hAfter, vBefore, vAfter);
        bot.teleportTo(anchor.x, anchor.y, anchor.z);
        bot.setDeltaMovement(Vec3.ZERO);
        return result;
    }

    /** F5: 同 tick vs 延迟 1 tick travel 时序对照。 */
    private static boolean assertF5Timing(ServerLevel level, BotPlayer bot, String corr, int tick) {
        Vec3 anchor = bot.position();
        // 同 tick：击退注入后立即任务 travel
        bot.teleportTo(anchor.x, anchor.y, anchor.z);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.knockback(1.0D, 1.0D, 0.0D);
        Vec3 p0 = bot.position();
        SoftPathProbeTask t1 = new SoftPathProbeTask(bot, bot.blockPosition().offset(5, 0, 0));
        runTaskTicks(t1, 1);
        double dSame = bot.position().distanceTo(p0);

        // 延迟 1 tick：击退注入 → settle 结算一个物理 tick → 再任务 travel
        bot.teleportTo(anchor.x, anchor.y, anchor.z);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.knockback(1.0D, 1.0D, 0.0D);
        Vec3 p1 = bot.position();
        SoftMovementPrimitive.settle(bot); // 该 tick 物理结算（击退自由作用+摩擦）
        double dKeep = bot.position().distanceTo(p1);
        SoftPathProbeTask t2 = new SoftPathProbeTask(bot, bot.blockPosition().offset(5, 0, 0));
        runTaskTicks(t2, 1);
        double dDelayed = bot.position().distanceTo(p1);

        boolean result = !Double.isNaN(dSame) && !Double.isNaN(dDelayed)
                && (dSame > EPS || dDelayed > EPS);
        BotLog.info("BOT_PHYSICS_F5 corr={} tick={} result={} dSame={} dKeep={} dDelayed={} "
                        + "delayExtra={}",
                corr, tick, result ? "PASS" : "FAIL", fmt(dSame), fmt(dKeep), fmt(dDelayed),
                fmt(dDelayed - dSame));
        bot.teleportTo(anchor.x, anchor.y, anchor.z);
        bot.setDeltaMovement(Vec3.ZERO);
        return result;
    }

    /** F2: 击退后延迟 1 tick travel 位移保留（包含击退分量 vs 无击退基线）。 */
    private static boolean assertF2DelayedTravel(ServerLevel level, BotPlayer bot, String corr, int tick) {
        Vec3 anchor = bot.position();
        // 基线：无击退直接任务 travel
        bot.teleportTo(anchor.x, anchor.y, anchor.z);
        bot.setDeltaMovement(Vec3.ZERO);
        Vec3 base0 = bot.position();
        SoftPathProbeTask base = new SoftPathProbeTask(bot, bot.blockPosition().offset(5, 0, 0));
        runTaskTicks(base, 1);
        double dBase = bot.position().distanceTo(base0);

        // 击退后延迟 travel（含自由结算 tick）
        bot.teleportTo(anchor.x, anchor.y, anchor.z);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.knockback(1.0D, 1.0D, 0.0D);
        SoftMovementPrimitive.settle(bot);
        Vec3 k0 = bot.position();
        SoftPathProbeTask task = new SoftPathProbeTask(bot, bot.blockPosition().offset(5, 0, 0));
        runTaskTicks(task, 1);
        double dKnock = bot.position().distanceTo(k0);

        boolean result = Math.abs(dKnock - dBase) > 0.01D;
        BotLog.info("BOT_PHYSICS_F2 corr={} tick={} result={} dBase={} dKnock={} keepExtra={}",
                corr, tick, result ? "PASS" : "FAIL", fmt(dBase), fmt(dKnock), fmt(dKnock - dBase));
        bot.teleportTo(anchor.x, anchor.y, anchor.z);
        bot.setDeltaMovement(Vec3.ZERO);
        return result;
    }

    /** 复用 SoftPhysicsObservationTest 的平地/清理模式（不修改其文件）。 */
    private static void prepareGround(ServerLevel level, BlockPos start) {
        for (int x = -5; x <= 15; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = 0; y <= 5; y++) {
                    level.setBlock(start.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlock(start.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    private static void runTaskTicks(Task task, int ticks) {
        for (int i = 0; i < ticks; i++) {
            Task.Status status = task.tick();
            if (status == Task.Status.FAILED || status == Task.Status.DONE) {
                break;
            }
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
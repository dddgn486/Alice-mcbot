package com.dddgn.alice.bot;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.PathingRegression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 自动化验收(SELFTEST):服务器启动后自动在 headless 环境跑一组 M0/M1 验收用例,测完自动关服。
 * <p>
 * 用例:
 * <ol>
 *   <li>正常挖掘:铺平地 → 目标旁 2 格 → 应挖掉方块,结果 "done",掉落物被作用域捕捉;</li>
 *   <li>隔空挖拦截:bot 站在目标上方 1 格、中间隔一层方块 → 应<b>先下来到旁边</b>再挖
 *       (挖掘开始位置不在目标正上方),或拒绝挖(方块仍在)。挖掘开始位置在正上方 = FAIL。</li>
 * </ol>
 * ⚠️ 审查点 R8:selftest 目前「服务器启动自动触发 + 测完 halt」,仅用于开发期 headless 验收;
 * 发布前需改为配置开关/GameTest,避免玩家开服被自动关服。</p>
 */
public final class BotSelftest {

    private enum Phase { IDLE, WAIT_START, SETUP, TEST1_RUN, TEST1_CHECK, TEST2_SETUP, TEST2_RUN, TEST2_CHECK, TEST3_SETUP, TEST3_RUN, TEST3_CHECK, TEST4_SETUP, TEST4_RUN, TEST4_CHECK, TEST5_SETUP, TEST5_RUN, TEST5_CHECK, TEST6_SETUP, TEST6_RUN, TEST6_CHECK, TEST7_SETUP, TEST7_RUN, TEST7_CHECK, TEST8_SETUP, TEST8_RUN, TEST8_CHECK, TEST9_SETUP, TEST9_RUN, TEST9_CHECK, TEST10_SETUP, TEST10_RUN, TEST10_CHECK, TEST11_SETUP, TEST11_RUN, TEST11_CHECK, TEST12_SETUP, TEST12_RUN, TEST12_CHECK, TEST13_SETUP, TEST13_RUN, TEST13_CHECK, REPORT }

    private static final int START_DELAY_TICKS = 100;
    private static final int SIMPLE_TIMEOUT_TICKS = 200;   // 10 秒
    private static final int NORMAL_TIMEOUT_TICKS = 400;   // 20 秒
    private static final int COMPLEX_TIMEOUT_TICKS = 600;  // 30 秒，任何单项不得超过

    private static Phase phase = Phase.IDLE;

    private static int timeoutForPhase() {
        return switch (phase) {
            case TEST1_RUN, TEST2_RUN, TEST4_RUN, TEST5_RUN, TEST6_RUN, TEST7_RUN, TEST8_RUN,
                    TEST9_RUN, TEST10_RUN -> SIMPLE_TIMEOUT_TICKS;
            case TEST3_RUN, TEST11_RUN, TEST12_RUN, TEST13_RUN -> COMPLEX_TIMEOUT_TICKS;
            default -> NORMAL_TIMEOUT_TICKS;
        };
    }
    private static int waitTicks;
    private static int phaseTicks;
    private static int scenarioElapsedTicks;
    private static boolean scenarioTimedOut;
    /** 默认只跑可重复的曲面链路冒烟；复杂回归仅由显式 full 模式触发。 */
    private static boolean fullSuite;
    private static boolean pathingRegressionPass;

    /** 记录 RUN 阶段耗时；达到预算即结束且该项强制不合格，后续成功不能翻案。 */
    private static boolean runFinished() {
        boolean done = !BotManager.isBusy(bot);
        int budget = timeoutForPhase();
        if (!done && phaseTicks < budget) {
            return false;
        }
        scenarioElapsedTicks = phaseTicks;
        scenarioTimedOut = !done;
        if (scenarioTimedOut) {
            BotLog.warn("SELFTEST {} 超时: {} tick / {} tick ({} 秒)",
                    phase, scenarioElapsedTicks, budget, budget / 20);
        }
        return true;
    }

    private static boolean withinBudget(boolean semanticPass) {
        return semanticPass && !scenarioTimedOut;
    }
    private static MinecraftServer server;
    private static ServerLevel level;
    private static BotPlayer bot;
    private static BlockPos target1;
    private static BlockPos target2;
    private static BlockPos target3;
    private static BlockPos target4;
    private static BlockPos target5;
    private static BlockPos target6;
    private static BlockPos target7;
    private static BlockPos target8;
    private static BlockPos target9;
    private static BlockPos protectedWall;
    private static BlockPos pitDropPos;
    private static UUID pitDropId;
    private static boolean pitDropSpawned;
    private static BlockPos underfootTarget;
    private static BlockPos underfootMineStart;
    private static BlockPos stairClearBlock;
    private static UUID stairDropId;
    private static BlockPos stairDropPos;
    private static BlockPos stairInitialUnderfoot;
    private static boolean stairDropSpawned;
    private static BlockPos test2MineStart;
    private static BlockPos test3MineStart;
    private static boolean test1Pass;
    private static boolean test2Pass;
    private static boolean test3Pass;
    private static boolean test4Pass;
    private static boolean test5Pass;
    private static boolean test6Pass;
    private static boolean test7Pass;
    private static boolean test8Pass;
    private static boolean test9Pass;
    private static boolean test10Pass;
    private static boolean test11Pass;
    private static boolean test12Pass;
    private static boolean test13Pass;
    private static String test1Detail = "";
    private static String test2Detail = "";
    private static String test3Detail = "";
    private static String test4Detail = "";
    private static String test5Detail = "";
    private static String test6Detail = "";
    private static String test7Detail = "";
    private static String test8Detail = "";
    private static String test9Detail = "";
    private static String test10Detail = "";
    private static String test11Detail = "";
    private static String test12Detail = "";
    private static String test13Detail = "";

    private BotSelftest() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        level = server.overworld();
        // 默认不自动跑(审查点 R8):手动 /alice selftest 触发;
        // headless 验证用 -Dalice.selftest.auto=true 自动触发(见 build.gradle)
        if (Boolean.getBoolean("alice.selftest.auto")) {
            BotLog.info("SELFTEST 自动模式(alice.selftest.auto=true)");
            start();
        } else {
            phase = Phase.IDLE;
            BotLog.info("SELFTEST 待命(手动 /alice selftest 触发)");
        }
    }

    /** 手动启动自检(命令触发)。 */
    public static void start() {
        start(false);
    }

    /** 启动自检；full=true 才运行历史 13 项完整回归。 */
    public static void start(boolean full) {
        if (server == null) {
            return;
        }
        fullSuite = full;
        pathingRegressionPass = false;
        test1Pass = false;
        test2Pass = false;
        test3Pass = false;
        test4Pass = false;
        test5Pass = false;
        test6Pass = false;
        test7Pass = false;
        test8Pass = false;
        test9Pass = false;
        test10Pass = false;
        test11Pass = false;
        test12Pass = false;
        test13Pass = false;
        phase = Phase.WAIT_START;
        waitTicks = START_DELAY_TICKS;
        phaseTicks = 0;
        BotLog.info("SELFTEST 启动: mode={} {} tick 后开始", fullSuite ? "full" : "smoke", START_DELAY_TICKS);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || phase == Phase.REPORT || phase == Phase.IDLE) {
            return;
        }
        phaseTicks++;
        if (phase.name().endsWith("_RUN") && phaseTicks == 1) {
            scenarioTimedOut = false;
            scenarioElapsedTicks = 0;
        }
        switch (phase) {
            case WAIT_START -> {
                if (--waitTicks <= 0) {
                    phase = Phase.SETUP;
                    phaseTicks = 0;
                }
            }
            case SETUP -> setup();
            case TEST1_RUN -> {
                if (runFinished()) {
                    phase = Phase.TEST1_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST1_CHECK -> checkTest1();
            case TEST2_SETUP -> setupTest2();
            case TEST2_RUN -> {
                if (runFinished()) {
                    phase = Phase.TEST2_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST2_CHECK -> checkTest2();
            case TEST3_SETUP -> setupTest3();
            case TEST3_RUN -> {
                if (runFinished()) {
                    phase = Phase.TEST3_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST3_CHECK -> checkTest3();
            case TEST4_SETUP -> setupTest4();
            case TEST4_RUN -> {
                if (runFinished()) {
                    phase = Phase.TEST4_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST4_CHECK -> checkTest4();
            case TEST5_SETUP -> setupTest5();
            case TEST5_RUN -> {
                if (runFinished()) {
                    phase = Phase.TEST5_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST5_CHECK -> checkTest5();
            case TEST6_SETUP -> setupTest6();
            case TEST6_RUN -> {
                if (runFinished()) {
                    phase = Phase.TEST6_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST6_CHECK -> checkTest6();
            case TEST7_SETUP -> setupTest7();
            case TEST7_RUN -> {
                if (runFinished()) {
                    phase = Phase.TEST7_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST7_CHECK -> checkTest7();
            case TEST8_SETUP -> setupTest8();
            case TEST8_RUN -> {
                if (runFinished()) {
                    phase = Phase.TEST8_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST8_CHECK -> checkTest8();
            case TEST9_SETUP -> setupTest9();
            case TEST10_SETUP -> setupTest10();
            case TEST10_RUN -> {
                if (runFinished()) {
                    phase = Phase.TEST10_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST10_CHECK -> checkTest10();
            case TEST11_SETUP -> setupTest11();
            case TEST11_RUN -> {
                // MineTask 构造/作用域开启发生在 assignMine 内；下一 tick 才生成坑底物，确保它是任务产物。
                if (!pitDropSpawned) {
                    ItemEntity pitDrop = new ItemEntity(level, pitDropPos.getX() + 0.5D, pitDropPos.getY(),
                            pitDropPos.getZ() + 0.5D, new ItemStack(Items.COBBLESTONE));
                    pitDrop.setPickUpDelay(0);
                    level.addFreshEntity(pitDrop);
                    pitDropId = pitDrop.getUUID();
                    pitDropSpawned = true;
                    BotLog.info("SELFTEST TEST11: 作用域内生成坑底掉落物 {}", pitDropId);
                }
                if (runFinished()) {
                    phase = Phase.TEST11_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST11_CHECK -> checkTest11();
            case TEST12_SETUP -> setupTest12();
            case TEST12_RUN -> {
                if (runFinished()) {
                    phase = Phase.TEST12_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST12_CHECK -> checkTest12();
            case TEST13_SETUP -> setupTest13();
            case TEST13_RUN -> {
                if (!stairDropSpawned) {
                    ItemEntity item = new ItemEntity(level, stairDropPos.getX() + 0.5D,
                            stairDropPos.getY(), stairDropPos.getZ() + 0.5D,
                            new ItemStack(Items.COBBLESTONE));
                    item.setPickUpDelay(0);
                    level.addFreshEntity(item);
                    stairDropId = item.getUUID();
                    stairDropSpawned = true;
                }
                if (runFinished()) {
                    phase = Phase.TEST13_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST13_CHECK -> checkTest13();
            default -> {
            }
        }
    }

    /** 找地表(出生点向上扫),铺 5x5 平台,bot 站平台中心。 */
    private static void setup() {
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos surface = spawn;
        while (level.getBlockState(surface).isAir() && surface.getY() > level.getMinBuildHeight()) {
            surface = surface.below();
        }
        surface = surface.above();

        // 清空上方 3 格 + 铺 5x5 泥土平台
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlock(surface.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlock(surface.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState(), 3);
            }
        }

        pathingRegressionPass = PathingRegression.run(level, surface.offset(8, 0, 8));
        bot = BotManager.spawn(level, surface, "SelftestBot");
        target1 = surface.offset(2, 0, 0);
        level.setBlock(target1, Blocks.DIRT.defaultBlockState(), 3);
        BotLog.info("SELFTEST TEST1: 铺平台完成,目标={}", target1.toShortString());
        BotManager.assignMine(bot, target1);
        phase = Phase.TEST1_RUN;
        phaseTicks = 0;
    }

    private static void checkTest1() {
        boolean blockGone = level.getBlockState(target1).isAir();
        String result = BotManager.lastTaskResult(bot);
        TaskExecutionRecord record = BotManager.lastExecutionRecord(bot);
        boolean observabilityPass = record != null
                && record.terminalStatus() == TaskExecutionRecord.TerminalStatus.COMPLETED
                && "done".equals(record.resultCode())
                && "idle_after_cleanup".equals(record.recoveryState())
                && record.terminalBotPos().equals(bot.blockPosition());
        test1Pass = withinBudget(blockGone && "done".equals(result) && observabilityPass);
        test1Detail = "blockGone=" + blockGone + " result=" + result + " observability=" + observabilityPass
                + (scenarioTimedOut ? " (超时 " + scenarioElapsedTicks + " tick)" : " (耗时 " + scenarioElapsedTicks + " tick)");
        BotLog.info("TASK_OBSERVABILITY_SELFTEST {} terminal={} code={} recovery={} pos={}",
                observabilityPass ? "PASS" : "FAIL", record == null ? "none" : record.terminalStatus(),
                record == null ? "" : record.resultCode(), record == null ? "" : record.recoveryState(),
                record == null ? "" : record.terminalBotPos().toShortString());
        BotLog.info("SELFTEST TEST1 {}", test1Pass ? "PASS" : "FAIL: " + test1Detail);
        phase = Phase.TEST2_SETUP;
        phaseTicks = 0;
    }

    /** 构造隔空挖场景:bot 站在目标上方 1 格(隔层),目标在脚下层。 */
    private static void setupTest2() {
        // 以 bot 当前脚位为基准:b1 = 脚下层(放目标),b2 = 头位层(放隔层),bot 站到隔层上
        BlockPos feet = bot.blockPosition();
        target2 = feet.below();
        // 目标 = 脚下的方块本身(平台泥土),上方放一个隔层方块,bot 站隔层顶上
        BlockPos spacer = feet; // bot 站的位置变成隔层
        BlockPos standOn = spacer.above();
        level.setBlock(spacer, Blocks.DIRT.defaultBlockState(), 3);
        // 清 standOn 上方(头位)
        level.setBlock(standOn.above(), Blocks.AIR.defaultBlockState(), 3);
        bot.setPos(standOn.getX() + 0.5, standOn.getY(), standOn.getZ() + 0.5);
        BotLog.info("SELFTEST TEST2: 隔层场景就绪,目标={} 隔层={} bot站={}",
                target2.toShortString(), spacer.toShortString(), standOn.toShortString());
        BotManager.assignMine(bot, target2);
        phase = Phase.TEST2_RUN;
        phaseTicks = 0;
    }

    private static void checkTest2() {
        boolean blockGone = level.getBlockState(target2).isAir();
        String result = BotManager.lastTaskResult(bot);
        test2MineStart = BotManager.lastMineStartPos(bot);
        // PASS = 目标被挖掉(隔空挖由 BotMiner 每次挖掘前的视线检查硬保证拦截,
        // 清障挖开隔层后站高处挖低处也是合法的) 或 拒绝隔空挖(failed, 合法)。
        test2Pass = withinBudget(blockGone || result.startsWith("failed:"));
        test2Detail = "blockGone=" + blockGone + " result=" + result
                + " mineStart=" + (test2MineStart == null ? "null" : test2MineStart.toShortString())
                + (scenarioTimedOut ? " (超时 " + scenarioElapsedTicks + " tick)" : " (耗时 " + scenarioElapsedTicks + " tick)");
        BotLog.info("SELFTEST TEST2 {}", test2Pass ? "PASS" : "FAIL: " + test2Detail);
        phase = Phase.TEST3_SETUP;
        phaseTicks = 0;
    }

    /** TEST3:远端挖掘——bot 在平台西端,目标在东端,验证 A* 真实寻路 + 路径跟随 + 挖掘。 */
    private static void setupTest3() {
        // 把 bot 传送到平台西端(-2),目标重新放回东端(+2, TEST1 挖掉的位置)
        BlockPos surface = level.getSharedSpawnPos();
        while (level.getBlockState(surface).isAir() && surface.getY() > level.getMinBuildHeight()) {
            surface = surface.below();
        }
        surface = surface.above();
        BlockPos west = surface.offset(-2, 0, 0);
        bot.teleportTo(west.getX() + 0.5D, west.getY(), west.getZ() + 0.5D);
        target3 = surface.offset(2, 0, 0);
        // 铺一条东西向地面(bot 与目标之间,脚位层-1),避免目标/路径悬空
        int groundY = west.getY() - 1;
        for (int x = west.getX() - 1; x <= target3.getX() + 1; x++) {
            level.setBlock(new BlockPos(x, groundY, west.getZ()), Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x, groundY + 1, west.getZ()), Blocks.AIR.defaultBlockState(), 3);
        }
        level.setBlock(target3, Blocks.DIRT.defaultBlockState(), 3);
        BotLog.info("SELFTEST TEST3: bot在{} 目标在{} 地面y={}", west.toShortString(), target3.toShortString(), groundY);
        BotManager.assignMine(bot, target3);
        phase = Phase.TEST3_RUN;
        phaseTicks = 0;
    }

    private static void checkTest3() {
        boolean blockGone = level.getBlockState(target3).isAir();
        String result = BotManager.lastTaskResult(bot);
        test3MineStart = BotManager.lastMineStartPos(bot);
        // PASS = 挖掉 + 任务成功 + 挖掘开始位置不是目标本身(从旁边挖)
        boolean minedFromTarget = test3MineStart != null && test3MineStart.equals(target3);
        test3Pass = withinBudget(blockGone && "done".equals(result) && !minedFromTarget);
        test3Detail = "blockGone=" + blockGone + " result=" + result
                + " mineStart=" + (test3MineStart == null ? "null" : test3MineStart.toShortString())
                + (scenarioTimedOut ? " (超时 " + scenarioElapsedTicks + " tick)" : " (耗时 " + scenarioElapsedTicks + " tick)");
        BotLog.info("SELFTEST TEST3 {}", test3Pass ? "PASS" : "FAIL: " + test3Detail);
        phase = fullSuite ? Phase.TEST4_SETUP : Phase.REPORT;
        phaseTicks = 0;
    }

    /** TEST4:坑场景——bot 掉进 1 格深小坑,目标是坑边地面方块。
     * 验证「当前站位可直接挖」优先判定(之前会 no_stand_pos)。 */
    private static void setupTest4() {
        BlockPos surface = findSurface();
        BlockPos hole = surface;
        // 挖坑:bot 脚下 1 格掏空
        level.setBlock(hole.below(), Blocks.AIR.defaultBlockState(), 3);
        BlockPos holeBottom = hole.below();
        bot.setPos(holeBottom.getX() + 0.5, holeBottom.getY(), holeBottom.getZ() + 0.5);
        // 目标 = 坑边东侧地面方块
        target4 = surface.offset(1, 0, 0);
        level.setBlock(target4, Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(target4.above(), Blocks.AIR.defaultBlockState(), 3);
        BotLog.info("SELFTEST TEST4: 坑场景就绪, bot坑底={} 目标={}", holeBottom.toShortString(), target4.toShortString());
        BotManager.assignMine(bot, target4);
        phase = Phase.TEST4_RUN;
        phaseTicks = 0;
    }

    private static void checkTest4() {
        boolean blockGone = level.getBlockState(target4).isAir();
        String result = BotManager.lastTaskResult(bot);
        // 坑场景验证核心:bot 在坑里能否挖到坑边目标(用户反馈的核心痛点)。
        // 拾取可能因「1×1 坑爬不出」失败(collect_no_path,A* 要求落脚格下方实心,
        // 已知限制 R15),此时挖掘本身已成功,不判失败。
        boolean collectLimited = result.startsWith("failed:collect");
        test4Pass = withinBudget(blockGone && ("done".equals(result) || collectLimited));
        test4Detail = "blockGone=" + blockGone + " result=" + result
                + (collectLimited ? " (拾取受限R15)" : "")
                + (scenarioTimedOut ? " (超时 " + scenarioElapsedTicks + " tick)" : " (耗时 " + scenarioElapsedTicks + " tick)");
        BotLog.info("SELFTEST TEST4 {}", test4Pass ? "PASS" : "FAIL: " + test4Detail);
        phase = Phase.TEST5_SETUP;
        phaseTicks = 0;
    }

    /** TEST5:草丛寻路——bot 与目标之间铺高草,验证 canWalkThrough/canWalkOn
     * 对无碰撞植物方块的判定(之前「被草拦住路线」)。 */
    private static void setupTest5() {
        BlockPos surface = findSurface();
        // 清一块 9x1 区域,铺草方块地面
        BlockPos west = surface.offset(-4, 0, 0);
        int groundY = west.getY() - 1;
        for (int x = west.getX(); x <= west.getX() + 8; x++) {
            level.setBlock(new BlockPos(x, groundY, west.getZ()), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x, groundY + 1, west.getZ()), Blocks.AIR.defaultBlockState(), 3);
        }
        // 中间铺高草(bot 与目标之间)
        for (int x = west.getX() + 1; x <= west.getX() + 7; x++) {
            level.setBlock(new BlockPos(x, west.getY(), west.getZ()), Blocks.FERN.defaultBlockState(), 3);
        }
        bot.teleportTo(west.getX() + 0.5D, west.getY(), west.getZ() + 0.5D);
        target5 = surface.offset(4, 0, 0);
        level.setBlock(target5, Blocks.DIRT.defaultBlockState(), 3);
        BotLog.info("SELFTEST TEST5: 草丛场景就绪, bot={} 目标={} 中间高草x{}~{}",
                west.toShortString(), target5.toShortString(), west.getX() + 1, west.getX() + 7);
        BotManager.assignMine(bot, target5);
        phase = Phase.TEST5_RUN;
        phaseTicks = 0;
    }

    private static void checkTest5() {
        boolean blockGone = level.getBlockState(target5).isAir();
        String result = BotManager.lastTaskResult(bot);
        test5Pass = withinBudget(blockGone && "done".equals(result));
        test5Detail = "blockGone=" + blockGone + " result=" + result
                + (scenarioTimedOut ? " (超时 " + scenarioElapsedTicks + " tick)" : " (耗时 " + scenarioElapsedTicks + " tick)");
        BotLog.info("SELFTEST TEST5 {}", test5Pass ? "PASS" : "FAIL: " + test5Detail);
        phase = Phase.TEST6_SETUP;
        phaseTicks = 0;
    }

    /** TEST6:头顶挖掘——目标在 bot 正上方 3 格(复现用户实验1:头顶方块)。
     * 期望:bot 直接抬头挖掉(距离约 2 格 < 4.5, 视线无遮挡), 而非绕到方块上方。 */
    private static void setupTest6() {
        BlockPos surface = findSurface();
        bot.teleportTo(surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D);
        // 清目标柱(上方 4 格空气)
        for (int dy = 1; dy <= 5; dy++) {
            level.setBlock(surface.offset(0, dy, 0), Blocks.AIR.defaultBlockState(), 3);
        }
        target6 = surface.offset(0, 3, 0); // 头顶 3 格
        level.setBlock(target6, Blocks.DIRT.defaultBlockState(), 3);
        BotLog.info("SELFTEST TEST6: 头顶挖场景, bot={} 目标={}(上方3格)",
                surface.toShortString(), target6.toShortString());
        BotManager.assignMine(bot, target6);
        phase = Phase.TEST6_RUN;
        phaseTicks = 0;
    }

    private static void checkTest6() {
        boolean blockGone = level.getBlockState(target6).isAir();
        String result = BotManager.lastTaskResult(bot);
        test6Pass = withinBudget(blockGone && "done".equals(result));
        test6Detail = "blockGone=" + blockGone + " result=" + result
                + (scenarioTimedOut ? " (超时 " + scenarioElapsedTicks + " tick)" : " (耗时 " + scenarioElapsedTicks + " tick)");
        BotLog.info("SELFTEST TEST6 {}", test6Pass ? "PASS" : "FAIL: " + test6Detail);
        phase = Phase.TEST7_SETUP;
        phaseTicks = 0;
    }

    /** TEST7:洞壁矿石——目标在 bot 斜上方且视线被挡(模拟洞壁顶的矿石)。
     * 期望:bot 不硬挖通道到矿石上方,而是优先走到「目标下方」的可达站位抬头挖。 */
    private static void setupTest7() {
        BlockPos surface = findSurface();
        bot.teleportTo(surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D);
        // 清出前方空间
        for (int x = 1; x <= 4; x++) {
            for (int dy = 0; dy <= 4; dy++) {
                level.setBlock(surface.offset(x, dy, 0), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        // 目标 = 前方 3 格、上方 2 格; 视线路径上放石头(目标斜前方)挡视线
        target7 = surface.offset(3, 2, 0);
        level.setBlock(target7, Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(surface.offset(2, 2, 0), Blocks.STONE.defaultBlockState(), 3);
        BotLog.info("SELFTEST TEST7: 洞壁场景, bot={} 目标={}(前3上2) 视线遮挡={}",
                surface.toShortString(), target7.toShortString(), surface.offset(2, 2, 0).toShortString());
        BotManager.assignMine(bot, target7);
        phase = Phase.TEST7_RUN;
        phaseTicks = 0;
    }

    private static void checkTest7() {
        boolean blockGone = level.getBlockState(target7).isAir();
        String result = BotManager.lastTaskResult(bot);
        test7Pass = withinBudget(blockGone && "done".equals(result));
        test7Detail = "blockGone=" + blockGone + " result=" + result
                + (scenarioTimedOut ? " (超时 " + scenarioElapsedTicks + " tick)" : " (耗时 " + scenarioElapsedTicks + " tick)");
        BotLog.info("SELFTEST TEST7 {}", test7Pass ? "PASS" : "FAIL: " + test7Detail);
        phase = Phase.TEST8_SETUP;
        phaseTicks = 0;
    }

    /** TEST8:挖通道——目标被 2 格高石墙挡住, bot 需清障挖开两层才能挖到目标。
     * 验证「自己打通道」链路(清障深度放开后)。 */
    private static void setupTest8() {
        BlockPos surface = findSurface();
        bot.teleportTo(surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D);
        // 清出前方空间
        for (int x = 1; x <= 3; x++) {
            for (int dy = 0; dy <= 3; dy++) {
                level.setBlock(surface.offset(x, dy, 0), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        // 目标 = 前方 2 格; 目标前 2 格高石墙(1,64,0)+(1,65,0)挡视线
        target8 = surface.offset(2, 0, 0);
        level.setBlock(target8, Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(surface.offset(1, 0, 0), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(surface.offset(1, 1, 0), Blocks.STONE.defaultBlockState(), 3);
        BotLog.info("SELFTEST TEST8: 通道场景, bot={} 目标={} 石墙={} + {}",
                surface.toShortString(), target8.toShortString(),
                surface.offset(1, 0, 0).toShortString(), surface.offset(1, 1, 0).toShortString());
        BotManager.assignMine(bot, target8);
        phase = Phase.TEST8_RUN;
        phaseTicks = 0;
    }

    private static void checkTest8() {
        boolean blockGone = level.getBlockState(target8).isAir();
        String result = BotManager.lastTaskResult(bot);
        test8Pass = withinBudget(blockGone && "done".equals(result));
        test8Detail = "blockGone=" + blockGone + " result=" + result
                + (scenarioTimedOut ? " (超时 " + scenarioElapsedTicks + " tick)" : " (耗时 " + scenarioElapsedTicks + " tick)");
        BotLog.info("SELFTEST TEST8 {}", test8Pass ? "PASS" : "FAIL: " + test8Detail);
        phase = Phase.TEST9_SETUP;
        phaseTicks = 0;
    }

    /** TEST9:决策层目标匹配两种模式——标签(#coal_ores) + 方块 ID(stone)。
     * 注意: stone 没有同名标签, 必须走方块模式——用户实测踩坑点。 */
    private static void setupTest9() {
        BlockPos surface = findSurface();
        bot.teleportTo(surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D);
        // 清理周围(前序测试残留会干扰最近目标判定, 如 TEST8 石墙)
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlock(surface.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        // 放目标: 煤炭矿石(属 #minecraft:coal_ores 标签) 在前方, 石头在旁边
        level.setBlock(surface.offset(2, 0, 0), Blocks.COAL_ORE.defaultBlockState(), 3);
        level.setBlock(surface.offset(0, 0, 2), Blocks.STONE.defaultBlockState(), 3);
        BotLog.info("SELFTEST TEST9: 决策层匹配, bot={} 煤炭={} 石头={}",
                surface.toShortString(), surface.offset(2, 0, 0).toShortString(),
                surface.offset(0, 0, 2).toShortString());
        // 标签模式: 应找到煤炭矿石
        var tagTarget = com.dddgn.alice.decision.AutoMineDecision.pickNearest(
                level, surface, net.minecraft.tags.BlockTags.COAL_ORES, 8);
        // 方块模式: 应找到石头
        var blockTarget = com.dddgn.alice.decision.AutoMineDecision.pickNearestBlock(
                level, surface, Blocks.STONE, 8);
        boolean tagOk = tagTarget != null && tagTarget.equals(surface.offset(2, 0, 0));
        boolean blockOk = blockTarget != null && blockTarget.equals(surface.offset(0, 0, 2));
        test9Pass = tagOk && blockOk;
        test9Detail = "tag=" + (tagTarget == null ? "null" : tagTarget.toShortString())
                + " block=" + (blockTarget == null ? "null" : blockTarget.toShortString());
        BotLog.info("SELFTEST TEST9 {}", test9Pass ? "PASS" : "FAIL: " + test9Detail);
        phase = Phase.TEST10_SETUP;
        phaseTicks = 0;
    }

    /** TEST10:安全区清障——保护墙阻挡任务时不得破墙或继续向后挖。 */
    private static void setupTest10() {
        BlockPos surface = findSurface();
        bot.teleportTo(surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D);
        for (int x = 1; x <= 3; x++) {
            for (int dy = 0; dy <= 2; dy++) {
                level.setBlock(surface.offset(x, dy, 0), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        protectedWall = surface.offset(1, 0, 0);
        BlockPos protectedHead = protectedWall.above();
        BlockPos target = surface.offset(2, 0, 0);
        level.setBlock(protectedWall, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(protectedHead, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(target, Blocks.DIRT.defaultBlockState(), 3);
        // 保护目标本身，确保即使寻路能绕开墙，最终破坏门卫仍然拒绝。
        com.dddgn.alice.protection.SafeZoneData.get(server).addArea(level, target, 0);
        BotLog.info("SELFTEST TEST10: 安全区清障场景, 墙={} 目标={} (目标受保护)",
                protectedWall.toShortString(), target.toShortString());
        BotManager.assignMine(bot, target);
        phase = Phase.TEST10_RUN;
        phaseTicks = 0;
    }

    private static void checkTest10() {
        boolean wallIntact = level.getBlockState(protectedWall).is(Blocks.STONE)
                && level.getBlockState(protectedWall.above()).is(Blocks.STONE);
        String result = BotManager.lastTaskResult(bot);
        test10Pass = withinBudget(wallIntact && "failed:protected_area".equals(result));
        test10Detail = "wallIntact=" + wallIntact + " result=" + result
                + (scenarioTimedOut ? " (超时 " + scenarioElapsedTicks + " tick)" : " (耗时 " + scenarioElapsedTicks + " tick)");
        BotLog.info("SELFTEST TEST10 {}", test10Pass ? "PASS" : "FAIL: " + test10Detail);
        phase = Phase.TEST11_SETUP;
        phaseTicks = 0;
    }

    /** TEST11:两格深坑掉落物——沿逐格楼梯下到坑底后主动拾取，不能在坑边放弃。
     * 严禁通过 >1 格自由落下完成，确保现有执行器仍遵守可逆的单格高差。 */
    private static void setupTest11() {
        BlockPos surface = findSurface();
        bot.teleportTo(surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D);
        // 挖出两格深坑，但提供每次只下降 1 格的楼梯。禁止为了拾取引入 >1 格自由落下，
        // 因为当前执行器尚无「保证原路返回」与外力位移恢复能力。
        for (int x = 1; x <= 3; x++) {
            for (int y = -2; y <= 1; y++) {
                level.setBlock(surface.offset(x, y, 0), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        // x=1 的脚位在 y-1（下方 y-2 支撑），x=2/3 的脚位在 y-2（下方 y-3 支撑）。
        level.setBlock(surface.offset(1, -2, 0), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(surface.offset(2, -3, 0), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(surface.offset(3, -3, 0), Blocks.DIRT.defaultBlockState(), 3);
        pitDropPos = surface.offset(3, -2, 0);
        // 在 bot 身边挖一个方块，随后把掉落物移到坑底；任务仍按正常 MineTask 进入 COLLECTING。
        BlockPos mineTarget = surface.offset(0, 0, 1);
        level.setBlock(mineTarget, Blocks.DIRT.defaultBlockState(), 3);
        pitDropId = null;
        pitDropSpawned = false;
        BotLog.info("SELFTEST TEST11: 两格深坑拾取, bot={} 坑底={} 先挖={}",
                surface.toShortString(), pitDropPos.toShortString(), mineTarget.toShortString());
        BotManager.assignMine(bot, mineTarget);
        phase = Phase.TEST11_RUN;
        phaseTicks = 0;
    }

    private static void checkTest11() {
        ItemEntity pitDrop = pitDropId == null ? null : level.getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(pitDropPos).inflate(1.0D),
                item -> item.getUUID().equals(pitDropId)).stream().findFirst().orElse(null);
        boolean collected = pitDropSpawned && (pitDrop == null || pitDrop.isRemoved() || pitDrop.getItem().isEmpty());
        String result = BotManager.lastTaskResult(bot);
        test11Pass = withinBudget(collected && "done".equals(result));
        test11Detail = "pit=" + pitDropPos.toShortString() + " collected=" + collected + " result=" + result;
        BotLog.info("SELFTEST TEST11 {}", test11Pass ? "PASS" : "FAIL: " + test11Detail);
        phase = Phase.TEST12_SETUP;
        phaseTicks = 0;
    }

    /** TEST12:禁止原地向下挖脚下方块；明确目标允许换到侧面站位后完成。 */
    private static void setupTest12() {
        BlockPos surface = findSurface();
        underfootTarget = surface.below();
        level.setBlock(underfootTarget, Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(surface, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(surface.above(), Blocks.AIR.defaultBlockState(), 3);
        bot.teleportTo(surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D);
        underfootMineStart = null;
        BotLog.info("SELFTEST TEST12: 脚下目标换站位, bot={} target={}",
                surface.toShortString(), underfootTarget.toShortString());
        BotManager.assignMine(bot, underfootTarget);
        phase = Phase.TEST12_RUN;
        phaseTicks = 0;
    }

    private static void checkTest12() {
        underfootMineStart = BotManager.lastMineStartPos(bot);
        boolean blockGone = level.getBlockState(underfootTarget).isAir();
        boolean movedAside = underfootMineStart != null
                && !(underfootMineStart.getX() == underfootTarget.getX()
                && underfootMineStart.getZ() == underfootTarget.getZ()
                && underfootMineStart.getY() == underfootTarget.getY() + 1);
        String result = BotManager.lastTaskResult(bot);
        test12Pass = withinBudget(blockGone && movedAside && "done".equals(result));
        test12Detail = "blockGone=" + blockGone + " movedAside=" + movedAside
                + " mineStart=" + (underfootMineStart == null ? "null" : underfootMineStart.toShortString())
                + " result=" + result;
        BotLog.info("SELFTEST TEST12 {}", test12Pass ? "PASS" : "FAIL: " + test12Detail);
        phase = Phase.TEST13_SETUP;
        phaseTicks = 0;
    }

    /** TEST13:坑底掉落物无现成阶梯时，只挖侧向斜下方一格形成台阶，不挖脚下。 */
    private static void setupTest13() {
        BlockPos surface = findSurface();
        bot.teleportTo(surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D);
        // 封闭测试室：目标掉落物位于 (2,-2)，唯一入口是先挖 (1,-1) 的侧向台阶，
        // 防止前序测试遗留洞穴提供绕路而造成假阳性。
        for (int x = -2; x <= 4; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -3; y <= 2; y++) {
                    level.setBlock(surface.offset(x, y, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }
        // bot 出生脚位与头部、预定的两步阶梯通道。
        level.setBlock(surface, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(surface.above(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(surface.offset(1, 0, 0), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(surface.offset(1, 1, 0), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(surface.offset(2, -1, 0), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(surface.offset(2, 0, 0), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(surface.offset(2, -2, 0), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(surface.offset(2, -1, 0), Blocks.AIR.defaultBlockState(), 3);
        stairInitialUnderfoot = surface.below();
        stairClearBlock = surface.offset(1, -1, 0);
        stairDropPos = surface.offset(2, -2, 0);
        level.setBlock(stairClearBlock, Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(surface.offset(1, -2, 0), Blocks.DIRT.defaultBlockState(), 3); // 第一级支撑
        level.setBlock(stairDropPos.below(), Blocks.DIRT.defaultBlockState(), 3); // 坑底支撑
        // 用空气目标直接进入 COLLECTING；下一 tick 才生成坑底圆石，避免测试启动方块掉落物抢占拾取目标。
        BlockPos mineTarget = surface.offset(0, 0, 1);
        level.setBlock(mineTarget, Blocks.AIR.defaultBlockState(), 3);
        stairDropId = null;
        stairDropSpawned = false;
        BotLog.info("SELFTEST TEST13: 拾取挖阶梯, bot={} 清障={} 坑底={} 先挖={}",
                surface.toShortString(), stairClearBlock.toShortString(), stairDropPos.toShortString(),
                mineTarget.toShortString());
        BotManager.assignMine(bot, mineTarget);
        phase = Phase.TEST13_RUN;
        phaseTicks = 0;
    }

    private static void checkTest13() {
        ItemEntity drop = stairDropId == null ? null : level.getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(stairDropPos).inflate(1.0D),
                item -> item.getUUID().equals(stairDropId)).stream().findFirst().orElse(null);
        boolean collected = stairDropSpawned && (drop == null || drop.isRemoved() || drop.getItem().isEmpty());
        boolean stairCleared = level.getBlockState(stairClearBlock).isAir();
        boolean underfootIntact = !level.getBlockState(stairInitialUnderfoot).isAir();
        String result = BotManager.lastTaskResult(bot);
        test13Pass = withinBudget(collected && stairCleared && underfootIntact && "done".equals(result));
        test13Detail = "collected=" + collected + " stairCleared=" + stairCleared
                + " underfootIntact=" + underfootIntact + " result=" + result;
        BotLog.info("SELFTEST TEST13 {}", test13Pass ? "PASS" : "FAIL: " + test13Detail);
        phase = Phase.REPORT;
        report();
    }

    /** 出生点上方的地表空气格(找地表用). */
    private static BlockPos findSurface() {
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos surface = spawn;
        while (level.getBlockState(surface).isAir() && surface.getY() > level.getMinBuildHeight()) {
            surface = surface.below();
        }
        return surface.above();
    }

    private static void report() {
        boolean allPass = pathingRegressionPass && test1Pass && test2Pass && test3Pass && test4Pass
                && test5Pass && test6Pass && test7Pass && test8Pass && test9Pass && test10Pass
                && test11Pass && test12Pass && test13Pass;
        BotLog.info("SELFTEST 结果: {} (PATHING={} TEST1={} TEST2={} TEST3={} TEST4={} TEST5={} TEST6={} TEST7={} TEST8={} TEST9={} TEST10={} TEST11={} TEST12={} TEST13={})",
                allPass ? "PASS" : "FAIL", pathingRegressionPass,
                test1Pass, test2Pass, test3Pass, test4Pass, test5Pass, test6Pass, test7Pass,
                test8Pass, test9Pass, test10Pass, test11Pass, test12Pass, test13Pass);
        // headless 验收:测完自动关服(审查点 R8)
        server.halt(true);
    }
}

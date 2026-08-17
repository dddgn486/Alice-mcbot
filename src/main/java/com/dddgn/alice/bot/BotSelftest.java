package com.dddgn.alice.bot;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
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

    private enum Phase { WAIT_START, SETUP, TEST1_RUN, TEST1_CHECK, TEST2_SETUP, TEST2_RUN, TEST2_CHECK, REPORT }

    private static final int START_DELAY_TICKS = 100;
    private static final int PHASE_TIMEOUT_TICKS = 600;

    private static Phase phase = Phase.WAIT_START;
    private static int waitTicks;
    private static int phaseTicks;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static FakePlayer bot;
    private static BlockPos target1;
    private static BlockPos target2;
    private static BlockPos test2MineStart;
    private static boolean test1Pass;
    private static boolean test2Pass;
    private static String test1Detail = "";
    private static String test2Detail = "";

    private BotSelftest() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        level = server.overworld();
        phase = Phase.WAIT_START;
        waitTicks = START_DELAY_TICKS;
        BotLog.info("SELFTEST 启动: 服务器就绪,{} tick 后开始", START_DELAY_TICKS);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || phase == Phase.REPORT) {
            return;
        }
        phaseTicks++;
        switch (phase) {
            case WAIT_START -> {
                if (--waitTicks <= 0) {
                    phase = Phase.SETUP;
                    phaseTicks = 0;
                }
            }
            case SETUP -> setup();
            case TEST1_RUN -> {
                if (!BotManager.isBusy(bot) || phaseTicks > PHASE_TIMEOUT_TICKS) {
                    phase = Phase.TEST1_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST1_CHECK -> checkTest1();
            case TEST2_SETUP -> setupTest2();
            case TEST2_RUN -> {
                if (!BotManager.isBusy(bot) || phaseTicks > PHASE_TIMEOUT_TICKS) {
                    phase = Phase.TEST2_CHECK;
                    phaseTicks = 0;
                }
            }
            case TEST2_CHECK -> checkTest2();
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
        test1Pass = blockGone && "done".equals(result);
        test1Detail = "blockGone=" + blockGone + " result=" + result
                + (phaseTicks > PHASE_TIMEOUT_TICKS ? " (超时)" : "");
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
        boolean minedFromAbove = test2MineStart != null
                && test2MineStart.getX() == target2.getX()
                && test2MineStart.getZ() == target2.getZ()
                && test2MineStart.getY() > target2.getY();
        // PASS = 方块被挖掉且不是隔空挖,或方块仍在(拒绝隔空挖,合法)
        test2Pass = !minedFromAbove && (blockGone || result.startsWith("failed:"));
        test2Detail = "blockGone=" + blockGone + " result=" + result
                + " mineStart=" + (test2MineStart == null ? "null" : test2MineStart.toShortString())
                + (phaseTicks > PHASE_TIMEOUT_TICKS ? " (超时)" : "");
        BotLog.info("SELFTEST TEST2 {}", test2Pass ? "PASS" : "FAIL: " + test2Detail);
        phase = Phase.REPORT;
        report();
    }

    private static void report() {
        boolean allPass = test1Pass && test2Pass;
        BotLog.info("SELFTEST 结果: {} (TEST1={} TEST2={})", allPass ? "PASS" : "FAIL",
                test1Pass, test2Pass);
        // headless 验收:测完自动关服(审查点 R8)
        server.halt(true);
    }
}

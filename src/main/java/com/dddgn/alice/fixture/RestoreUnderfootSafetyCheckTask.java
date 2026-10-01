package com.dddgn.alice.fixture;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.dddgn.alice.task.RestoreScopeTask;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import com.dddgn.alice.task.FixtureClaim;

/**
 * ⭐ `C2`（`D-399` 的强判据）：**回收自己放的柱子时不许把 bot 摔下去**。
 *
 * <h2>为什么必须单独一个夹具（2026-09-22 实测教训）</h2>
 * `craft_station` 里守卫**开/关都是 `biggestFall=1`** ⇒ 判据空跑。根因：那个场景的拆除是
 * **严格自上而下**（每次拆的都是自己脚下那格 ⇒ 逐格降 1）。要让"没守卫就真摔"成立，账本顺序必须是
 * **自下而上**（= 真机 `PILLAR` 上行搭建的顺序：先放最下面那块）：
 *
 * <ol>
 *   <li>账本顺序 `[柱底, 中, 柱顶]`；bot 站在**柱顶之上**（脚位 = 柱顶 +1）；</li>
 *   <li>拆柱底 / 中段：与 bot 脚位无关 ⇒ 都拆掉；</li>
 *   <li>轮到柱顶（= **bot 正踩着的那格**）：<b>守卫在</b> ⇒ 下方已是空气
 *       （`canWalkOn(level, 柱顶)` 读 `柱顶.below()` = 空气）⇒ **延后 → 如实 `skipped` + `underfoot_unsafe`**
 *       ⇒ bot **全程不掉**；<b>守卫不在</b> ⇒ 柱顶被拆 ⇒ bot 从 `柱顶+1` **摔到地面**（≥2 格）⇒ **红**。</li>
 * </ol>
 *
 * <h2>判据（`D-472` 后重做；红臂都能变红）</h2>
 * <ol>
 *   <li>⭐ `biggestFall <= 1`（单 tick 坠落 ≤ 1 格）+ ⭐ 臂④ 的**落地落差 ≤ 1 格**。⚠️ 前者对**慢速
 *       1~3 格自落是钝的**（脚位格子每 tick 最多差 1）——`D-472` 实测：旧代码把 bot 踩着的中间那格
 *       拆掉、bot 从 y=101 掉到 99，`biggestFall` 照样是 `1` ⇒ 补"落地到落地"的度量（见 `sample`）；</li>
 *   <li>⭐ 臂①**换得到安全站位 ⇒ 全拆干净**（`D-472` 新语义）：世界事实 = `PILLAR_H` 格全是空气、
 *       账本清空。⚠️ **红臂** = 去掉 `MineBlockRunner.canMineInPlace` 的 `underfootUnsafe` 前置
 *       （或退回旧的"当场放弃"）⇒ 必红；</li>
 *   <li>⭐ 臂②**现场仍是我方**（条件之②）：账本记 `COBBLESTONE`、现场是 `DIRT` ⇒ 必须点名 `not_ours`
 *       且**世界事实**里那格 `DIRT` 原封不动（"绝不拆不是自己放的方块"）；</li>
 *   <li>⭐ 臂③**已加载**（条件之①）：条目落在 `+512` 格（从未加载）⇒ 必须点名
 *       `chunk_not_loaded`，且**跑完仍未加载**（这条世界事实就是"不许为了回收去开图"）；</li>
 *   <li>⭐ 臂④**换不到安全站位 ⇒ 当场放弃 + 如实 `underfoot_unsafe`**（`D-472` 新增；`D-403` 的
 *       放弃路径**唯一正例**）：孤悬方块 ⇒ 执行器给不出站位 ⇒ 点名 `underfoot_unsafe` +
 *       世界事实"那格原封不动" + bot **一格都没动** + 账本仍挂 1 条；</li>
 *   <li>收尾 `endSupported`（bot 结束时脚下有支撑）。</li>
 * </ol>
 *
 * <p>⚠️ 四臂**分三次** `RestoreScopeTask` 跑（臂①② / 臂③ / 臂④ 各一次）：理由见 `startFarArm`
 * 与 `startFloatArm`。
 *
 * <p>⚠️ **本夹具不覆盖什么**（如实登记）："换不到"这条前提是**行为反推**的（bot 没动 + 方块没被拆 +
 * 手里有镐 ⇒ 执行器给不出站位），没有直接断言"规划器返回了 `no_reachable_standing_point`"
 * （那个失败码不在本夹具的读面上）。
 */
public final class RestoreUnderfootSafetyCheckTask implements Task {

    /** 孤立原点（避开 3000/3600/3700/3800/3900 段）。 */
    private static final BlockPos ORIGIN = new BlockPos(4000, 100, 2600);
    /** 地板（摔下来会落在这里 ⇒ 落差 3 格，足够区分"摔/没摔"且不会致命）。 */
    private static final int FLOOR_Y = 98;
    /**
     * ⭐ `RC1` 臂②（`D-403` 保留条件之②「现场仍是我方」）：**故意做成"现场已非我方"** —— 账本记
     * `COBBLESTONE`，现场摆 `DIRT`。放在楼梯**南侧 5 格**（避开臂①的作业面与摔落路径）。
     *
     * <p>⚠️ **不能用"播种时就摆好 DIRT"这个写法**（2026-09-24 首轮实测）：`dropStale` 的**幽灵条目**
     * 销账（`现场已非我方 ⇒ 销掉`）跑在 `buildQueue` **开头** ⇒ 那条条目在 `pickNext` 之前就没了，
     * 于是 `not_ours` 分支**永远到不了**（实测：`销掉 1 条已失效条目: 4001,99,2603(cobblestone→dirt)`，
     * `restore_underfoot_safety=FAIL … notes=…:underfoot_unsafe`）。
     * ⇒ 正确复现 = **先让现场与账本一致**（这样 `dropStale` 会留下它），**等 `buildQueue` 跑过之后**
     * 再把世界改成 `DIRT`（= 真机里"我方行动期间现场被别人改了"那个竞态窗口）。
     */
    private static final BlockPos NOT_OURS_POS =
            new BlockPos(ORIGIN.getX() + 1, FLOOR_Y + 1, ORIGIN.getZ() + 5);
    /**
     * ⭐ `RC1` 臂③（保留条件之①「已加载」）：条目落在**从未加载**的区块里。
     * 取 +512 格（= 32 区块）远大于服务端 `view-distance=10`（= 160 格）⇒ 保证未加载；
     * ⚠️ 这一格**绝对不许碰世界**（`setBlock`/`getBlockState` 都会把它加载掉 ⇒ 前提当场消失）。
     */
    private static final int FAR_OFFSET_X = 512;
    /** 地板南向延伸（臂②那格要落在地板上，别悬空）。 */
    private static final int FLOOR_DZ = 6;
    /**
     * 我方 TEMP **阶梯**的级数（第 i 级 = `(x+i, FLOOR_Y+1+i, z)`）。
     * ⚠️ 必须是**阶梯**而不是"柱子"：回收的推进是"站到目标上方 → 向下拆"，柱子每级上方是实心的
     * ⇒ 它根本碰不到（2026-09-22 实测：柱形几何得到 `skipped=3 / side_break_failed`，什么都没拆）。
     * 真机里能拆的也正是**脚手架阶梯**（每级上方是空气）。
     */
    private static final int PILLAR_H = 3;
    /**
     * ⭐ `D-472` 臂④（**换不到安全站位** ⇒ 必须当场放弃 + 如实 `underfoot_unsafe`）：
     * 一块**孤悬在高处**的我方方块 —— 正下方是空气（⇒ 拆了必掉），而**四周没有任何触及得到的可站面**
     * （地板在 {@code FLOOR_Y = 98}，方块在 {@code FLOOR_Y + 10} ⇒ 从地板脚位到方块中心 ≈ 10 格，
     * 远超触及）⇒ 规划器**必然给不出站位点**。
     *
     * <p>为什么必须补这一臂：臂① 的旧语义（"脚下那格必须被守卫拒绝"）在 `D-472` 之后**不再成立** ——
     * 阶梯几何里**换得到**侧面站位（规划器给 `4001,101,2600`），于是现在三格**全部被安全拆掉**。
     * `underfoot_unsafe` 这条**放弃路径**必须另有正例，否则 `D-403`/`D-399` C 的"如实归因、绝不静默"
     * 就重新变成零覆盖。
     */
    private static final int FLOAT_DX = 6;
    private static final int FLOAT_DY = 10;
    private static final int FLOAT_DZ = 6;
    /** 地板向东延伸到的 dx（必须罩住臂④那根柱子正下方，免得"没摔"时 bot 落在世界地形上）。 */
    private static final int FLOOR_DX_MAX = FLOAT_DX + 1;
    private static final int BUDGET_TICKS = 1200;

    /**
     * 三个阶段 = 三次 `RestoreScopeTask`（臂①②一次、臂③一次、臂④一次；理由见 `startFarArm`）：
     * {@code SETUP → RUN_A → SETUP_B → RUN_B → SETUP_C → RUN_C → DONE}。
     */
    private enum Phase { SETUP, RUN_A, SETUP_B, RUN_B, SETUP_C, RUN_C, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int totalTicks;
    private int checks;
    private ScopeBuffer scope;
    private RestoreScopeTask restore;
    private int biggestFall;
    private int unsupportedTicks;
    private int lastFootY = Integer.MIN_VALUE;
    /**
     * ⭐ `D-472`：**"落地到落地"的落差**（只在"脚下有支撑"的 tick 上比较脚位 y）。
     *
     * <p>为什么必须补这个量：`biggestFall` 是**单 tick 的格子差** ⇒ 对**慢速 1~3 格自落是钝的**
     * （脚位格子每 tick 最多差 1）。`D-472` 取证实测：旧代码在阶梯几何里把 bot 踩着的中间那格拆掉、
     * bot 从 y=101 掉到 99（**2 格**），而 `biggestFall` 照样是 `1`、判据照样绿。
     */
    private int biggestLandingDrop;
    private int lastSupportedFootY = Integer.MIN_VALUE;
    /** 臂④ 开跑时的基线（该臂只认**本臂**产生的落差，不与臂①的走位混算）。 */
    private int landingDropBaseC;
    private int unsupportedBaseC;
    private boolean endSupported;
    private String terminal = "-";
    private String notes = "-";
    private int skippedCount = -1;
    /** ⭐ `A4`：臂①②结束时账本里仍挂着的条目（读数**当场取**，别等收尾）。 */
    private java.util.Set<BlockPos> leftA = java.util.Set.of();
    /** ⭐ `RC1` 臂③的读数（与臂①②分开记 —— 两次 `RestoreScopeTask` 各自的 notes/skipped）。 */
    private String terminalFar = "-";
    private String notesFar = "-";
    private int skippedFar = -1;
    private int remainingFar = -1;
    private java.util.Set<BlockPos> leftFar = java.util.Set.of();
    /** ⭐ `D-472` 臂④（换不到安全站位）的读数：与前三臂分开记。 */
    private String terminalC = "-";
    private String notesC = "-";
    private int skippedC = -1;
    private int remainingC = -1;
    private java.util.Set<BlockPos> leftC = java.util.Set.of();
    /** 臂④ 开跑时 bot 的脚位 y（用来断言"执行器一步都没动"）。 */
    private int floatFootYAtStart = Integer.MIN_VALUE;
    /** ⭐ `RC1` 臂②：现场"变成别人的方块"是否已经发生（避免重复写）。 */
    private boolean notOursSwapped;
    /** ⭐ `RC1` 臂③：远区块那条目用的**内层作用域**（见 `startFarArm`）。 */
    private String farScopeId = "-";
    /** ⭐ `D-472` 臂④：孤悬方块那条目用的**内层作用域**（见 `startFloatArm`）。 */
    private String floatScopeId = "-";
    /**
     * ⭐ **夹具卫生（2026-09-24 由 `A2′` 的组合点名实测抓出）**：进场景**之前** bot 的脚位。
     * 收尾必须回**这里**，不许回"自己的场景原点" —— 场景方块已还原成原始地形，原点常常是**空中**
     * ⇒ bot 掉下去（实测：`single:restore_underfoot_safety,break_refused` 组合跑，后一步继承到
     * y≈44 的坑里，全部 `BREAK_OUT_OF_REACH`）。
     */
    private BlockPos entryFoot;
    private boolean reported;
    /** ⭐ `A4`（2026-09-23）：本步账本作用域 id —— 收尾按**世界事实**断言"只该剩脚下那一条"。 */
    private String scopeId = "-";
    /** ⭐ `A4`：DONE 时账本里仍挂着的条目数（正向对照的读数）。 */
    private int remainingAfter = -1;
    /** ⭐ `Z1`/`D-398`：夹具自摆的"保护区 + 任务区"前提（结束复位）。 */
    private FixtureClaim.Handle zone;

    public RestoreUnderfootSafetyCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "RestoreUnderfootSafetyCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "passed" : "failed";
    }

    private void check(String what, boolean ok) {
        checks++;
        if (ok) {
            findings.add(what + " ✓");
        } else {
            failures.add(what + " ✗");
            BotLog.warn("[C2] FAIL {}", what);
        }
    }

    private int pillarBaseY() {
        return FLOOR_Y + 1;
    }

    /** 第 i 级阶梯的位置（每级 +1 x、+1 y）。 */
    private static BlockPos stepPos(int i) {
        return new BlockPos(ORIGIN.getX() + i, FLOOR_Y + 1 + i, ORIGIN.getZ());
    }

    @Override
    public Task.Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        totalTicks++;
        phaseTicks++;
        if (totalTicks > BUDGET_TICKS) {
            failures.add("超时 " + BUDGET_TICKS + " tick（phase=" + phase + "）");
            return finish(level);
        }
        switch (phase) {
            case SETUP -> {
                if (phaseTicks == 1) {
                    buildScene(level);
                }
                if (phaseTicks >= 3) {
                    startRestore(level);
                    phase = Phase.RUN_A;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case RUN_A -> {
                sample(level);
                Task.Status status = restore.tick();
                // ⭐ 臂②：`restore.tick()` 第 1 tick 已经跑过 `buildQueue`（含 `dropStale`）⇒ 现在把
                // 「现场仍是我方」这条**打破**（= 真机里我方行动期间现场被改的竞态窗口）。
                if (phaseTicks == 2 && !notOursSwapped) {
                    notOursSwapped = true;
                    place(level, NOT_OURS_POS, Blocks.DIRT);
                    BotLog.info("[C2] 臂② 现场改为 DIRT（账本仍记 COBBLESTONE）⇒ {} 现在是 {}",
                            NOT_OURS_POS.toShortString(), blockIdAt(level, NOT_OURS_POS));
                }
                if (status != Task.Status.RUNNING) {
                    terminal = String.valueOf(restore.terminalReason());
                    List<String> n = restore.notes();
                    notes = n.isEmpty() ? "-" : String.join(" | ", n);
                    skippedCount = restore.skippedCount();
                    leftA = pendingPositions(scopeId);
                    remainingAfter = leftA.size();
                    phase = Phase.SETUP_B;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case SETUP_B -> {
                if (phaseTicks == 1) {
                    startFarArm(level);
                }
                if (phaseTicks >= 3) {
                    phase = Phase.RUN_B;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case RUN_B -> {
                sample(level);
                Task.Status status = restore.tick();
                if (status != Task.Status.RUNNING) {
                    terminalFar = String.valueOf(restore.terminalReason());
                    List<String> n = restore.notes();
                    notesFar = n.isEmpty() ? "-" : String.join(" | ", n);
                    skippedFar = restore.skippedCount();
                    leftFar = pendingPositions(farScopeId);
                    remainingFar = leftFar.size();
                    phase = Phase.SETUP_C;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            // ⭐ `D-472` 臂④：**换不到安全站位** ⇒ 必须当场放弃 + 如实 `underfoot_unsafe`
            case SETUP_C -> {
                if (phaseTicks == 1) {
                    startFloatArm(level);
                }
                if (phaseTicks >= 3) {
                    phase = Phase.RUN_C;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case RUN_C -> {
                sample(level);
                Task.Status status = restore.tick();
                if (status != Task.Status.RUNNING) {
                    terminalC = String.valueOf(restore.terminalReason());
                    List<String> n = restore.notes();
                    notesC = n.isEmpty() ? "-" : String.join(" | ", n);
                    skippedC = restore.skippedCount();
                    leftC = pendingPositions(floatScopeId);
                    remainingC = leftC.size();
                    phase = Phase.DONE;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case DONE -> {
                return finish(level);
            }
            default -> {
                return Task.Status.FAILED;
            }
        }
    }

    /** 逐 tick 采样：脚位是否有支撑 + 单 tick 最大坠落 + **落地到落地的落差**（`D-472`）。 */
    private void sample(ServerLevel level) {
        BlockPos foot = com.dddgn.alice.pathing.MovementHelper.footCell(level, bot);
        boolean supported = com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, foot);
        if (!supported) {
            unsupportedTicks++;
        }
        if (lastFootY != Integer.MIN_VALUE) {
            biggestFall = Math.max(biggestFall, lastFootY - foot.getY());
        }
        lastFootY = foot.getY();
        // ⭐ `D-472`：只在"脚下真的有支撑"的 tick 之间比落差 —— 这才是"我踩着的方块没了"的度量。
        // 走下一级台阶（-1）与"方块被抽掉"（-N）在这里可分；`biggestFall` 分不出。
        if (supported) {
            if (lastSupportedFootY != Integer.MIN_VALUE) {
                biggestLandingDrop = Math.max(biggestLandingDrop, lastSupportedFootY - foot.getY());
            }
            lastSupportedFootY = foot.getY();
        }
        endSupported = supported;
    }

    private Task.Status finish(ServerLevel level) {
        if (reported) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        reported = true;
        endSupported = com.dddgn.alice.pathing.MovementHelper.canWalkOn(level,
                com.dddgn.alice.pathing.MovementHelper.footCell(level, bot));
        check("⭐ 回收不许把 bot 摔下去（单 tick 坠落 ≤ 1 格，实测 biggestFall=" + biggestFall + "）",
                biggestFall <= 1);
        // ⭐⭐ `D-472`（2026-09-27）**口径重做**：本臂原判据是"脚下那格必须被守卫**拒绝**并点名
        // `underfoot_unsafe`"。那条语义在 `D-472` 之后**不再成立** —— `RestoreScopeTask` 不再当场放弃，
        // 而是**换安全站位再拆**（`D-406` §三「守卫时机错」的修法；阶梯几何里规划器给的是 `4001,101,2600`）
        // ⇒ 三格**全部被安全拆掉**。实测（`single:restore_underfoot_safety`，2026-09-27）：
        //   旧代码 `restored=2 … remaining=1`（且 bot 从 y=101 掉到 99 —— `biggestFall` 看不见）；
        //   新代码 `restored=3 … remaining=0`。
        // ⇒ 臂① 现在断言的是**能力**：换得到站位 ⇒ **一格都不许剩**（世界事实 + 账本清空）；
        //   `underfoot_unsafe` 那条**放弃路径**改由**臂④**（孤悬、换不到）覆盖。
        // ⚠️ **红臂**：把 `canMineInPlace` 的 `underfootUnsafe` 前置去掉（或退回旧的当场放弃）⇒
        //   这一臂必红（`remainingAfter=1` / 脚下那格还在）。
        boolean stepsGone = true;
        StringBuilder stepIds = new StringBuilder();
        for (int i = 0; i < PILLAR_H; i++) {
            String id = blockIdAt(level, stepPos(i));
            stepIds.append(stepPos(i).toShortString()).append('=').append(id).append(' ');
            stepsGone &= id.equals("minecraft:air");
        }
        check("⭐ 臂① 换得到安全站位 ⇒ " + PILLAR_H + " 格必须**全部安全拆掉**（世界事实：" + stepIds
                        + "｜terminal=" + terminal + " notes=" + notes + "）",
                stepsGone && !terminal.contains("nothing_to_restore"));
        // ③ 臂①的正向对照：世界事实（账本里还挂着几条）—— 回收没真的拆掉东西时必然红。
        // ⭐ `D-472`：口径从"恰好剩脚下那格"改成"**一条都不剩**"（同上理由）。
        check("⭐ 臂① 正向对照：账本必须**清空**（实测仍挂 " + remainingAfter + " 条：" + leftA + "）",
                remainingAfter == 0 && leftA.isEmpty());
        // ④ `RC1` 臂②（`D-403` 保留条件之②「现场仍是我方」）：必须点名 `not_ours` 且**绝不拆**。
        String notOursNow = blockIdAt(level, NOT_OURS_POS);
        check("⭐ 臂② 现场已非我方 ⇒ 必须点名 `not_ours` 且**绝不拆**（实测 notes=" + notes
                        + "；现场 " + NOT_OURS_POS.toShortString() + "=" + notOursNow + "）",
                notes.contains("not_ours") && notOursNow.equals("minecraft:dirt"));
        // ⑤⑥ `RC1` 臂③（`D-403` 保留条件之①「已加载」）：点名 + **跑完仍未加载**（不许为了回收开图）。
        // ⚠️ `farLoaded` 必须在这里**第一次**读（之前任何 `getBlockState(farPos())` 都会把前提毁掉）。
        boolean farLoaded = level.isLoaded(farPos());
        check("⭐ 臂③ 未加载区块 ⇒ 必须点名 `chunk_not_loaded` 且**跑完仍未加载**（实测 notesFar="
                        + notesFar + " terminalFar=" + terminalFar + " skippedFar=" + skippedFar
                        + " farLoaded=" + farLoaded + "）",
                notesFar.contains("chunk_not_loaded") && !farLoaded);
        check("⭐ 臂③ 正向对照：未加载那条**必须留在账本里**等下次（实测仍挂 " + remainingFar
                        + " 条：" + leftFar + "）",
                remainingFar == 1 && leftFar.equals(java.util.Set.of(farPos())));
        // ⭐⭐ `D-472` 臂④（**换不到安全站位** ⇒ 必须当场放弃 + 如实 `underfoot_unsafe` + 绝不摔）：
        // 这是 `underfoot_unsafe` 这条放弃路径**唯一的正例**（臂① 的旧语义已随 `D-472` 作废）。
        String floatId = blockIdAt(level, floatPos());
        boolean floatMoved = Math.abs((int) Math.floor(bot.getY()) - floatFootYAtStart) > 0;
        check("⭐ 臂④ 换不到安全站位 ⇒ 必须点名 `underfoot_unsafe` 且**一格都没动**（实测 notesC="
                        + notesC + " skippedC=" + skippedC + " footY=" + (int) Math.floor(bot.getY())
                        + "(起点 " + floatFootYAtStart + ")）",
                notesC.contains("underfoot_unsafe") && skippedC >= 1 && !floatMoved);
        check("⭐ 臂④ 正向对照（世界事实）：孤悬那格**必须原封不动**（实测 " + floatPos().toShortString()
                        + "=" + floatId + " ｜terminalC=" + terminalC + " remainingC=" + remainingC + "）",
                floatId.equals("minecraft:cobblestone") && remainingC == 1
                        && leftC.equals(java.util.Set.of(floatPos())));
        // ⭐ `D-472`：**"落地到落地的落差"** —— 臂④ 的现场（脚下是 10 格空气）里，只要真拆了那格，
        // bot 必然长距离坠落 ⇒ 这个量会直接 > 1。`biggestFall`（单 tick 格子差）在这里**也能红**，
        // 但臂④ 用"落地落差"更直接：它问的是"我踩着的方块没了之后我落在哪"。
        int dropC = biggestLandingDrop - landingDropBaseC;
        int unsupportedC = unsupportedTicks - unsupportedBaseC;
        check("⭐ 臂④ 不许把 bot 摔下去（本臂落地落差 ≤ 1 格，实测 dropC=" + dropC
                        + " unsupportedC=" + unsupportedC + " endSupported=" + endSupported + "）",
                dropC <= 1);
        check("收尾 bot 脚下必须有支撑（实测 endSupported=" + endSupported + "）", endSupported);
        cleanup(level);
        BotLog.info("[C2] SUMMARY checks={} failures={} biggestFall={} biggestLandingDrop={}"
                        + " unsupportedTicks={} endSupported={} terminal={} skipped={} remaining={}"
                        + " notes={} terminalFar={} skippedFar={} remainingFar={} notesFar={}"
                        + " terminalC={} skippedC={} remainingC={} notesC={} ticks={} → {}",
                checks, failures.size(), biggestFall, biggestLandingDrop, unsupportedTicks, endSupported,
                terminal, skippedCount, remainingAfter, notes, terminalFar, skippedFar, remainingFar,
                notesFar, terminalC, skippedC, remainingC, notesC, totalTicks,
                failures.isEmpty() ? "PASS" : "FAIL");
        for (String line : findings) {
            BotLog.info("[C2]   {}", line);
        }
        for (String line : failures) {
            BotLog.warn("[C2]   {}", line);
        }
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 回收摔落夹具（C2）："
                    + (failures.isEmpty() ? "PASS" : "FAIL") + " checks=" + checks
                    + " failures=" + failures.size() + "（biggestFall=" + biggestFall
                    + " skipped=" + skippedCount + "）"));
        }
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 场景与账本 ====================

    private void buildScene(ServerLevel level) {
        // ⭐ 先记下"进来时站在哪"（收尾回这里；见 `entryFoot` 的注释）
        if (entryFoot == null) {
            entryFoot = com.dddgn.alice.pathing.MovementHelper.footCell(level, bot).immutable();
        }
        // 地板（覆盖阶梯 + 南向延伸到臂②那格 + 东向延伸到臂④那根柱子正下方）—— 摔下来落这里
        for (int dx = -1; dx <= FLOOR_DX_MAX; dx++) {
            for (int dz = -1; dz <= FLOOR_DZ; dz++) {
                place(level, new BlockPos(ORIGIN.getX() + dx, FLOOR_Y, ORIGIN.getZ() + dz), Blocks.STONE);
            }
        }
        // 我方 TEMP **阶梯**（自下而上：i=0 最矮最早）
        for (int i = 0; i < PILLAR_H; i++) {
            place(level, stepPos(i), Blocks.COBBLESTONE);
        }
        // ⭐ `RC1` 臂②：**先摆成与账本一致**的 `COBBLESTONE`（否则 `dropStale` 会在 `buildQueue`
        // 开头就把条目当幽灵销掉）；真的变 `DIRT` 是 `RUN_A` 第 2 tick 的事（`swapNotOursCell`）。
        place(level, NOT_OURS_POS, Blocks.COBBLESTONE);
        // bot 站到**最高一级之上**
        BlockPos foot = new BlockPos(stepPos(PILLAR_H - 1).getX(),
                stepPos(PILLAR_H - 1).getY() + 1, stepPos(PILLAR_H - 1).getZ());
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D, 0.0F, 0.0F);
        bot.controller().stopMovement();
        BotLog.info("[C2] SETUP 地板 y={} 阶梯 {}..{} 脚位={} 臂②现场={}({})", FLOOR_Y,
                stepPos(0).toShortString(), stepPos(PILLAR_H - 1).toShortString(), foot.toShortString(),
                NOT_OURS_POS.toShortString(), blockIdAt(level, NOT_OURS_POS));
    }

    /** 方块 id 读数（世界事实断言用；这格必须**已加载**，否则等于自己把现场加载了）。 */
    private static String blockIdAt(ServerLevel level, BlockPos pos) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock()));
    }

    /** 臂③的远区块坐标（`+512` 格 ⇒ 远超 `view-distance=10`）。 */
    private static BlockPos farPos() {
        return new BlockPos(ORIGIN.getX() + FAR_OFFSET_X, FLOOR_Y + 1, ORIGIN.getZ());
    }

    /** ⭐ `D-472` 臂④：孤悬在高处的那块（正下方空气 + 四周无可站面）。 */
    private static BlockPos floatPos() {
        return new BlockPos(ORIGIN.getX() + FLOAT_DX, FLOOR_Y + FLOAT_DY, ORIGIN.getZ() + FLOAT_DZ);
    }

    /**
     * **当时**的账本读数：本步 scope 里还挂着的、**受保护**的 TEMP 条目位置。
     *
     * <p>⚠️ 必须在每臂**收尾那一刻**取（`dropStale` 已跑过 ⇒ 读数 = 世界事实），不能拖到总收尾 ——
     * 臂③开始前会主动销掉臂①留下的那条（见 `startFarArm`），拖后取会把两次读数混在一起。
     */
    private java.util.Set<BlockPos> pendingPositions(String sid) {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        for (WorldModLedger.Entry e : WorldModLedger.pendingTemporaryProtected(
                bot.serverLevel(), sid)) {
            out.add(e.pos());
        }
        return out;
    }

    private void startRestore(ServerLevel level) {
        FixtureToolKit.resetInventory(bot);
        // ⭐ `A4`（2026-09-23）：**必须发镐** —— 账本里播种的是 `Blocks.COBBLESTONE`（镐类方块），
        // 旧版只 `resetInventory` ⇒ 回收要靠手挖石头（慢到超预算 ⇒ 同样落到 `side_break_failed`）。
        // ⚠️ **但它不是那条红的成因**：实测加镐后仍是 `side_break_failed`，真因见下面 `openScope` 的顺序
        // （`protected_area`）。本行留下是为了让夹具测的是**回收路径**，不是"手挖石头要多久"。
        FixtureToolKit.ensurePickaxe(bot);
        bot.controller().stopMovement();
        scope = new ScopeBuffer();
        // ⭐ `D-398`/`Z1`（2026-09-22）：**回收只在保护区内发生** —— 区外按裁定不记账、不恢复
        // ⇒ 不摆这个前提，下面的账本播种会被 `recordPlacement` 整批跳过（静默假绿：
        // `pending=0` → `nothing_to_restore` → 三条判据在"什么都没发生"的世界里全绿）。
        // 前提 = 认领场景区块 + L2 任务区封套（理由见 `FixtureClaim` 类注释）。
        //
        // ⭐⭐ `A4`（2026-09-23）：**顺序不能反** —— 必须**先开作用域、再声明任务区**。
        // `JobAreaRegistry.zoneOf` 是**按"当前作用域"**找生效任务区的：
        //     `scopeId = WorldModLedger.currentScope(server, owner); zone = ZONES.get(scopeId);`
        // 旧版反着来（先 `protect` ⇒ 任务区挂在外层电池步作用域 `#N:Regression:<step>` 上，
        // 再 `openScope("c2_underfoot")` ⇒ 当前作用域变成新的内层 `#N+1`）⇒ 回收期的每一次写入
        // 都在**另一个**作用域上 ⇒ `zoneOf` 返回 null ⇒ `ZoneAuthority` 判 `protected_area`
        // ⇒ `BlockInteraction.breakable=false` ⇒ `MineRunner: TARGET_NOT_BREAKABLE` ⇒ `restored=0`。
        // **实测链条（2026-09-23，探针已删）**：`[PROBE-A4] breakAllowed=true refusal=protected_area
        // grant=RestoreScope:SCAFFOLD_RESTORE`；`[PathRetry] plan_failed UNREACHABLE goal=…`；
        // `[MineTask] failure=TARGET_NOT_BREAKABLE`。⇒ 与**几何/工具无关**（旧注释把它记成"几何拆不到"，
        // 那是**误诊**；`ensurePickaxe` 仍然必要，但它不是这条红的成因）。
        // 正确形状的参照 = `task/LedgerScopeCheckTask`（先 `openScope` 后 `protect`）。
        String scopeId = WorldModLedger.openScope(level.getServer(), bot.getUUID(), "c2_underfoot");
        this.scopeId = scopeId;
        // ⚠️ 前提盒子必须**同时**覆盖：① 阶梯 + 摔落面；② 臂②那格（南侧 3 格）；③ 臂③的远区块
        // （认领是**按区块**的，`x+512` ⇒ 顺带认领中间那 33 列区块 —— 这些区块**不读不写**，
        //  认领本身是纯数据操作，不会加载任何区块，这正是臂③能成立的前提）。
        zone = FixtureClaim.protect(level, bot.getUUID(),
                new BlockPos(ORIGIN.getX() - 2, FLOOR_Y, ORIGIN.getZ() - 2),
                new BlockPos(ORIGIN.getX() + FAR_OFFSET_X + 2, FLOOR_Y + PILLAR_H + 2,
                        ORIGIN.getZ() + FLOOR_DZ),
                "region_lumber");
        if (!zone.ok()) {
            failures.add("FIXTURE_ZONE_PREMISE_FAILED " + zone.describe());
            BotLog.warn("[C2] 前提未成立：{}", zone.describe());
            return;
        }
        // ⭐ **自下而上**播种（= 真机 `PILLAR` 上行的真实顺序）
        for (int i = 0; i < PILLAR_H; i++) {
            BlockPos pos = stepPos(i);
            WorldModLedger.recordPlacement(level, bot.getUUID(),
                    // ⚠️ requester 必须含**派生前缀**（`tools/policy-map.py` 的 derived_markers：
                    // check/probe/dump/diagnostic/regression/battery/demo）⇒ 否则 `check-policy-matrix` 报
                    // "未登记的 requester 字面量"（2026-09-22 我第一版用了 `c2-fixture` ⇒ 门禁红了一次）。
                    com.dddgn.alice.write.Attribution.of("check:c2-underfoot",
                            com.dddgn.alice.write.WriteReason.STEP_PLACEMENT),
                    pos, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
        }
        // ⭐ `RC1` 臂②（`D-403` 保留条件之②「现场仍是我方」）：账本记 `COBBLESTONE`，现场是 `DIRT`
        // ⇒ 回收**必须**当场放弃 + 点名 `not_ours`，而且**绝不许拆**（世界事实断言：DIRT 原封不动）。
        WorldModLedger.recordPlacement(level, bot.getUUID(),
                com.dddgn.alice.write.Attribution.of("check:c2-underfoot",
                        com.dddgn.alice.write.WriteReason.STEP_PLACEMENT),
                NOT_OURS_POS, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
        restore = new RestoreScopeTask(bot, scope, scopeId);
        BotLog.info("[C2] 账本播种 {} 块（自下而上）+ 臂②现场 {} 实际={} ｜ scope={} pending={}", PILLAR_H,
                NOT_OURS_POS.toShortString(), blockIdAt(level, NOT_OURS_POS), scopeId,
                WorldModLedger.pendingTemporary(level.getServer(), scopeId).size());
    }

    /**
     * ⭐ `RC1` 臂③（保留条件之①「已加载」）：**第二次** `RestoreScopeTask`，队列里**只有**远区块那一条。
     *
     * <h2>为什么必须分两个阶段（而不是把三条条目塞进同一次回收）</h2>
     * ① `buildQueue` 会用 `centroid/spreadRadius` 给 `ScopeBuffer.begin(center, radius)` 定范围：
     * 队列里混进 512 格外的条目 ⇒ 半径被拉到 256 格 ⇒ 掉落物收集范围被无谓放大；
     * ② 更要紧的是**臂③的前提必须"没被别的东西先读过"**（见 `WorldModLedger.dropStale` 的未加载守卫）。
     *
     * <p>臂①留下的"脚下那格"条目先**销掉**（断言已取）⇒ 本臂队列干净 ⇒ `pending` 只有远区块那条。
     */
    private void startFarArm(ServerLevel level) {
        WorldModLedger.forget(level, stepPos(PILLAR_H - 1));
        // ⭐⭐ **必须另开一个内层作用域**（`openScopes` 是"owner → 当前 scope"的映射，再开一个就顶掉）：
        // 否则远区块那条目会进**臂①②的同一个队列** ⇒ `buildQueue` 的 `centroid/spreadRadius` 被拉到
        // 256 格 ⇒ `ScopeBuffer.begin(center, radius)` 罩住半个世界（掉落物收集范围被无谓放大）。
        // ⚠️ 任务区封套挂在**臂①的作用域**上 ⇒ 本臂**不依赖**它：臂③只读一次账本 + 一次 `isLoaded`，
        // **不拆任何方块**（这正是"未加载 ⇒ 不碰"的语义）。
        farScopeId = WorldModLedger.openScope(level.getServer(), bot.getUUID(), "c2_chunk_not_loaded");
        // ⚠️ **不碰世界**：`recordPlacement` 只写账本（`previous/placed` 由入参给出，不读方块）。
        WorldModLedger.recordPlacement(level, bot.getUUID(),
                com.dddgn.alice.write.Attribution.of("check:c2-underfoot",
                        com.dddgn.alice.write.WriteReason.STEP_PLACEMENT),
                farPos(), Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
        check("⭐ 臂③ 前提：{} 所在区块**本来就没加载**（实测 isLoaded="
                + level.isLoaded(farPos()) + "，chunk=(" + (farPos().getX() >> 4) + ","
                + (farPos().getZ() >> 4) + ")）", !level.isLoaded(farPos()));
        scope = new ScopeBuffer();
        restore = new RestoreScopeTask(bot, scope, farScopeId);
        BotLog.info("[C2] 臂③ SETUP 远区块条目 {}（+{} 格）scope={} pending={}", farPos().toShortString(),
                FAR_OFFSET_X, farScopeId,
                WorldModLedger.pendingTemporary(level.getServer(), farScopeId).size());
    }

    /**
     * ⭐ `D-472` 臂④（**换不到安全站位**）：第三次 `RestoreScopeTask`，队列里**只有**孤悬的那一块。
     *
     * <h2>为什么这一臂必须存在</h2>
     * 臂① 的旧语义是"脚下那格必须被守卫**拒绝**"（`notes` 点名 `underfoot_unsafe`）。`D-472` 之后
     * 那条语义**不再成立**：阶梯几何里**换得到**侧面站位（规划器给 `4001,101,2600`）⇒ 三格
     * **全部被安全拆掉**（实测 `restored=3 remaining=0`）。⇒ `underfoot_unsafe` 这条**放弃路径**
     * 要另找正例，否则"如实归因、绝不静默"（`D-403`）重新变成零覆盖。
     *
     * <h2>几何（为什么它必然"换不到"）</h2>
     * 方块孤悬在 {@code FLOOR_Y + 10}：① 正下方是空气 ⇒ 拆了必掉；② 地板在 {@code FLOOR_Y}，
     * 从地板脚位（{@code FLOOR_Y+1}）到方块中心 ≈ 10 格，**远超触及**；③ 四周全是空气 ⇒
     * **规划器必然给不出任何站位点** ⇒ 执行器只能失败 ⇒ 必须按 `underfoot_unsafe` 如实归因。
     */
    private void startFloatArm(ServerLevel level) {
        place(level, floatPos(), Blocks.COBBLESTONE);
        // bot 站到**它上面**（这一步是"取件时就在目标上"，与臂① 的时序互补）
        bot.teleportTo(level, floatPos().getX() + 0.5D, floatPos().getY() + 1, floatPos().getZ() + 0.5D,
                0.0F, 0.0F);
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        floatScopeId = WorldModLedger.openScope(level.getServer(), bot.getUUID(), "c2_no_safe_stand");
        // ⚠️ 账本记的是"我方放置"（`recordPlacement` 只写账本，`previous/placed` 由入参给出）。
        WorldModLedger.recordPlacement(level, bot.getUUID(),
                com.dddgn.alice.write.Attribution.of("check:c2-underfoot",
                        com.dddgn.alice.write.WriteReason.STEP_PLACEMENT),
                floatPos(), Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
        // 前提断言（防空跑）：落点确实不安全 + bot 确实站在它上面 + 手里确实有镐（不许拿"没工具"当借口）
        check("⭐ 臂④ 前提：孤悬方块 " + floatPos().toShortString() + " 正下方是空气（落点不安全）"
                        + " 且 bot 正踩在它上面（实测 canWalkOn(target)="
                        + com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, floatPos())
                        + " foot=" + com.dddgn.alice.pathing.MovementHelper.footCell(level, bot)
                        .toShortString() + "）",
                !com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, floatPos())
                        && floatPos().equals(com.dddgn.alice.pathing.MovementHelper.footCell(level, bot)
                        .below()));
        check("⭐ 臂④ 前提：bot 手里有镐（不许把「没工具」当成「换不到站位」）",
                com.dddgn.alice.action.BlockInteraction.hasCorrectTool(bot, floatPos()));
        landingDropBaseC = biggestLandingDrop;
        unsupportedBaseC = unsupportedTicks;
        floatFootYAtStart = (int) Math.floor(bot.getY());
        scope = new ScopeBuffer();
        restore = new RestoreScopeTask(bot, scope, floatScopeId);
        BotLog.info("[C2] 臂④ SETUP 孤悬方块 {}（脚下空气、四周无可站面）scope={} pending={}",
                floatPos().toShortString(), floatScopeId,
                WorldModLedger.pendingTemporary(level.getServer(), floatScopeId).size());
    }

    private void place(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        touched.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, block.defaultBlockState(), 3);
    }

    private void cleanup(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        // ⭐ `RC1`（2026-09-24）：断言已取 ⇒ 把"注定留在账本里"的条目销掉。不是洁癖：
        // 臂③那条指向**未加载**区块，谁在后面跑一次 `dropStale` 都等于替它开图（正是本条要防的事）。
        WorldModLedger.forget(level, NOT_OURS_POS);
        // ⭐ `D-472`：臂① 现在会把三格全拆掉（不再留"脚下那格"）⇒ 下面这条只是**兜底销账**；
        // 臂④ 那条（孤悬那格）是**故意留着**的（断言已取），一并销掉。
        WorldModLedger.forget(level, stepPos(PILLAR_H - 1));
        WorldModLedger.forget(level, floatPos());
        WorldModLedger.forget(level, farPos());
        if (zone != null) {
            zone.release();   // 夹具纪律：结束复位（含失败路径）
        }
        WorldModLedger.closeScope(level.getServer(), bot.getUUID());
        BlockPos home = new BlockPos(ORIGIN.getX(), FLOOR_Y + 1, ORIGIN.getZ());
        // ⭐ 夹具卫生：回**进来时**的脚位（不是自己的场景原点 —— 那里多半已经是空中）
        BlockPos back = entryFoot != null ? entryFoot : home;
        bot.teleportTo(level, back.getX() + 0.5D, back.getY(), back.getZ() + 0.5D, 0.0F, 0.0F);
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}

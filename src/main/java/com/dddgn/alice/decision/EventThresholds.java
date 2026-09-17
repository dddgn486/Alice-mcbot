package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * **事件阈值**（S4 事件层 / D-150）：把"值得开口的病症"变成事件，**只报可行动的**，
 * 并且**同一次病症只报一次**（阈值跨越 = 事件；`hysteresis` 复位后才可能再报）。
 *
 * <p>为什么必须有它：在此之前决策层的唯一触发是"任务终态"——"工具快坏了""卡住不动了""无活可干"
 * 这三类**可行动病症**完全没人上报，bot 会安静地把镐子磨没、或者安静地卡住。
 *
 * <p>纪律（对齐项目"噪声=隐患"）：
 * <ul>
 *   <li>只报**三档之内**的可行动病症：{@code TOOL_LOW}（工具耐久见底）、{@code STUCK}（有任务但长时间不动）、
 *       {@code DANGER}（维生中断 / **掉血**，见 {@link #HEALTH_LOSS_COOLDOWN_TICKS}）；</li>
 *   <li>树叶清障、单次失败重试这类**噪声**不上报；</li>
 *   <li>每种病症**跨越阈值只报一次**，复位阈值更高（滞回），避免抖动刷屏。</li>
 * </ul>
 *
 * <p>⚠️ 2026-09-15（S-5）更正一处**文档与代码不符**：上面"三档"里的 {@code DANGER} 此前**只有 `S-1`
 * 在维生中断时记事件**，本类里根本没有对应判据（"三档"是一句空话）。现在补上真正属于本层的第三档：
 * {@link #checkHealthLoss} —— **掉血**（读 {@code HazardState.previousHealth}）。
 */
public final class EventThresholds {

    /** 工具耐久低于该比例 ⇒ 报 TOOL_LOW；回到该比例以上才复位（滞回）。 */
    public static final float TOOL_LOW_RATIO = 0.20F;
    public static final float TOOL_REARM_RATIO = 0.35F;
    /**
     * **有移动意图**却脚位连续不动这么多 tick ⇒ 报 STUCK。
     *
     * <p>为什么判据里必须有"移动意图"（`controller.hasActiveMovement()`）：只有"有任务且不动"会把
     * **等待态**（等 LLM 回复、等请示答复、等冷却）误报成病症 —— 那些时刻决策层什么都做不了，
     * 报了就是噪声。真卡住的定义是"**正在走**却一格没挪"。
     */
    public static final int STUCK_WINDOW_TICKS = 200;

    /**
     * **长作业周期复评的窗口**（M2 / G2）：有任务在跑、却连续这么多 tick **没有任何可观测进度**
     * ⇒ 报一次 {@code NO_PROGRESS}。
     *
     * <p>**默认 0 = 关**。为什么默认关：这是"**自主**"的物理载体（没有它，长作业对决策层是黑箱：
     * 开始响一次、结束响一次），但打开它 = 增加决策调用频率 ⇒ **由使用者显式开启**（对齐
     * `LlmConfig.idleDecisionEnabled` 同为默认关的既有取舍）。夹具/测试用 {@link #setNoProgressWindow}。
     *
     * <p>**可观测进度的定义**（三者任一变化即算有进度）：① 任务的 `Job.progressSummary()`；
     * ② bot 脚位；③ 背包指纹。⇒ 走路中的任务**不会**误报（脚位在变），真停滞的才会。
     * **例外**：开着容器菜单时不算（等交互是**等待态**，不是病症 —— 同 `STUCK` 的判据纪律）。
     */
    public static volatile int NO_PROGRESS_WINDOW_TICKS = 0;

    /**
     * **掉血上报的最小间隔**（tick，S-5 / 2026-09-15）：连续受伤**合并**成一条 {@code DANGER} 事件。
     *
     * <p>为什么要它：掉血的事实来自 {@code HazardState.previousHealth}（此前**只写不读** ⇒ "bot 在掉血"
     * 对决策层完全不可见）。但直接"每 tick 掉血就报"会把 32 槽的事件环刷满（火里烧伤每 20 tick 一次、
     * 岩浆里每次 1 点）⇒ 用**冷却 + 累计**：一条事件说清"这段时间一共掉了多少"。
     */
    public static final int HEALTH_LOSS_COOLDOWN_TICKS = 40;

    private static final class State {
        boolean toolReported;
        BlockPos lastFoot;
        long lastMoveTick = -1L;
        /** 上一次上报 STUCK 的（脚位, 任务）—— 同一 episode 只报一次，避免"意图闪烁"反复刷屏。 */
        BlockPos lastReportedFoot;
        String lastReportedTask = "";
        /** M2：上一次"有进度"的指纹与其时刻；同 episode 只报一次 NO_PROGRESS。 */
        String lastProgress = null;
        long progressSinceTick = -1L;
        /** D-268⑤：**本 episode 内**达到过的最近目标距离（格）。
         *  只在"比本段更近"时更新，并在每次真正进展时重新取基线 ⇒ 撞到 0 也不会永久卡死；
         *  走远/绕圈都不算推进（旧口径把"脚位变了"当进度 ⇒ 走远也算"有进度"，是错的）。 */
        double episodeMinDistance = Double.MAX_VALUE;
        boolean noProgressReported;
        int noProgressEmits;
        /** 队列第④项：上一次低频进度事件的 tick（-1 = 还没报过）。 */
        long lastProgressEventTick = -1L;
        int progressEmits;
        /** S-5：掉血上报的基准血量（回血则跟随，"上一段伤"就此结账）与上次上报时刻（-1 = 还没报过）。 */
        float healthBaseline = Float.NaN;
        long healthLossReportedAt = -1L;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private EventThresholds() {
    }

    /** 每 tick（`BotManager` 调度循环里调）：只读观察 + 越阈值时**发一次**事件。 */
    public static void tick(BotPlayer bot) {
        State state = STATES.computeIfAbsent(bot.getUUID(), ignored -> new State());
        long now = bot.getServer().getTickCount();

        checkToolDurability(bot, state);
        checkStuck(bot, state, now);
        checkNoProgress(bot, state, now);
        maybeEmitProgress(bot, state, now);
        checkHealthLoss(bot, state, now);
    }

    /**
     * **掉血 ⇒ `DANGER` 事件**（S-5，2026-09-15）—— `HazardState.previousHealth` 的**第一个读者**。
     *
     * <p>为什么放在阈值层而不是维生层：维生层只回答"危险是什么、要不要否决"；"**这件事要不要上报**"是
     * 本层的职责（同样的信号在烧 20 秒和蹭掉 1 点血时的处理强度不同）。也正因如此，它**不要求有任务**
     * —— 空手站在火里的掉血和跑任务时的掉血一样要可见。
     *
     * <p>判据（滞回 + 冷却，两条都是"抗噪声"）：
     * <ol>
     *   <li>**只在真的掉了的那一 tick 才可能上报**（{@link HazardState#healthLost()}）—— 否则"停在
     *       19/20 血"这种静止状态会每 40 tick 被重复报一次"掉血"；</li>
     *   <li>两次上报至少间隔 {@link #HEALTH_LOSS_COOLDOWN_TICKS}；血量回到基准以上（回血）⇒ 基准跟随，
     *       把"下一段伤"当成新的一笔。</li>
     * </ol>
     */
    private static void checkHealthLoss(BotPlayer bot, State state, long now) {
        HazardState hazard = com.dddgn.alice.survival.SurvivalSystem.current(bot);
        float health = hazard.health();
        if (Float.isNaN(state.healthBaseline)) {
            state.healthBaseline = health;   // 首次观察：只立基准，不报（避免"上线即报掉血"）
            return;
        }
        if (health >= state.healthBaseline) {
            state.healthBaseline = health;   // 满血/回血 ⇒ 结账前一段伤
            return;
        }
        if (!hazard.healthLost()) {
            return;                          // 本 tick 没掉血（只是还没回满）⇒ 不重复报
        }
        if (state.healthLossReportedAt >= 0L
                && now - state.healthLossReportedAt < HEALTH_LOSS_COOLDOWN_TICKS) {
            return;                          // 冷却中：把这段伤**累计**到下一条里
        }
        float lost = hazard.healthLostAmount();
        float total = state.healthBaseline - health;
        state.healthLossReportedAt = now;
        state.healthBaseline = health;
        BotLog.warn("[Threshold] 掉血 DANGER bot={} tickLoss={} total={} health={}/{} hazard={} pos={}",
                bot.getName().getString(), fmt(lost), fmt(total), fmt(health), fmt(bot.getMaxHealth()),
                hazard.type(), bot.blockPosition().toShortString());
        // ⚠️ 用统一出口 `emit`（环 + 日志 + **通知决策层**）：掉血是**可行动病症**（该逃/该打/该吃），
        // 与 TOOL_LOW/STUCK 同级。通知由 `GoalDirector.maybeTrigger` 节流 + 自检暂停兜底，不会刷屏。
        emit(bot, "DANGER", "warn",
                "掉血 -" + fmt(total) + "（剩余 " + fmt(health) + "/" + fmt(bot.getMaxHealth()) + "）",
                "delta=" + fmt(total) + " health=" + fmt(health) + " max=" + fmt(bot.getMaxHealth())
                        + " hazard=" + hazard.type() + " pos=" + bot.blockPosition().toShortString());
    }

    private static String fmt(float value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** 工具耐久：取背包里**剩余耐久最低**的斧/镐（"最可能先坏的那把"）。 */
    private static void checkToolDurability(BotPlayer bot, State state) {
        ItemStack worst = ItemStack.EMPTY;
        float worstRatio = 1.0F;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            boolean tool = stack.getItem() instanceof AxeItem
                    || stack.getItem() instanceof PickaxeItem;
            if (!tool) {
                continue;
            }
            int max = stack.getMaxDamage();
            if (max <= 0) {
                continue;
            }
            float ratio = (float) (max - stack.getDamageValue()) / max;
            if (ratio < worstRatio) {
                worstRatio = ratio;
                worst = stack;
            }
        }
        if (worst.isEmpty()) {
            state.toolReported = false;
            return;
        }
        if (!state.toolReported && worstRatio <= TOOL_LOW_RATIO) {
            state.toolReported = true;
            // 基-9：事件文案要**可行动** —— 说清"这是哪类工具、在不在快捷栏、有没有更好的可换"，
            // 于是决策层可以选 `maintain_tool`（能自己解决）或如实上报（解决不了）。
            com.dddgn.alice.bot.ToolSupply.Kind kind = worst.getItem() instanceof AxeItem
                    ? com.dddgn.alice.bot.ToolSupply.Kind.AXE
                    : com.dddgn.alice.bot.ToolSupply.Kind.PICKAXE;
            com.dddgn.alice.bot.ToolSupply.Snapshot toolState =
                    com.dddgn.alice.bot.ToolSupply.inspect(bot, kind);
            String fix = !toolState.inHotbar() ? "（在主背包，可用 maintain_tool 搬进快捷栏）"
                    : toolState.bestMainRemaining() > toolState.remaining()
                    ? "（主背包有更好的，可用 maintain_tool 换上）"
                    : "（身上没有更好的 ⇒ 需外部补充）";
            String summary = "工具耐久见底：" + worst.getHoverName().getString() + " 剩余 "
                    + (worst.getMaxDamage() - worst.getDamageValue()) + "/" + worst.getMaxDamage()
                    + String.format(java.util.Locale.ROOT, "（%.0f%%）", worstRatio * 100) + fix;
            emit(bot, "TOOL_LOW", "warn", summary, "ratio=" + String.format(java.util.Locale.ROOT,
                    "%.2f", worstRatio));
        } else if (state.toolReported && worstRatio >= TOOL_REARM_RATIO) {
            state.toolReported = false;   // 换了新工具/修好了 ⇒ 复位，下次见底还能报
        }
    }

    /**
     * 卡住：**有任务在跑 + 有移动意图**，脚位却长时间不变。
     *
     * <p>无任务（idle）或没有移动意图（等待态）都**不计时**（时钟拨到当前 tick）—— 那不是病症。
     * 同一（脚位, 任务）episode 只报一次；脚位一变（脱困）或换了任务才允许再报。
     */
    private static void checkStuck(BotPlayer bot, State state, long now) {
        String task = BotManager.currentTaskSummary(bot);
        BlockPos foot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
        boolean movingIntent = bot.controller() != null && bot.controller().hasActiveMovement();
        if (task == null || !movingIntent) {
            state.lastFoot = foot;
            state.lastMoveTick = now;
            return;
        }
        if (!foot.equals(state.lastFoot)) {
            state.lastFoot = foot;
            state.lastMoveTick = now;
            return;
        }
        if (state.lastMoveTick < 0) {
            state.lastMoveTick = now;
            return;
        }
        long still = now - state.lastMoveTick;
        if (still < STUCK_WINDOW_TICKS) {
            return;
        }
        if (foot.equals(state.lastReportedFoot) && task.equals(state.lastReportedTask)) {
            return;
        }
        state.lastReportedFoot = foot;
        state.lastReportedTask = task;
        emit(bot, "STUCK", "warn", "有移动意图但 " + still + " tick 没挪过格：" + task,
                "foot=" + foot.toShortString());
    }

    /**
     * **长作业周期复评**（M2 / G2）：有任务在跑却没有**可观测进度** ⇒ 报一次 {@code NO_PROGRESS}。
     *
     * <p>为什么它不是 `STUCK` 的重复：`STUCK` 要求"**正在走**却不动"（有移动意图），
     * 而"**在干活却什么都没产出**"（挖不动、目标一直是同一个、背包没变）它**完全看不见**。
     * 长作业里静音的是后者 —— 一个 `maxTicks` 很长的 Job 只会**开始响一次、结束响一次**。
     */
    /**
     * **低频进度事件**（队列第④项）：有任务在跑 ⇒ 每 {@link #PROGRESS_EVENT_INTERVAL_TICKS} tick 报一次
     * 「当前任务 + 它的进度摘要」。默认关（0）⇒ 生产行为**不变**；夹具/自检开窗后才生效。
     */
    private static void maybeEmitProgress(BotPlayer bot, State state, long now) {
        int interval = PROGRESS_EVENT_INTERVAL_TICKS;
        if (interval <= 0) {
            state.lastProgressEventTick = -1L;
            return;
        }
        String task = BotManager.currentTaskSummary(bot);
        if (task == null) {
            state.lastProgressEventTick = -1L;   // idle：没有"进度"可报
            return;
        }
        if (state.lastProgressEventTick >= 0 && now - state.lastProgressEventTick < interval) {
            return;
        }
        String job = BotManager.currentTaskProgressSummary(bot);
        state.lastProgressEventTick = now;
        state.progressEmits++;
        emit(bot, "PROGRESS", "info",
                "进度：任务=" + task + " 进度=" + (job == null ? "-" : job),
                "interval=" + interval + " tick=" + now);
    }

    private static void checkNoProgress(BotPlayer bot, State state, long now) {
        int window = NO_PROGRESS_WINDOW_TICKS;
        String progress = window <= 0 ? null : progressFingerprint(bot);
        if (progress == null) {
            // 关着 / 没任务 / 正在等容器交互 ⇒ 复位基线（下次真正开始跑时从头计时）
            state.lastProgress = null;
            state.progressSinceTick = now;
            state.noProgressReported = false;
            state.episodeMinDistance = Double.MAX_VALUE;   // 新 episode：重新开始算"最近到过多近"
            return;
        }
        // **D-268⑤**：第 4 项进度 = **目标被推进**（本 episode 内比之前更近）——
        // 独立于"任务/进度/背包"指纹：走远、原地绕圈、来回踱步都**不算**推进。
        boolean distanceProgress = false;
        double d = goalDistance(bot);
        if (d >= 0) {
            if (state.episodeMinDistance == Double.MAX_VALUE) {
                state.episodeMinDistance = d;                      // 本段基线
            } else if (d < state.episodeMinDistance - 0.5D) {
                distanceProgress = true;
                state.episodeMinDistance = d;
            }
        }
        if (!progress.equals(state.lastProgress) || distanceProgress) {
            // 有进度 ⇒ 重新武装（滞回的自然形式：不需要第二个比例阈值）
            state.lastProgress = progress;
            state.progressSinceTick = now;
            state.noProgressReported = false;
            state.episodeMinDistance = d >= 0 ? d : Double.MAX_VALUE;   // 新一段的基线就是"现在"
            return;
        }
        if (state.progressSinceTick < 0) {
            state.progressSinceTick = now;
            return;
        }
        long still = now - state.progressSinceTick;
        if (still < window || state.noProgressReported) {
            return;
        }
        state.noProgressReported = true;
        state.noProgressEmits++;
        emit(bot, "NO_PROGRESS", "warn",
                "有任务在跑但 " + still + " tick 无可观测进度：" + BotManager.currentTaskSummary(bot),
                "window=" + window + " progress=" + progress);
    }

    /** 距**当前任务目标**的格数；没有方块目标（实体/区域/无任务）⇒ **-1**（= 不判几何进展）。 */
    private static double goalDistance(BotPlayer bot) {
        BlockPos goalPos = BotManager.currentTaskTargetPos(bot);
        if (goalPos == null) {
            return -1D;
        }
        BlockPos foot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
        return Math.sqrt(foot.distSqr(goalPos));
    }

    /**
     * 进度指纹（**不含**"目标距离"）：任务 + Job 进度 + 背包。
     *
     * <p>"目标是否被推进"由 {@link #goalDistance} 在 {@code checkNoProgress} 里单独判（D-268⑤）；
     * 只有**没有方块目标**的任务（实体/区域）才把脚位放进指纹 —— 否则那类任务永远算"没进度"。
     */
    private static String progressFingerprint(BotPlayer bot) {
        String task = BotManager.currentTaskSummary(bot);
        if (task == null) {
            return null;   // idle：没有"进度"可言
        }
        // 等容器交互是**等待态**（不是病症）：`STUCK` 的判据纪律同样适用（别把等待报成卡住）
        if (bot.containerMenu != null && bot.containerMenu != bot.inventoryMenu) {
            return null;
        }
        String jobRaw = BotManager.currentTaskProgressSummary(bot);
        // **survey/17 §4.2(c)（2026-09-17）**：**失败计数不是进度**。
        // 外部 141 小时实测的"种土豆"失效模式（`failed` 一直涨、`mined` 不动）若把 `failed=N` 当进度，
        // 会**反武装**本判据（越失败越显得"在动"）⇒ 从进度信号里剔除它。产出（`mined x/y`）与背包照旧算进度。
        String job = jobRaw == null ? null : jobRaw.replaceAll("\\s*failed=\\d+", "");
        BlockPos foot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
        // **D-268⑤（2026-09-17 用户裁定：语义 = "白忙一场"）**：进度**不是**"脚位变了"。
        // 原指纹含 `foot.toShortString()` ⇒ 只要脚一动就算有进度 ⇒ "原地绕圈/走远走回"永远不报
        // NO_PROGRESS（长作业里最典型的"白忙一场"被漏掉）。
        // 现在：**有方块目标**的任务不看脚位（看"有没有更近"，见 `goalDistance`）；
        // 只有没有方块目标（实体/区域）的任务才退回脚位。
        String where = goalDistance(bot) >= 0 ? "-" : foot.toShortString();
        return task + "|" + (job == null ? "-" : job) + "|" + where
                + "|" + inventoryFingerprint(bot);
    }

    /** 背包指纹：物品 id + 数量的滚动和（只判"变没变"，不做语义）。 */
    private static long inventoryFingerprint(BotPlayer bot) {
        long hash = 17L;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            hash = hash * 31L + net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getKey(stack.getItem()).hashCode();
            hash = hash * 31L + stack.getCount();
            hash = hash * 31L + stack.getDamageValue();
        }
        return hash;
    }

    /** **测试/夹具用**：设置 NO_PROGRESS 窗口（0 = 关）。生产默认关，见字段文档。 */
    /**
     * **低频进度事件（队列第④项 / `survey/16 §1`，2026-09-17）**：间隔 tick 数，**0 = 关（生产默认关）**。
     *
     * <p>为什么需要：长作业里 `progressSummary()`（`mined 3/16 failed=2 …`）**只在决策时刻被顺带看到**，
     * 于是"它在动还是在种土豆"这件事，决策层与玩家都只能等到出问题才知道。本事件把进度**按固定低频**
     * 送进既有事件环（`DecisionEvents`：环 + 日志 + 通知决策层），**不新增任何 S2C/协议面**。
     *
     * <p>⚠️ 按需/低频是硬要求：一次可视化识别比一次文本决策贵得多，进度上报同理（`survey/17 §1.7b`）。
     */
    /**
     * **默认打开、粗粒度（D-289，2026-09-17 用户裁定 8-3 (i)）**：200 tick = 10 秒一条进度事件。
     * 为什么默认开：长任务没有进度就"看不见"✗；10 秒一条的代价极低 ✓，`/alice progress [<ticks>]` 可随时改/关 ✓。
     * 想回到"默认静默"就把这里改回 0（判据见门禁 PG-P1）。
     */
    public static volatile int PROGRESS_EVENT_INTERVAL_TICKS = 200;

    /** **测试/夹具用**：设置进度事件间隔（0 = 关）。 */
    public static void setProgressEventInterval(int ticks) {
        PROGRESS_EVENT_INTERVAL_TICKS = Math.max(0, ticks);
    }

    /** **测试/汇报用**：本 bot 累计报过几次低频进度事件。 */
    public static int progressEmits(BotPlayer bot) {
        State state = STATES.get(bot.getUUID());
        return state == null ? 0 : state.progressEmits;
    }

    public static void setNoProgressWindow(int ticks) {
        NO_PROGRESS_WINDOW_TICKS = Math.max(0, ticks);
    }

    /**
     * **测试/夹具用**：把"无进度"时钟拨到当前 tick 并清掉已报标记（不改窗口）。
     * 理由与 {@link #resetStuckTracking} 相同：夹具无法保证进入用例时 bot 恰好刚开始这一段。
     */
    public static void resetNoProgressTracking(BotPlayer bot) {
        State state = STATES.computeIfAbsent(bot.getUUID(), ignored -> new State());
        state.lastProgress = null;
        state.progressSinceTick = bot.getServer().getTickCount();
        state.episodeMinDistance = Double.MAX_VALUE;
        state.noProgressReported = false;
    }

    /** **测试/汇报用**：本 bot 累计报过几次 NO_PROGRESS。 */
    public static int noProgressEmits(BotPlayer bot) {
        State state = STATES.get(bot.getUUID());
        return state == null ? 0 : state.noProgressEmits;
    }

    /**
     * **测试/夹具用**（S-5）：把掉血基准拨到当前血量、清掉冷却。
     *
     * <p>理由与 {@link #resetNoProgressTracking} 相同：夹具无法保证"进入用例前 40 tick 内没报过掉血"
     * （前面的电池步骤可能已经让 bot 受过伤、冷却还在走）⇒ 不拨时钟的话，用例会**偶发**看不到事件。
     */
    public static void resetHealthTracking(BotPlayer bot) {
        State state = STATES.computeIfAbsent(bot.getUUID(), ignored -> new State());
        state.healthBaseline = bot.getHealth();
        state.healthLossReportedAt = -1L;
    }

    /** **测试用**：当前是否处于"已报未复位"档。 */
    public static boolean noProgressReported(BotPlayer bot) {
        State state = STATES.get(bot.getUUID());
        return state != null && state.noProgressReported;
    }

    private static void emit(BotPlayer bot, String type, String severity, String summary, String data) {
        // 统一出口（DecisionEvents）：环 + 日志 + 通知决策层（自检暂停时只记录不通知）
        DecisionEvents.emit(bot, type, severity, summary, data);
    }

    /** 测试/汇报用：当前是否处于"工具见底"档。 */
    public static boolean toolLowReported(BotPlayer bot) {
        State state = STATES.get(bot.getUUID());
        return state != null && state.toolReported;
    }

    /**
     * **测试/复位用**：把"卡住"时钟拨到当前 tick（不改阈值）。
     *
     * <p>为什么需要它：`STUCK` 的判据是"脚位连续不动 200 tick"，而夹具无法保证进入该用例时
     * bot 恰好刚动过（前面的电池步骤可能已经让它站了几百 tick）⇒ 事件会在用例准备好之前就报掉。
     * 有了它，用例可以先把时钟拨零、再开始计时，判据仍然是真的。
     */
    public static void resetStuckTracking(BotPlayer bot) {
        State state = STATES.computeIfAbsent(bot.getUUID(), ignored -> new State());
        state.lastFoot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
        state.lastMoveTick = bot.getServer().getTickCount();
        state.lastReportedFoot = null;
        state.lastReportedTask = "";
    }

    public static void forget(UUID botId) {
        STATES.remove(botId);
    }
}

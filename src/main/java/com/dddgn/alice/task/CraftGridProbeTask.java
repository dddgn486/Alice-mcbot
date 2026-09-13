package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.CraftMenuIntrospection;
import com.dddgn.alice.task.craft.CraftStation;
import com.dddgn.alice.task.craft.GridDiscovery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **合成网格探针**（阶段 3-A / S1-3，D-192）：一次右键跑完，输出 `SUMMARY key=VALUE`。**只读**。
 *
 * <p>它要回答的问题（决定 L3"标签页层"到底要不要发包）：
 * "**不发送任何页签消息**的前提下，站点菜单里到底有没有一个**可寻址、且 active** 的 3×3 网格 + 结果槽？"
 * —— 有 ⇒ 按用户裁定"打开子标签页与不打开实际没区别" ⇒ **L3 什么都不做**（也就没有需要复位的东西）；
 * 没有 ⇒ L3 才需要真的切换状态（那时才谈发包/反射，且必须自断言切换成功）。
 *
 * <p>输出（聊天 + 日志同款）：
 * <pre>
 * [CraftGridProbe] selected=upgradetab station=upgradetab pos=46, 64, 306
 * [CraftGridProbe] menu=StorageContainerMenu(sophisticatedstorage:storage) slots=96
 * [CraftGridProbe] discover=OK grid=3x3 slots=[54,55,…] result=63 inv=[…] matrix=CraftingItemHandler(9) …
 * [CraftGridProbe] slots: 0:Slot/ItemStackHandler@-100,-100 …（全表，含 x,y 与 active）
 * [CraftGridProbe] SUMMARY selected=… grid_found=true menu_slots=96 … read_only=true verdict=PASS
 * </pre>
 *
 * <p>**只读强度的断言**：跑完账本里**没有**我方临时方块（`read_only=true`），且**不发包**（`tab_action=none`）。
 * 夹具自带传送（D-187 §6.9.1：独立物品入口没人替你摆位）。
 */
public class CraftGridProbeTask implements Task {

    /** 场景起点（与 `alice_test:craft_tab_course` 一致；探针靠它自带传送）。 */
    public static final BlockPos START = new BlockPos(46, 64, 304);
    private static final int SCAN_RADIUS = 6;
    private static final int MAX_TICKS = 400;
    /** 等"落地"的预算：teleport 之后要几 tick 才会 onGround（实测下一 tick 仍为 false）。 */
    private static final int SETTLE_TICKS = 40;

    private enum Phase { PREPARE, OPEN, REPORT, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> facts = new LinkedHashMap<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int phaseTicks;
    private CraftStation.Opened opened;
    private MenuSession session;
    private AbstractContainerMenu menu;

    public CraftGridProbeTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    /**
     * **是自检**：探针跑完不许招来决策层（D-189）。
     *
     * <p>2026-09-13 实测事故：本类当时只靠命名约定识别自检，而它叫 `…ProbeTask`、约定只认 `…CheckTask`
     * ⇒ 探针终态触发 LLM、自动起了两次 `region_lumber`（用户最早的抱怨原样复发）。
     * 现在**约定已扩展到 `*ProbeTask`**，这里再显式覆写一次：即使将来改名，也不会再让探针去招生产任务。
     */
    @Override
    public boolean isSelfCheck() {
        return true;
    }

    @Override
    public String taskName() {
        return "CraftGridProbe";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(opened != null && opened.pos() != null ? opened.pos() : START);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(",", failures);
    }

    @Override
    public Status tick() {
        if (phase == Phase.DONE) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        if (++ticks > MAX_TICKS) {
            failures.add("probe_timeout");
            return finish();
        }
        phaseTicks++;
        return switch (phase) {
            case PREPARE -> prepare();
            case OPEN -> open();
            case REPORT -> report();
            case DONE -> finish();
        };
    }

    private Status prepare() {
        bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        check("start_premise", bot.blockPosition().distSqr(START) <= 4.0D,
                "foot=" + bot.blockPosition().toShortString() + " start=" + START.toShortString());
        // 只读：起手把上一轮的幽灵账目销掉，便于末尾判"账本干净"
        int stale = WorldModLedger.dropStale(bot.serverLevel());
        record("stale_dropped", String.valueOf(stale));
        record("selected", CraftStation.selected(bot));
        record("candidates", CraftStation.describe(bot, SCAN_RADIUS));
        BotLog.info("[CraftGridProbe] selected={} candidates={}", CraftStation.selected(bot),
                CraftStation.describe(bot, SCAN_RADIUS));
        // **不在本 tick 开菜单**：刚 teleport 完的那一 tick 物理还没结算（`onGround` 可能是上一处的状态），
        // 而 `MenuSession.open` 有 K-3 门：空中一律硬拒（`menu_not_settled`）⇒ 会把"探针没摆好"记成"菜单打不开"。
        // 所以传送与开菜单**分成两个 tick**（D-187 §6.9 的老教训：夹具前提要自己保证）。
        return advance(Phase.OPEN);
    }

    private Status open() {
        if (opened == null) {
            // **等落地**：`MenuSession.open` 有 K-3 门（空中一律硬拒 `menu_not_settled`），
            // 而实测 teleport 之后**不止一 tick** 才会 `onGround=true`（17:01 日志：下一 tick 仍是 false）。
            // ⇒ 这里显式等"落地"再开菜单，等不到就如实失败（不猜、不硬开）。
            if (!bot.onGround()) {
                if (phaseTicks > SETTLE_TICKS) {
                    check("station_opened", false, "not_on_ground after " + phaseTicks + " ticks");
                    return finish();
                }
                return Status.RUNNING;
            }
            record("settle_ticks", String.valueOf(phaseTicks));
            opened = CraftStation.open(bot, SCAN_RADIUS);
            BotLog.info("[CraftGridProbe] open → {} foot={} onGround={}", opened.describe(),
                    bot.blockPosition().toShortString(), bot.onGround());
            if (!opened.ok()) {
                record("open", opened.describe());
                check("station_opened", false, opened.describe());
                return finish();
            }
            check("station_opened", true, opened.describe());
            record("station", opened.station().id()
                    + (opened.pos() == null ? "" : "@" + opened.pos().toShortString()));
            if (opened.menu() != null) {
                menu = opened.menu();
                return advance(Phase.REPORT);
            }
            session = opened.session();
            return Status.RUNNING;
        }
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            check("menu_opened", false, "state=FAILED reason=" + session.failure());
            return finish();
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > 60 ? failOpen() : Status.RUNNING;
        }
        check("menu_opened", true, "state=OPEN ticks=" + phaseTicks);
        menu = bot.containerMenu;
        return advance(Phase.REPORT);
    }

    private Status failOpen() {
        check("menu_opened", false, "timeout state=" + session.state());
        return finish();
    }

    private Status report() {
        // **对"来源未知的菜单"取值一律加保护**（2026-09-13 实测：一次 `menu.getType()` 就把服务端打崩）。
        // 探针是"看事实"的工具，任何意外都必须变成一行如实报告，而不是一次崩溃。
        try {
            return reportUnguarded();
        } catch (RuntimeException | LinkageError e) {
            record("probe_exception", e.getClass().getSimpleName() + ": " + e.getMessage());
            failures.add("probe_exception");
            BotLog.warn("[CraftGridProbe] 探针异常（已拦截，未崩服务端）：{}", e.toString());
            return finish();
        }
    }

    private Status reportUnguarded() {
        String menuDescription = GridDiscovery.describeMenu(menu);
        record("menu", menuDescription);
        BotLog.info("[CraftGridProbe] menu={}", menuDescription);

        GridDiscovery.Result discovery = GridDiscovery.discover(menu, bot);
        record("discover", discovery.describe());
        record("grid_found", String.valueOf(discovery.ok()));
        BotLog.info("[CraftGridProbe] discover={}", discovery.describe());

        // 全槽位事实表（含 x,y 与 active）——"点开标签页才显示"到底是不是渲染层，看这张表
        List<GridDiscovery.SlotInfo> slots = GridDiscovery.describeSlots(menu);
        StringBuilder table = new StringBuilder();
        for (GridDiscovery.SlotInfo info : slots) {
            if (!table.isEmpty()) {
                table.append(' ');
            }
            table.append(info.describe());
        }
        BotLog.info("[CraftGridProbe] slots({}): {}", slots.size(), table);
        record("menu_slots", String.valueOf(slots.size()));
        long inactive = slots.stream().filter(s -> !s.active()).count();
        record("inactive_slots", String.valueOf(inactive));

        // **只读反射诊断**（D-192）：当"方块实体存档里明明有升级槽/升级，菜单里却没有"时，
        // 必须把"菜单手里的包装器"与"方块实体手里的包装器"**分别**打印出来对比 —— 靠推理解决不了这种矛盾。
        for (String fact : CraftMenuIntrospection.facts(menu, bot.serverLevel())) {
            int eq = fact.indexOf('=');
            if (eq > 0) {
                record(fact.substring(0, eq), fact.substring(eq + 1));
            }
            BotLog.info("[CraftGridProbe] {}", fact);
        }

        // L3 判据：**没有发包**的前提下，网格是否已可寻址（有 ⇒ 按用户裁定"没区别就不用管"）
        record("tab_action", "none");
        record("grid_addressable_without_tab", String.valueOf(discovery.ok()));
        return finish();
    }

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private void record(String key, String value) {
        facts.put(key, value);
    }

    private void check(String name, boolean ok, String detail) {
        record(name, ok ? "true" : "false");
        BotLog.info("[CraftGridProbe] {}={} {}", name, ok ? "true" : "false", detail);
        if (!ok) {
            failures.add(name);
        }
    }

    private Status finish() {
        phase = Phase.DONE;
        // 只读硬断言：账本里不该有我方临时方块（探针不写世界）
        int pending = WorldModLedger.pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
        check("read_only", pending == 0, "pendingTemporary=" + pending);
        if (session != null && session.state() == MenuSession.State.OPEN) {
            session.close("probe_done");
        }
        session = null;
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            if (!summary.isEmpty()) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append(" verdict=").append(failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[CraftGridProbe] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[CraftGridProbe] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }
}

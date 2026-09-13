package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * **机器站点只读探针**（阶段 3-B / S2，D-206）：零参数、**零写入**，一次右键跑完输出 `SUMMARY`。
 *
 * <p>要回答的问题（S1 已经回答"配方读得出"；本步回答"**机器本身认不认得出来**"）：
 * <ol>
 *   <li>**机器方块**：按方块 id **命名空间**找（数据驱动，不写死机器清单）；</li>
 *   <li>**菜单**：开出来是什么类？`menu.slots` 里有哪些槽（槽类/容器/下标）？</li>
 *   <li>**进度数据**：菜单里有没有原版 `ContainerData` 字段？没有的话，容器/方块实体**自己**有没有
 *       进度类访问器（按**方法名形态**找，只读，不猜语义）？</li>
 *   <li>**零写入**：我方账本 pending=0（只读探针的自证）。</li>
 * </ol>
 *
 * <p>**只读**：只找方块、开菜单、读事实；不改世界、不发包、不派任务。认不出就**如实报码**。
 */
public class MachineStationProbeTask implements Task {

    /** 目标机器所属命名空间（换模组只改这里 + 场景里的方块）。 */
    private static final String NAMESPACE = "mekanism";
    private static final int SCAN_RADIUS = 6;
    private static final int MAX_TICKS = 400;
    private static final int OPEN_TICKS = 80;
    /** 进度类访问器的**方法名形态**（只认形态 + 只读调用，不认类名）。 */
    private static final String[] PROGRESS_HINTS = {"progress", "scaled", "active", "operating", "duration"};

    private enum Phase { FIND, OPEN, REPORT, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();

    private Phase phase = Phase.FIND;
    private int ticks;
    private int phaseTicks;
    private BlockPos machine;
    private MenuSession session;
    private boolean finished;

    public MachineStationProbeTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "MachineStationProbe";   // `*ProbeTask` 约定：探针不招 LLM
    }

    @Override
    public boolean isSelfCheck() {
        return true;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(machine == null ? bot.blockPosition() : machine);
    }

    @Override
    public String failureReason() {
        return String.join(",", failures);
    }

    @Override
    public Status tick() {
        if (finished) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        if (++ticks > MAX_TICKS) {
            failures.add("probe_timeout");
            return finish();
        }
        phaseTicks++;
        return switch (phase) {
            case FIND -> find();
            case OPEN -> open();
            case REPORT -> report();
            case DONE -> finish();
        };
    }

    private Status find() {
        var level = bot.serverLevel();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(bot.blockPosition().offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                bot.blockPosition().offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            if (!id.startsWith(NAMESPACE + ":")) {
                continue;
            }
            double distance = pos.distSqr(bot.blockPosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        record("machine_found", best != null ? "true" : "false");
        if (best == null) {
            check("machine_found", false, "半径 " + SCAN_RADIUS + " 内没有 " + NAMESPACE + " 方块（场景没摆？）");
            return finish();
        }
        machine = best;
        String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(machine).getBlock()).toString();
        record("machine_block", id + "@" + machine.toShortString());
        var blockEntity = level.getBlockEntity(machine);
        record("machine_block_entity", blockEntity == null ? "-" : blockEntity.getClass().getName());
        check("machine_found", true, id + "@" + machine.toShortString());
        if (!bot.onGround()) {
            return phaseTicks > OPEN_TICKS ? failAndFinish("not_on_ground") : Status.RUNNING;
        }
        session = MenuSession.open(bot, machine, 0);
        return advance(Phase.OPEN);
    }

    private Status open() {
        if (session == null) {
            return failAndFinish("menu_session_missing");
        }
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            return failAndFinish("menu_open_failed:" + session.failure());
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > OPEN_TICKS ? failAndFinish("menu_open_timeout") : Status.RUNNING;
        }
        return advance(Phase.REPORT);
    }

    /** 读事实：菜单类 / 槽位表 / 进度数据（`ContainerData` 或上游自述）/ 零写入。 */
    private Status report() {
        AbstractContainerMenu menu = bot.containerMenu;
        if (menu == null) {
            return failAndFinish("menu_closed");
        }
        record("menu_class", menu.getClass().getName());
        record("menu_slots", String.valueOf(menu.slots.size()));
        StringBuilder slots = new StringBuilder();
        for (Slot slot : menu.slots) {
            if (slots.length() > 0) {
                slots.append(' ');
            }
            slots.append('#').append(slot.index).append('=').append(slot.getClass().getSimpleName())
                    .append('/').append(slot.container.getClass().getSimpleName())
                    .append("(cs=").append(slot.getContainerSlot()).append(')');
        }
        record("slot_table", slots.length() == 0 ? "-" : slots.toString());
        BotLog.info("[MachineStation] 菜单 {}", facts.get("menu_class"));
        BotLog.info("[MachineStation] 槽位表 {}", facts.get("slot_table"));

        // ① 原版路径：菜单里有 `ContainerData` 字段吗（按类型找，不按名字）
        ContainerData data = findContainerData(menu);
        record("container_data", data == null ? "-" : data.getClass().getSimpleName() + "(count=" + data.getCount() + ")");
        // ② 上游自述：容器/方块实体上的**进度类访问器**（按方法名形态找，只读，不调用写方法）
        Object upstream = callNoArg(menu, "getTileEntity");
        record("upstream_accessor", upstream == null ? "-" : "getTileEntity→" + upstream.getClass().getName());
        if (upstream != null) {
            List<String> hints = new ArrayList<>();
            collectProgressHints(upstream, hints);
            record("upstream_progress_methods", hints.isEmpty() ? "-" : String.join(",", hints));
            BotLog.info("[MachineStation] 上游自述进度方法 {}", hints);
        }
        int pending = WorldModLedger.pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
        record("no_writes", String.valueOf(pending == 0));
        if (pending != 0) {
            failures.add("no_writes");
        }
        session.close("probe_done");
        return advance(Phase.DONE);
    }

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private Status failAndFinish(String code) {
        failures.add(code);
        BotLog.warn("[MachineStation] 失败 {}", code);
        return finish();
    }

    private Status finish() {
        phase = Phase.DONE;
        finished = true;
        if (session != null) {
            session.close("probe_end");
        }
        StringBuilder summary = new StringBuilder();
        for (var entry : facts.entrySet()) {
            if (summary.length() > 0) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append(" verdict=").append(failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[MachineStation] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[MachineStation] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }

    // ==================== 只读反射小工具 ====================

    private static ContainerData findContainerData(AbstractContainerMenu menu) {
        Class<?> type = menu.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!ContainerData.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(menu);
                    if (value instanceof ContainerData containerData) {
                        return containerData;
                    }
                } catch (Throwable ignored) {
                    // 读不到就换下一个
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Object callNoArg(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 只收集**方法名**（不调用），避免任何副作用；命中形态的记进 `hints`。 */
    private static void collectProgressHints(Object target, List<String> hints) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) {
                    continue;
                }
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                for (String hint : PROGRESS_HINTS) {
                    if (name.contains(hint)) {
                        hints.add(method.getName() + "→" + method.getReturnType().getSimpleName());
                        break;
                    }
                }
            }
            type = type.getSuperclass();
        }
    }

    private void record(String key, String value) {
        facts.put(key, value);
    }

    private void check(String name, boolean ok, String detail) {
        record(name, ok ? "true" : "false");
        BotLog.info("[MachineStation] {}={} {}", name, ok ? "true" : "false", detail);
        if (!ok) {
            failures.add(name);
        }
    }
}

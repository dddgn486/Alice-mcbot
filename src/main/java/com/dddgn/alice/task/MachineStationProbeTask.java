package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.MachineMap;
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
 *
 * <p><b>S3（D-209）起改为「按表认机器」</b>：机器方块 id 全部来自 {@link MachineMap}（单一出处），
 * 半径内**表里登记过的**方块每类探一台（同类型取最近），于是"开的是哪台"由表决定、**可自证**——
 * 旧实现是"命名空间里最近的方块"，双机器场景下无法证明点对了哪一台（这是 S3 勘察认定的唯一真缺口）。
 *
 * <p><b>§6.9.1 三条前提</b>：① **几何盒** = 以 bot **脚位**为中心的立方盒（半径 {@value #SCAN_RADIUS}，
 * 含上下），可达上限 {@value #REACH_LIMIT} 格，超出者记 `reach_skipped`（**事实，不判红**：摆位是场景的事）；
 * ② **世界/模组假设** = 装了对应模组、场景已由 `alice_test:machine_course` 摆好（电池 `stepSkippable`
 * 会先跑该函数）；一台都够不着 ⇒ `machine_absent` ⇒ 电池记 **SKIP**（模组集可变，不判红）；
 * ③ **层归属** = 两条断言都落在"这一台机器"的属性上：**方块实体自述的配方类型**（`getRecipeType()`，
 * 方块↔方块实体是编译期绑定）与**菜单类**，不经任务层、不会被上游短路。
 *
 * <p><b>§6.9.3 三问自答</b>：① 层归属见上（不经查询层/任务层）；② 依赖的假设已在上一条写清且**自断言**
 * （机器不在 ⇒ `_absent` SKIP；够不着 ⇒ `reach_skipped` 进 SUMMARY；表里没登记 ⇒ `untabled_blocks` 留痕）；
 * ③ 失败时用户侧**看不到任何动作**（只读探针），判据是聊天 `SUMMARY m1_…/m2_… verdict=FAIL`，
 * 失败码形如 `m1_binding`（方块实体自述类型与表不符）、`m2_menu_class_matches`（菜单类漂移）、
 * `machine_absent:radius_6`（电池把本步记 SKIP）。
 */
public class MachineStationProbeTask implements Task {

    /** 探针扫描半径（**立方盒**，含上下；见 §6.9.1① 的几何盒纪律）。 */
    private static final int SCAN_RADIUS = 6;
    /** 交互可达上限（原版 `blockInteractionRange` = 4.5，留余量给眼高换算）。 */
    private static final double REACH_LIMIT = 4.4;
    /** 多台机器 ⇒ 预算比单台宽（原 400）；但**必须小于电池步预算 400**，
     *  否则电池先按 TIMEOUT 记账、夹具自己的 `probe_timeout` 与事实留痕都来不及打出来。 */
    private static final int MAX_TICKS = 350;
    private static final int OPEN_TICKS = 80;
    /** 进度类访问器的**方法名形态**（只认形态 + 只读调用，不认类名）。 */
    private static final String[] PROGRESS_HINTS = {"progress", "scaled", "active", "operating", "duration"};

    /** 场景起点（`alice_test:machine_course` 的平台起点；两台机器在它东侧 z=306 / z=307）。 */
    public static final BlockPos START = new BlockPos(66, 64, 304);

    /** 表里登记过的方块所属命名空间（只用于"上游有、表里没有"的**诊断**留痕）。 */
    private static final String TABLE_NAMESPACE = tableNamespace();

    private enum Phase { PREPARE, FIND, OPEN, REPORT, NEXT, RESET, DONE }

    /** 待探的一台机器：**位置 + 表里的那一行**（判据全从行里来）。 */
    private record MachineTarget(BlockPos pos, MachineMap.Row row) {
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
    private final List<MachineTarget> targets = new ArrayList<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int phaseTicks;
    private int targetIndex;
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
        return TaskTarget.block(targets.isEmpty() ? bot.blockPosition()
                : targets.get(Math.min(targetIndex, targets.size() - 1)).pos());
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
            case PREPARE -> prepare();
            case FIND -> find();
            case OPEN -> open();
            case REPORT -> report();
            case NEXT -> next();
            case RESET -> reset();
            case DONE -> finish();
        };
    }

    /**
     * **自带传送 + 起点前提**（用户 2026-09-13 纪律：场景夹具不许依赖"电池的 provision 帮我挪过去"，
     * standalone 右键也必须成立）。失败/成功都在 {@link #reset()} 里复位。
     */
    private Status prepare() {
        bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        var ground = FixturePremise.onGround(bot);
        var ownMenu = FixturePremise.ownMenu(bot);
        // ⚠️ **不要在这里 `check(premise_on_ground, ground.ok())`**（2026-09-17 单跑实测的坑）：
        // teleport 的**那一 tick**，`onGround` 读到的可能仍是**上一处**的状态 ⇒ 这里恒为 true，
        // 于是"前提自证"变成假绿，真正的落地复核被推迟到下一 tick（`MenuSession` 的 K-3 门在那里硬拒
        // `menu_not_settled`）⇒ 判据错位。⇒ 落地前提改在 `find()` 里**等物理结算之后**复核。
        // 原始读数仍然留痕（可 grep），只是**不作为通过判据**。
        record("on_ground_immediately_after_teleport", ground.detail());
        check("premise_own_menu", ownMenu.ok(), ownMenu.detail());
        record("start_pos", bot.blockPosition().toShortString());
        BotLog.info("[MachineStation] 已传送 bot 到场景起点 {}（{}）；落地前提留到下一相位复核 ✓",
                START.toShortString(), bot.blockPosition().toShortString());
        return advance(Phase.FIND);
    }

    /**
     * **按表认机器**（S3/D-209）：只认 {@link MachineMap} 里登记过的方块，同类型取**最近**一台。
     * 旧实现的"命名空间里最近的方块"无法回答"我开的是哪一台"（双机器场景下只能靠距离撞）。
     *
     * <p>**先等物理结算**（{@link com.dddgn.alice.task.FixturePremise#SETTLE_TICKS}）：见 {@link #prepare()} 的注释 —— 不这样做，
     * `MenuSession.open` 会在传送后**第一 tick** 就撞 K-3 门（`menu_not_settled`），
     * 而 CORE 里因为 bot 恰好本来就站在起点上 ⇒ **看不出来**（模块化单跑才暴露 ✗）。
     */
    private Status find() {
        if (!FixturePremise.settledOnGround(bot, phaseTicks)) {
            if (phaseTicks > OPEN_TICKS) {
                return failAndFinish("not_on_ground");
            }
            if (phaseTicks == FixturePremise.SETTLE_TICKS + 1) {
                BotLog.info("[MachineStation] 等物理结算：传送后第 {} tick onGround={}", phaseTicks,
                        bot.onGround());
            }
            return Status.RUNNING;
        }
        // 到这里 = **传送之后过了 SETTLE_TICKS tick** 且真的站在地上 ⇒ 落地前提**当场自证** ✓
        check("premise_on_ground", true, FixturePremise.onGround(bot).detail()
                + "（传送后第 " + phaseTicks + " tick 复核 ⇒ 不是传送那一 tick 的陈旧读数 ✓）");
        var level = bot.serverLevel();
        record Hit(double distance, BlockPos pos, MachineMap.Row row) {
        }
        List<Hit> hits = new ArrayList<>();
        List<String> untabled = new ArrayList<>();
        List<String> reachSkipped = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                bot.blockPosition().offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                bot.blockPosition().offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            MachineMap.Row row = MachineMap.forBlock(id);
            if (row == null) {
                if (id.startsWith(TABLE_NAMESPACE + ":")) {
                    // 上游有、表里没有：**诊断留痕**（不判红——模组集可变；运行期覆盖由 machine_route 步报）
                    untabled.add(id + "@" + pos.toShortString());
                }
                continue;
            }
            double distance = pos.distSqr(bot.blockPosition());
            if (distance > REACH_LIMIT * REACH_LIMIT) {
                reachSkipped.add(id + "@" + pos.toShortString());
                continue;
            }
            hits.add(new Hit(distance, pos.immutable(), row));
        }
        hits.sort(java.util.Comparator.comparingDouble(Hit::distance));
        java.util.LinkedHashSet<String> seenTypes = new java.util.LinkedHashSet<>();
        for (Hit hit : hits) {
            if (!seenTypes.add(hit.row().typeId())) {
                continue;   // 同类型多台（如工厂变体）：只探最近那台，其余由下面的事实记录
            }
            targets.add(new MachineTarget(hit.pos(), hit.row()));
        }
        List<String> duplicates = new ArrayList<>();
        for (Hit hit : hits) {
            if (targets.stream().noneMatch(t -> t.pos().equals(hit.pos()))) {
                duplicates.add(hit.row().typeId() + "@" + hit.pos().toShortString());
            }
        }
        record("scan_radius", String.valueOf(SCAN_RADIUS));
        record("reach_limit", String.valueOf(REACH_LIMIT));
        record("machine_map", MachineMap.describe());
        record("found_types", String.valueOf(targets.size()));
        record("reach_skipped", reachSkipped.isEmpty() ? "[]" : reachSkipped.toString());
        record("untabled_blocks", untabled.isEmpty() ? "[]" : untabled.toString());
        record("same_type_extra", duplicates.isEmpty() ? "[]" : duplicates.toString());
        if (!reachSkipped.isEmpty() || !untabled.isEmpty()) {
            BotLog.warn("[MachineStation] 事实留痕 reach_skipped={} untabled_blocks={}", reachSkipped, untabled);
        }
        if (targets.isEmpty()) {
            // 模组未装 / 场景没摆 ⇒ 由电池按 `_absent` 记 SKIP（**不判红**：环境不具备）
            failures.add("machine_absent:radius_" + SCAN_RADIUS);
            BotLog.info("[MachineStation] 半径 {} 内没有表里登记的机器方块 ⇒ 本项 SKIP", SCAN_RADIUS);
            return finish();
        }
        StringBuilder planned = new StringBuilder();
        for (MachineTarget t : targets) {
            if (planned.length() > 0) {
                planned.append(',');
            }
            planned.append(t.row().typeId()).append('@').append(t.pos().toShortString());
        }
        record("machines", planned.toString());
        BotLog.info("[MachineStation] 按表找到 {} 台：{}", targets.size(), planned);
        return advance(Phase.OPEN);
    }

    private Status open() {
        MachineTarget target = targets.get(targetIndex);
        if (session == null) {
            session = MenuSession.open(bot, target.pos(), 0);
            return phaseTicks > OPEN_TICKS
                    ? failAndFinish("menu_open_timeout:" + target.row().typeId()) : Status.RUNNING;
        }
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            return failAndFinish("menu_open_failed:" + target.row().typeId() + ":" + session.failure());
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > OPEN_TICKS
                    ? failAndFinish("menu_open_timeout:" + target.row().typeId()) : Status.RUNNING;
        }
        return advance(Phase.REPORT);
    }

    /** 一台探完 → 关菜单 → 下一台；都探完则复位。 */
    private Status next() {
        if (session != null) {
            session.close("probe_next");
            session = null;
        }
        if (bot.containerMenu != null
                && !(bot.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu)) {
            bot.closeContainer();
        }
        targetIndex++;
        return targetIndex >= targets.size() ? advance(Phase.RESET) : advance(Phase.OPEN);
    }

    /**
     * 读事实（每台一组 `m{i}_*` 键）：方块/方块实体 / 菜单类 / 槽位表 / 进度数据；
     * 并对**这一台**做两条断言：
     * <ol>
     *   <li>`m{i}_menu_class_matches`：菜单类 == 表里登记值（**只对已实测登记过的行断言**；
     *       未登记的行只观察并记 `menu_class_declared=false` —— 拿"猜出来的期望"当断言就是制造假红）；</li>
     *   <li>`m{i}_binding`：**方块实体自述的配方类型** == 表里的类型（"点对了哪台"的最强可及证据）。</li>
     * </ol>
     */
    private Status report() {
        MachineTarget target = targets.get(targetIndex);
        MachineMap.Row row = target.row();
        String prefix = "m" + (targetIndex + 1) + "_";
        AbstractContainerMenu menu = bot.containerMenu;
        if (menu == null) {
            return failAndFinish("menu_closed:" + row.typeId());
        }
        record(prefix + "type", row.typeId());
        record(prefix + "block", BuiltInRegistries.BLOCK
                .getKey(bot.serverLevel().getBlockState(target.pos()).getBlock())
                + "@" + target.pos().toShortString());
        record(prefix + "reach", String.format(java.util.Locale.ROOT, "%.2f",
                Math.sqrt(target.pos().distSqr(bot.blockPosition()))));
        var blockEntity = bot.serverLevel().getBlockEntity(target.pos());
        record(prefix + "be", blockEntity == null ? "-" : blockEntity.getClass().getName());

        String menuClass = menu.getClass().getName();
        record(prefix + "menu_class", menuClass);
        record(prefix + "menu_slots", String.valueOf(menu.slots.size()));
        StringBuilder slots = new StringBuilder();
        StringBuilder roles = new StringBuilder();
        for (Slot slot : menu.slots) {
            if (slots.length() > 0) {
                slots.append(' ');
            }
            slots.append('#').append(slot.index).append('=').append(slot.getClass().getSimpleName())
                    .append('/').append(slot.container.getClass().getSimpleName())
                    .append("(cs=").append(slot.getContainerSlot()).append(')');
            String role = readSlotRole(slot);
            if (!role.isEmpty()) {
                if (roles.length() > 0) {
                    roles.append(' ');
                }
                roles.append('#').append(slot.index).append('=').append(role);
            }
        }
        record(prefix + "slot_table", slots.length() == 0 ? "-" : slots.toString());
        // 槽位**角色**：机器槽的 `slot.container` 是上游共用的空容器（恒 cs=0），看它没有信息量；
        // 真正的角色由上游自述 —— `InventoryContainerSlot.getSlotType()`（`ContainerSlotType` 枚举）
        // 与 `getInventorySlot()`（底下的 `InputInventorySlot`/`OutputInventorySlot`/… 实现类）。
        // 只读 getter、纯观察：**这是 S4 机器闭环"哪个下标是输入/输出"的数据来源，不靠猜**。
        record(prefix + "slot_roles", roles.length() == 0 ? "-" : roles.toString());
        BotLog.info("[MachineStation] {} 菜单 {} 槽位表 {}", row.typeId(), menuClass,
                facts.get(prefix + "slot_table"));
        BotLog.info("[MachineStation] {} 槽位角色（上游自述） {}", row.typeId(),
                facts.get(prefix + "slot_roles"));

        // ① 菜单类与表一致（未登记的行只观察）
        if (row.menuDeclared()) {
            check(prefix + "menu_class_matches", row.menuClass().equals(menuClass),
                    "表=" + row.menuClass() + " 实际=" + menuClass);
        } else {
            record(prefix + "menu_class_declared", "false（未实测登记 ⇒ 只观察，待本轮日志确认后按数据回填）");
        }
        // ② 方块实体自述的配方类型 == 表里的类型（方块↔方块实体编译期绑定，故这是"哪台机器"的硬证据）
        String beType = readRecipeTypeName(blockEntity);
        record(prefix + "be_recipe_type", beType == null ? "unverified" : beType);
        if (beType == null) {
            record(prefix + "binding", "unverified（方块实体没有可读的 getRecipeType/getRegistryName）");
        } else {
            check(prefix + "binding", row.typeId().equals(beType),
                    "表=" + row.typeId() + " 方块实体自述=" + beType);
        }

        // ③ 原版路径：菜单里有 `ContainerData` 字段吗（按类型找，不按名字）
        ContainerData data = findContainerData(menu);
        record(prefix + "container_data",
                data == null ? "-" : data.getClass().getSimpleName() + "(count=" + data.getCount() + ")");
        // ④ 上游自述：容器/方块实体上的**进度类访问器**（按方法名形态找，只读，不调用写方法）
        Object upstream = callNoArg(menu, "getTileEntity");
        record(prefix + "upstream_accessor",
                upstream == null ? "-" : "getTileEntity→" + upstream.getClass().getName());
        if (upstream != null) {
            List<String> hints = new ArrayList<>();
            collectProgressHints(upstream, hints);
            record(prefix + "progress_methods", hints.isEmpty() ? "-" : String.join(",", hints));
            BotLog.info("[MachineStation] {} 上游自述进度方法 {}", row.typeId(), hints);
        }
        session.close("probe_done");
        session = null;
        return advance(Phase.NEXT);
    }

    /**
     * **结束复位**（用户纪律）：关菜单（若还开着）+ 停输入 + **回到场景起点**，
     * 让"下一次点/下一步"从同一个干净前提开始；失败路径也走这里。
     */
    private Status reset() {
        // **零写入自证**（整轮一次，不在每台里重复）：只读探针不得给账本留任何待回收条目。
        // ⭐ `Z4`（2026-09-23）：账本口径在野外是空集（`Z1` 起区外不记账）⇒ 同时判**闸门计数的
        // 真实写入次数**（与区无关、更强），并把人口印进读数。
        int pending = WorldModLedger.pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
        int writes = com.dddgn.alice.action.WriteBudget.writeCount(bot);
        record("no_writes", String.valueOf(pending == 0 && writes == 0)
                + "(" + com.dddgn.alice.action.WriteBudget.population(bot) + ")");
        if (pending != 0) {
            failures.add("no_writes");
        }
        if (session != null) {
            session.close("probe_reset");
            session = null;
        }
        if (bot.containerMenu != null && !(bot.containerMenu
                instanceof net.minecraft.world.inventory.InventoryMenu)) {
            bot.closeContainer();
        }
        bot.controller().stopMovement();
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        record("reset", "true");
        BotLog.info("[MachineStation] 结束复位：bot 回到 {}（onGround={}）",
                bot.blockPosition().toShortString(), bot.onGround());
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

    /**
     * 调**公有**无参访问器（含接口 `default` 方法）：上游 provider 的 `getRegistryName()` 就是
     * 一个 `default` 方法，`getDeclaredMethod` 在实现类上找不到它。
     */
    private static Object callPublicNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * **上游自述的槽位角色**（只读，S4 的数据来源）：`ContainerSlotType` 枚举 + 底下的
     * `IInventorySlot` 实现类（`InputInventorySlot`/`OutputInventorySlot`/`EnergyInventorySlot`/…）。
     *
     * <p>为什么不能看 `slot.container`：机器槽的容器字段是上游共用的空容器（实测恒 `SimpleContainer(cs=0)`），
     * 玩家槽才是有意义的 `Inventory(cs=…)`。角色只能问上游。不是 Mekanism 槽（原版/玩家槽）⇒ 返回空串。
     */
    private static String readSlotRole(Slot slot) {
        Object type = callPublicNoArg(slot, "getSlotType");
        Object backing = callPublicNoArg(slot, "getInventorySlot");
        if (type == null && backing == null) {
            return "";
        }
        return (type == null ? "?" : String.valueOf(type))
                + (backing == null ? "" : "/" + backing.getClass().getSimpleName());
    }

    /**
     * **方块实体自述的配方类型**（只读）：`getRecipeType()` → `getRegistryName()`；
     * 拿不到就如实返回 null（调用方记 `unverified`，**不判红** —— 别的模组没有这套访问器）。
     */
    private static String readRecipeTypeName(Object blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        Object provider = callPublicNoArg(blockEntity, "getRecipeType");
        if (provider == null) {
            return null;
        }
        Object name = callPublicNoArg(provider, "getRegistryName");
        if (name instanceof net.minecraft.resources.ResourceLocation location) {
            return location.toString();
        }
        if (provider instanceof net.minecraft.world.item.crafting.RecipeType<?> type) {
            var key = BuiltInRegistries.RECIPE_TYPE.getKey(type);
            return key == null ? null : key.toString();
        }
        return null;
    }

    /** 表里登记过的方块所属命名空间（用于"上游有、表里没有"的诊断留痕）。 */
    private static String tableNamespace() {
        for (MachineMap.Row row : MachineMap.rows()) {
            String block = row.primaryBlock();
            if (block != null && block.indexOf(':') > 0) {
                return block.substring(0, block.indexOf(':'));
            }
        }
        return "";
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

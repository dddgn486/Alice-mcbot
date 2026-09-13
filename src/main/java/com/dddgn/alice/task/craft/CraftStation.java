package com.dddgn.alice.task.craft;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * **合成工作站**（阶段 3-A / S1-2，D-192）：把"在哪儿合成"变成**一等、可选、可切换**的对象。
 *
 * <p>用户裁定（2026-09-13）：*"先实现工作站的可切换，不急着完全适配其他模组"*、
 * *"不要求这些方式能够自动选优，玩家切换也足够了"* ⇒
 * <ul>
 *   <li>**候选由确定性代码产出**（每个候选：能不能用 + 为什么不能用），**不做自动选优**；</li>
 *   <li>玩家用 `/alice craft station <auto|inventory|table|upgradetab>` 切换；</li>
 *   <li>`auto` **只复刻现状**（随身 2×2 → 工作台 3×3），**不含**升级页签 ——
 *       要用升级页签请**显式**选，避免"悄悄换了一条生产路径"。</li>
 * </ul>
 *
 * <p>**未知默认只读**：升级页签站点的取法协议（QuickMove 目标）与材料来源**尚未实测**，
 * 因此描述符里如实写 `UNKNOWN`，本类**只提供只读解析**（打开菜单 + 发现网格），不做任何写入。
 * 执行接入见第二步（`docs/MOD_COMPAT_CRAFT_STATION_PLAN.md` §3 S1-5）。
 */
public final class CraftStation {

    /** 站点形态：随身菜单 / 世界里的方块（"背包"这类物品形态留下一步）。 */
    public enum Kind { INVENTORY, BLOCK }

    /** 结果槽**取法协议**：不同站点对同一个 `QUICK_MOVE` 的语义不同，必须显式声明。 */
    public enum Take {
        /** 单次取（点到光标上，落点由调用方决定）。 */
        SINGLE_TAKE,
        /** 原版 shift-click 语义（合一次、产物进背包）—— 已在 A3 上验证。 */
        QUICK_MOVE,
        /** **未实测**：不许执行，只能只读观察。 */
        UNKNOWN
    }

    /** **材料来源**：守恒断言的判据（材料在容器/网络里时，"玩家背包 −M"的口径不成立）。 */
    public enum Source { INVENTORY, UNKNOWN }

    /**
     * 一个站点。`id` 是玩家可输入的稳定名字。
     *
     * @param provisionUpgrade **能把这个站点"装配"起来的升级物品 id**（null = 不可装配）。
     *                         装配层用它决定"该往升级槽塞什么"，**不需要判断站点类名**。
     */
    public record Descriptor(String id, String label, Kind kind, Take take, Source source, String note,
                             ResourceLocation provisionUpgrade) {
    }

    /** 随身 2×2（今天 A2 走的路）。 */
    public static final Descriptor INVENTORY = new Descriptor("inventory", "随身 2×2", Kind.INVENTORY,
            Take.QUICK_MOVE, Source.INVENTORY, "玩家自带 InventoryMenu（D-163 布局）", null);
    /** 工作台 3×3（今天 A3 走的路）。 */
    public static final Descriptor TABLE = new Descriptor("table", "工作台 3×3", Kind.BLOCK,
            Take.QUICK_MOVE, Source.INVENTORY, "原版 crafting_table", null);
    /** 精妙存储/精妙背包的"合成升级页签"（3×3 藏在容器菜单里）。 */
    public static final Descriptor UPGRADE_TAB = new Descriptor("upgradetab", "升级页签容器 3×3", Kind.BLOCK,
            Take.UNKNOWN, Source.UNKNOWN,
            "sophisticatedstorage 的容器（按方块 id 形态识别）；协议/来源待实测（第二步）",
            ResourceLocation.fromNamespaceAndPath("sophisticatedstorage", "crafting_upgrade"));

    /**
     * **熔炼页签容器**（A4b / D-198）：同一个精妙容器，装的是**熔炼升级**而不是合成升级 ——
     * 站点 = "容器 + 哪一种能力"，所以用**独立描述符**表达（`provisionUpgrade` 不同）。
     */
    public static final Descriptor COOKING_TAB = new Descriptor("cookingtab", "熔炼页签容器 3 格", Kind.BLOCK,
            Take.UNKNOWN, Source.UNKNOWN, "sophisticatedstorage 容器 + 熔炼升级（按时间工作）",
            ResourceLocation.fromNamespaceAndPath("sophisticatedstorage", "smelting_upgrade"));

    private static final List<Descriptor> ALL = List.of(INVENTORY, TABLE, UPGRADE_TAB, COOKING_TAB);
    /** `auto` 的现状顺序：**不含** upgradetab（不做自动选优）。 */
    private static final List<Descriptor> AUTO_ORDER = List.of(INVENTORY, TABLE);
    /** 每个 bot 的选择（`auto` 或站点 id）。**内存态**：重启回到 `auto`（未接存档，如实记录）。 */
    private static final Map<UUID, String> SELECTED = new LinkedHashMap<>();

    private CraftStation() {
    }

    public static List<Descriptor> all() {
        return ALL;
    }

    /** 按 id 取站点；不认识返回 null（调用方如实报错，不猜）。 */
    public static Descriptor byId(String id) {
        if (id == null) {
            return null;
        }
        for (Descriptor station : ALL) {
            if (station.id().equalsIgnoreCase(id.trim())) {
                return station;
            }
        }
        return null;
    }

    public static String selected(BotPlayer bot) {
        return SELECTED.getOrDefault(bot.getUUID(), "auto");
    }

    /** 设置选择：`auto` 或站点 id；不合法返回 false（**不悄悄改成别的**）。 */
    public static boolean select(BotPlayer bot, String id) {
        if ("auto".equalsIgnoreCase(id)) {
            SELECTED.remove(bot.getUUID());
            return true;
        }
        if (byId(id) == null) {
            return false;
        }
        SELECTED.put(bot.getUUID(), byId(id).id());
        return true;
    }

    /**
     * 一个候选站点的**事实**：能不能用 + 为什么。
     *
     * <p>`detail` 里带**可核对的现场事实**（方块坐标 / 菜单槽数），便于在日志里一眼确认，
     * 而不是只给一句"可用"。
     */
    public record Candidate(Descriptor station, boolean available, String reason) {
    }

    /** 扫描候选站点（**只读**：只查方块与菜单，不打开、不改世界）。 */
    public static List<Candidate> candidates(BotPlayer bot, int radius) {
        ServerLevel level = bot.serverLevel();
        BlockPos center = bot.blockPosition();
        List<Candidate> out = new ArrayList<>();
        for (Descriptor station : ALL) {
            // 候选列出是**报告路径**（`bot_report` 每次都会走）⇒ 任何一格异常都必须变成一行事实，
            // 不许把服务端 tick 打崩（2026-09-13 的教训）。
            try {
                out.add(probeCandidate(bot, level, center, radius, station));
            } catch (RuntimeException | LinkageError e) {
                out.add(new Candidate(station, false,
                        "probe_exception:" + e.getClass().getSimpleName()));
            }
        }
        return out;
    }

    private static Candidate probeCandidate(BotPlayer bot, ServerLevel level, BlockPos center,
                                            int radius, Descriptor station) {
        return switch (station.kind()) {
            case INVENTORY -> {
                GridDiscovery.Result discovery = GridDiscovery.discover(bot.inventoryMenu, bot);
                yield new Candidate(station, discovery.ok(),
                        discovery.ok() ? discovery.describe() : "随身菜单：" + discovery.describe());
            }
            case BLOCK -> {
                BlockPos pos = findStationBlock(level, center, radius, station);
                if (pos == null) {
                    yield new Candidate(station, false,
                            "半径 " + radius + " 内没有 " + describeStationBlock(station));
                }
                boolean reach = inReach(bot, pos);
                yield new Candidate(station, true, "block=" + pos.toShortString()
                        + (reach ? "" : "（不在触及距离内，需要先走过去）"));
            }
        };
    }

    /** 一行事实（`bot_report` / 探针用）：当前选择 + 每个候选能不能用。 */
    public static String describe(BotPlayer bot, int radius) {
        StringBuilder sb = new StringBuilder(selected(bot));
        sb.append("（候选：");
        boolean first = true;
        for (Candidate candidate : candidates(bot, radius)) {
            if (!first) {
                sb.append(" / ");
            }
            first = false;
            sb.append(candidate.station().label()).append(candidate.available() ? " ✓" : " ✗")
                    .append(candidate.available() ? "" : "（" + candidate.reason() + "）");
        }
        return sb.append('）').toString();
    }

    /**
     * **解析出"用哪个站点、菜单在哪"**（只读层 L0/L1）。
     *
     * <p>`auto` 按现状顺序取**第一个可用**的站点；显式选中的站点**不可用就如实失败**
     * （`station_unavailable`）—— 不悄悄回退到别的站点，否则"切换"这件事就不可信了。
     *
     * @return {@link Opened}：`ok` 时 `menu` 非空（随身）或 `session` 非空（方块，需调用方 tick 到 OPEN）
     */
    public record Opened(Descriptor station, boolean ok, String code, String detail,
                         BlockPos pos, AbstractContainerMenu menu, MenuSession session) {

        public String describe() {
            return (ok ? "OK" : "FAIL:" + code) + " station=" + station.id()
                    + (pos == null ? "" : " pos=" + pos.toShortString())
                    + (detail.isEmpty() ? "" : " " + detail);
        }
    }

    public static final class Codes {
        public static final String NO_STATION = "station_unavailable";
        public static final String OUT_OF_REACH = "station_out_of_reach";
        public static final String MENU_FAILED = "menu_open_failed";
        public static final String UNKNOWN_ID = "unknown_station_id";

        private Codes() {
        }
    }

    public static Opened open(BotPlayer bot, int radius) {
        String selection = selected(bot);
        List<Descriptor> order = new ArrayList<>();
        if ("auto".equals(selection)) {
            order.addAll(AUTO_ORDER);
        } else {
            Descriptor station = byId(selection);
            if (station == null) {
                return new Opened(INVENTORY, false, Codes.UNKNOWN_ID, "记的选择是 " + selection, null, null, null);
            }
            order.add(station);
        }
        ServerLevel level = bot.serverLevel();
        for (Descriptor station : order) {
            if (station.kind() == Kind.INVENTORY) {
                AbstractContainerMenu menu = bot.inventoryMenu;
                if (menu == null) {
                    continue;
                }
                return new Opened(station, true, "", "随身菜单（无需打开）", null, menu, null);
            }
            BlockPos pos = findStationBlock(level, bot.blockPosition(), radius, station);
            if (pos == null) {
                if (!"auto".equals(selection)) {
                    return new Opened(station, false, Codes.NO_STATION,
                            "半径 " + radius + " 内没有 " + describeStationBlock(station), null, null, null);
                }
                continue;   // auto：换下一个候选
            }
            if (!inReach(bot, pos)) {
                return new Opened(station, false, Codes.OUT_OF_REACH,
                        "block=" + pos.toShortString() + " 不在触及距离内（本步不做走路）", pos, null, null);
            }
            MenuSession session;
            try {
                session = MenuSession.open(bot, pos, 0);
            } catch (RuntimeException | LinkageError e) {
                return new Opened(station, false, Codes.MENU_FAILED,
                        "open 抛异常 " + e.getClass().getSimpleName(), pos, null, null);
            }
            BotLog.info("[CraftStation] open station={} pos={} menu={}", station.id(),
                    pos.toShortString(), MenuSession.describeGates());
            return new Opened(station, true, "", "opening", pos, null, session);
        }
        return new Opened(INVENTORY, false, Codes.NO_STATION, "没有可用站点（选择=" + selection + "）",
                null, null, null);
    }

    /** 站点形态的**方块 id 判据**（C0：只认 id 形态，不引编译期依赖）。 */
    private static BlockPos findStationBlock(ServerLevel level, BlockPos center, int radius,
                                             Descriptor station) {
        if (station == TABLE) {
            return TableCraft.findTable(level, center, radius);
        }
        if (station == UPGRADE_TAB) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                    center.offset(radius, radius, radius))) {
                if (!isUpgradeTabContainer(level.getBlockState(pos))) {
                    continue;
                }
                double distance = pos.distSqr(center);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos.immutable();
                }
            }
            return best;
        }
        return null;
    }

    private static String describeStationBlock(Descriptor station) {
        if (station == TABLE) {
            return "crafting_table";
        }
        if (station == UPGRADE_TAB) {
            return "精妙存储容器（sophisticatedstorage 的 chest/barrel/shulker 形态方块）";
        }
        return station.label();
    }

    /** 精妙存储的容器方块：命名空间 + id 形态（不含具体版本清单 ⇒ 换版本/换木种照样认）。 */
    private static boolean isUpgradeTabContainer(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null || !"sophisticatedstorage".equals(key.getNamespace())) {
            return false;
        }
        String path = key.getPath();
        return path.contains("chest") || path.contains("barrel") || path.contains("shulker");
    }

    /** 触及校验（与传输/工作台同口径：眼位到方块中心 ≤ 方块触及 + 1）。 */
    public static boolean inReach(BotPlayer bot, BlockPos pos) {
        return bot.getEyePosition().distanceTo(pos.getCenter()) <= bot.getBlockReach() + 1.0D;
    }

    /** 便于自检/报告：原版工作台方块常量。 */
    public static Object vanillaTableForDisplay() {
        return Blocks.CRAFTING_TABLE;
    }
}

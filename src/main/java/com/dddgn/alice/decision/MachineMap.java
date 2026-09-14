package com.dddgn.alice.decision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **机器类型 ↔ 机器方块/菜单 的单一出处**（阶段 3-B / S3，接在 {@link RecipeDump#stationFor} 旁边）。
 *
 * <p>为什么必须有一处表：S1 之后查询层能报出"只由机器产线做得出"的路线，但路线里的 `station` 是
 * **配方类型 id**（`mekanism:enriching`）——那**不是**一个可以去的地方；而"去最近的 `mekanism:` 方块"
 * 又无法自证点对了哪一台（双机器场景下只能靠距离撞）。本表把"**哪个类型 = 哪台方块**"钉成数据。
 *
 * <p><b>字段来源纪律</b>（`docs/MOD_ADAPTER_PROTOCOL.md`）：
 * <ul>
 *   <li>`typeId` / `blockIds`：**必须登记**，出处是上游注册名（离线可复核：`javap` 读
 *       `mekanism.common.recipe.MekanismRecipeType` 与 `mekanism.common.registries.MekanismBlocks`
 *       的字符串常量；`tools/check-machine-map.sh` 的 Tier B 每次构建前**双向**复核）；</li>
 *   <li>`menuClass`：**只登记客户端实测过的**，没实测过写 {@link #UNKNOWN}（探针会记录观察值、
 *       不拿它当断言，避免"猜出来的期望"制造假红）。**注意它不是机器身份**：Mekanism 所有单方块机器
 *       共用 `MekanismTileContainer`（enriching 与 crushing 实测同值、槽位表也逐项相同）
 *       ⇒ 菜单类只能拿来看"菜单形状有没有漂移"，**分辨"点对了哪台"的硬证据是方块↔方块实体绑定**
 *       （探针的 `m{i}_binding`：BE 自述配方类型 == 表里的类型）；</li>
 *   <li>`capability`：缺省、且今天**所有行**都是 {@link Capability#READ_ONLY}
 *       —— Alice 还没有任何机器的执行适配；`EXECUTABLE` 的出现前提见其 javadoc；</li>
 *   <li>**槽位下标一律不入表**：槽位/进度运行时问上游（本表只管"是哪台方块、点对了没"）。</li>
 * </ul>
 *
 * <p><b>覆盖口径</b>：表 = 上游**全部**类型，一种表示、不留第二种：
 * 有单方块站点的行写 `blockIds=[方块]`；上游有类型但**没有单方块站点**的写 `blockIds=[]`
 * （`knownUnmapped()` 由这些行派生，**不另设清单**——两处真相必然漂移）。
 * 今天 23 行有站点 + 4 行无站点 = 27 = 上游全部类型（Tier B 双向断言）。
 */
public final class MachineMap {

    /** 客户端实测过的菜单类名；**未实测**用这个（探针只观察、不断言）。 */
    public static final String UNKNOWN = "-";

    /** 本表取证的上游版本（换版本必须重跑 Tier B 并复核 `source` 列）。 */
    public static final String SOURCE_JAR = "mekanism-1.20.1-10.4.16.80.jar";

    private static final String SRC = SOURCE_JAR + "#MekanismRecipeType+MekanismBlocks(javap 2026-09-14)";

    /**
     * 能力口径。**缺省必须是 {@link #READ_ONLY}**（未知模组能力默认只读，协议 §1）。
     *
     * <p>{@link #EXECUTABLE} 今天**没有任何一行**使用，它的出现条件（三者同时满足，静态检查强制）：
     * ① 有执行适配器（能派发"放料/取产物/等进度"）；② `menuClass` 已客户端实测登记；
     * ③ 有对应的客户端验证记录。在那之前，`MACHINE_ROUTE` 一律只报路线、执行侧如实拒绝。
     */
    public enum Capability { READ_ONLY, EXECUTABLE }

    /**
     * 一行机器映射。
     *
     * @param typeId     配方类型 id（上游注册名，如 `mekanism:enriching`）
     * @param blockIds   该类型对应的机器方块 id，**按优先序**（首个 = 主方块）；空 = 上游有类型但无单方块站点
     * @param menuClass  实测的菜单类名，或 {@link #UNKNOWN}
     * @param capability 能力（今天全为 {@code READ_ONLY}）
     * @param source     取证出处
     * @param note       备注（1:N、多方块、零配方等）
     */
    public record Row(String typeId, List<String> blockIds, String menuClass, Capability capability,
                      String source, String note) {

        public boolean hasSite() {
            return !blockIds.isEmpty();
        }

        /** 主方块 id（无站点则 null）。 */
        public String primaryBlock() {
            return blockIds.isEmpty() ? null : blockIds.get(0);
        }

        public boolean menuDeclared() {
            return !UNKNOWN.equals(menuClass);
        }
    }

    /** 上游有、但**没有单方块机器站点**的类型（由 `blockIds=[]` 的行派生）。 */
    public record Unmapped(String typeId, String reason) {
    }

    // ==================== 表本体（唯一真源；一行一条，便于静态解析） ====================

    private static final List<Row> ROWS = List.of(
            // —— 基础加工机（S2 已客户端实测 enriching/enrichment_chamber）——
            row("mekanism:enriching", "mekanism:enrichment_chamber", "mekanism.common.inventory.container.tile.MekanismTileContainer",
                    "S2 客户端实测（machine_station，2026-09-14）；菜单类是**通用 tile 容器**，不区分机器（见类注释）"),
            row("mekanism:crushing", "mekanism:crusher", "mekanism.common.inventory.container.tile.MekanismTileContainer",
                    "1:N——工厂变体 basic_/advanced_/elite_/ultimate_crushing_factory 同类型不同方块；v1 只登记基础机（工厂 id 已在 jar 中，按需按数据补行）。菜单类 S3 客户端实测（machine_station，2026-09-14），与 enriching **同一个类**"),
            row("mekanism:smelting", "mekanism:energized_smelter", null,
                    "上游**注册但零配方**（jar 里没有 smelting 配方目录）⇒ 运行期不会出现在配方管理器，探针按 `with_site_unobserved` 如实报出，不算孤儿行"),
            row("mekanism:combining", "mekanism:combiner", null, ""),
            row("mekanism:compressing", "mekanism:osmium_compressor", null, ""),
            row("mekanism:purifying", "mekanism:purification_chamber", null, ""),
            row("mekanism:injecting", "mekanism:chemical_injection_chamber", null, ""),
            row("mekanism:sawing", "mekanism:precision_sawmill", null, ""),
            row("mekanism:metallurgic_infusing", "mekanism:metallurgic_infuser", null, ""),
            row("mekanism:oxidizing", "mekanism:chemical_oxidizer", null, ""),
            row("mekanism:chemical_infusing", "mekanism:chemical_infuser", null, ""),
            row("mekanism:separating", "mekanism:electrolytic_separator", null, ""),
            row("mekanism:washing", "mekanism:chemical_washer", null, ""),
            row("mekanism:crystallizing", "mekanism:chemical_crystallizer", null, ""),
            row("mekanism:dissolution", "mekanism:chemical_dissolution_chamber", null, ""),
            row("mekanism:reaction", "mekanism:pressurized_reaction_chamber", null, ""),
            row("mekanism:rotary", "mekanism:rotary_condensentrator", null, ""),
            row("mekanism:activating", "mekanism:solar_neutron_activator", null, ""),
            row("mekanism:centrifuging", "mekanism:isotopic_centrifuge", null, ""),
            row("mekanism:nucleosynthesizing", "mekanism:antiprotonic_nucleosynthesizer", null, ""),
            row("mekanism:pigment_extracting", "mekanism:pigment_extractor", null, ""),
            row("mekanism:pigment_mixing", "mekanism:pigment_mixer", null, ""),
            row("mekanism:painting", "mekanism:painting_machine", null, ""),
            // —— 上游有类型、无单方块机器站点（`blockIds=[]`；"没行"与"无站点"必须可区分）——
            noSite("mekanism:evaporating", "热蒸发**多方块**（`EvaporationMultiblockData`），无单方块站点"),
            noSite("mekanism:energy_conversion", "未定位到单方块机器（电力转换在机器内部/槽位层）"),
            noSite("mekanism:gas_conversion", "未定位到单方块机器（气体转换在机器内部/槽位层）"),
            noSite("mekanism:infusion_conversion", "未定位到单方块机器（灌注转换在机器内部/槽位层）"));

    /** 类型 → 行（含 `blockIds=[]` 的"无站点"行）。 */
    private static final Map<String, Row> BY_TYPE = indexByType();

    /** 方块 id → 行（探针据此"按表认机器"，不再按命名空间前缀撞）。 */
    private static final Map<String, Row> BY_BLOCK = indexByBlock();

    private MachineMap() {
    }

    // 行构造：**每行一条单行调用**，字段顺序固定 ⇒ `tools/machine-map.py` 可静态解析并与 CSV 对账。
    private static Row row(String typeId, String blockId, String menuClass, String note) {
        return new Row(typeId, List.of(blockId), menuClass == null ? UNKNOWN : menuClass,
                Capability.READ_ONLY, SRC, note);
    }

    private static Row noSite(String typeId, String note) {
        return new Row(typeId, List.of(), UNKNOWN, Capability.READ_ONLY, SRC, note);
    }

    private static Map<String, Row> indexByType() {
        Map<String, Row> map = new LinkedHashMap<>();
        for (Row r : ROWS) {
            Row previous = map.put(r.typeId(), r);
            if (previous != null) {
                throw new IllegalStateException("MachineMap 类型重复：" + r.typeId());
            }
        }
        return Map.copyOf(map);
    }

    private static Map<String, Row> indexByBlock() {
        Map<String, Row> map = new LinkedHashMap<>();
        for (Row r : ROWS) {
            for (String blockId : r.blockIds()) {
                Row previous = map.put(blockId, r);
                if (previous != null) {
                    throw new IllegalStateException("MachineMap 方块重复：" + blockId
                            + "（" + previous.typeId() + " 与 " + r.typeId() + "）");
                }
            }
        }
        return Map.copyOf(map);
    }

    // ==================== 查询 ====================

    /** 全部行（只读快照）。 */
    public static List<Row> rows() {
        return ROWS;
    }

    /** "上游有类型、无单方块站点"的那些行（**派生**，不另设清单）。 */
    public static List<Unmapped> knownUnmapped() {
        return ROWS.stream().filter(r -> !r.hasSite())
                .map(r -> new Unmapped(r.typeId(), r.note()))
                .toList();
    }

    /** 类型 id → 行；**未登记返回 null**（调用方据此"如实回落"，不猜）。 */
    public static Row forType(String typeId) {
        return typeId == null ? null : BY_TYPE.get(typeId);
    }

    /** 类型 id → 主方块 id；无行/无站点返回 null。 */
    public static String blockFor(String typeId) {
        Row r = forType(typeId);
        return r == null ? null : r.primaryBlock();
    }

    /** 方块 id → 行；**不是表里的机器就返回 null**（探针据此按表认机器）。 */
    public static Row forBlock(String blockId) {
        return blockId == null ? null : BY_BLOCK.get(blockId);
    }

    public static boolean covers(String typeId) {
        Row r = forType(typeId);
        return r != null && r.hasSite();
    }

    public static String describe() {
        long withSite = ROWS.stream().filter(Row::hasSite).count();
        return "machine_rows=" + ROWS.size() + " with_site=" + withSite
                + " no_site=" + knownUnmapped().size()
                + " declared_menu=" + ROWS.stream().filter(Row::menuDeclared).count()
                + " executable=" + ROWS.stream().filter(r -> r.capability() == Capability.EXECUTABLE).count()
                + " source=" + SOURCE_JAR;
    }
}

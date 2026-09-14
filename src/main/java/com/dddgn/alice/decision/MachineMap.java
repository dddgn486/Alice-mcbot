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
 *   <li>`capability`：缺省 {@link Capability#READ_ONLY}（未知模组能力默认只读，协议 §1）。
 *       **今天只有一行是 {@link Capability#EXECUTABLE}**（`mekanism:enriching`，2026-09-14 D-217 起）——
 *       它同时满足 `EXECUTABLE` 的三条件；其余行哪怕 `menuClass` 已实测也**仍是只读**。
 *       行构造用 {@code executable(...)} 而不是 {@code row(...)}，静态检查与 CSV 都认这个区别；</li>
 *   <li>**槽位下标一律不入表**：槽位/进度运行时问上游（本表只管"是哪台方块、点对了没"）。</li>
 * </ul>
 *
 * <p><b>覆盖口径</b>：表 = 上游**全部**类型，一种表示、不留第二种：
 * 有单方块站点的行写 `blockIds=[方块]`；上游有类型但**没有单方块站点**的写 `blockIds=[]`
 * （`knownUnmapped()` 由这些行派生，**不另设清单**——两处真相必然漂移）。
 * **每个已收录的模组都必须全量**（Tier B 双向断言，按命名空间分别跑）：
 * Mekanism 23 行有站点 + 4 行无站点 = **27**；Thermal 26 行有站点 + 6 行无站点 = **32**
 * （上游 `TCoreRecipeTypes` 的全部类型；其中 `brewer` / `hive_extractor` 零配方、
 * 6 个是借用父机器站点的增幅子类型 ⇒ 如实登记为无独立站点）。
 * 因为本表 **1 方块 ↔ 1 类型**（`BY_BLOCK` 唯一，探针靠它"按表认机器"），
 * 同台机器上的第二个配方类型**不能**复用同一个方块 id，只能进无站点行。
 */
public final class MachineMap {

    /** 客户端实测过的菜单类名；**未实测**用这个（探针只观察、不断言）。 */
    public static final String UNKNOWN = "-";

    /** 本表取证的上游版本（换版本必须重跑 Tier B 并复核 `source` 列）。 */
    public static final String SOURCE_JAR = "mekanism-1.20.1-10.4.16.80.jar";

    /**
     * Thermal 的取证出处。**注意它有两个 jar**：`thermal_core` 是 `thermal_foundation` 的**内嵌 jar（JiJ）**
     * （`META-INF/jarjar/thermal_core-1.20.1-11.0.6.24.jar`）——`mods/*.jar` 逐个 `unzip` 时**只显示为一行**，
     * 11 个 `device_*` 方块与 22 条 device/fuel 配方类型全在里面（`docs/THERMAL_S1_FACTS.md` §0）。
     */
    public static final String SOURCE_JAR_THERMAL =
            "thermal_expansion-1.20.1-11.0.1.29.jar+thermal_foundation-1.20.1-11.0.6.70.jar"
                    + "(内嵌 thermal_core-1.20.1-11.0.6.24.jar)";

    private static final String SRC = SOURCE_JAR + "#MekanismRecipeType+MekanismBlocks(javap 2026-09-14)";

    private static final String SRC_THERMAL = SOURCE_JAR_THERMAL
            + "#TCoreRecipeTypes+TExpBlocks+TCoreBlocks(javap 2026-09-14)";

    /**
     * 能力口径。**缺省必须是 {@link #READ_ONLY}**（未知模组能力默认只读，协议 §1）。
     *
     * <p>{@link #EXECUTABLE} 的出现条件（三者同时满足；前两条由 `tools/machine-map.py` 静态强制，
     * 第三条靠决策条目/测试矩阵留痕）：① 有执行适配器（能派发"放料/取产物/等进度"）；
     * ② `menuClass` 已客户端实测登记；③ 有对应的客户端验证记录。
     * **今天只有 `mekanism:enriching` 一行**（D-217）；没验过的机器，`MACHINE_ROUTE` 只报路线、执行侧如实拒绝。
     */
    public enum Capability { READ_ONLY, EXECUTABLE }

    /**
     * **站点种类**（T3 / 2026-09-14，用户拍板）。
     *
     * <p><b>为什么必须分开</b>：旧的 `blockIds=[]` 一个布尔把三件**性质完全不同**的事混成了一句 ——
     * "站点是别人的方块"（Thermal 6 个增幅/回收子类型）、"站点是多方块"、"**我们没查清**"。
     * 后果是实测过的错事实：那 6 行被记进 `noSite` ⇒ 永远 `not_executable`，
     * 而它们的宿主方块**都在注册表里**（无头基线 `MachineProbe` SUMMARY：
     * `46+10=56=types` 且 `row_block_missing=[]`）。
     *
     * <p>尤其：{@link #INTERNAL}（"确定在机器内部"）与 {@link #UNLOCATED}（"没查清"）**必须分开** ——
     * 把"没查清"写成"确定没有"就是同一族的错事实。
     */
    public enum SiteKind {
        /** 本类型**拥有**自己的单方块站点（`blockIds` 非空）。 */
        SINGLE,
        /** **共享站点**：站点就是 {@code hostTypeId} 那行的方块（本行 `blockIds` 必须为空）。 */
        SHARED,
        /** 上游用**多方块结构**（`*MultiblockData` 那类），没有单方块站点。 */
        MULTIBLOCK,
        /**
         * 站点在机器**内部**（槽位层），不是世界里的方块。
         *
         * <p>⚠️ **今天没有任何一行用它**（`energy_conversion` / `gas_conversion` / `infusion_conversion`
         * 三条按 {@link #UNLOCATED} 记 —— 因为"在机器内部"只是注释里的**猜测**，没有取证）。
         * 用它的第一条行出现时，**同时**加 `internal(...)` 构造器；`tools/machine-map.py` 的构造器普查
         * 会在两边不同步时报错，所以不会漏。
         */
        INTERNAL,
        /** **我们没查清**（不等于"确定没有"）。 */
        UNLOCATED
    }

    /**
     * 一行机器映射。
     *
     * @param typeId     配方类型 id（上游注册名，如 `mekanism:enriching`）
     * @param blockIds   该类型**自己拥有**的机器方块 id，**按优先序**（首个 = 主方块）；
     *                   `SINGLE` 之外一律为空（共享站点写在 {@code hostTypeId}，不写这里 —— 见 T3 设计：
     *                   `BY_BLOCK` 保持 1 方块↔1 宿主行，索引与门禁都不必放宽）
     * @param siteKind   站点种类（见 {@link SiteKind}）
     * @param hostTypeId `SHARED` 时指向宿主行的 typeId；其余为 null
     * @param menuClass  实测的菜单类名，或 {@link #UNKNOWN}
     * @param capability 能力（今天只有 `mekanism:enriching` 是 EXECUTABLE）
     * @param source     取证出处
     * @param note       备注（1:N、多方块、零配方等）
     */
    public record Row(String typeId, List<String> blockIds, SiteKind siteKind, String hostTypeId,
                      String menuClass, Capability capability, String source, String note) {

        /** **有站点** = 自己能解析出站点方块（`SINGLE` 自己拥有；`SHARED` 借宿主）。 */
        public boolean hasSite() {
            return siteKind == SiteKind.SINGLE || siteKind == SiteKind.SHARED;
        }

        /** 本行**自己拥有**的主方块 id（`SINGLE` 之外一律 null）。 */
        public String primaryBlock() {
            return blockIds.isEmpty() ? null : blockIds.get(0);
        }

        /**
         * **站点方块 id**：`SINGLE` 用本行的；`SHARED` 用**宿主行**的；其余 **null（不猜）**。
         *
         * <p>只有它才该被当作"这台机器在世界里的哪个方块"——`primaryBlock()` 对 `SHARED` 行永远是 null。
         */
        public String siteBlock() {
            if (siteKind == SiteKind.SINGLE) {
                return primaryBlock();
            }
            if (siteKind == SiteKind.SHARED) {
                Row host = MachineMap.forType(hostTypeId);
                return host == null ? null : host.primaryBlock();
            }
            return null;
        }

        public boolean menuDeclared() {
            return !UNKNOWN.equals(menuClass);
        }
    }

    /** 上游有、但**没有单方块机器站点**的类型（`MULTIBLOCK` / `INTERNAL` / `UNLOCATED` 派生）。 */
    public record Unmapped(String typeId, String reason) {
    }

    // ==================== 表本体（唯一真源；一行一条，便于静态解析） ====================

    private static final List<Row> ROWS = List.of(
            // —— 基础加工机（S2 已客户端实测 enriching/enrichment_chamber）——
            // **(c) 增量 2 / D-217 起 = EXECUTABLE**：三条件齐了 —— ① 执行适配器
            // `task/craft/MachineCycle`（走→开→电→放料→等→取，夹具与生产同一份）；② `menuClass` 客户端实测
            // （S2 `machine_station` 第四轮）；③ 客户端验证记录：S4 单机闭环第七/九/十/十一轮 +
            // 第十一轮 S4 v2 自走到机器旁（`latest.log:3209`）。**其余行仍 READ_ONLY**（没验过就是没验过）。
            executable("mekanism:enriching", "mekanism:enrichment_chamber", "mekanism.common.inventory.container.tile.MekanismTileContainer",
                    "S2 客户端实测（machine_station，2026-09-14）；菜单类是**通用 tile 容器**，不区分机器（见类注释）；"
                            + "S4/S4v2 闭环已实测 ⇒ D-217 起有执行准入"),
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
            // —— 上游有类型但**没有单方块站点**：T3 起按 `SiteKind` **分类**登记，不再一律 `noSite`
            //    （旧的单一布尔把"站点是别人的""多方块""没查清"混成一句 ⇒ 造出过 M-1 那批错事实）——
            multiblock("mekanism:evaporating", "热蒸发**多方块**（`EvaporationMultiblockData`），无单方块站点"),
            unlocated("mekanism:energy_conversion", "未定位到单方块机器（电力转换在机器内部/槽位层）"),
            unlocated("mekanism:gas_conversion", "未定位到单方块机器（气体转换在机器内部/槽位层）"),
            unlocated("mekanism:infusion_conversion", "未定位到单方块机器（灌注转换在机器内部/槽位层）"),
            // ==================== Thermal（阶段 3-B (a) S2，2026-09-14）====================
            // 取证：`cofh.thermal.core.init.registries.TCoreRecipeTypes`（**上游 32 个 `thermal:` 类型全在这一个类里**）
            // + `cofh.thermal.expansion.init.registries.TExpBlocks` + `TCoreBlocks` 的字符串常量（javap，见 SOURCE_JAR_THERMAL）。
            // **32 ≠ 运行时的 30**：`brewer` 与 `hive_extractor` 上游注册了类型但本次客户端**零配方** ⇒
            // 运行时的配方管理器里不出现（探针按 `with_site_unobserved` 如实报出，与 `mekanism:smelting` 同构）。
            // **本段全部 READ_ONLY**：没客户端实测过 `menuClass` 就只能是只读（`EXECUTABLE` 三条件见 Capability）。
            // 事实来源：`docs/THERMAL_S1_FACTS.md`（设备清单/分类/红线①），本段只落"类型 ↔ 方块"。
            //
            // —— 14 台单方块机器（`TExpBlocks`；`machine_crafter` 上游**没有同名类型**（它执行原版合成配方），故不建行）——
            row("thermal:press", "thermal:machine_press", null, "", SRC_THERMAL),
            row("thermal:pulverizer", "thermal:machine_pulverizer", null, "", SRC_THERMAL),
            row("thermal:smelter", "thermal:machine_smelter", null, "", SRC_THERMAL),
            row("thermal:insolator", "thermal:machine_insolator", null, "", SRC_THERMAL),
            row("thermal:centrifuge", "thermal:machine_centrifuge", null, "", SRC_THERMAL),
            row("thermal:bottler", "thermal:machine_bottler", null, "", SRC_THERMAL),
            row("thermal:crucible", "thermal:machine_crucible", null, "", SRC_THERMAL),
            row("thermal:sawmill", "thermal:machine_sawmill", null, "", SRC_THERMAL),
            row("thermal:crystallizer", "thermal:machine_crystallizer", null, "", SRC_THERMAL),
            row("thermal:chiller", "thermal:machine_chiller", null, "", SRC_THERMAL),
            row("thermal:refinery", "thermal:machine_refinery", null, "", SRC_THERMAL),
            row("thermal:pyrolyzer", "thermal:machine_pyrolyzer", null, "", SRC_THERMAL),
            row("thermal:furnace", "thermal:machine_furnace", null, "", SRC_THERMAL),
            row("thermal:brewer", "thermal:machine_brewer", null,
                    "上游有类型但**本次客户端零配方** ⇒ 运行时不出现（`with_site_unobserved`，不是孤儿行）", SRC_THERMAL),
            // —— 7 台发电机（dynamo）：**红线① —— 烧燃料造能量，永远只能是只读、永不进 EXECUTABLE** ——
            row("thermal:numismatic_fuel", "thermal:dynamo_numismatic", null,
                    "发电机（7 个 `Dynamo*` 类 ↔ 7 个 `*_fuel` 类型 1:1）⇒ 红线①：不得有执行准入", SRC_THERMAL),
            row("thermal:lapidary_fuel", "thermal:dynamo_lapidary", null, "发电机（红线①）", SRC_THERMAL),
            row("thermal:gourmand_fuel", "thermal:dynamo_gourmand", null, "发电机（红线①）", SRC_THERMAL),
            row("thermal:stirling_fuel", "thermal:dynamo_stirling", null, "发电机（红线①）", SRC_THERMAL),
            row("thermal:compression_fuel", "thermal:dynamo_compression", null, "发电机（红线①）", SRC_THERMAL),
            row("thermal:magmatic_fuel", "thermal:dynamo_magmatic", null, "发电机（红线①）", SRC_THERMAL),
            row("thermal:disenchantment_fuel", "thermal:dynamo_disenchantment", null, "发电机（红线①）", SRC_THERMAL),
            // —— 5 个 device 方块：**都在 `thermal_foundation` 的内嵌 `thermal_core` 里**（JiJ，见 SOURCE_JAR_THERMAL）——
            row("thermal:rock_gen", "thermal:device_rock_gen", null,
                    "内嵌 thermal_core；`device_rock_gen` 同时是**特性开关**与方块（只看外层 jar 会误判成「无设备」，S1 事实表 §0）", SRC_THERMAL),
            row("thermal:tree_extractor", "thermal:device_tree_extractor", null, "", SRC_THERMAL),
            row("thermal:fisher_boost", "thermal:device_fisher", null, "", SRC_THERMAL),
            row("thermal:potion_diffuser_boost", "thermal:device_potion_diffuser", null, "", SRC_THERMAL),
            row("thermal:hive_extractor", "thermal:device_hive_extractor", null,
                    "上游有类型但**本次客户端零配方** ⇒ 运行时不出现", SRC_THERMAL),
            // —— 6 个"寄居"子类型：站点与菜单**借用父机器** ——
            //    **T3（2026-09-14）修正了这里的错事实**：旧模型是"1 方块 ↔ 1 类型 + `blockIds` 唯一"，
            //    表达不了"共享站点"，于是这 6 行被如实登记成**无站点** ⇒ `not_executable` 永远成立，
            //    而它们的宿主方块**都在注册表里**（无头基线 `MachineProbe`：`46+10=56=types`、
            //    `row_block_missing=[]`）。现在用 `SiteKind.SHARED + hostTypeId` 显式表达，
            //    **不再动 `BY_BLOCK` 索引**（仍 1 方块 ↔ 1 宿主行）。
            //    ⚠️ **共享行恒 READ_ONLY**（用户 2026-09-14 裁定）：宿主行以后升 EXECUTABLE **也不继承** ——
            //    `indexByType()` 里有类初始化期断言强制这一条。
            sharedSite("thermal:smelter_catalyst", "thermal:smelter",
                    "增幅子类型：站点即 `thermal:machine_smelter`（该方块归 `thermal:smelter` 行）", SRC_THERMAL),
            sharedSite("thermal:smelter_recycle", "thermal:smelter",
                    "同上（`smelter` 的回收/副产物规则）", SRC_THERMAL),
            sharedSite("thermal:insolator_catalyst", "thermal:insolator",
                    "增幅子类型：站点即 `thermal:machine_insolator`", SRC_THERMAL),
            sharedSite("thermal:pulverizer_catalyst", "thermal:pulverizer",
                    "增幅子类型：站点即 `thermal:machine_pulverizer`", SRC_THERMAL),
            sharedSite("thermal:pulverizer_recycle", "thermal:pulverizer", "同上", SRC_THERMAL),
            sharedSite("thermal:tree_extractor_boost", "thermal:tree_extractor",
                    "增幅子类型：站点即 `thermal:device_tree_extractor`", SRC_THERMAL));

    /** 类型 → 行（含 `blockIds=[]` 的"无站点"行）。 */
    private static final Map<String, Row> BY_TYPE = indexByType();

    /** 方块 id → 行（探针据此"按表认机器"，不再按命名空间前缀撞）。 */
    private static final Map<String, Row> BY_BLOCK = indexByBlock();

    private MachineMap() {
    }

    // 行构造：**每行一条单行调用**，字段顺序固定 ⇒ `tools/machine-map.py` 可静态解析并与 CSV 对账。
    private static Row row(String typeId, String blockId, String menuClass, String note) {
        return new Row(typeId, List.of(blockId), SiteKind.SINGLE, null,
                menuClass == null ? UNKNOWN : menuClass, Capability.READ_ONLY, SRC, note);
    }

    /**
     * 同上，但**显式给出取证件**（多模组表必需：一行一条只有一个 `SRC` 会把 Thermal 的出处写成 Mekanism 的）。
     *
     * <p>字段顺序**故意把 `src` 放在最后**：`tools/machine-map.py` 按位置取 `args[0..3]`，
     * 追加第 5 个参数不会改变 CSV 的解析结果。
     */
    private static Row row(String typeId, String blockId, String menuClass, String note, String src) {
        return new Row(typeId, List.of(blockId), SiteKind.SINGLE, null,
                menuClass == null ? UNKNOWN : menuClass, Capability.READ_ONLY, src, note);
    }

    /**
     * **共享站点行**（T3）：站点就是 `hostTypeId` 那行的方块；本行 `blockIds` **必须为空**。
     *
     * <p>**恒 `READ_ONLY`**（用户 2026-09-14 裁定）：宿主行以后升 `EXECUTABLE` **也不继承**准入 ——
     * "未知模组能力默认只读"的边界不因为别人的改动被扩大。`indexByType()` 有类初始化期断言强制。
     */
    private static Row sharedSite(String typeId, String hostTypeId, String note, String src) {
        return new Row(typeId, List.of(), SiteKind.SHARED, hostTypeId,
                UNKNOWN, Capability.READ_ONLY, src, note);
    }

    /** 多方块站点（无单方块站点）。 */
    private static Row multiblock(String typeId, String note, String src) {
        return new Row(typeId, List.of(), SiteKind.MULTIBLOCK, null, UNKNOWN, Capability.READ_ONLY, src, note);
    }

    /** **未查清**（不等于"确定没有"）。与 {@link #internal} 分开是 T3 的要点之一。 */
    private static Row unlocated(String typeId, String note, String src) {
        return new Row(typeId, List.of(), SiteKind.UNLOCATED, null, UNKNOWN, Capability.READ_ONLY, src, note);
    }

    // —— 上面三个的"用默认 SRC"重载（与 `row`/`noSite` 的旧风格一致：单模组段的行不必重复写出处）——
    private static Row multiblock(String typeId, String note) {
        return multiblock(typeId, note, SRC);
    }

    private static Row unlocated(String typeId, String note) {
        return unlocated(typeId, note, SRC);
    }

    /**
     * 有**执行准入**的行（能派发"放料/取产物/等进度"）。用它的那一行必须同时满足 `Capability.EXECUTABLE`
     * 的三条件（见其 javadoc）；`tools/machine-map.py` 静态断言前两条（有站点 + 有实测 `menuClass`），
     * 第三条由决策条目与测试矩阵留痕。
     */
    private static Row executable(String typeId, String blockId, String menuClass, String note) {
        return new Row(typeId, List.of(blockId), SiteKind.SINGLE, null,
                menuClass == null ? UNKNOWN : menuClass, Capability.EXECUTABLE, SRC, note);
    }

    private static Map<String, Row> indexByType() {
        Map<String, Row> map = new LinkedHashMap<>();
        for (Row r : ROWS) {
            Row previous = map.put(r.typeId(), r);
            if (previous != null) {
                throw new IllegalStateException("MachineMap 类型重复：" + r.typeId());
            }
        }
        // **T3 结构不变式**：类初始化期就响，不等跑起来才发现（与 `BY_BLOCK` 的重复断言同一风格）。
        // 这三条对应"共享站点"最容易出错的地方：宿主没登记 / 宿主自己没站点 / 共享行偷偷带了准入。
        for (Row r : ROWS) {
            if (r.siteKind() != SiteKind.SHARED) {
                continue;
            }
            Row host = map.get(r.hostTypeId());
            if (host == null) {
                throw new IllegalStateException("SHARED 行的宿主类型未登记："
                        + r.typeId() + " → " + r.hostTypeId());
            }
            if (host.siteKind() != SiteKind.SINGLE) {
                throw new IllegalStateException("SHARED 行的宿主必须自己是 SINGLE："
                        + r.typeId() + " → " + r.hostTypeId() + "（实为 " + host.siteKind() + "）");
            }
            if (!r.blockIds().isEmpty()) {
                throw new IllegalStateException("SHARED 行不得自带方块（共享写在 hostTypeId）：" + r.typeId());
            }
            if (r.capability() != Capability.READ_ONLY) {
                throw new IllegalStateException("SHARED 行必须恒 READ_ONLY（用户 2026-09-14 裁定，不继承宿主准入）："
                        + r.typeId());
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

    /** 类型 id → 主方块 id；无行/无站点返回 null。**`SHARED` 行返回宿主行的方块**（T3）。 */
    public static String blockFor(String typeId) {
        Row r = forType(typeId);
        return r == null ? null : r.siteBlock();
    }

    /** 方块 id → 行；**不是表里的机器就返回 null**（探针据此按表认机器）。 */
    public static Row forBlock(String blockId) {
        return blockId == null ? null : BY_BLOCK.get(blockId);
    }

    public static String describe() {
        long withSite = ROWS.stream().filter(Row::hasSite).count();
        return "machine_rows=" + ROWS.size() + " with_site=" + withSite
                + " no_site=" + knownUnmapped().size()
                + " shared_site=" + ROWS.stream().filter(r -> r.siteKind() == SiteKind.SHARED).count()
                + " declared_menu=" + ROWS.stream().filter(Row::menuDeclared).count()
                + " executable=" + ROWS.stream().filter(r -> r.capability() == Capability.EXECUTABLE).count()
                + " source=" + SOURCE_JAR + " source_thermal=" + SOURCE_JAR_THERMAL;
    }
}

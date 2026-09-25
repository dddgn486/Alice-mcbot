package com.dddgn.alice.config;

import com.dddgn.alice.job.fishbone.FishboneTemplate;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * ⭐ **鱼骨尺寸配置**（切片 4；用户 2026-09-25 裁定：「主巷深和子巷深做成可配置的，
 * 夹具的测试缩小一点，我自己的实测工具保持大一点」）。
 *
 * <h2>为什么是配置而不是常量 / 不是命令参数</h2>
 * <ul>
 *   <li>真机入口 `alice:fishbone_job` 必须**仍然零参数**（`AGENTS.md` 客户端测试规则：不许要用户输坐标
 *       或长参数串）⇒ 尺寸不能做成命令参数，只能放在**点击之前**就定好的地方；</li>
 *   <li>尺寸是**你的存档**的属性（你想挖多大的鱼骨），不是代码的属性 ⇒ 放 `config/alice-fishbone.toml`，
 *       改完重启客户端即可，不用重编译、不用重同步 jar；</li>
 *   <li>⭐ **唯一出处**仍然是 {@code FishboneJobItem.templateFor}：物品、夹具、配置三者都汇到那一个工厂，
 *       不存在"配置读一份、常量抄一份"。</li>
 * </ul>
 *
 * <h2>默认值 = 用户 2026-09-25 真机反馈定的形状</h2>
 * <table border="1">
 *   <tr><th>键</th><th>默认</th><th>依据</th></tr>
 *   <tr><td>{@code mainLength}</td><td>20</td><td>计划 §8 的保守默认（沿用）</td></tr>
 *   <tr><td>{@code spurSpacing}</td><td><b>3</b></td><td>用户裁定「两根之间空 2 格」= **中心距 3 格**</td></tr>
 *   <tr><td>{@code spurLength}</td><td><b>32</b></td><td>用户裁定「子巷深度起码 32 格」</td></tr>
 *   <tr><td>{@code side}</td><td><b>{@code BOTH}</b></td><td>用户裁定「主巷左右两边**对称**」（原来交替 = 不对称）</td></tr>
 *   <tr><td>{@code height}</td><td>2</td><td>净高（用户 2026-09-21 裁定）</td></tr>
 *   <tr><td>{@code oreBudgetPerUnit}</td><td>16</td><td>见下（计划写 8，本片实测后抬到 16，理由写在字段注释里）</td></tr>
 * </table>
 *
 * <p>按默认值，真机形状 = 主巷 20 + **6 个位置 × 2 侧 = 12 条肋 × 32 格** ⇒ **404 单元 / 808 格**。
 * 真机实测速度 ≈ **47 tick/单元**（`latest.log` 2026-09-25 12:16~12:17 的相邻单元时间差反推，
 * 与离线夹具的 46 tick/单元一致）⇒ 约 **16 分钟**。⚠️ 这是一次**长作业**，但用户明确
 * 「我可以随时终止，不需要完整跑完」⇒ 不做断点续跑。
 *
 * <h2>非法值怎么办</h2>
 * {@code defineInRange} 只能表达区间，表达不了"0 或 ≥ 2"这种合法域 ⇒ 越界的**语义**由
 * {@link FishboneTemplate} 的构造器**拒绝并说明**（计划 §2：「非法即拒绝而不是夹取」），
 * 物品的右键把那条消息原样告诉你。**这里不复制一份校验**（否则就是第二份真值）。
 */
public final class FishboneConfig {

    /** 配置规格（在 {@code AliceMod} 构造器里注册到 `ModConfig.Type.COMMON`）。 */
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue MAIN_LENGTH;
    public static final ForgeConfigSpec.IntValue SPUR_SPACING;
    public static final ForgeConfigSpec.IntValue SPUR_LENGTH;
    public static final ForgeConfigSpec.EnumValue<FishboneTemplate.SpurSide> SIDE;
    public static final ForgeConfigSpec.IntValue HEIGHT;
    public static final ForgeConfigSpec.IntValue ORE_BUDGET_PER_UNIT;

    /** 主巷长度默认值。 */
    public static final int DEFAULT_MAIN_LENGTH = 20;
    /** 支巷间距默认值（**中心距 3 格** = 两根之间空 2 格）。 */
    public static final int DEFAULT_SPUR_SPACING = 3;
    /** 支巷长度默认值（用户：起码 32 格）。 */
    public static final int DEFAULT_SPUR_LENGTH = 32;
    /** 支巷侧向默认值（`BOTH` = 主巷左右对称）。 */
    public static final FishboneTemplate.SpurSide DEFAULT_SIDE = FishboneTemplate.SpurSide.BOTH;
    /** 净高默认值。 */
    public static final int DEFAULT_HEIGHT = FishboneTemplate.DEFAULT_HEIGHT;

    /**
     * **每个模板单元最多消费几个顺手挖候选**（计划 §10.1 的 `oreBudgetPerUnit`）。
     *
     * <p>⚠️ **本片偏离计划默认值 8 ⇒ 16**，理由（可复核）：
     * ① 真正的自然上界是**触及范围**（`inPlaceReachable` = 视线通 + `getBlockReach() ≈ 4.5` 格）
     * —— 每个单元能追到的矿格本来就只有十几个；
     * ② 计划那 8 是"别让一个单元无限挖"的成本护栏，而 8 会把**一个正常大小的矿脉截断一半**
     * （用户 2026-09-25 报告的正是"矿簇没挖完"）；
     * ③ 护栏仍然在（16 而非无限），触发时打 `ore_budget_exhausted` 并**继续推进**（不停任务）。
     * 复核触发：真机出现"某个单元追矿超过 16 格"或"追矿把一轮作业拖长到明显不合理" ⇒ 调回 8。
     */
    public static final int DEFAULT_ORE_BUDGET_PER_UNIT = 16;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("鱼骨挖矿（alice:fishbone_job 右键）的尺寸。改完重启客户端生效。",
                        "非法组合（例如 spurSpacing=1）会被模板构造器拒绝，右键时原样告诉你原因。")
                .push("fishbone");
        MAIN_LENGTH = b.comment("主巷长度（格）。默认 " + DEFAULT_MAIN_LENGTH + "。")
                .defineInRange("mainLength", DEFAULT_MAIN_LENGTH, 1, 128);
        SPUR_SPACING = b.comment("支巷间距（**中心距**，格）。0 = 不开支巷；否则必须 ≥ "
                        + FishboneTemplate.MIN_SPUR_SPACING + "（间隔 1 会把相邻支巷塌成大厅）。默认 "
                        + DEFAULT_SPUR_SPACING + "（= 两根之间空 2 格）。")
                .defineInRange("spurSpacing", DEFAULT_SPUR_SPACING, 0, 64);
        SPUR_LENGTH = b.comment("支巷长度（格，≥ 1）。默认 " + DEFAULT_SPUR_LENGTH + "。")
                .defineInRange("spurLength", DEFAULT_SPUR_LENGTH, 1, 64);
        SIDE = b.comment("支巷侧向：LEFT / RIGHT / ALTERNATE（交替，不对称）/ BOTH（左右对称）。默认 "
                        + DEFAULT_SIDE + "。")
                .defineEnum("side", DEFAULT_SIDE);
        HEIGHT = b.comment("巷道净高（2 或 3；2 = 玩家能走）。默认 " + DEFAULT_HEIGHT + "。")
                .defineInRange("height", DEFAULT_HEIGHT, FishboneTemplate.MIN_HEIGHT, FishboneTemplate.MAX_HEIGHT);
        ORE_BUDGET_PER_UNIT = b.comment("每个模板单元最多消费几个暴露矿（顺手挖 / 追簇的上限）。默认 "
                        + DEFAULT_ORE_BUDGET_PER_UNIT + "；0 = 关闭顺手挖。详见 FishboneConfig 的字段注释。")
                .defineInRange("oreBudgetPerUnit", DEFAULT_ORE_BUDGET_PER_UNIT, 0, 256);
        b.pop();
        SPEC = b.build();
    }

    private FishboneConfig() {
    }

    public static int mainLength() {
        return MAIN_LENGTH.get();
    }

    public static int spurSpacing() {
        return SPUR_SPACING.get();
    }

    public static int spurLength() {
        return SPUR_LENGTH.get();
    }

    public static FishboneTemplate.SpurSide side() {
        return SIDE.get();
    }

    public static int height() {
        return HEIGHT.get();
    }

    public static int oreBudgetPerUnit() {
        return ORE_BUDGET_PER_UNIT.get();
    }
}

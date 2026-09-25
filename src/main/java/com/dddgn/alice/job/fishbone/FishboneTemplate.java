package com.dddgn.alice.job.fishbone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * **鱼骨模板**（`D-386`）—— "参数就是唯一真值，bot 不猜"。
 *
 * <p>定位（`docs/plans/2026-09-21-鱼骨挖矿计划.md` §0/§2）：鱼骨把"算不出怎么去的深处矿"换成
 * "**我脚下/眼前这一格**" ⇒ **深层搜索被取消，而不是被优化**。模板决定一切：bot 不选目标、不猜策略。
 *
 * <h2>切片范围与字段（**只声明真正有消费者的字段**）</h2>
 *
 * <ul>
 *   <li><b>切片 1（主巷 + 返回）</b>：`startFoot` / `dir` / `mainLength` / `height` 四个字段；</li>
 *   <li>⭐ <b>切片 2 第一步（支巷几何）</b>：新增 `spurSpacing` / `spurLength` / `side` 三个字段 ——
 *       **消费者就是本类的 {@link #cells()} / {@link #unitFoots()} / {@link #advanceCells()} /
 *       {@link #scopeRadius()}**（外加夹具的几何判据），不是"先声明等以后用"。</li>
 *   <li>计划 §2 的另几个字段（`maxGapLength` / `bridgeBlockBudget` / `oreQuota` / `oreTarget` …）
 *       **故意仍不声明**：它们的消费者（大矿洞三条上限、追簇、露头矿识别）在切片 2 第二步与切片 4 才出现。
 *       "声明了没人用"本身就是缺陷（门禁 `J5-P1` 会红）。</li>
 * </ul>
 *
 * <h2>纯几何、不读世界（判据 `C1`/`C3`/`C5` 的基准）</h2>
 *
 * <ul>
 *   <li>一个**单元** = 脚位格 + 其上 `height-1` 格（净高 2 ⇒ 脚位 + 头位两格）；</li>
 *   <li>单元序列**严格有序**：主巷按 `i = 1..mainLength`；某条支巷**插在它分叉出去的那个主巷单元之后**
 *       （"开出去 → 挖到端点 → 原路退回主巷"，退回不是单元、不占模板格）；</li>
 *   <li>**脚位格的下方不属于模板**（不挖地板）⇒ bot 不会把自己挖穿（计划 §3 第 4 条）。</li>
 * </ul>
 *
 * @param startFoot   起点**脚位格**（玩家给；也是 `RETURN` 的目标、收集作用域的中心）
 * @param dir         主巷方向（**只许四向水平**；竖直方向没有"主巷"的语义）
 * @param mainLength  主巷长度（格；= 主巷推进单元的个数）
 * @param spurSpacing 支巷间距（每多少格开一条；**0 = 不开支巷**，否则必须 ≥ 2 —— 计划 §2 合法域：
 *                    间隔 1 会把相邻支巷塌成大厅）
 * @param spurLength  支巷长度（格；**≥ 1**）
 * @param side        支巷侧向（{@link SpurSide}；`spurSpacing == 0` 时本字段无效）
 * @param height      巷道净高（脚位 + 上方可通行格数；**2 或 3**，默认 2 —— 用户 2026-09-21 裁定）
 */
public record FishboneTemplate(BlockPos startFoot, Direction dir, int mainLength,
                               int spurSpacing, int spurLength, SpurSide side, int height) {

    /** 净高默认值（用户 2026-09-21 裁定：净高 2 = 玩家能走的通道）。 */
    public static final int DEFAULT_HEIGHT = 2;

    /** 净高下限（1 格高不是"巷道"）。 */
    public static final int MIN_HEIGHT = 2;

    /** 净高上限（计划 §2 合法域：`3` 只在"通行更舒服"时用，破坏数 +50%）。 */
    public static final int MAX_HEIGHT = 3;

    /** 支巷间距下限（计划 §2：`∈ {0} ∪ [2, ∞)`；1 会把相邻支巷塌成大厅）。 */
    public static final int MIN_SPUR_SPACING = 2;

    /** 支巷侧向（计划 §2 的 `SpurSide`）。 */
    public enum SpurSide {
        /** 一律朝行进方向的**左手**侧（见 {@link #sideDir} 的"左右"定义）。 */
        LEFT,
        /** 一律朝行进方向的**右手**侧。 */
        RIGHT,
        /** 交替（第 1 条左、第 2 条右、第 3 条左 …）。 */
        ALTERNATE,
        /** 每个位置**两侧都开**（支巷数翻倍）。 */
        BOTH
    }

    public FishboneTemplate {
        Objects.requireNonNull(startFoot, "startFoot");
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(side, "side");
        startFoot = startFoot.immutable();
        // 非法即**拒绝**而不是夹取（计划 §2 的口径）：夹取会让"玩家要 20 格"静默变成"挖了 3 格"。
        if (dir.getAxis().isVertical()) {
            throw new IllegalArgumentException("dir 必须是水平四向（N/S/E/W）：" + dir);
        }
        if (mainLength < 1) {
            throw new IllegalArgumentException("mainLength 必须 ≥ 1：" + mainLength);
        }
        if (height < MIN_HEIGHT || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("height 必须是 " + MIN_HEIGHT + " 或 " + MAX_HEIGHT + "：" + height);
        }
        if (spurSpacing != 0 && spurSpacing < MIN_SPUR_SPACING) {
            throw new IllegalArgumentException("spurSpacing 必须是 0（不开支巷）或 ≥ " + MIN_SPUR_SPACING
                    + "：" + spurSpacing);
        }
        if (spurLength < 1) {
            throw new IllegalArgumentException("spurLength 必须 ≥ 1：" + spurLength);
        }
    }

    /** 便捷构造：**纯主巷**（不开支巷；切片 1 的口径，夹具与旧调用点用它）。 */
    public static FishboneTemplate main(BlockPos startFoot, Direction dir, int mainLength) {
        return new FishboneTemplate(startFoot, dir, mainLength, 0, 1, SpurSide.LEFT, DEFAULT_HEIGHT);
    }

    /** 便捷构造：主巷 + 支巷（净高取默认值）。 */
    public static FishboneTemplate spurs(BlockPos startFoot, Direction dir, int mainLength,
                                         int spurSpacing, int spurLength, SpurSide side) {
        return new FishboneTemplate(startFoot, dir, mainLength, spurSpacing, spurLength, side, DEFAULT_HEIGHT);
    }

    /** 是否开支巷。 */
    public boolean hasSpurs() {
        return spurSpacing > 0;
    }

    /** 支巷**位置**个数（沿主巷每隔 `spurSpacing` 格一个；`0` = 不开）。 */
    public int spurCount() {
        return hasSpurs() ? mainLength / spurSpacing : 0;
    }

    /**
     * 第 `k`（1-based）个支巷位置的行进方向侧向。
     *
     * <p>**左右的定义**（必须写死，否则"左"会随实现漂）：以**行进方向**为前，
     * `LEFT = dir.getCounterClockWise()`（朝北行进时左手是西）、`RIGHT = dir.getClockWise()`。
     *
     * @return 该位置的侧向方向列表（`BOTH` ⇒ 两个；其余 ⇒ 一个）
     */
    private List<Direction> sidesAt(int k) {
        if (side == SpurSide.BOTH) {
            return List.of(dir.getCounterClockWise(), dir.getClockWise());
        }
        boolean left = side == SpurSide.LEFT || (side == SpurSide.ALTERNATE && k % 2 == 1);
        return List.of(left ? dir.getCounterClockWise() : dir.getClockWise());
    }

    /**
     * 一个**推进单元**（脚位格 + 它归属哪条主巷单元 + 它是不是支巷的第几格）。
     *
     * <p>⭐ **切片 2 第二步的消费者就是 `FishboneJob`**：它必须能分辨"这一格是主巷还是支巷"，
     * 因为 `§10.2`（用户 2026-09-21 裁定）对两者是**两档处置**：
     * <b>支巷</b>遇不可挖/液体 ⇒ 放弃这条子巷、原路退回主巷继续；<b>主巷</b>遇 ⇒ 如实失败 + 先回家。
     * 光有脚位坐标分不出这两档（`cells()` 是平铺的）⇒ 归属信息必须由**几何真源**给出，
     * 不许让 `FishboneJob` 自己用坐标反推（那就等于第二份几何实现）。
     *
     * @param foot     脚位格
     * @param mainUnit 它挂靠的主巷单元（1-based；主巷单元自己 =  它自己）
     * @param spurDir  支巷方向；**`null` = 这是主巷单元**
     * @param spurStep 支巷里的第几格（1..`spurLength`）；主巷单元 = 0
     */
    public record Unit(BlockPos foot, int mainUnit, Direction spurDir, int spurStep) {

        /** 是不是支巷单元（`spurDir != null`）。 */
        public boolean isSpur() {
            return spurDir != null;
        }
    }

    /**
     * **单元序列**（纯几何、不读世界、确定性、可复算）：
     * 主巷第 `i` 个单元之后，紧接着它分叉出去的那些支巷单元（由近到远）。
     *
     * <p>这是"逐格推进"的**唯一顺序真源** —— `FishboneJob` 按它取下一个待挖单元；
     * {@link #unitFoots()} 只是它的投影（**不许各写一份**）。
     */
    public List<Unit> units() {
        List<Unit> out = new ArrayList<>(mainLength + spurCount() * spurLength);
        int k = 0;
        for (int i = 1; i <= mainLength; i++) {
            BlockPos mainFoot = startFoot.relative(dir, i);
            out.add(new Unit(mainFoot, i, null, 0));
            if (!hasSpurs() || i % spurSpacing != 0) {
                continue;
            }
            k++;
            for (Direction sideDir : sidesAt(k)) {
                for (int j = 1; j <= spurLength; j++) {
                    out.add(new Unit(mainFoot.relative(sideDir, j), i, sideDir, j));
                }
            }
        }
        return List.copyOf(out);
    }

    /** **单元脚位格序列**（{@link #units()} 的投影；顺序与它逐格一致）。 */
    public List<BlockPos> unitFoots() {
        return units().stream().map(Unit::foot).toList();
    }

    /**
     * **模板格序列**（纯几何）：按 {@link #unitFoots()} 的顺序展开，每个单元先脚位格、再其上 `height-1` 格。
     *
     * <p>判据 `C1`（模板 = 事实）与 `C5`（白名单）都以**本方法的返回**为基准 ——
     * 因此它必须是纯函数（同参数同结果），否则判据会跟着实现漂。
     */
    public List<BlockPos> cells() {
        List<BlockPos> out = new ArrayList<>(unitFoots().size() * height);
        for (BlockPos foot : unitFoots()) {
            for (int dy = 0; dy < height; dy++) {
                out.add(foot.above(dy));
            }
        }
        return List.copyOf(out);
    }

    /** 模板格集合（`C1`/`C5` 的白名单；顺序无关，去重）。 */
    public Set<BlockPos> cellSet() {
        return Set.copyOf(new LinkedHashSet<>(cells()));
    }

    /** 第 `unit`（1-based）个**主巷**单元的脚位格；越界即抛（调用方自己保证范围）。 */
    public BlockPos footOf(int unit) {
        if (unit < 1 || unit > mainLength) {
            throw new IllegalArgumentException("unit 越界：" + unit + " ∉ [1, " + mainLength + "]");
        }
        return startFoot.relative(dir, unit);
    }

    /** 模板末端脚位格（主巷最深处的脚位）。 */
    public BlockPos lastFoot() {
        return footOf(mainLength);
    }

    /**
     * **推进格数**（判据 `C3` 的分母 = "搜索规模 ≤ 常数 × 推进格数"里的那个数）。
     *
     * <p>切片 1 = 主巷单元数；⭐ 切片 2 = 主巷 + 支巷单元数（`BOTH` 两侧都算）。
     * ⚠️ 追簇的游走格**不计入**（那是"模板外"的改动，由 `C1` 的"簇游走区域"单独对账）。
     */
    public int advanceCells() {
        return unitFoots().size();
    }

    /**
     * **作业作用域半径**（计划 §3 `COLLECT` 的纪律：半径必须**从模板推导**，不许沿用默认 32 —— `D-346`
     * 的教训是"追取上限 < 作业直径 ⇒ 判据永不成立"）。
     *
     * <p>⭐ 切片 2：**必须把支巷算进去**（否则支巷里的掉落物落在作用域外 ⇒ 判据 `C2` 永不成立）。
     * `+ 2` = 包围盒对角余量（起点那一格 + 一格缓冲）。
     */
    public int scopeRadius() {
        return mainLength + (hasSpurs() ? spurLength : 0) + 2;
    }

    public String describe() {
        return "dir=" + dir.getName() + " main=" + mainLength
                + " spurSpacing=" + spurSpacing + " spurLen=" + spurLength
                + " side=" + side + " spurs=" + spurCount()
                + " height=" + height + " start=" + startFoot.toShortString()
                + " units=" + advanceCells() + " cells=" + cells().size();
    }
}

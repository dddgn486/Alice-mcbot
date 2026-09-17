package com.dddgn.alice.task.check;

import java.util.List;

/**
 * **分类自检模块（R-2）**：一组同类自检步，**必须能单独运行**（不依赖其它模块留下的世界状态 ✓）。
 *
 * <p>用户原话（2026-09-17）：「每个电池步按分类模块化，**一个模块保证可以单独测**」。
 * 因此模块内**只允许**依赖：自己的场景（{@code scenes}）+ 自己的发料（{@code provision}）+ 自己的前提检查，
 * **禁止**依赖"上一步/上一模块留下的方块或区块热度"（今天的反例：`single:craft_table` 单跑必红 ✗）。
 */
public interface CheckModule {

    /** 模块 id（命令行/日志里用；如 {@code ledger}）。 */
    String id();

    /** 模块标题（人读）。 */
    String title();

    /** 本模块的步（顺序即运行顺序；构建时不能有副作用 ✗）。 */
    List<CheckStep> steps(CheckContext ctx);

    /**
     * **期望判决**（默认 `PASS` ✓）。只有"自检/反例"类模块才声明别的值（如 `harness_self` 声明 `FAIL` ✓）
     * ⇒ `tools/module-selftest.sh` 按声明断言 ✓，**不会把"故意失败"当成回归** ✗。
     */
    default String expectedVerdict() {
        return "PASS";
    }
}

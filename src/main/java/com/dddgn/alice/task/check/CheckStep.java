package com.dddgn.alice.task.check;

import com.dddgn.alice.task.Task;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * **自检步（R-2 电池模块化的基本单元）**：一步 = 场景 + 发料 + 一个任务 + 预算 + 可选的前置/收尾策略。
 *
 * <p>与旧的电池内私有 `Step` 记录**逐字段等价**（搬迁不改语义 ✓）；新增两点：
 * ① 归入 {@link CheckModule}（分类模块 ✓）；② 归因/清理策略跟着步走（{@code keepWorldState} ✓，见 D-283）。
 *
 * @param name          步名（判决行里的名字，也是台账/策展的键）
 * @param profile       运行档位归类（BASELINE/MAIN 进 CORE，EXTRA 只在 FULL 跑）
 * @param scenes        本步要用到的数据包场景函数（地形类优先，玩家向场景会 `tp @s`/`give @s` ⇒ 慎用）
 * @param provision     起任务前的一次性准备（传送到起点、发料、清库存等；可空）
 * @param factory       本步的任务工厂（每步一个**可独立运行**的任务 ✓ —— 模块化验收的核心）
 * @param budgetTicks   预算 tick（超时即判失败并给出理由）
 * @param doneWhen      额外的完成判据（可空 = 只看任务的终态）
 * @param skipWhen      跳过条件（可空；用于"平台/前置不具备时跳过"而非假绿）
 * @param keepWorldState 本步**故意**留下我方临时方块（默认 false ⇒ 留了没声明就是**本步判红**，D-283）
 */
public record CheckStep(String name,
                        CheckProfile profile,
                        List<String> scenes,
                        Runnable provision,
                        Supplier<Task> factory,
                        int budgetTicks,
                        Predicate<Task> doneWhen,
                        Predicate<Task> skipWhen,
                        boolean keepWorldState) {

    /** 普通步（最常用）。 */
    public static CheckStep of(String name, CheckProfile profile, List<String> scenes, Runnable provision,
                               Supplier<Task> factory, int budgetTicks) {
        return new CheckStep(name, profile, scenes, provision, factory, budgetTicks, null, null, false);
    }

    /** 普通步 + 跳过条件（前置不具备时 skip 而不是假绿）。 */
    public static CheckStep skippable(String name, CheckProfile profile, List<String> scenes, Runnable provision,
                                      Supplier<Task> factory, int budgetTicks, Predicate<Task> skipWhen) {
        return new CheckStep(name, profile, scenes, provision, factory, budgetTicks, null, skipWhen, false);
    }

    /** **故意留世界状态**的步：声明后 `endStep` 不再因遗留而判红（D-283 的 B 方案）。 */
    public static CheckStep keeping(String name, CheckProfile profile, List<String> scenes, Runnable provision,
                                    Supplier<Task> factory, int budgetTicks) {
        return new CheckStep(name, profile, scenes, provision, factory, budgetTicks, null, null, true);
    }

    public CheckStep withDoneWhen(Predicate<Task> doneWhen) {
        return new CheckStep(name, profile, scenes, provision, factory, budgetTicks, doneWhen, skipWhen, keepWorldState);
    }
}

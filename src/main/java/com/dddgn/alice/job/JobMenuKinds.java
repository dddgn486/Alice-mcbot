package com.dddgn.alice.job;

import java.util.List;

/**
 * ⭐ **`JobRequest.Kind` ↔ 菜单 kind 字面量的<u>单一真源</u>**（批 `4a` 柱③ 第 1 件，用户 2026-09-29 裁）。
 *
 * <h2>它替掉了什么（为什么必须有它）</h2>
 * 同一个概念在本仓有**四种写法**，而它们的对应关系原先**只存在于一处 bash 变量里**
 * （`tools/check-job-menu-listable.sh` 的 {@code MAPPING="LUMBER=lumber…"}）：
 * <ol>
 *   <li>{@link JobRequest.Kind} 的枚举名（`LUMBER`）；</li>
 *   <li>本类给出的菜单 id（`lumber`）；</li>
 *   <li>`decision/CandidateMenu.java` 里 `new Entry(...)` 的第 2 个实参（菜单真的印出来的字面量）；</li>
 *   <li>提示词词表（`decision/GoalDirector.VOCABULARY`）里的写法。</li>
 * </ol>
 * ⇒ 原先改一处忘另一处会**静默漂移**（门禁的映射表住在**壳脚本**里，而不是代码里）。
 * 本类把那层对应关系**搬回代码**，成为可编译、可被门禁读取的唯一出处。
 *
 * <h2>⭐ 为什么用 {@code switch} <u>表达式</u>而不是一张表</h2>
 * 这是一个 {@code switch} **表达式**且**没有 `default`** ⇒ ⭐ **新增一个 {@link JobRequest.Kind}
 * 而这里没给分支 ⇒ <u>编译不过</u>**（javac：`the switch expression does not cover all possible
 * input values`）。这正是本项目反复要的性质（`O17-a` / `D-520`：**新增取值就编译不过**），
 * 而 {@code Map<String,String>} 那样的表**做不到**（漏一项照样编译）。
 *
 * <h2>⛔ 它不是什么</h2>
 * <ul>
 *   <li>⛔ **不是运行期注册表**：没有可变状态、没有 `register(...)`；
 *       `O63` 明确否决过"运行期注册对象"（= 第二份真相 + 丢掉编译期穷尽性）；</li>
 *   <li>⛔ **不改 {@link JobRequest.Kind} 的形状**：给枚举加负载会惊动既有的两个解析器
 *       （`check-job-kind-contracts.sh:37` 与 `check-job-menu-listable.sh:54` 都按
 *       `^\s*([A-Z][A-Z0-9_]*)\s*,?\s*$` 逐行取常量名 ⇒ 加了负载就**一行都取不到**）；</li>
 *   <li>⛔ **不替菜单写字面量**：`CandidateMenu` 里仍然**内联**写 `"lumber"` 这类字面量
 *       —— 因为门禁 `check-job-menu-listable.sh` 的 ②③ 是**位置化**断言
 *       （只认第 2 个实参，照 `D-441` 的教训），得有个真字面量可认。
 *       本类与那些字面量的关系由门禁**双向核**（见下）。</li>
 * </ul>
 *
 * <h2>门禁链（单一真源 → 生成物 → 双向核）</h2>
 * <pre>
 *   JobMenuKinds.java（单一真源，本类）
 *        ↓  python3 tools/job-kind-view.py --write
 *   docs/JOB_KIND_VIEW.csv（人读视图，入库）
 *        ↓  check-job-menu-listable.sh 读它当映射表（⛔ 不再是 bash 变量）
 *   CandidateMenu.java 里 new Entry(...) 的第 2 实参（实物）
 * </pre>
 * 双向核：`tools/job-kind-view.py`（无参 = 门禁）断言 **Java ↔ CSV** 一致 ＋ **Kind 覆盖双向**
 * （每个枚举值都有行、每条 case 都是真枚举值）；`check-job-menu-listable.sh` 断言
 * **CSV ↔ 菜单字面量** 双向（位置化命中）。
 *
 * <p>⚠️ {@code CRAFT → "craftable"} 是**有意**的：同一概念在菜单侧的第 3 种写法（`D-342` 同族），
 * **正是本机制要钉住的东西** —— ⛔ 不许"机械小写化"通过。
 */
public final class JobMenuKinds {

    private JobMenuKinds() {
    }

    /**
     * 该 kind 在**候选菜单里**使用的 kind 字面量。
     *
     * <p>⚠️ 返回的字符串必须与 {@code decision/CandidateMenu.java} 里某个 {@code new Entry(...)}
     * 的**第 2 个实参逐字相同** —— 门禁 `check-job-menu-listable.sh` 会位置化核对。
     *
     * @param kind 目标级作业种类（非 null；枚举穷尽由编译器保证）
     * @return 菜单 kind 字面量
     */
    public static String menuKind(JobRequest.Kind kind) {
        // ⛔ 不许加 default：无 default 的 switch 表达式才会在"新增枚举值"时**编译不过**。
        return switch (kind) {
            case LUMBER -> "lumber";
            case MINE -> "mine";
            case REGION_LUMBER -> "region_lumber";
            case COLLECT -> "collect";
            case CRAFT -> "craftable";
        };
    }

    /** 全部 kind（诊断/生成视图用；顺序 = 枚举声明顺序）。 */
    public static List<JobRequest.Kind> all() {
        return List.of(JobRequest.Kind.values());
    }

    /** 一行读数（诊断用）。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (JobRequest.Kind k : JobRequest.Kind.values()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(k).append('→').append(menuKind(k));
        }
        return sb.toString();
    }
}

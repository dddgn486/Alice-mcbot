package com.dddgn.alice.task.check;

import com.dddgn.alice.task.check.modules.BreakRefusedModule;
import com.dddgn.alice.task.check.modules.ContractsModule;
import com.dddgn.alice.task.check.modules.GatesModule;
import com.dddgn.alice.task.check.modules.LlmModule;
import com.dddgn.alice.task.check.modules.PickupModule;
import com.dddgn.alice.task.check.modules.TelemetryModule;
import com.dddgn.alice.task.check.modules.WriteModule;
import com.dddgn.alice.task.check.modules.CraftModule;
import com.dddgn.alice.task.check.modules.DeathModule;
import com.dddgn.alice.task.check.modules.HarnessSelfModule;
import com.dddgn.alice.task.check.modules.DecisionModule;
import com.dddgn.alice.task.check.modules.LedgerModule;
import com.dddgn.alice.task.check.modules.LumberModule;
import com.dddgn.alice.task.check.modules.MachineModule;
import com.dddgn.alice.task.check.modules.MiningModule;
import com.dddgn.alice.task.check.modules.MineDropRangeModule;
import com.dddgn.alice.task.check.modules.PathingModule;
import com.dddgn.alice.task.check.modules.OwnershipModule;
import com.dddgn.alice.task.check.modules.ProtectionModule;
import com.dddgn.alice.task.check.modules.SurvivalModule;
import com.dddgn.alice.task.check.modules.ToolsModule;
import com.dddgn.alice.task.check.modules.TransferModule;

import java.util.List;
import java.util.Set;

/**
 * **自检模块注册表（R-2 Phase 1b）**：模块的**唯一出处** ⇒ 命令行/无头/门禁都从这里取 id ✓。
 *
 * <p>新增模块的纪律（写给后来人）：把步搬进模块时，**同步保证模块能单独跑通** ✓
 *（场景 + 发料 + 前提都由模块自带 ✗ 不许依赖"前序模块把区块热起来"这种隐含前提 ✗）。
 */
public final class CheckModules {

    private static final List<CheckModule> ALL = List.of(
            new LedgerModule(),
            new HarnessSelfModule(),
            new PathingModule(),
            new DecisionModule(),
            new CraftModule(),
            new MachineModule(),
            new MiningModule(),
            new LumberModule(),
            new TransferModule(),
            new SurvivalModule(),
            new DeathModule(),
            new ToolsModule(),
            new GatesModule(),
            new LlmModule(),
            new PickupModule(),
            new TelemetryModule(),
            new WriteModule(),
            new ContractsModule(),
            new ProtectionModule(),
            new OwnershipModule(),
            new BreakRefusedModule(),
            // ⭐ `survey/22 §1.5①` 的缺陷取证（**故意红**：声明 `expectedVerdict()=FAIL`，且不进电池）
            // —— 为什么它单独成模块、且必须不被电池组合：见 `MineDropRangeModule` 类头「双向绊线」。
            new MineDropRangeModule()
    );

    private CheckModules() {
    }

    public static List<CheckModule> all() {
        return ALL;
    }

    /** 按 id 取模块；未知 id 返回 null（调用方**如实失败** ✗ 不许静默降级）。 */
    public static CheckModule byId(String id) {
        for (CheckModule module : ALL) {
            if (module.id().equals(id)) {
                return module;
            }
        }
        return null;
    }

    /** `id:EXPECTED` 形式的期望判决表（供 `list-modules` 与验收脚本 ✓）。 */
    public static String expectedVerdicts() {
        return ALL.stream().map(m -> m.id() + ":" + m.expectedVerdict())
                .collect(java.util.stream.Collectors.joining(","));
    }

    public static Set<String> knownIds() {
        return ALL.stream().map(CheckModule::id).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }
}

package com.dddgn.alice.region.authz;

import com.dddgn.alice.region.JobRegionRegistry;
import com.dddgn.alice.write.WritePolicyMatrix;
import com.dddgn.alice.write.WriteReason;

/**
 * ⭐ **E 维：授权到哪一档**（2026-10-01 用户裁定「拆三个谓词后定名」；结构提案 `§5`）。
 *
 * <p><b>它回答什么</b>：这一格**已被任务区覆盖**（D 维已放行，见 {@link AreaPermission}）之后，
 * **这个动作**在这片 job 区被授予的档位上**允不允许**：破坏要 `allowsBreak()`，放置要 `allowsPlace()`，
 * 且 `L1`（临时脚手架）只许**临时**放置（{@link WriteReason#temporary()}）。
 *
 * <p>⭐ **档位从哪来**：`zone.level()` = **声明者的初始授予**（`WritePolicyMatrix.areaLevel`），
 * `zone.effectiveLevel()` = 再按 `D-338` 附注十四**封顶**后的结果（保护区内、非玩家发起
 * ⇒ 从 `L2/L3` 降到 `L1`：能垫脚，**拆不了玩家的方块**）。**只收紧、不放宽**。
 * ⇒ 这里**只读**这两个值，⛔ 不自己造档位、⛔ 不改写它们（封顶的唯一出处仍是 `JobRegionRegistry.JobRegion`）。
 *
 * <p>⛔ **不是入口**（这就是本类**包内可见**的原因）：外部要问授权只能走
 * {@link AreaPermission#authorize} 及其包装 —— 编译器保证**不存在第二个授权入口**。
 * ⛔ 也**不许**把"还能动几次"（F 维）搬进来：那是 {@link Quota#inJobRegionPlaceRefusal} 的问题。
 */
final class AreaPermissionLevel {

    private AreaPermissionLevel() {
    }

    /**
     * E 维判定（**放行或拒绝，没有第三态** —— 走到这里 D 维已经放行过）。
     *
     * @return `ALLOW` = 这一档放行这个动作（F 维还会接着问"还能动几次"）；`DENY` = 带码拒绝
     */
    static AreaPermission.Decision check(JobRegionRegistry.JobRegion zone, WriteReason reason,
                                         AreaPermission.Act act) {
        WritePolicyMatrix.Level declared = zone.level();
        WritePolicyMatrix.Level effective = zone.effectiveLevel();
        String capNote = effective == declared ? ""
                : "【保护区内·非玩家发起（LLM/未归因）⇒ 从 " + declared.label() + " 封顶 "
                        + effective.label() + "】";
        if (act == AreaPermission.Act.BREAK) {
            if (!effective.allowsBreak()) {
                boolean readOnly = effective == WritePolicyMatrix.Level.L0_READ_ONLY;
                return new AreaPermission.Decision(AreaPermission.Verdict.DENY,
                        readOnly ? "zone_read_only" : "zone_break_not_allowed",
                        "任务区 " + zone.kind() + " 等级 " + effective.label() + capNote
                                + (readOnly ? "（只读）" : "（临时脚手架 ⇒ 不许破坏）"));
            }
            return new AreaPermission.Decision(AreaPermission.Verdict.ALLOW, null,
                    "任务区 " + zone.kind() + " 等级 " + effective.label() + capNote
                            + " ⇒ 放行破坏（理由 " + reason.name() + "）");
        }
        if (!effective.allowsPlace()) {
            return new AreaPermission.Decision(AreaPermission.Verdict.DENY, "zone_read_only",
                    "任务区 " + zone.kind() + " 等级 " + effective.label() + capNote + "（只读）⇒ 不许放置");
        }
        if (effective == WritePolicyMatrix.Level.L1_SCAFFOLD && !reason.temporary()) {
            return new AreaPermission.Decision(AreaPermission.Verdict.DENY, "zone_place_not_scaffold",
                    "任务区 " + zone.kind() + " 等级 L1 只许**临时**放置，而 " + reason.name() + " 不是");
        }
        return new AreaPermission.Decision(AreaPermission.Verdict.ALLOW, null,
                "任务区 " + zone.kind() + " 等级 " + effective.label() + capNote
                        + " ⇒ 放行放置（理由 " + reason.name() + "）");
    }
}

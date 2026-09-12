package com.dddgn.alice.pathing.core;

import net.minecraft.core.BlockPos;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * **能力闸门**（基-8 / G8）：让 {@link MovementCapabilities} 从"没人读的元数据"变成**执行期真的会拦人**的复验。
 *
 * <p>为什么必须补（2026-09-12 复核）：`MovementCapabilities` 一共 12 个分量，除
 * `requiredRecoverabilityLevel`（基-1 起有读者）之外，**其余 10 个读者为 0** ——
 * 与 P0-B 同一病灶：声明了"这个 Movement 会改世界 / 会破坏 / 要授权 / 耗资源 / 需要工具"，
 * 但没有任何代码问过它。于是"寻路不许偷偷写世界"（D-076）实际只靠
 * `PathRequest.allowedMovementTypes` 这一层**类型清单**在守，能力声明本身是装饰。
 *
 * <p><b>为什么在**执行期**复验而不是只在搜索期</b>：与 D-076 里"plan 可能比产生它的请求活得更久"
 * 同一条理由 —— 搜索期检查过的事实（保护区、一次性方块、工具、预算）在真正执行前**可能已经变了**
 * （玩家刚划了保护区、方块用完了、镐子用坏了、预算被别的动作吃掉）。
 *
 * <p>本类是**纯函数**：世界事实由 {@link Facts} 提供 ⇒ 自检可以喂假事实直接断言"能不能拦住"，
 * 不需要开世界（这也是"签名能力空实现"不该再复发的前提：能力必须**可断言**）。
 */
public final class CapabilityGate {

    /** 纯通行请求允许的 Movement 集合（与 `PathRequest.pureTraversal()` 一致）。 */
    public static final Set<MovementType> PURE_TRAVERSAL_TYPES = Set.of(
            MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
            MovementType.DESCEND, MovementType.FALL);

    /** 执行期需要的**世界事实**（由调用方注入，便于自检与替换）。 */
    public interface Facts {
        /** 受保护则返回保护理由（`protected_area`/`protected_block`/`protected_tag`），否则 null。 */
        String protectionReason(BlockPos pos);

        /** 一次性方块数量（放置类 Movement 的消耗品）。 */
        int throwawayBlocks();

        /** 是否还有写入预算（`breaking=true` 问破坏额度，否则问放置额度）。 */
        boolean hasWriteBudget(boolean breaking);

        /** 是否具备该 Movement 需要的工具（没有正确工具时破坏会白挖）。 */
        boolean hasRequiredTool(MovementType type);

        /** 本次请求是不是**纯通行**请求（允许集 ⊆ 纯通行集合）。 */
        boolean pureTraversalRequest();
    }

    private CapabilityGate() {
    }

    /**
     * 复验一条 Movement 能不能**真的执行**。通过 ⇒ 空；否则返回拒绝码（会写进会话失败码，便于归因）。
     *
     * <p>拒绝码：{@code CAPABILITY_UNAUTHORIZED}（会改世界却拿纯通行请求执行 —— 声明与请求不符，
     * 说明规划输出跑到了授权之外）、{@code ZONE_*}（保护区）、{@code NO_THROWAWAY_BLOCKS}（资源）、
     * {@code NO_REQUIRED_TOOL}（工具）、{@code BREAK_BUDGET_EXHAUSTED}/{@code PLACE_BUDGET_EXHAUSTED}（预算）。
     */
    public static Optional<String> check(MovementCapabilities caps, MovementType type, BlockPos toFoot,
                                         Facts facts) {
        if (!caps.changesWorld()) {
            return Optional.empty();   // 纯通行：不碰世界 ⇒ 不需要授权/预算/保护/资源检查
        }
        // ① 声明与请求的一致性：会改世界的 Movement 不该出现在"纯通行请求"里
        if (facts.pureTraversalRequest()) {
            return Optional.of("CAPABILITY_UNAUTHORIZED");
        }
        // ② 保护区（requiresZoneAuthorization）
        if (caps.requiresZoneAuthorization()) {
            String reason = facts.protectionReason(toFoot);
            if (reason != null) {
                return Optional.of("ZONE_" + reason.toUpperCase(Locale.ROOT));
            }
        }
        // ③ 消耗品（consumesResources）
        if (caps.consumesResources() && facts.throwawayBlocks() <= 0) {
            return Optional.of("NO_THROWAWAY_BLOCKS");
        }
        // ④ 工具（requiresTool）
        if (caps.requiresTool() && !facts.hasRequiredTool(type)) {
            return Optional.of("NO_REQUIRED_TOOL");
        }
        // ⑤ 写入预算
        if (caps.canBreakBlocks() && !facts.hasWriteBudget(true)) {
            return Optional.of("BREAK_BUDGET_EXHAUSTED");
        }
        if (caps.canPlaceBlocks() && !facts.hasWriteBudget(false)) {
            return Optional.of("PLACE_BUDGET_EXHAUSTED");
        }
        return Optional.empty();
    }

    /** 自检/汇报用：这条 Movement 的能力声明里哪些字段是"会拦人"的。 */
    public static String describe(MovementCapabilities caps) {
        return "changesWorld=" + caps.changesWorld()
                + " break=" + caps.canBreakBlocks()
                + " place=" + caps.canPlaceBlocks()
                + " zoneAuth=" + caps.requiresZoneAuthorization()
                + " consumes=" + caps.consumesResources()
                + " needsTool=" + caps.requiresTool()
                + " intents=" + caps.mutationIntents();
    }
}

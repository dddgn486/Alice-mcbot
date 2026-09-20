package com.dddgn.alice.job.mine;

import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.GoalSpec;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * **候选的通行成本读数**（`D-329` §2.1 成本契约的接缝）。
 *
 * <p>为什么要有这个接口：成本模型必须**可判定**。真实现的成本来自"一次 Dijkstra 成本场"
 * （{@link StandingCostField}），那需要世界；而**选择规则本身**（排序、权重、退化、单种类惰性）
 * 是纯逻辑 ⇒ 夹具可以注入**脚本化成本**，把规则逐条咬死（本项目一贯口径：把"事实"与"规则"分开测）。
 *
 * <p>⚠️ 成本**不是判决**：估不出成本（不可达/超预算）只是"排序靠后"，**不许**当成"不能挖"——
 * 那会变成另一个 `SEARCH_LIMIT ≠ UNREACHABLE`（`D-076`/`S3`）。
 */
public interface CandidateCostProvider {

    /**
     * 一次选择的成本读数。
     *
     * @param travelByAnchor 候选锚点（{@link net.minecraft.core.BlockPos#asLong()}）→ 通行成本；
     *                       缺项 = 本次估不出（排序时按"最差"处理，**不拒绝**）
     * @param estimatedCells 这次为了成本跑了多少个格子（成本有界的**可测**证据）
     * @param note           进决策日志的一行说明（如 `dijkstra cells=37`）
     */
    record Result(Map<Long, Double> travelByAnchor, int estimatedCells, String note) {

        public Result {
            travelByAnchor = Map.copyOf(travelByAnchor);
        }

        public static Result empty(String note) {
            return new Result(Map.of(), 0, note);
        }

        public double travel(Candidate candidate) {
            Double value = travelByAnchor.get(candidate.anchor().asLong());
            return value == null ? Double.POSITIVE_INFINITY : value;
        }
    }

    Result estimate(ServerPlayer bot, GoalSpec spec, List<Candidate> candidates);

    /** 夹具用：脚本化成本（确定性，不读世界）。 */
    static CandidateCostProvider scripted(Map<Long, Double> costsByAnchor) {
        Map<Long, Double> copy = new HashMap<>(costsByAnchor);
        return (bot, spec, candidates) -> new Result(copy, candidates.size(), "scripted");
    }

    /** 夹具用：把"欧氏距离"当成本（⇒ 与 {@code NearestPolicy} 的排序同源，可用于退化对照）。 */
    static CandidateCostProvider euclidean(ServerPlayer bot) {
        return (serverPlayer, spec, candidates) -> {
            Map<Long, Double> costs = new HashMap<>();
            for (Candidate candidate : candidates) {
                costs.put(candidate.anchor().asLong(), Math.sqrt(bot.distanceToSqr(
                        candidate.anchor().getX() + 0.5D, candidate.anchor().getY() + 0.5D,
                        candidate.anchor().getZ() + 0.5D)));
            }
            return new Result(costs, 0, "euclidean");
        };
    }
}

package com.dddgn.alice.reach;

/**
 * **"走到站位格"这一步允许什么手段** —— 接近能力的**声明**（`D-443` 裁定 1a，2026-09-25）。
 *
 * <ul>
 *   <li>{@link #PURE_PASSAGE}（默认）= {@code PathRequest.of}：只走，不改世界（`D-076` 默认）；</li>
 *   <li>{@link #PLACEMENT_ALLOWED} = {@code PathRequest.withPlacement}：**只放不拆**
 *       （`PLACE_STEP_AND_TRAVERSE` + `PILLAR` + `FALL`）—— 与鱼骨的 `A14` **同一个集合**，
 *       所以**不扩大任何写入授权面**（`D-443` 裁定 7b：额度用尽 ⇒ 如实放弃，不静默降级）。</li>
 * </ul>
 *
 * <p>⚠️ 消费方（今天的唯一消费者 = 鱼骨）必须自己持有预算与上限：本枚举只表达"**允许**"，
 * 不表达"**放多少**"（`C8` 的三条上限 + `A14` 的作业累计额度仍在作业侧）。
 *
 * <p>⭐ 2026-09-29（改革 ① 主体 · `①-0`「拆信封」，`D-527`）：本枚举原先住在
 * `task/mining/MiningProfile` 里当**嵌套枚举**，现在**提出来独立成类并落到 `reach/`**。
 * 依据 = `plans §4.2`① 要把「选」搬进 `reach/`，而 `reach/` **不许** import `task/`
 * （`check-layer-direction` 断言①）⇒ 一个住在 `task/` 里的嵌套类型会把新家**再次拖回循环**
 * （`D-460` 记的正是这条形状："漏搬一个，循环就换个方向长回来"）。
 * ⛔ **语义一个字没改**：取值仍是两个、默认仍是 {@link #PURE_PASSAGE}；
 * `MiningProfile` 现在**引用**本类（`task/` → `reach/` 是合法方向），字段名与访问器名都没动。
 *
 * <p>⚠️ **它不是我"授权本体"**：真正的授权来源是**作业级声明**（`D-500` §IV），
 * 本枚举只是"这次接近**申请**什么能力"的那一半；允许与否仍要过 `Attribution`/预算闸门。
 */
public enum ApproachCapability {
    /** 只走，不改世界（`D-076` 默认；`PathRequest.of`）。 */
    PURE_PASSAGE,

    /** **只放不拆**：允许"补一块再走"（`PathRequest.withPlacement`；与鱼骨 `A14` 同一个集合）。 */
    PLACEMENT_ALLOWED
}

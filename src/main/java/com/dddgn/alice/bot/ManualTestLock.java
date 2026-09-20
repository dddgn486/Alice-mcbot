package com.dddgn.alice.bot;

import com.dddgn.alice.decision.BotEventLog;
import com.dddgn.alice.log.BotLog;

/**
 * **手动测试占用锁**（`D-360`，用户 2026-09-20 要求："测试工具要阻断 LLM 接手"）。
 *
 * <p>为什么必须有它：LLM 是**在线**的（客户端 `config/alice-llm.json` 里 `enabled=true`），它唯一的执行入口是
 * {@link BotManager#assignJob}（`start_job` → `GoalDirector.execute`）。手动实测时如果它插进来起任务
 * （换掉正在跑的、或并发起一个），**测的就不是一个人的行为了** —— 数据直接作废，而且现场很难复盘。
 *
 * <p>口径：
 * <ul>
 *   <li>锁上时，**生产入口一律拒绝**（`assignJob`）并且**响亮可见**（日志 + 事件环 `REFUSED`）；</li>
 *   <li>**唯一**的绕过口是 {@link BotManager#assignManualTestJob}（名字里带 `ManualTest` ⇒ 谁在别处调它一眼能看出错）；</li>
 *   <li>锁**只挡"起新任务"**，不挡观察、不挡停止 —— 它不是一个"静默模式"，而是一道准入闸。</li>
 * </ul>
 *
 * <p>⚠️ 它**不影响电池**：默认未上锁（`active()==false`），电池的 74 步一步都不碰它。
 */
public final class ManualTestLock {

    /** 未上锁时的理由（空串 = 未上锁；用空串而不是 null，日志里少一类 NPE）。 */
    private static String reason = "";

    /**
     * ⭐ **手动窗口**：手动测试入口"自己发起这一次起任务"时要放行一次。
     *
     * <p>为什么不用"再加一个 public 入口来绕过锁"：那会多一条**能被误用**的生产 API（而且改名/改签名会让
     * 既有结构断言漂移——2026-09-20 当场踩到：`assignJob` 一拆成 public 委派 + private 实现，
     * `D-338` 的事件环断言就找不到真正的函数体了）。窗口是**作用域内的一次性放行**：
     * 只有调用方自己在 `finally` 里关，深度计数保证嵌套/异常都不会把锁永久打开。
     */
    private static int manualWindowDepth;

    private ManualTestLock() {
    }

    /** 上锁（重复上锁只如实追加理由，不抛）。 */
    public static synchronized void on(String why) {
        reason = why == null || why.isBlank() ? "手动测试" : why;
        BotLog.warn("[TestMode] 手动测试占用**已上锁**：{} ⇒ 决策层（LLM）的起任务请求会被拒绝", reason);
    }

    /** 解锁（幂等）。 */
    public static synchronized void off(String why) {
        if (reason.isEmpty()) {
            return;
        }
        BotLog.warn("[TestMode] 手动测试占用**已解锁**（{}）：{}", why == null ? "-" : why, reason);
        reason = "";
    }

    public static synchronized boolean active() {
        return !reason.isEmpty();
    }

    public static synchronized String reason() {
        return reason;
    }

    /** 打开手动窗口（**只在手动测试入口里用**，且必须 `finally` 关闭）。 */
    public static synchronized void beginManualWindow() {
        manualWindowDepth++;
    }

    /** 关闭手动窗口（幂等到 0）。 */
    public static synchronized void endManualWindow() {
        if (manualWindowDepth > 0) {
            manualWindowDepth--;
        }
    }

    public static synchronized boolean inManualWindow() {
        return manualWindowDepth > 0;
    }

    /** 拒绝理由码（机器可读；进日志与事件环）。 */
    public static synchronized String refusal() {
        return "manual_test_lock:" + (reason.isEmpty() ? "-" : reason);
    }

    /**
     * 生产准入检查（**唯一调用点 = `BotManager.assignJob`**）。
     *
     * @return `null` = 放行；否则是拒绝理由码
     */
    public static String refusalFor(BotPlayer bot, String what) {
        if (!active() || inManualWindow()) {
            return null;
        }
        String code = refusal();
        if (bot != null) {
            // 与既有拒绝路径同形（`JobKindContract` 那条也是这么记的）⇒ 决策层/事件环读得到
            BotEventLog.record(bot, "REFUSED", "warn",
                    "手动测试占用中 ⇒ 拒绝起任务（" + what + "；" + code + "）");
        }
        BotLog.warn("[TestMode] 拒绝起任务（{}）：{}（要起任务先解锁）", what, code);
        return code;
    }
}

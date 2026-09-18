package com.dddgn.alice.compat.ftbchunks;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * **FTB Chunks 只读桥**（`D-326`）—— 只回答一个问题：**"这一格，FTB 认领允不允许这只 bot 编辑方块？"**
 *
 * <p>写路径**一个字都不碰**（不做 claim/unclaim/改权限），只调用 **FTB 自己那条裁决函数**，
 * 让它按它自己的配置/队伍/认领状态做决定。
 *
 * <p><b>已核实（对装好的 `ftb-chunks-forge-2001.3.8.jar` 反汇编 + 1.20.1 Forge 源码逐条对照）</b>：
 * <ul>
 *   <li>{@code FTBChunksAPI.api()} ⇒ {@code FTBChunksAPI$API}：{@code boolean isManagerLoaded()} /
 *       {@code ClaimedChunkManager getManager()}；</li>
 *   <li>{@code ClaimedChunkManager.shouldPreventInteraction(Entity, InteractionHand, BlockPos, Protection, Entity)}；</li>
 *   <li>{@code Protection.EDIT_BLOCK}（公开静态字段）—— Forge 平台把
 *       {@code FTBChunksExpected.getBlockBreakProtection()/getBlockPlaceProtection()} **都**实现为返回它
 *       （`forge/.../FTBChunksExpectedImpl.java:23-33`）；</li>
 *   <li>FTB 自己的钩子也用同一个函数 + 同一个常量：`FTBChunks.java:276`/`:321`（破坏）、`:331`（放置）
 *       ⇒ 我们问的问题与 FTB 自己问的**逐字一致**。</li>
 * </ul>
 *
 * <p><b>`D-318` 的教训（这条类同样是它的产物）</b>：上一版兼容层靠"猜"方法名 ⇒ 整套兼容从未生效、也从未报错。
 * 所以这里每个成员都**核对签名**（参数类型逐条比），缺类 ⇒ `ftbchunks_absent`、签名不符 ⇒
 * `signature_mismatch:<成员>` 并**打一条 warn**，把"猜错了"变成看得见的事实。
 */
public final class FtbChunksBridge {

    private static final AtomicBoolean PROBED = new AtomicBoolean();
    private static volatile boolean available;
    private static volatile String unavailableReason = "not_probed";
    private static volatile String lastError = "";

    private static Method apiMethod;              // FTBChunksAPI.api()
    private static Method isManagerLoadedMethod;  // API.isManagerLoaded()
    private static Method getManagerMethod;       // API.getManager()
    private static Method preventMethod;          // ClaimedChunkManager.shouldPreventInteraction(...)
    private static Object editBlockProtection;    // Protection.EDIT_BLOCK

    private FtbChunksBridge() {
    }

    /** FTB Chunks 是否在场且签名对得上（不含"管理器已加载"这一运行期条件）。 */
    public static boolean available() {
        probe();
        return available;
    }

    /** 不可用原因（`ftbchunks_absent` / `signature_mismatch:<成员>`）。 */
    public static String unavailableReason() {
        probe();
        return unavailableReason;
    }

    /** 最近一次调用失败原因（成功时为空串）。 */
    public static String lastError() {
        return lastError;
    }

    /**
     * **FTB 会不会拦下这次方块编辑**（`true` = 拦）。
     *
     * <p>语义与 FTB 自己的破坏/放置钩子一致：认领隐私设置（PUBLIC/ALLY/成员）· 数据包白名单标签 ·
     * `/ftbchunks admin bypass_protection` · 旁观者 · 全局 `disable_protection` · 荒野策略，全部由它自己判。
     *
     * @throws RuntimeException 桥不可用或调用失败（调用方应先查 {@link #available()}）
     */
    public static boolean wouldPreventBlockEdit(ServerPlayer bot, BlockPos pos) {
        if (!available()) {
            throw new IllegalStateException("FTB Chunks 桥不可用：" + unavailableReason);
        }
        try {
            Object api = apiMethod.invoke(null);
            if (!(boolean) isManagerLoadedMethod.invoke(api)) {
                throw new IllegalStateException("FTB Chunks 管理器尚未加载");
            }
            Object manager = getManagerMethod.invoke(api);
            return (boolean) preventMethod.invoke(manager, bot, InteractionHand.MAIN_HAND, pos,
                    editBlockProtection, null);
        } catch (ReflectiveOperationException failure) {
            lastError = failure.toString();
            throw new IllegalStateException("FTB Chunks 裁决调用失败：" + failure, failure);
        }
    }

    private static void probe() {
        if (!PROBED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI");
            Class<?> apiInterface = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI$API");
            Class<?> managerInterface = Class.forName("dev.ftb.mods.ftbchunks.api.ClaimedChunkManager");
            Class<?> protectionClass = Class.forName("dev.ftb.mods.ftbchunks.api.Protection");

            apiMethod = apiClass.getMethod("api");
            if (apiMethod.getReturnType() != apiInterface) {
                fail("signature_mismatch:FTBChunksAPI.api");
                return;
            }
            isManagerLoadedMethod = apiInterface.getMethod("isManagerLoaded");
            if (isManagerLoadedMethod.getReturnType() != boolean.class) {
                fail("signature_mismatch:API.isManagerLoaded");
                return;
            }
            getManagerMethod = apiInterface.getMethod("getManager");
            if (getManagerMethod.getReturnType() != managerInterface) {
                fail("signature_mismatch:API.getManager");
                return;
            }
            preventMethod = managerInterface.getMethod("shouldPreventInteraction",
                    net.minecraft.world.entity.Entity.class, InteractionHand.class, BlockPos.class,
                    protectionClass, net.minecraft.world.entity.Entity.class);
            if (preventMethod.getReturnType() != boolean.class) {
                fail("signature_mismatch:ClaimedChunkManager.shouldPreventInteraction");
                return;
            }
            Field editBlock = protectionClass.getField("EDIT_BLOCK");
            if (editBlock.getType() != protectionClass) {
                fail("signature_mismatch:Protection.EDIT_BLOCK");
                return;
            }
            editBlockProtection = editBlock.get(null);
            if (editBlockProtection == null) {
                fail("signature_mismatch:Protection.EDIT_BLOCK(null)");
                return;
            }
            available = true;
            unavailableReason = "";
            BotLog.info("[FTB] FTB Chunks 只读桥已就绪（裁决函数 = ClaimedChunkManager.shouldPreventInteraction + "
                    + "Protection.EDIT_BLOCK，与它自己的破坏/放置钩子逐字一致；写路径一字不碰）");
        } catch (ClassNotFoundException absent) {
            unavailableReason = "ftbchunks_absent";
        } catch (ReflectiveOperationException mismatch) {
            fail("signature_mismatch:" + mismatch.getMessage());
        }
    }

    private static void fail(String reason) {
        available = false;
        unavailableReason = reason;
        BotLog.warn("[FTB] FTB Chunks 桥不可用（{}）⇒ 第三方保护预检**不生效**（我们自己的保护区/预算闸门仍然有效）；"
                + "这是待修的缺口，不是「没有这条规则」", reason);
    }
}

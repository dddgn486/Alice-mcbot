package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotOwnership;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.BotWorldData;
import com.dddgn.alice.log.BotLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * **假人归属自检**（D-319，2026-09-18）—— 电池步 {@code bot_ownership}。
 *
 * <p>钉住的是"创建者登记"这一层最容易悄悄错掉的四件事（每条都是纯数据，**零世界写入**）：
 * <ol>
 *   <li><b>认领是单向的</b>：已登记 ⇒ 再认领必须**失败且一个字都不改**（否则权限会跟着人飘）；</li>
 *   <li><b>未登记 ≠ 有主</b>：老存档 / 探针 / 命令方块 ⇒ 「未登记」，且**不是空字符串**
 *       （空串会被读成"有主但没名字"，是最典型的静默错）；</li>
 *   <li><b>存档往返一致</b>：`write` → `read` 完全一致，而且**未登记时不写键**（老存档形状不变 ⇒ 可回退）；</li>
 *   <li><b>走生产落盘路径</b>：`BotManager.saveToWorld` → `BotWorldData` 的 tag 里确实带创建者
 *       （只测辅助函数会漏掉"生产路径没接线"这类最贵的错 —— 与 `D-317` 的教训同源）。</li>
 * </ol>
 *
 * <p>夹具**自带起点与复位**：进入前记下当前归属、结束时精确写回（失败路径同样走收尾），
 * 并把新学的门禁也带上：`BotOwnership` **不许有同名方法重载**（`D-317` 用一次客户端崩溃换来的那条）。
 */
public final class BotOwnershipCheckTask implements Task {

    /** 六个阶段各一 tick + 余量：本步**不碰世界**，所以预算可以很小。 */
    private static final int BUDGET_TICKS = 60;

    private enum Phase { GUARD, REGISTER, REFUSE, PERSIST, LEGACY, CLEANUP, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();

    private Phase phase = Phase.GUARD;
    private int ticks;
    private int checks;
    private boolean done;

    /** 进入前的归属（用于精确复原 + 断言复原成功）。 */
    private BotOwnership.Creator creatorBefore = BotOwnership.NONE;

    public BotOwnershipCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "BotOwnershipCheck";
    }

    @Override
    public TaskTarget target() {
        return bot == null ? TaskTarget.block(net.minecraft.core.BlockPos.ZERO) : TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Task.Status tick() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        if (++ticks > BUDGET_TICKS) {
            check("自检必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        switch (phase) {
            case GUARD -> guardPhase();
            case REGISTER -> registerPhase();
            case REFUSE -> refusePhase();
            case PERSIST -> persistPhase();
            case LEGACY -> legacyPhase();
            case CLEANUP -> cleanupPhase();
            case DONE -> {
                return finish();
            }
            default -> {
            }
        }
        return Task.Status.RUNNING;
    }

    // ==================== 阶段 ====================

    /** 前提自证 + 把起点摆成"未登记"（否则后续判据会被进入前的状态污染）。 */
    private void guardPhase() {
        check("前提：本步需要一只假人（无 bot ⇒ 归属无从谈起）", bot != null);
        check("前提：本步需要观察者玩家（身份类判据必须有一个真实玩家当认领者）", observer != null);
        if (bot == null || observer == null) {
            advance(Phase.DONE);
            return;
        }
        creatorBefore = BotOwnership.creatorOfBot(bot);
        BotOwnership.applyTo(bot, BotOwnership.NONE);

        check("起点：清成未登记后 `registered()` 必须为假（实际 " + desc(BotOwnership.creatorOfBot(bot)) + "）",
                !BotOwnership.creatorOfBot(bot).registered());
        check("未登记的口径是「" + BotOwnership.UNREGISTERED + "」而**不是空字符串**（空串=有主但没名字）",
                BotOwnership.UNREGISTERED.equals(BotOwnership.describe(BotOwnership.NONE))
                        && !BotOwnership.UNREGISTERED.isEmpty());
        advance(Phase.REGISTER);
    }

    /** 空 ⇒ 认领成功，且登记的是**认领者**的 UUID 与当时名字。 */
    private void registerPhase() {
        boolean adopted = BotOwnership.adopt(bot, observer);
        check("空归属 ⇒ 认领必须成功（adopt=true）", adopted);

        BotOwnership.Creator creator = BotOwnership.creatorOfBot(bot);
        check("创建者 UUID = 认领者 UUID（实际 " + BotOwnership.shortId(creator.uuid()) + "）",
                observer.getUUID().equals(creator.uuid()));
        check("创建者名字 = 认领**当时**的名字快照（实际 " + desc(creator.name()) + "）",
                observer.getGameProfile().getName().equals(creator.name()));
        check("describe 里能看到创建者名字（实际 " + BotOwnership.describe(creator) + "）",
                BotOwnership.describe(creator).contains(observer.getGameProfile().getName()));
        advance(Phase.REFUSE);
    }

    /** ⭐ 反向对照点：**已有创建者 ⇒ 再认领必须被拒且不改写**。 */
    private void refusePhase() {
        BotOwnership.Creator frozen = BotOwnership.creatorOfBot(bot);
        // 用 bot 自己当"另一个认领者"：adopt 的第二个参数只要有身份即可，这里要验的是"有主就不许改"
        boolean again = BotOwnership.adopt(bot, bot);
        check("已有创建者 ⇒ 再认领必须 false（实际 " + again + "）", !again);
        check("已有创建者 ⇒ 归属一个字都不许改（" + desc(frozen) + " vs " + desc(BotOwnership.creatorOfBot(bot)) + "）",
                frozen.equals(BotOwnership.creatorOfBot(bot)));
        check("边界：认领者传 null 也必须 false（不许把 bot 变成无主/半登记）",
                !BotOwnership.adopt(bot, null) && BotOwnership.creatorOfBot(bot).equals(frozen));
        advance(Phase.PERSIST);
    }

    /** 存档往返 + **走生产落盘路径**（saveToWorld → BotWorldData）。 */
    private void persistPhase() {
        BotOwnership.Creator creator = BotOwnership.creatorOfBot(bot);
        CompoundTag tag = new CompoundTag();
        BotOwnership.write(tag, creator);
        check("已登记 ⇒ 存档里必须出现 " + BotOwnership.NBT_CREATOR + " 键", tag.hasUUID(BotOwnership.NBT_CREATOR));
        check("存档往返：read(write(c)) 必须与 c 完全一致（实际 " + desc(BotOwnership.read(tag)) + "）",
                BotOwnership.read(tag).equals(creator));

        CompoundTag empty = new CompoundTag();
        BotOwnership.write(empty, BotOwnership.NONE);
        check("未登记 ⇒ **不许写键**（老存档形状不变 ⇒ 可回退到不认归属的版本）",
                !empty.hasUUID(BotOwnership.NBT_CREATOR) && !empty.contains(BotOwnership.NBT_CREATOR_NAME));

        // ⭐ 生产路径（不是只测辅助函数）：命令/adopt 走的就是 saveToWorld
        BotManager.saveToWorld(bot);
        CompoundTag world = BotWorldData.get(bot.getServer()).botTag();
        check("生产落盘：`saveToWorld` 之后世界存档 tag 里带创建者（实际 "
                        + desc(world == null ? null : BotOwnership.read(world)) + "）",
                world != null && BotOwnership.read(world).equals(creator));
        advance(Phase.LEGACY);
    }

    /** 老存档：**不猜、不静默补**；顺带复用 D-317 的门禁形状（不许同名重载）。 */
    private void legacyPhase() {
        CompoundTag legacy = new CompoundTag();
        legacy.putUUID("UUID", UUID.randomUUID());
        legacy.putString("Name", "老假人");
        BotOwnership.Creator fromLegacy = BotOwnership.read(legacy);
        check("老存档（没有 Creator 键）⇒ 读出未登记，且 uuid/name **都是 null**（不许补空串）",
                fromLegacy.uuid() == null && fromLegacy.name() == null);

        BotOwnership.applyTo(bot, BotOwnership.NONE);
        BotOwnership.applyTo(bot, fromLegacy);
        check("把老存档读进 bot 不会静默补主（实际 " + BotOwnership.describe(BotOwnership.creatorOfBot(bot)) + "）",
                !BotOwnership.creatorOfBot(bot).registered());

        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new LinkedHashSet<>();
        for (Method method : BotOwnership.class.getDeclaredMethods()) {
            if (!seen.add(method.getName())) {
                duplicated.add(method.getName());
            }
        }
        check("门禁：`BotOwnership` 不许有**同名方法重载**（D-317：离线判据看不见调用点选错重载）"
                + "（实际 " + BotOwnership.class.getDeclaredMethods().length + " 个方法，重名=" + duplicated + "）",
                duplicated.isEmpty());
        advance(Phase.CLEANUP);
    }

    /** 复位：归属写回进入前的值，并断言**内存与存档**都回去了。 */
    private void cleanupPhase() {
        BotOwnership.applyTo(bot, creatorBefore);
        BotManager.saveToWorld(bot);

        check("自清理：内存里的归属回到进入前（" + desc(creatorBefore) + " vs "
                        + desc(BotOwnership.creatorOfBot(bot)) + "）",
                BotOwnership.creatorOfBot(bot).equals(creatorBefore));
        CompoundTag world = BotWorldData.get(bot.getServer()).botTag();
        check("自清理：世界存档里的归属也回到进入前",
                world != null && BotOwnership.read(world).equals(creatorBefore));

        bot.controller().stopMovement();   // 本步不动 bot，但保持"结束即停输入"的同一条纪律
        advance(Phase.DONE);
        finish();
    }

    // ==================== 收尾 / 工具 ====================

    private Task.Status finish() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[Ownership] SUMMARY checks={} failures={} {} → {}（进入前归属={}，结算后={}）",
                checks, failures.size(), failures, pass ? "PASS" : "FAIL",
                BotOwnership.describe(creatorBefore), BotOwnership.describe(BotOwnership.creatorOfBot(bot)));
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal(
                    "[alice] 假人归属自检 " + (pass ? "PASS" : "FAIL " + failures)));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private void advance(Phase next) {
        phase = next;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }

    private static String desc(Object value) {
        return value == null ? "无" : value.toString();
    }
}

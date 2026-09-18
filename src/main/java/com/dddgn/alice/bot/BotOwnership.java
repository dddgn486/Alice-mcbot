package com.dddgn.alice.bot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * **假人归属（创建者）**（D-319）—— 登记 / 展示 / 持久化 / 认领的**唯一出处**。
 *
 * <p>为什么要有它（用户 2026-09-18 裁定）：bot 需要**它自己的一套身份**，而这套身份应当**继承它的创建者**
 * —— 第一步就是先把"创建者是谁"**登记下来**。今天完全没有：`/alice spawn` 只收名字，
 * `BotPlayer` 上也没有任何归属字段。
 *
 * <p>⚠️ 本轮**只做登记与显示**：**不接入任何权限判定**（"只有创建者能指挥该 bot"是另一次裁定，
 * 会改权限模型 ⇒ 不许顺手做）。FTB 侧的"继承创建者的队伍身份与权限"是**下一步**，
 * 那时才会用到这里的 {@code creator}（见 `docs/OPEN_ITEMS_LEDGER.md` 的登记项）。
 *
 * <p>三条口径（每条都有判据钉住，见 {@code BotOwnershipCheckTask}）：
 * <ul>
 *   <li><b>未登记 ≠ 有主</b>：老存档 / 夹具探针 / 命令方块生成的 bot 没有创建者 ⇒ 一律 {@link #NONE}，
 *       显示「未登记」，**不猜、不静默补**（要认领就显式 {@link #adopt}）；</li>
 *   <li><b>认领是单向的</b>：只在**空**的时候写上；已有创建者 ⇒ 返回 false 且**一个字都不改**；</li>
 *   <li><b>不写空键</b>：未登记时 {@link #write} 不往存档里造键 ⇒ 老存档形状完全不变（可回退）。</li>
 * </ul>
 */
public final class BotOwnership {

    /** 存档键（未登记 ⇒ 这两个键都不出现）。 */
    public static final String NBT_CREATOR = "Creator";
    public static final String NBT_CREATOR_NAME = "CreatorName";

    /** 展示用的「无主」文案（**刻意不是空字符串** —— 空串会被读成"有主但没名字"）。 */
    public static final String UNREGISTERED = "未登记";

    /** 创建者快照（UUID + **登记当时**的名字）。 */
    public record Creator(UUID uuid, String name) {

        /** 是否已登记（`uuid != null` 是唯一判据）。 */
        public boolean registered() {
            return uuid != null;
        }
    }

    /** 未登记（唯一入口；别在别处 `new Creator(null, null)`）。 */
    public static final Creator NONE = new Creator(null, null);

    private BotOwnership() {
    }

    /** 从玩家取快照（用**登记当时**的名字 ⇒ 事后改名不改变已登记的事实）。 */
    public static Creator creatorOfPlayer(ServerPlayer player) {
        if (player == null) {
            return NONE;
        }
        return new Creator(player.getUUID(), player.getGameProfile().getName());
    }

    /** 从 bot 取当前登记值。 */
    public static Creator creatorOfBot(BotPlayer bot) {
        if (bot == null || bot.creatorUuid() == null) {
            return NONE;
        }
        return new Creator(bot.creatorUuid(), bot.creatorName());
    }

    /** 写进 bot（`NONE` ⇒ 清空；夹具的"复位"也走这里，口径只有一份）。 */
    public static void applyTo(BotPlayer bot, Creator creator) {
        if (bot == null) {
            return;
        }
        Creator value = creator == null ? NONE : creator;
        bot.setCreator(value.uuid(), value.name());
    }

    /**
     * **认领**：把 `player` 登记为这只 bot 的创建者 —— **只在未登记时生效**。
     *
     * @return true = 这次真的写上了；false = 本来就有主（**不改写**）或参数不合法
     */
    public static boolean adopt(BotPlayer bot, ServerPlayer player) {
        if (bot == null || player == null || bot.creatorUuid() != null) {
            return false;
        }
        applyTo(bot, creatorOfPlayer(player));
        return true;
    }

    /** 展示文案：`demo (1a2b3c4d)` / `未登记`。 */
    public static String describe(Creator creator) {
        Creator value = creator == null ? NONE : creator;
        if (!value.registered()) {
            return UNREGISTERED;
        }
        String name = value.name() == null || value.name().isEmpty() ? "?" : value.name();
        return name + " (" + shortId(value.uuid()) + ")";
    }

    /** 短 id（人读够用；完整 UUID 仍在存档里）。 */
    public static String shortId(UUID uuid) {
        return uuid == null ? "-" : uuid.toString().substring(0, 8);
    }

    /** 写存档：**未登记就不写键**（老存档形状不变 ⇒ 可回退到不认归属的版本）。 */
    public static void write(CompoundTag tag, Creator creator) {
        Creator value = creator == null ? NONE : creator;
        if (tag == null || !value.registered()) {
            return;
        }
        tag.putUUID(NBT_CREATOR, value.uuid());
        if (value.name() != null) {
            tag.putString(NBT_CREATOR_NAME, value.name());
        }
    }

    /** 读存档：**没有键 ⇒ {@link #NONE}**（老存档读出来就是"未登记"，不猜、不补）。 */
    public static Creator read(CompoundTag tag) {
        if (tag == null || !tag.hasUUID(NBT_CREATOR)) {
            return NONE;
        }
        String name = tag.contains(NBT_CREATOR_NAME) ? tag.getString(NBT_CREATOR_NAME) : null;
        return new Creator(tag.getUUID(NBT_CREATOR), name);
    }
}

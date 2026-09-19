package com.dddgn.alice.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * **归位点**（`D-338` 附注四①，2026-09-19 用户定案）：**每 bot 一个**、由**玩家用命令**设定，
 * 是返程优先级链的**最前项**（**归位点 > 安全区 > 保护区**）—— 有归位点（同维度）时**跳过区几何**
 * （用户原话："如果玩家设定了归位点，就跳过这个策略"）。
 *
 * <p><b>为什么需要它</b>：区几何只能给"**区域级**的到家位置"（安全区内部 / 保护区内部），
 * 小基地（1×1~3×2 区块）没有内部区块 ⇒ 退化成"进区即到"、可能停在贴边的地方。
 * 用户 2026-09-19 的裁定是"**鼓励玩家自己设定归位点**，而不是优化没必要的逻辑" ⇒
 * **精确落点的正解就是本类**，不要再回头给内部区块加几何。
 *
 * <p><b>形状</b>：`Map<botUuid → (dimension, pos, radius)>`。⚠️ **维度必须记**：归位点是"世界里某个点"，
 * 而返程只在**当前维度**内成立（跨维度返程不做）。
 *
 * <p><b>半径</b>（默认 {@link #DEFAULT_RADIUS}）：到达判据 = **XZ 距离 ≤ 半径**，末段在半径内找**可站格**
 * —— 精确那一格可能被占/不可站（"站得进去"是执行层的事，判据层只要求"到附近"）。
 *
 * <p>⚠️ 本类**只存坐标**：不读方块、不加载区块、不授权（与 {@link SafeZoneData} 同一条纪律）。
 */
public final class ReturnPointData extends SavedData {

    public static final String DATA_KEY = "alice_return_points";

    /** 默认半径（格）：容错用（精确那一格可能被占/在水里/在半砖上）。 */
    public static final int DEFAULT_RADIUS = 3;

    /** 半径上限（命令不暴露；防呆：半径大到把整个基地当"到了"就失去意义）。 */
    public static final int MAX_RADIUS = 64;

    /** 持久化格式版本。 */
    private static final int FORMAT_VERSION = 1;

    /**
     * 一个归位点。
     *
     * @param dimension 设定时所在的维度（返程只在同维度生效）
     * @param pos       脚位格（设定者**站位**那一格）
     * @param radius    到达半径（格，XZ）
     */
    public record Point(ResourceLocation dimension, BlockPos pos, int radius) {
        public String describe() {
            return dimension + "@" + pos.toShortString() + " r=" + radius;
        }
    }

    private final Map<UUID, Point> points = new LinkedHashMap<>();

    public static ReturnPointData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(ReturnPointData::load, ReturnPointData::new, DATA_KEY);
    }

    /** 从 NBT 读入（SavedData 工厂入口；夹具也用它做**存/读往返**契约测试）。 */
    public static ReturnPointData load(CompoundTag root) {
        ReturnPointData data = new ReturnPointData();
        for (Tag entry : root.getList("points", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) entry;
            UUID bot = tag.hasUUID("bot") ? tag.getUUID("bot") : null;
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
            if (bot == null || dimension == null) {
                continue;   // 坏条目**丢掉**（读不出主人的归位点没有意义）；不静默改写成别人的
            }
            int radius = tag.contains("radius", Tag.TAG_INT)
                    ? clampRadius(tag.getInt("radius")) : DEFAULT_RADIUS;
            data.points.put(bot, new Point(dimension, new BlockPos(
                    tag.getInt("x"), tag.getInt("y"), tag.getInt("z")), radius));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("version", FORMAT_VERSION);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Point> entry : points.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("bot", entry.getKey());
            tag.putString("dimension", entry.getValue().dimension().toString());
            tag.putInt("x", entry.getValue().pos().getX());
            tag.putInt("y", entry.getValue().pos().getY());
            tag.putInt("z", entry.getValue().pos().getZ());
            tag.putInt("radius", entry.getValue().radius());
            list.add(tag);
        }
        root.put("points", list);
        return root;
    }

    // ==================== 读写 ====================

    /** 该 bot 的归位点；没设定过 ⇒ `null`。 */
    public Point get(UUID bot) {
        return bot == null ? null : points.get(bot);
    }

    /** 设定/覆盖归位点（返回是否真的改了内容 —— 同值重复设定算未改）。 */
    public boolean set(UUID bot, ResourceLocation dimension, BlockPos pos, int radius) {
        Point next = new Point(dimension, pos.immutable(), clampRadius(radius));
        Point current = points.get(bot);
        if (next.equals(current)) {
            return false;
        }
        points.put(bot, next);
        setDirty();
        return true;
    }

    /** 取消该 bot 的归位点；没设定过 ⇒ false。 */
    public boolean clear(UUID bot) {
        if (bot == null || points.remove(bot) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /** 已设定的归位点数量（命令/夹具读）。 */
    public int count() {
        return points.size();
    }

    private static int clampRadius(int radius) {
        return Math.max(0, Math.min(radius, MAX_RADIUS));
    }
}

package com.dddgn.alice.bot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 假人世界存档:把 bot 的概要状态(UUID/名字/位置/朝向/游戏模式)存入主世界
 * {@code level.dat} 对应的 SavedData,服务器重启后自动恢复。
 * <p>
 * 语义:存档里「有记录」= 当前存在一个 bot;spawn 时写、remove/死亡时清。
 * 只存概要(不存背包/经验),bot 是测试假人,恢复时重建即可。</p>
 */
public class BotWorldData extends SavedData {

    public static final String DATA_KEY = "alice_bot";

    private CompoundTag botTag;

    /** 取主世界存档(不存在则新建)。 */
    public static BotWorldData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage()
                .computeIfAbsent(BotWorldData::load, BotWorldData::new, DATA_KEY);
    }

    private static BotWorldData load(CompoundTag tag) {
        BotWorldData data = new BotWorldData();
        if (tag.contains("bot")) {
            data.botTag = tag.getCompound("bot");
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (botTag != null) {
            tag.put("bot", botTag);
        }
        return tag;
    }

    public boolean hasBot() {
        return botTag != null;
    }

    public CompoundTag botTag() {
        return botTag;
    }

    public void setBot(CompoundTag tag) {
        this.botTag = tag;
        setDirty();
    }

    public void clearBot() {
        this.botTag = null;
        setDirty();
    }
}

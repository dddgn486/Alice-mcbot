package com.dddgn.alice.task.lumber;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 树木类型枚举。
 * <p>
 * 定义了 Minecraft 中各种树的类型，以及对应的树苗物品。
 * 用于识别树的种类和自动补种。
 */
public enum TreeType {
    OAK("橡树", Items.OAK_SAPLING),
    BIRCH("白桦", Items.BIRCH_SAPLING),
    SPRUCE("云杉", Items.SPRUCE_SAPLING),
    JUNGLE("丛林木", Items.JUNGLE_SAPLING),
    ACACIA("金合欢", Items.ACACIA_SAPLING),
    DARK_OAK("深色橡木", Items.DARK_OAK_SAPLING),
    CHERRY("樱花木", Items.CHERRY_SAPLING),
    MANGROVE("红树", Items.MANGROVE_PROPAGULE),
    UNKNOWN("未知", Items.AIR);
    
    private final String displayName;
    private final Item sapling;
    
    TreeType(String displayName, Item sapling) {
        this.displayName = displayName;
        this.sapling = sapling;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public Item getSapling() {
        return sapling;
    }
}

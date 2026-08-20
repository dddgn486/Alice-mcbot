package com.dddgn.alice.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Alice 的破坏保护规则，保存在主世界 SavedData 中。
 * 区域按维度隔离，使用水平圆形半径并覆盖该维度所有高度；方块规则是全世界通用的 ID/标签黑名单。
 */
public final class SafeZoneData extends SavedData {

    public static final String DATA_KEY = "alice_safe_zones";

    private final List<Area> areas = new ArrayList<>();
    private final Set<ResourceLocation> protectedBlocks = new LinkedHashSet<>();
    private final Set<ResourceLocation> protectedTags = new LinkedHashSet<>();

    public static SafeZoneData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(SafeZoneData::load, SafeZoneData::new, DATA_KEY);
    }

    private static SafeZoneData load(CompoundTag root) {
        SafeZoneData data = new SafeZoneData();
        for (Tag entry : root.getList("areas", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) entry;
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
            if (dimension != null && tag.contains("radius", Tag.TAG_INT)) {
                data.areas.add(new Area(dimension, new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                        tag.getInt("radius")));
            }
        }
        readIds(root.getList("blocks", Tag.TAG_STRING), data.protectedBlocks);
        readIds(root.getList("tags", Tag.TAG_STRING), data.protectedTags);
        return data;
    }

    private static void readIds(ListTag source, Set<ResourceLocation> target) {
        for (Tag value : source) {
            ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
            if (id != null) {
                target.add(id);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag areaTags = new ListTag();
        for (Area area : areas) {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", area.dimension.toString());
            tag.putInt("x", area.center.getX());
            tag.putInt("y", area.center.getY());
            tag.putInt("z", area.center.getZ());
            tag.putInt("radius", area.radius);
            areaTags.add(tag);
        }
        root.put("areas", areaTags);
        root.put("blocks", writeIds(protectedBlocks));
        root.put("tags", writeIds(protectedTags));
        return root;
    }

    private static ListTag writeIds(Set<ResourceLocation> ids) {
        ListTag result = new ListTag();
        for (ResourceLocation id : ids) {
            result.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        return result;
    }

    public void addArea(ServerLevel level, BlockPos center, int radius) {
        areas.add(new Area(level.dimension().location(), center.immutable(), radius));
        setDirty();
    }

    /** Remove areas in this dimension whose center is at the supplied position. */
    public int removeAreasAt(ServerLevel level, BlockPos center) {
        int before = areas.size();
        areas.removeIf(area -> area.dimension.equals(level.dimension().location()) && area.center.equals(center));
        if (areas.size() != before) {
            setDirty();
        }
        return before - areas.size();
    }

    public boolean addBlock(ResourceLocation id) {
        boolean changed = protectedBlocks.add(id);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean removeBlock(ResourceLocation id) {
        boolean changed = protectedBlocks.remove(id);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean addTag(ResourceLocation id) {
        boolean changed = protectedTags.add(id);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean removeTag(ResourceLocation id) {
        boolean changed = protectedTags.remove(id);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /** Returns a stable failure reason suffix, or null when the block may be broken. */
    public String protectionReason(ServerLevel level, BlockPos pos) {
        for (Area area : areas) {
            if (area.dimension.equals(level.dimension().location()) && area.contains(pos)) {
                return "protected_area";
            }
        }
        BlockState state = level.getBlockState(pos);
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockId != null && protectedBlocks.contains(blockId)) {
            return "protected_block";
        }
        for (ResourceLocation tagId : protectedTags) {
            if (state.is(TagKey.create(Registries.BLOCK, tagId))) {
                return "protected_tag";
            }
        }
        return null;
    }

    /** 两个位置是否同时属于同一个当前维度保护区。仅用于保守移动边界，不授权破坏。 */
    public boolean sharesArea(ServerLevel level, BlockPos first, BlockPos second) {
        for (Area area : areas) {
            if (area.dimension.equals(level.dimension().location())
                    && area.contains(first) && area.contains(second)) {
                return true;
            }
        }
        return false;
    }

    public String summary() {
        return "areas=" + areas.size() + " blocks=" + protectedBlocks.size() + " tags=" + protectedTags.size();
    }

    private record Area(ResourceLocation dimension, BlockPos center, int radius) {
        private boolean contains(BlockPos pos) {
            long dx = (long) pos.getX() - center.getX();
            long dz = (long) pos.getZ() - center.getZ();
            return dx * dx + dz * dz <= (long) radius * radius;
        }
    }
}

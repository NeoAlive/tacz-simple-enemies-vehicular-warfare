package com.neoalive.tacz_sewv.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One infantry spawn row on a spawn_probe: registry id, quantity, and fire chance (%).
 * Chance is persisted for a future consumer — unused by the editor itself.
 */
public final class SpawnProbeInfantryEntry {

    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 64;
    public static final int MIN_CHANCE = 0;
    public static final int MAX_CHANCE = 100;
    public static final int DEFAULT_CHANCE = 100;

    private final String id;
    private final int count;
    private final int chance;

    public SpawnProbeInfantryEntry(String id, int count, int chance) {
        this.id = id == null ? "" : id;
        this.count = clampCount(count);
        this.chance = clampChance(chance);
    }

    public String id() {
        return id;
    }

    public int count() {
        return count;
    }

    public int chance() {
        return chance;
    }

    public SpawnProbeInfantryEntry withCount(int count) {
        return new SpawnProbeInfantryEntry(this.id, count, this.chance);
    }

    public SpawnProbeInfantryEntry withChance(int chance) {
        return new SpawnProbeInfantryEntry(this.id, this.count, chance);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putInt("Count", count);
        tag.putInt("Chance", chance);
        return tag;
    }

    public static SpawnProbeInfantryEntry load(CompoundTag tag) {
        String id = tag.getString("Id");
        if (id.isEmpty()) return null;
        int count = tag.contains("Count") ? tag.getInt("Count") : MIN_COUNT;
        int chance = tag.contains("Chance") ? tag.getInt("Chance") : DEFAULT_CHANCE;
        return new SpawnProbeInfantryEntry(id, count, chance);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeVarInt(count);
        buf.writeVarInt(chance);
    }

    public static SpawnProbeInfantryEntry read(FriendlyByteBuf buf) {
        return new SpawnProbeInfantryEntry(buf.readUtf(256), buf.readVarInt(), buf.readVarInt());
    }

    public static int clampCount(int count) {
        return Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
    }

    public static int clampChance(int chance) {
        return Math.max(MIN_CHANCE, Math.min(MAX_CHANCE, chance));
    }
}

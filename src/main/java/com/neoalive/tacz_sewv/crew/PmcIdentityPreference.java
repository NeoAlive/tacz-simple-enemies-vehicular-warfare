package com.neoalive.tacz_sewv.crew;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-player PMC branding: company name and logo selection for owned units and captured hulls.
 *
 * <p>Separate from {@link NamePreference} so the name-pool category and PMC identity can be
 * edited independently in the TDT Identity tab.
 */
public final class PmcIdentityPreference extends SavedData {

    public static final String DEFAULT_POOL = "pmc_default";
    public static final String DEFAULT_LOGO = "pmc_1";

    public record PmcIdentity(String companyName, String logoPool, String logoId) {
        public static PmcIdentity defaults() {
            return new PmcIdentity("", DEFAULT_POOL, DEFAULT_LOGO);
        }
    }

    private static final String DATA_NAME = "tacz_sewv_pmc_identity";

    private final Map<UUID, PmcIdentity> identities = new HashMap<>();

    public PmcIdentityPreference() {
    }

    public static PmcIdentityPreference load(CompoundTag nbt) {
        PmcIdentityPreference data = new PmcIdentityPreference();
        if (nbt.contains("identities", Tag.TAG_LIST)) {
            ListTag list = nbt.getList("identities", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                data.identities.put(
                        tag.getUUID("uuid"),
                        new PmcIdentity(
                                tag.getString("company"),
                                tag.getString("pool"),
                                tag.getString("logo")));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PmcIdentity> entry : identities.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", entry.getKey());
            tag.putString("company", entry.getValue().companyName());
            tag.putString("pool", entry.getValue().logoPool());
            tag.putString("logo", entry.getValue().logoId());
            list.add(tag);
        }
        nbt.put("identities", list);
        return nbt;
    }

    private static PmcIdentityPreference store(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                return overworld.getDataStorage().computeIfAbsent(
                        PmcIdentityPreference::load, PmcIdentityPreference::new, DATA_NAME);
            }
        }
        return new PmcIdentityPreference();
    }

    public static void set(Player player, PmcIdentity identity) {
        PmcIdentityPreference data = store(player.level());
        data.identities.put(player.getUUID(), identity);
        data.setDirty();
    }

    public static PmcIdentity get(Player player) {
        return store(player.level()).identities.getOrDefault(player.getUUID(), PmcIdentity.defaults());
    }
}

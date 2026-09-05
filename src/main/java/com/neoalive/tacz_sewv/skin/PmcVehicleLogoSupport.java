package com.neoalive.tacz_sewv.skin;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.crew.LogoPoolIndex;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketVehicleDogTag;

/**
 * Stamps faction logos onto SBW dogTag bones. PMC uses the owning player's identity; RU/US pick
 * randomly from {@code ru_default}/{@code us_default} when live crew ownership changes.
 */
public final class PmcVehicleLogoSupport {

    public static final String TAG_POOL = "sewv:pmc_logo_pool";
    public static final String TAG_LOGO = "sewv:pmc_logo_id";
    public static final String TAG_FACTION = "sewv:logo_faction";

    public static final String RU_DEFAULT_POOL = "ru_default";
    public static final String US_DEFAULT_POOL = "us_default";

    private PmcVehicleLogoSupport() {
    }

    /**
     * Re-stamp from live unanimous crew ownership. No-op when empty/mixed/player-aboard or when
     * the stamped faction already matches (keeps the RU/US roll sticky across remounts).
     */
    public static void applyFromOwnership(VehicleEntity hull) {
        CrewFacts.Faction faction = CrewFacts.factionOf(hull);
        if (faction == null) return;
        applyForFaction(hull, faction, CrewFacts.pmcOwner(hull));
    }

    /**
     * Spawn-time stamp: passengers may not be aboard yet, so the spawn faction is passed in.
     * RU/US always re-roll; PMC uses {@code ownerUuid} when present.
     */
    public static void applySpawnFaction(VehicleEntity hull, CrewFacts.Faction faction,
                                         @Nullable UUID pmcOwner) {
        if (faction == null) return;
        applyForFaction(hull, faction, pmcOwner);
    }

    public static void applyIfPmcCaptured(VehicleEntity hull, @Nullable UUID ownerUuid) {
        if (ownerUuid == null) return;
        if (VehicleSkinSupport.get(hull) != CrewFacts.Faction.PMC) return;
        if (!(hull.level() instanceof ServerLevel level)) return;

        PmcIdentityPreference.PmcIdentity identity = resolveIdentity(level, ownerUuid);
        stamp(hull, CrewFacts.Faction.PMC, identity.logoPool(), identity.logoId());
    }

    /** Re-stamp every loaded PMC-painted hull owned by this player. */
    public static void restampOwned(ServerPlayer owner) {
        PmcIdentityPreference.PmcIdentity identity = PmcIdentityPreference.get(owner);
        for (ServerLevel level : owner.server.getAllLevels()) {
            AABB box = new AABB(-3.0E7, level.getMinBuildHeight(), -3.0E7, 3.0E7, level.getMaxBuildHeight(), 3.0E7);
            for (VehicleEntity hull : level.getEntitiesOfClass(VehicleEntity.class, box, e -> true)) {
                if (VehicleSkinSupport.get(hull) != CrewFacts.Faction.PMC) continue;
                UUID pmcOwner = CrewFacts.pmcOwner(hull);
                if (!owner.getUUID().equals(pmcOwner)) continue;
                stamp(hull, CrewFacts.Faction.PMC, identity.logoPool(), identity.logoId());
            }
        }
    }

    public static void syncTo(ServerPlayer player, VehicleEntity hull) {
        if (!hull.getPersistentData().contains(TAG_POOL)) return;
        List<List<Short>> grid = readStampedGrid(hull);
        if (grid == null) return;
        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PacketVehicleDogTag(hull.getId(), grid));
    }

    private static void applyForFaction(VehicleEntity hull, CrewFacts.Faction faction,
                                        @Nullable UUID pmcOwner) {
        if (!(hull.level() instanceof ServerLevel level)) return;

        CompoundTag data = hull.getPersistentData();
        String stamped = data.contains(TAG_FACTION) ? data.getString(TAG_FACTION) : "";
        String want = faction.name().toLowerCase(Locale.ROOT);
        if (want.equals(stamped) && data.contains(TAG_POOL) && data.contains(TAG_LOGO)) {
            return;
        }

        switch (faction) {
            case PMC -> {
                if (pmcOwner == null) return;
                PmcIdentityPreference.PmcIdentity identity = resolveIdentity(level, pmcOwner);
                stamp(hull, CrewFacts.Faction.PMC, identity.logoPool(), identity.logoId());
            }
            case RU -> pickAndStamp(hull, CrewFacts.Faction.RU, RU_DEFAULT_POOL);
            case US -> pickAndStamp(hull, CrewFacts.Faction.US, US_DEFAULT_POOL);
        }
    }

    private static void pickAndStamp(VehicleEntity hull, CrewFacts.Faction faction, String poolId) {
        List<String> icons = LogoPoolIndex.iconsIn(poolId);
        if (icons.isEmpty()) return;
        String iconId = icons.get(hull.getRandom().nextInt(icons.size()));
        stamp(hull, faction, poolId, iconId);
    }

    private static void stamp(VehicleEntity hull, CrewFacts.Faction faction, String poolId,
                              String iconId) {
        if (!LogoPoolIndex.isValidIcon(poolId, iconId)) return;

        List<List<Short>> grid = PmcLogoEncoder.encode(poolId, iconId);
        if (grid == null || grid.isEmpty() || isBlank(grid)) return;

        hull.setDogTagIcon(grid);
        CompoundTag data = hull.getPersistentData();
        data.putString(TAG_POOL, poolId);
        data.putString(TAG_LOGO, iconId);
        data.putString(TAG_FACTION, faction.name().toLowerCase(Locale.ROOT));
        sync(hull, grid);
    }

    @Nullable
    private static List<List<Short>> readStampedGrid(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        if (!data.contains(TAG_POOL) || !data.contains(TAG_LOGO)) return null;
        return PmcLogoEncoder.encode(data.getString(TAG_POOL), data.getString(TAG_LOGO));
    }

    private static void sync(VehicleEntity hull, List<List<Short>> grid) {
        if (!(hull.level() instanceof ServerLevel)) return;
        NetworkHandler.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> hull),
                new PacketVehicleDogTag(hull.getId(), grid));
    }

    private static boolean isBlank(List<List<Short>> grid) {
        for (List<Short> col : grid) {
            for (Short s : col) {
                if (s != null && s != -1) return false;
            }
        }
        return true;
    }

    private static PmcIdentityPreference.PmcIdentity resolveIdentity(ServerLevel level, UUID ownerUuid) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(ownerUuid);
        if (player != null) {
            return PmcIdentityPreference.get(player);
        }
        return PmcIdentityPreference.PmcIdentity.defaults();
    }
}

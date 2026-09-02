package com.neoalive.tacz_sewv.skin;

import java.util.List;
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
 * Stamps the owning player's PMC logo onto SBW dogTag bones when a hull is painted PMC faction.
 */
public final class PmcVehicleLogoSupport {

    public static final String TAG_POOL = "sewv:pmc_logo_pool";
    public static final String TAG_LOGO = "sewv:pmc_logo_id";

    private PmcVehicleLogoSupport() {
    }

    public static void applyIfPmcCaptured(VehicleEntity hull, @Nullable UUID ownerUuid) {
        if (ownerUuid == null) return;
        if (VehicleSkinSupport.get(hull) != CrewFacts.Faction.PMC) return;
        if (!(hull.level() instanceof ServerLevel level)) return;

        PmcIdentityPreference.PmcIdentity identity = resolveIdentity(level, ownerUuid);
        stamp(hull, identity);
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
                stamp(hull, identity);
            }
        }
    }

    public static void syncTo(ServerPlayer player, VehicleEntity hull) {
        if (VehicleSkinSupport.get(hull) != CrewFacts.Faction.PMC) return;
        if (!hull.getPersistentData().contains(TAG_POOL)) return;
        List<List<Short>> grid = readStampedGrid(hull);
        if (grid == null) return;
        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PacketVehicleDogTag(hull.getId(), grid));
    }

    private static void stamp(VehicleEntity hull, PmcIdentityPreference.PmcIdentity identity) {
        if (!LogoPoolIndex.isValidIcon(identity.logoPool(), identity.logoId())) return;

        List<List<Short>> grid = PmcLogoEncoder.encode(identity.logoPool(), identity.logoId());
        if (grid == null || grid.isEmpty() || isBlank(grid)) return;

        hull.setDogTagIcon(grid);
        CompoundTag data = hull.getPersistentData();
        data.putString(TAG_POOL, identity.logoPool());
        data.putString(TAG_LOGO, identity.logoId());
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

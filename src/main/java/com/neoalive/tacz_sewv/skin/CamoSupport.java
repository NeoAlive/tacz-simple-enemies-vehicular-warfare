package com.neoalive.tacz_sewv.skin;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.PacketDistributor;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketEntityCamo;

/**
 * Biome camo: one number N per unit and per hull ({@code sewv:camo}), decided server-side from
 * where it spawned, so helmet, vest, uniform and hull all resolve their {@code _N} art.
 * Clients fall back to the old random pick for any piece with no {@code _N} file.
 *
 * <p>The choice is a pure function of (world seed, ~4x4-chunk region, biome, faction) — a crew
 * spawned beside its tank lands on the same N without anything passing it along.
 * {@code -1} is stored as "decided: no camo" so an unmapped biome is not re-asked every load.
 */
public final class CamoSupport {

    public static final String TAG = "sewv:camo";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> WARNED_BIOMES = ConcurrentHashMap.newKeySet();

    private CamoSupport() {
    }

    private static boolean enabled() {
        try {
            return SewvConfig.CAMO_BIOME_SELECTION.get();
        } catch (Throwable t) {
            return false; // config not baked yet
        }
    }

    /** Camo for {@code faction} at {@code pos}, or -1. */
    public static int forPos(ServerLevel level, BlockPos pos, CrewFacts.Faction faction) {
        String biome = level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("");
        // ponytail: region = 64-block cell; a squad straddling a cell edge can split camo.
        long seed = level.getSeed() ^ ((long) (pos.getX() >> 6) * 0x9E3779B97F4A7C15L)
                ^ ((long) (pos.getZ() >> 6) * 0xC2B2AE3D27D4EB4FL);
        int camo = CamoTable.pick(faction.name().toLowerCase(Locale.ROOT), biome, seed);
        if (camo < 0 && WARNED_BIOMES.add(biome)) {
            LOGGER.warn("[sewv-camo] biome {} has no camo mapping — units there keep the random pick", biome);
        }
        return camo;
    }

    /** Unit join hook: tag once, from where it stands. Also retrofits units saved before camo. */
    public static void issue(AbstractUnit unit) {
        if (!enabled() || unit.getPersistentData().contains(TAG)) return;
        try {
            CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);
            if (faction == null || !(unit.level() instanceof ServerLevel level)) return;
            unit.getPersistentData().putInt(TAG, forPos(level, unit.blockPosition(), faction));
        } catch (Throwable t) {
            LOGGER.warn("[sewv-camo] could not pick camo for {}: {}", unit, t.toString());
        }
    }

    /** Hull join hook: a hull painted before camo existed gets its camo from where it stands. */
    public static void retrofitHull(VehicleEntity hull) {
        if (!enabled() || hull.getPersistentData().contains(TAG)) return;
        CrewFacts.Faction paint = VehicleSkinSupport.get(hull);
        if (paint != null) stampHull(hull, paint);
    }

    /** Spawn paint: the hull's camo from its own position. */
    public static void stampHull(VehicleEntity hull, CrewFacts.Faction faction) {
        if (!enabled()) return;
        try {
            if (hull.level() instanceof ServerLevel level) {
                setHull(hull, forPos(level, hull.blockPosition(), faction));
            }
        } catch (Throwable t) {
            LOGGER.warn("[sewv-camo] could not pick camo for {}: {}", hull, t.toString());
        }
    }

    /** Field capture: an untagged hull takes the camo of the unit that painted it. */
    public static void inheritHull(VehicleEntity hull, Entity rider) {
        if (!enabled() || hull.getPersistentData().contains(TAG)) return;
        CompoundTag data = rider.getPersistentData();
        if (data.contains(TAG)) setHull(hull, data.getInt(TAG));
    }

    private static void setHull(VehicleEntity hull, int camo) {
        hull.getPersistentData().putInt(TAG, camo);
        NetworkHandler.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> hull),
                new PacketEntityCamo(hull.getId(), camo));
    }

    /** Start-tracking sync. Always sends (−1 too) so a reused entity id never keeps a stale camo. */
    public static void syncTo(ServerPlayer player, Entity entity) {
        if (!enabled()) return;
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketEntityCamo(entity.getId(), get(entity)));
    }

    public static int get(@Nullable Entity entity) {
        if (entity == null) return -1;
        CompoundTag data = entity.getPersistentData();
        return data.contains(TAG) ? data.getInt(TAG) : -1;
    }
}

package com.neoalive.tacz_sewv.fob;

import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.NotNull;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

public final class FobSupport {

    public static final String TAG_CMD = "sewv:fob_cmd";
    public static final String TAG_ROUTE = "sewv:fob_route";

    private FobSupport() {}

    public static int masterSize() {
        return Math.max(1, SewvConfig.FOB_MASTER_SIZE.get());
    }

    public static int stockpileSize() {
        return Math.max(1, SewvConfig.FOB_STOCKPILE_SIZE.get());
    }

    public static int parkingSize() {
        return Math.max(1, SewvConfig.FOB_PARKING_SIZE.get());
    }

    public static AABB horizontalAabb(BlockPos center, int size, Level level) {
        double half = size / 2.0;
        double cx = center.getX() + 0.5;
        double cz = center.getZ() + 0.5;
        return new AABB(
                cx - half, level.getMinBuildHeight(), cz - half,
                cx + half, level.getMaxBuildHeight(), cz + half);
    }

    public static void refreshCachedAabbs(FobInstance fob, Level level) {
        fob.cachedMasterAabb = horizontalAabb(fob.commandPos, masterSize(), level);
        fob.cachedBufferAabb = fob.cachedMasterAabb;
        fob.cachedStockpileAabb = fob.stockpilePos != null
                ? horizontalAabb(fob.stockpilePos, stockpileSize(), level) : null;
        fob.cachedParkingAabb = fob.parkingPos != null
                ? horizontalAabb(fob.parkingPos, parkingSize(), level) : null;
    }

    @Nullable
    public static BlockPos stampPos(Entity entity) {
        if (!entity.getPersistentData().contains(TAG_CMD)) return null;
        return BlockPos.of(entity.getPersistentData().getLong(TAG_CMD));
    }

    public static void stamp(Entity entity, BlockPos commandPos) {
        entity.getPersistentData().putLong(TAG_CMD, commandPos.asLong());
    }

    public static void clearStamp(Entity entity) {
        entity.getPersistentData().remove(TAG_CMD);
    }

    public static boolean isStamped(Entity entity) {
        return entity.getPersistentData().contains(TAG_CMD);
    }

    public static void markRoutePending(Entity entity, BlockPos commandPos) {
        entity.getPersistentData().putLong(TAG_ROUTE, commandPos.asLong());
    }

    public static void clearRoutePending(Entity entity) {
        entity.getPersistentData().remove(TAG_ROUTE);
    }

    public static boolean hasRoutePending(Entity entity) {
        return entity.getPersistentData().contains(TAG_ROUTE);
    }

    @Nullable
    public static BlockPos routeCommandPos(Entity entity) {
        if (!entity.getPersistentData().contains(TAG_ROUTE)) return null;
        return BlockPos.of(entity.getPersistentData().getLong(TAG_ROUTE));
    }

    public static boolean withinMasterAabb(FobInstance fob, Entity entity, Level level) {
        AABB box = fob.cachedMasterAabb;
        if (box == null) {
            refreshCachedAabbs(fob, level);
            box = fob.cachedMasterAabb;
        }
        return box != null && box.contains(entity.getX(), entity.getY(), entity.getZ());
    }

    @Nullable
    public static FobInstance fobForEntity(Entity entity, Level level) {
        BlockPos cmd = stampPos(entity);
        if (cmd == null || !(level instanceof ServerLevel server)) return null;
        return FobManager.get(server).getFob(cmd);
    }

    public static boolean blocksOrders(PmcUnitEntity pmc) {
        FobInstance fob = fobForEntity(pmc, pmc.level());
        return fob != null && fob.fobCommandActive && isStamped(pmc);
    }

    @Nullable
    public static BlockPos randomPatrolPos(FobInstance fob, Level level, long salt) {
        AABB box = fob.cachedMasterAabb;
        if (box == null) {
            refreshCachedAabbs(fob, level);
            box = fob.cachedMasterAabb;
        }
        if (box == null) return null;
        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ);
        int spanX = Math.max(1, maxX - minX);
        int spanZ = Math.max(1, maxZ - minZ);
        int x = minX + (int) (Math.floorMod(salt, spanX));
        int z = minZ + (int) (Math.floorMod(salt * 31L + 7L, spanZ));
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    @Nullable
    public static AABB parkingPad(FobInstance fob, Level level) {
        if (fob.parkingPos == null) return null;
        if (fob.cachedParkingAabb == null) {
            refreshCachedAabbs(fob, level);
        }
        return fob.cachedParkingAabb;
    }

    /** True when {@code entity} is inside the parking pad (not the center block). */
    public static boolean withinParkingPad(FobInstance fob, Entity entity, Level level) {
        AABB pad = parkingPad(fob, level);
        if (pad == null) return false;
        return pad.inflate(0.5).contains(entity.getX(), entity.getY(), entity.getZ());
    }

    /**
     * Clears stale route tags (dead FOB, unassigned unit, missing parking, etc.) and returns
     * {@code true} when the tag was removed.
     */
    public static boolean sanitizeRoutePending(PmcUnitEntity pmc, ServerLevel level) {
        if (!hasRoutePending(pmc)) return false;

        BlockPos routeCmd = routeCommandPos(pmc);
        if (routeCmd == null) {
            clearRoutePending(pmc);
            FobDebug.logEntity(pmc, "cleared route — missing command pos tag");
            return true;
        }
        if (!isStamped(pmc)) {
            clearRoutePending(pmc);
            FobDebug.logEntity(pmc, "cleared route — not stamped");
            return true;
        }

        FobInstance fob = FobManager.get(level).getFob(routeCmd);
        if (fob == null) {
            clearRoutePending(pmc);
            FobDebug.logEntity(pmc, "cleared route — FOB gone");
            return true;
        }
        if (!fob.valid || fob.parkingPos == null) {
            clearRoutePending(pmc);
            FobDebug.logEntity(pmc, "cleared route — invalid FOB or missing parking");
            return true;
        }
        if (!fob.assignedLiving.contains(pmc.getUUID())) {
            clearRoutePending(pmc);
            FobDebug.logEntity(pmc, "cleared route — unassigned");
            return true;
        }
        if (!pmc.isAlive()) {
            clearRoutePending(pmc);
            return true;
        }
        return false;
    }

    @Nullable
    public static BlockPos parkDestination(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        if (vehicle == null) return null;
        if (hasRoutePending(unit)) return null;
        if (FobResupplySupport.holdingForResupply(unit, vehicle)) return null;
        FobInstance fob = fobForEntity(vehicle, vehicle.level());
        if (fob == null || !fob.fobCommandActive || fob.scrambleActive) return null;
        if (!fob.assignedVehicles.contains(vehicle.getUUID())) return null;
        if (unit.getTarget() != null) return null;
        BlockPos park = fob.parkingPos;
        if (park == null) return null;
        return park;
    }

    @Nullable
    public static AbstractUnit ownerPerspective(ServerLevel level, UUID ownerId) {
        for (Entity e : level.getAllEntities()) {
            if (e instanceof PmcUnitEntity pmc && ownerId.equals(pmc.getOwnerUUID())) {
                return pmc;
            }
        }
        return null;
    }

    @Nullable
    public static AbstractUnit ownerPerspectiveAny(ServerLevel level, UUID ownerId) {
        AbstractUnit local = ownerPerspective(level, ownerId);
        if (local != null) return local;
        for (ServerLevel dim : level.getServer().getAllLevels()) {
            if (dim == level) continue;
            local = ownerPerspective(dim, ownerId);
            if (local != null) return local;
        }
        return null;
    }

    /** RU/US-crewed hulls are off limits — a stolen assigned vehicle reads as locked. */
    public static boolean isVehicleLocked(VehicleEntity hull) {
        CrewFacts.Faction faction = CrewFacts.factionOf(hull);
        return faction == CrewFacts.Faction.RU || faction == CrewFacts.Faction.US;
    }

    /**
     * Player ownership for a crewed PMC hull or an empty hull whose last driver was that player
     * (or their PMC). Mixed crews, RU/US occupation, and ownerless PMC garrison hulls answer false.
     */
    public static boolean vehicleOwnedBy(@NotNull VehicleEntity hull, UUID playerId) {
        if (isVehicleLocked(hull)) return false;
        UUID owner = CrewFacts.pmcOwner(hull);
        if (owner != null) return owner.equals(playerId);
        UUID empty = playerOwnerOfEmptyHull(hull);
        return playerId.equals(empty);
    }

    /**
     * Last driver UUID for an empty hull, or null when passengers remain, the last driver is not
     * a player/PMC, or the hull is enemy-crewed.
     */
    @Nullable
    public static UUID playerOwnerOfEmptyHull(VehicleEntity hull) {
        if (!hull.getPassengers().isEmpty() || isVehicleLocked(hull)) return null;
        Entity last = hull.getLastDriver();
        if (last instanceof net.minecraft.server.level.ServerPlayer sp) return sp.getUUID();
        if (last instanceof PmcUnitEntity pmc) return pmc.getOwnerUUID();
        return null;
    }

    /** Route-to-FOB keeps driving the parking waypoint through contact — fire only, no chase. */
    public static boolean holdsRouteThroughContact(AbstractUnit unit) {
        return unit instanceof PmcUnitEntity pmc && hasRoutePending(pmc);
    }

    public static boolean isHostileToOwner(AbstractUnit perspective, LivingEntity target) {
        return target.isAlive() && !VehicleTargeting.isNonHostile(perspective, target);
    }
}

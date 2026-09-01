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
    public static BlockPos parkDestination(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        if (vehicle == null) return null;
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

    public static boolean vehicleOwnedBy(@NotNull VehicleEntity hull, UUID playerId) {
        UUID owner = CrewFacts.pmcOwner(hull);
        return owner != null && owner.equals(playerId);
    }

    public static boolean isHostileToOwner(AbstractUnit perspective, LivingEntity target) {
        return target.isAlive() && !VehicleTargeting.isNonHostile(perspective, target);
    }
}

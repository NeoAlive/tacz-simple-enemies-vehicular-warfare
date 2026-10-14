package com.neoalive.tacz_sewv.compat.lv;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;
import org.ywzj.vehicle.api.YwzjVehicleAPI;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WarnOnce;

/**
 * {@code TankSpawner.spawnCrewedVehicle} for Limitless Vehicles: an LV hull from the faction's
 * LV pool, fuelled and loaded, with one unit per seat.
 *
 * <p>The crew is <b>not</b> mounted here. On its first server tick an LV hull throws every
 * passenger out ({@code AbstractVehicle.tick}, {@code tickCount == 1}); {@link LvEvents} exempts
 * our units from that, but the spawn does not lean on it where it can do without — the crew is
 * queued in {@link LvPendingCrews} and boards once that tick has passed.
 *
 * <p>Pool entries are LV <em>vehicle data</em> ids, which are not entity types (several data ids
 * build the same generic {@code tracked_vehicle} entity), so they are resolved through LV's data
 * manager, and anything that is not a wheeled / tracked / boat hull is skipped with a warning.
 */
final class LvSpawner {

    private LvSpawner() {}

    static List<? extends String> pool(TankFaction faction) {
        return switch (faction) {
            case RU -> SewvConfig.RU_LV_POOL.get();
            case US -> SewvConfig.US_LV_POOL.get();
            case PMC -> SewvConfig.PMC_LV_POOL.get();
        };
    }

    /**
     * @param water null = any crewable hull (the command); true = boats only; false = land only
     *              (an event placing on water or on ground draws from the same mixed pool)
     */
    @Nullable
    static Entity spawnCrewed(ServerLevel level, BlockPos requestedPos, TankFaction faction,
                              @Nullable UUID ownerId, @Nullable String vehicleId, @Nullable Boolean water) {
        List<? extends String> pool = pool(faction);
        if (vehicleId != null && !pool.contains(vehicleId)) return null; // only what the config allows
        List<String> candidates = new ArrayList<>();
        for (String id : vehicleId != null ? List.of(vehicleId) : pool) {
            if (resolve(id) != null) candidates.add(id);
        }
        // Random order, first that builds a hull of the wanted class.
        AbstractVehicle hull = null;
        LvHulls.Kind kind = null;
        while (!candidates.isEmpty() && hull == null) {
            String id = candidates.remove(level.random.nextInt(candidates.size()));
            AbstractVehicle built = build(level, requestedPos, id);
            LvHulls.Kind k = built == null ? null : LvHulls.kindOf(built);
            if (k == null || built.getMainCubeOBB() == null) {
                WarnOnce.warn(TaczSewv.LOGGER, "sewv-lv-not-crewable-" + id,
                        "[sewv] Limitless Vehicles pool entry '" + id + "' is not a wheeled, tracked or boat hull; skipping it.");
                continue;
            }
            if (water != null && water != (k == LvHulls.Kind.VESSEL)) continue;
            hull = built;
            kind = k;
        }
        if (hull == null) return null;

        Vector3f ext = hull.getMainCubeOBB().obb().extents();
        double half = Math.max(ext.x, ext.z);
        double height = ext.y * 2.0;
        java.util.function.Function<Vec3, AABB> boxAt =
                at -> new AABB(at.x - half, at.y, at.z - half, at.x + half, at.y + height, at.z + half);
        BlockPos pos = kind == LvHulls.Kind.VESSEL
                ? TankSpawner.findClearWaterSpawn(level, requestedPos, boxAt)
                : TankSpawner.findClearSpawn(level, requestedPos, boxAt);
        if (pos == null) return null;

        hull.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(hull);
        // A fresh hull starts dry and empty. A crew cannot refuel or rearm itself, so it leaves full;
        // after that the supply doctrine (LvSupply) decides who gets topped up.
        if (hull.energyInfo != null) hull.setEnergy(hull.energyInfo.energyCapacity);
        loadMagazines(hull);

        LvPendingCrews.enqueue(level, hull, faction, ownerId);
        return hull;
    }

    /** Called by {@link LvPendingCrews} once the hull is past its first tick: one unit per seat. */
    static void mountCrew(ServerLevel level, Entity entity, TankFaction faction, @Nullable UUID ownerId) {
        if (!(entity instanceof AbstractVehicle hull) || !hull.isAlive()) return;
        int seats = hull.seats == null ? 0 : hull.seats.size();
        int mounted = 0;
        for (int i = 0; i < seats; i++) {
            AbstractUnit crew = TankSpawner.createCrewUnit(level, faction, ownerId);
            crew.setPos(hull.getX(), hull.getY(), hull.getZ());
            crew.finalizeSpawn(level, level.getCurrentDifficultyAt(hull.blockPosition()), MobSpawnType.EVENT, null, null);
            level.addFreshEntity(crew);
            if (!crew.startRiding(hull)) {
                crew.discard();
                break;
            }
            mounted++;
        }
        // Never leave a fuelled, loaded, empty hull: that is exactly what RU/US infantry scavenge.
        if (mounted == 0) hull.discard();
    }

    private static void loadMagazines(AbstractVehicle hull) {
        for (PartUnit<?> part : hull.getPartUnits()) {
            if (!(part instanceof WeaponUnit wu)) continue;
            for (AbstractVehicleWeapon<?> weapon : wu.getIndexedWeapons()) {
                weapon.setRemainAmmo(weapon.getMaxCapacity());
            }
        }
    }

    /** A hull built from its data but not yet in the world. */
    @Nullable
    private static AbstractVehicle build(ServerLevel level, BlockPos pos, String id) {
        BaseVehicleData data = resolve(id);
        if (data == null) return null;
        float yaw = level.random.nextFloat() * 360.0F;
        AbstractVehicle hull = data.construct(level, Vec3.atBottomCenterOf(pos), 0.0F, yaw);
        if (hull != null) hull.initData(); // seats, OBBs and energy info; onAddedToWorld then skips it
        return hull;
    }

    @Nullable
    private static BaseVehicleData resolve(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        try {
            return YwzjVehicleAPI.getVehicleDataManager().getVehicleData(rl).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}

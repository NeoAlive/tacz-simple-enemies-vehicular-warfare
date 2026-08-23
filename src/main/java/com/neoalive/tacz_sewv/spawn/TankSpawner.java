package com.neoalive.tacz_sewv.spawn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.compat.AshAmmoCompat;
import com.neoalive.tacz_sewv.compat.McspAmmoCompat;
import com.neoalive.tacz_sewv.compat.NpcVehicleOverrides;
import com.neoalive.tacz_sewv.compat.VvpAmmoCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.skin.VehicleSkinSupport;
import com.neoalive.tacz_sewv.util.VehicleEngineLoot;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;

public final class TankSpawner {

    private TankSpawner() {}

    /** Which faction crews the vehicle; each has its own world-overridable vehicle pool. */
    public enum TankFaction {
        RU, US, PMC;

        /** Fixed AT / grenade emplacements (SBW TOW, VVP Kornet / AGS-30, …). */
        public List<? extends String> towPool(ServerLevel level) {
            return WorldVehiclePools.get(level).list(this, WorldVehiclePools.Category.TOW);
        }

        /** Ground armour / IFV pool for this world (COMMON config is the seed only). */
        public List<? extends String> vehiclePool(ServerLevel level) {
            return WorldVehiclePools.get(level).list(this, WorldVehiclePools.Category.GROUND);
        }

        /** The faction's ship pool — a dedicated list, not appended to {@link #vehiclePool},
         * since a ship needs a water-adjacent spawn position a ground pool pick never does. */
        public List<? extends String> shipPool(ServerLevel level) {
            return WorldVehiclePools.get(level).list(this, WorldVehiclePools.Category.SHIP);
        }

        /** The faction's plane pool — dedicated like ships; RU/US spawn airborne, PMC on the ground. */
        public List<? extends String> planePool(ServerLevel level) {
            return WorldVehiclePools.get(level).list(this, WorldVehiclePools.Category.PLANE);
        }

        /** Rotary-wing pool — land spawn + takeoff, separate from {@link #vehiclePool}. */
        public List<? extends String> heliPool(ServerLevel level) {
            return WorldVehiclePools.get(level).list(this, WorldVehiclePools.Category.HELI);
        }
    }

    /**
     * Spawns a faction vehicle picked at random from the faction's configured pool.
     * Equivalent to {@link #spawnTankWithCrew(ServerLevel, BlockPos, TankFaction, UUID, String)}
     * with no specific vehicle requested.
     */
    @Nullable
    public static VehicleEntity spawnTankWithCrew(ServerLevel level, BlockPos pos, TankFaction faction, @Nullable UUID ownerId) {
        return spawnTankWithCrew(level, pos, faction, ownerId, null);
    }

    // Resolved once per unique pool entry and cached for the server's lifetime. Pool lists can
    // change via WorldVehiclePools mid-session, but entity-type identity for a given id does not.
    private static final Map<ResourceLocation, Boolean> VEHICLE_TYPE_CACHE = new HashMap<>();

    private static boolean isVehicleEntityType(ServerLevel level, ResourceLocation rl, EntityType<?> type) {
        return VEHICLE_TYPE_CACHE.computeIfAbsent(rl, k -> type.create(level) instanceof VehicleEntity);
    }

    /** True when the faction's configured pool contains at least one loadable SW vehicle. */
    public static boolean hasSpawnableVehicle(ServerLevel level, TankFaction faction) {
        if (!spawnsEnabled(level, faction)) return false;
        return hasSpawnable(level, faction.vehiclePool(level));
    }

    /** Ground or heli pool non-empty — events that roll combat vehicles use this gate. */
    public static boolean hasSpawnableCombatVehicle(ServerLevel level, TankFaction faction) {
        if (!spawnsEnabled(level, faction)) return false;
        return hasSpawnable(level, faction.vehiclePool(level))
                || hasSpawnable(level, faction.heliPool(level));
    }

    /** The same, for the faction's separate ship pool — see {@link #spawnShipWithCrew}. */
    public static boolean hasSpawnableShip(ServerLevel level, TankFaction faction) {
        if (!spawnsEnabled(level, faction)) return false;
        return hasSpawnable(level, faction.shipPool(level));
    }

    /** The same, for the faction's separate plane pool — see {@link #spawnPlaneWithCrew}. */
    public static boolean hasSpawnablePlane(ServerLevel level, TankFaction faction) {
        if (!spawnsEnabled(level, faction)) return false;
        return hasSpawnable(level, faction.planePool(level));
    }

    /** The same, for the faction's helicopter pool — see {@link #spawnHeliWithCrew}. */
    public static boolean hasSpawnableHeli(ServerLevel level, TankFaction faction) {
        if (!spawnsEnabled(level, faction)) return false;
        return hasSpawnable(level, faction.heliPool(level));
    }

    /** Per-world spawn gate ({@code /gamerule sewvRuSpawns} / {@code sewvUsSpawns}). PMC is always on here. */
    public static boolean spawnsEnabled(ServerLevel level, TankFaction faction) {
        return switch (faction) {
            case RU -> level.getGameRules().getBoolean(ModGameRules.RU_SPAWNS);
            case US -> level.getGameRules().getBoolean(ModGameRules.US_SPAWNS);
            case PMC -> true;
        };
    }

    private static boolean hasSpawnable(ServerLevel level, List<? extends String> pool) {
        for (String id : pool) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null || !ForgeRegistries.ENTITY_TYPES.containsKey(rl)) continue;
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(rl);
            if (type != null && isVehicleEntityType(level, rl, type)) return true;
        }
        return false;
    }

    /**
     * Spawns a faction vehicle with a full crew of the matching faction: one unit per
     * seat the vehicle exposes, mounted in seat order (seat 0 becomes the driver).
     * When {@code vehicleId} is non-null it must be one of the faction's configured
     * pool entries and that exact vehicle is used; when null, one is picked at random
     * from the pool. For PMC, {@code ownerId} (when non-null) makes the crew
     * commandable by that player. Returns the spawned vehicle, or null if it couldn't
     * be spawned (no space, the requested id isn't a valid pooled SW vehicle, the pool
     * is empty, or SW isn't loaded).
     */
    @Nullable
    public static VehicleEntity spawnTankWithCrew(ServerLevel level, BlockPos requestedPos, TankFaction faction,
                                                  @Nullable UUID ownerId, @Nullable String vehicleId) {
        return spawnCrewedVehicle(level, requestedPos, faction, ownerId, vehicleId, faction.vehiclePool(level), false, true);
    }

    /**
     * Same contract as {@link #spawnTankWithCrew(ServerLevel, BlockPos, TankFaction, UUID, String)},
     * for the faction's ship pool instead — the only real difference is spawn positioning: a ship
     * is placed via {@link #findClearWaterSpawn} rather than {@link #findClearSpawn}, since it
     * must float rather than stand. Crew mounting, ammo/energy stocking and the (no-op for ships)
     * helicopter takeoff order are all identical and shared with the ground path below.
     */
    @Nullable
    public static VehicleEntity spawnShipWithCrew(ServerLevel level, BlockPos requestedPos, TankFaction faction,
                                                  @Nullable UUID ownerId, @Nullable String vehicleId) {
        return spawnCrewedVehicle(level, requestedPos, faction, ownerId, vehicleId, faction.shipPool(level), true, true);
    }

    /**
     * Same contract as {@link #spawnTankWithCrew} against the helicopter pool. Land spawn; seat-0
     * takeoff is applied inside {@code spawnCrewedVehicle} when the hull is {@code HELICOPTER}.
     */
    @Nullable
    public static VehicleEntity spawnHeliWithCrew(ServerLevel level, BlockPos requestedPos, TankFaction faction,
                                                 @Nullable UUID ownerId) {
        return spawnHeliWithCrew(level, requestedPos, faction, ownerId, null);
    }

    @Nullable
    public static VehicleEntity spawnHeliWithCrew(ServerLevel level, BlockPos requestedPos, TankFaction faction,
                                                 @Nullable UUID ownerId, @Nullable String vehicleId) {
        return spawnCrewedVehicle(level, requestedPos, faction, ownerId, vehicleId, faction.heliPool(level), false, true);
    }

    /**
     * Event/structure helper: pick GROUND or HELI uniformly among non-empty pools, then spawn.
     * Existing chance/count knobs stay unchanged — only the pool category may vary per slot.
     */
    @Nullable
    public static VehicleEntity spawnCombatVehicleWithCrew(ServerLevel level, BlockPos requestedPos,
                                                           TankFaction faction, @Nullable UUID ownerId) {
        boolean ground = hasSpawnable(level, faction.vehiclePool(level));
        boolean heli = hasSpawnable(level, faction.heliPool(level));
        if (!ground && !heli) return null;
        if (ground && heli) {
            return level.random.nextBoolean()
                    ? spawnTankWithCrew(level, requestedPos, faction, ownerId, null)
                    : spawnHeliWithCrew(level, requestedPos, faction, ownerId, null);
        }
        return ground
                ? spawnTankWithCrew(level, requestedPos, faction, ownerId, null)
                : spawnHeliWithCrew(level, requestedPos, faction, ownerId, null);
    }

    /**
     * Blocks above the surface a RU/US plane is placed at spawn. Matches DrivePlaneGoal's default
     * cruise band ({@code DEFAULT_CRUISE_ALTITUDE * 3} clamped into 90–180) so the first loiter tick
     * does not climb or dive hard to correct altitude.
     */
    private static final double PLANE_AIR_SPAWN_ALT = 105.0;

    /** Horizontal speed (blocks/tick) given to an airborne spawn so SBW's lift is non-zero before throttle spools. */
    private static final double PLANE_AIR_SPAWN_SPEED = 0.55;

    /**
     * Same contract as {@link #spawnTankWithCrew} for the faction's plane pool. RU/US planes spawn
     * already airborne (gear up, modest forward speed, flight command NONE → DrivePlaneGoal loiters):
     * they cannot be player-ordered to take off, and SBW needs airspeed to stay up. PMC planes spawn
     * on the ground with TAKEOFF like helicopters — the player can order flight themselves.
     */
    @Nullable
    public static VehicleEntity spawnPlaneWithCrew(ServerLevel level, BlockPos requestedPos, TankFaction faction,
                                                   @Nullable UUID ownerId) {
        return spawnPlaneWithCrew(level, requestedPos, faction, ownerId, null);
    }

    @Nullable
    public static VehicleEntity spawnPlaneWithCrew(ServerLevel level, BlockPos requestedPos, TankFaction faction,
                                                   @Nullable UUID ownerId, @Nullable String vehicleId) {
        if (!spawnsEnabled(level, faction)) return null;
        EntityType<?> planeType = selectVehicleType(faction.planePool(level), vehicleId, level.random);
        if (planeType == null) return null;

        boolean airborne = faction != TankFaction.PMC;
        BlockPos ground = findClearSpawn(level, requestedPos, planeType);
        if (ground == null) return null;

        double x = ground.getX() + 0.5;
        double z = ground.getZ() + 0.5;
        double y = airborne ? ground.getY() + PLANE_AIR_SPAWN_ALT : ground.getY();

        Entity planeEntity = planeType.create(level);
        if (!(planeEntity instanceof VehicleEntity plane)) return null;

        plane.setPos(x, y, z);
        if (airborne) {
            // Level flight attitude — a fresh entity's pitch can be anything; lofted or inverted
            // ruins the lift nudge below.
            plane.setXRot(0.0F);
            plane.setGearUp(true);
            float yawRad = plane.getYRot() * ((float) Math.PI / 180.0F);
            // Forward along body yaw (not look angle): look follows pitch, which we just zeroed,
            // but yaw-based is the same and clearer for "along the runway heading".
            plane.setDeltaMovement(new Vec3(-Math.sin(yawRad) * PLANE_AIR_SPAWN_SPEED, 0.0,
                    Math.cos(yawRad) * PLANE_AIR_SPAWN_SPEED));
        }
        level.addFreshEntity(plane);

        if (plane.hasEnergyStorage()) {
            plane.setEnergy(plane.getMaxEnergy());
        }
        stockAmmo(plane, faction);

        int seats = Math.max(1, plane.getMaxPassengers());
        int mounted = 0;
        for (int i = 0; i < seats; i++) {
            AbstractUnit crew = createCrewUnit(level, faction, ownerId);
            crew.setPos(x, y, z);
            crew.finalizeSpawn(level, level.getCurrentDifficultyAt(crew.blockPosition()), MobSpawnType.EVENT, null, null);
            level.addFreshEntity(crew);
            if (!crew.startRiding(plane)) {
                crew.discard();
                break;
            }
            mounted++;
        }

        if (mounted == 0) {
            plane.discard();
            return null;
        }

        // PMC: ground takeoff order (player can also re-issue it). RU/US stay at NONE so the goal
        // loiters immediately — they never receive a takeoff packet.
        if (!airborne) {
            try {
                if (plane.computed().getEngineType() == EngineType.AIRCRAFT
                        && plane.getFirstPassenger() instanceof IHelicopterPilot pilot) {
                    pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_TAKEOFF);
                }
            } catch (Exception ignored) {
                // Unreadable vehicle data — leave it on the ground rather than abort the spawn.
            }
        }

        VehicleSkinSupport.applySpawnFaction(plane, faction);
        if (faction == TankFaction.RU || faction == TankFaction.US) {
            VehicleEngineLoot.markPending(plane);
        }
        return plane;
    }

    @Nullable
    private static VehicleEntity spawnCrewedVehicle(ServerLevel level, BlockPos requestedPos, TankFaction faction,
                                                     @Nullable UUID ownerId, @Nullable String vehicleId,
                                                     List<? extends String> pool, boolean water,
                                                     boolean requireSpawnsEnabled) {
        if (requireSpawnsEnabled && !spawnsEnabled(level, faction)) return null;
        EntityType<?> tankType = selectVehicleType(pool, vehicleId, level.random);
        if (tankType == null) return null; // nothing valid configured/requested — bail safely

        BlockPos pos = water
                ? findClearWaterSpawn(level, requestedPos, tankType)
                : findClearSpawn(level, requestedPos, tankType);
        if (pos == null) return null; // no room (or no water) within snap radius — bail safely

        Entity tankEntity = tankType.create(level);
        if (!(tankEntity instanceof VehicleEntity tank)) return null; // configured id isn't an SW vehicle

        tank.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(tank);

        // Freshly-created vehicles start with 0 energy and an empty container — an AI
        // driver can't refuel/rearm itself, so fully charge it and stock its guns'
        // real ammunition so it can move and fire.
        if (tank.hasEnergyStorage()) {
            tank.setEnergy(tank.getMaxEnergy());
        }
        stockAmmo(tank, faction);

        // One unit per seat, mounted in join order: SW's VehicleEntity assigns
        // seats sequentially, so the first rider lands in seat 0 (driver) and
        // the rest man the remaining weapon/passenger stations.
        int seats = Math.max(1, tank.getMaxPassengers());
        int mounted = 0;
        for (int i = 0; i < seats; i++) {
            AbstractUnit crew = createCrewUnit(level, faction, ownerId);
            crew.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            crew.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
            level.addFreshEntity(crew);
            if (!crew.startRiding(tank)) {
                // Seat refused the rider (vehicle full despite getMaxPassengers, or
                // a mod cancelled the mount) — don't leave the unit standing around.
                crew.discard();
                break;
            }
            mounted++;
        }

        if (mounted == 0) {
            // The very first seat was refused: a fully fuelled, fully armed hull with nobody
            // aboard is completely empty by SeekAbandonedVehicleGoal's own test, which makes it
            // a scavenge magnet for any nearby RU/US infantry — the exact "bare hull near
            // infantry" trap this mod avoids everywhere else. Any later seat failing (mounted > 0)
            // already has a driver, so it isn't "completely empty" and stays safe.
            tank.discard();
            return null;
        }

        // Helicopters spawn on the ground, so order the pilot (seat 0) to take off:
        // the crew climbs straight up to cruise altitude before transiting, rather
        // than skimming terrain toward its first objective. Every crew type now
        // implements IHelicopterPilot, so this drives RU/US autonomous crews the
        // same as owned PMC ones; ground vehicles ignore it (the goal only reads
        // the command while mounted in a helicopter).
        //
        // Read from computed() (the STATIC datapack data, valid the instant the entity exists),
        // NOT getEngineInfo() — that field is lazily populated inside travel() on the hull's first
        // baseTick, one tick AFTER addFreshEntity, so it is null for every hull at this exact spot
        // in spawnTankWithCrew. Same gotcha DerelictVehicleEvent already works around; this call
        // site never got the fix, so no helicopter spawned through TankSpawner ever took off.
        try {
            EngineType engine = tank.computed().getEngineType();
            engine = NpcVehicleOverrides.applyEngineHint(entityId(tank), engine);
            if (engine == EngineType.HELICOPTER
                    && tank.getFirstPassenger() instanceof IHelicopterPilot pilot) {
                pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_TAKEOFF);
            }
        } catch (Exception ignored) {
            // Unreadable vehicle data — leave it on the ground rather than abort the spawn.
        }

        // Source-based paint: command/event crewed spawns always get the faction skin. Field
        // captures keep the chance roll in VehicleSkinEvents.onMount instead.
        VehicleSkinSupport.applySpawnFaction(tank, faction);
        if (faction == TankFaction.RU || faction == TankFaction.US) {
            VehicleEngineLoot.markPending(tank);
        }
        return tank;
    }

    /**
     * Like {@link #spawnTankWithCrew} but draws from an explicit pool list (e.g. a team_base's
     * configured entries) instead of the world faction pool.
     */
    @Nullable
    public static VehicleEntity spawnTankWithCrewFromPool(ServerLevel level, BlockPos requestedPos,
                                                          TankFaction faction, @Nullable UUID ownerId,
                                                          @Nullable String vehicleId,
                                                          List<? extends String> pool) {
        return spawnCrewedVehicle(level, requestedPos, faction, ownerId, vehicleId, pool, false, false);
    }

    /**
     * Spawns a fuelled/armed hull from {@code pool}, mounts {@code player} in seat 0, and optionally
     * fills remaining seats with PMC owned by that player. Returns the hull, or null on failure.
     */
    @Nullable
    public static VehicleEntity spawnPlayerDrivenWithOptionalCrew(ServerLevel level, BlockPos requestedPos,
                                                                   ServerPlayer player, @Nullable String vehicleId,
                                                                   List<? extends String> pool, boolean withPmcCrew) {
        EntityType<?> type = selectVehicleType(pool, vehicleId, level.random);
        if (type == null) {
            // Allow a random pick when vehicleId is null; when non-null and missing from pool, fail.
            if (vehicleId != null) return null;
            type = pickVehicleType(pool, level.random);
        }
        if (type == null) return null;

        BlockPos pos = findClearSpawn(level, requestedPos, type);
        if (pos == null) return null;

        Entity entity = type.create(level);
        if (!(entity instanceof VehicleEntity tank)) return null;

        tank.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(tank);

        if (tank.hasEnergyStorage()) {
            tank.setEnergy(tank.getMaxEnergy());
        }
        stockAmmo(tank, TankFaction.PMC);

        // Player first → seat 0 (driver). startRiding relocates the player onto the hull.
        if (!player.startRiding(tank)) {
            tank.discard();
            return null;
        }

        if (withPmcCrew) {
            int seats = Math.max(1, tank.getMaxPassengers());
            UUID ownerId = player.getUUID();
            for (int i = 1; i < seats; i++) {
                AbstractUnit crew = createCrewUnit(level, TankFaction.PMC, ownerId);
                crew.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                crew.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
                level.addFreshEntity(crew);
                if (!crew.startRiding(tank)) {
                    crew.discard();
                    break;
                }
            }
        }

        try {
            EngineType engine = tank.computed().getEngineType();
            engine = NpcVehicleOverrides.applyEngineHint(entityId(tank), engine);
            if (engine == EngineType.HELICOPTER
                    && tank.getFirstPassenger() instanceof IHelicopterPilot pilot) {
                pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_TAKEOFF);
            }
        } catch (Exception ignored) {
            // leave on the ground
        }

        VehicleSkinSupport.applySpawnFaction(tank, TankFaction.PMC);
        return tank;
    }

    /**
     * Spawns a single BARE vehicle from {@code faction}'s pool: the hull only, with no crew,
     * no ammunition and no energy. Used for PMC (player-friendly) structures — a fully crewed,
     * fuelled, loaded tank standing at a friendly camp would be free and overpowered, so the
     * player is left to capture, refuel and man it themselves. Returns the hull, or null when
     * the pool is empty/unresolvable or there is no room at {@code pos}.
     */
    @Nullable
    public static VehicleEntity spawnBareVehicle(ServerLevel level, BlockPos requestedPos, TankFaction faction) {
        if (!spawnsEnabled(level, faction)) return null;
        EntityType<?> type = selectVehicleType(faction.vehiclePool(level), null, level.random);
        if (type == null) return null;
        BlockPos pos = findClearSpawn(level, requestedPos, type);
        if (pos == null) return null;

        Entity entity = type.create(level);
        if (!(entity instanceof VehicleEntity vehicle)) return null;
        vehicle.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(vehicle);
        return vehicle; // no setEnergy, no stockAmmo, no crew — deliberately inert
    }

    /**
     * Unpacks a vehicle container onto the ground: the aircraft it was holding, restored to the
     * state it was packed in, with nobody aboard.
     *
     * <p>This is the honest counterpart to {@link #spawnPlaneWithCrew}, which manufactures a fresh
     * airframe. A container carries the machine the player already owned — its damage, its fuel,
     * its magazines and its skin all live in {@code entityTag} — so reading only the entity id off
     * it and building a new one from scratch quietly replaced the player's aircraft with a better
     * one every time they unpacked it. A container with no {@code entityTag} was never packed from
     * a live vehicle (it is a crafted or creative crate), and an empty airframe is exactly what it
     * should produce: energy and ammunition are the player's problem, the same as they are for
     * SuperbWarfare's own crate.
     *
     * <p>The stored UUID is deliberately <b>discarded</b>. It identifies the vehicle that was
     * packed, and {@code addFreshEntity} silently refuses an entity whose UUID is already in the
     * level — so two copies of one crate (a creative duplicate, an inventory-duplication bug)
     * would unpack once and then fail with no error at all.
     *
     * @param entityTag the packed vehicle's NBT, or null for a crate that stores only a type.
     */
    @Nullable
    public static VehicleEntity unpackPlane(ServerLevel level, BlockPos requestedPos, TankFaction faction,
                                            String vehicleId, @Nullable CompoundTag entityTag) {
        EntityType<?> type = selectVehicleType(faction.planePool(level), vehicleId, level.random);
        if (type == null) return null;
        BlockPos pos = findClearSpawn(level, requestedPos, type);
        if (pos == null) return null;

        Entity entity = type.create(level);
        if (!(entity instanceof VehicleEntity vehicle)) return null;
        if (entityTag != null && !entityTag.isEmpty()) {
            vehicle.load(entityTag);
            vehicle.setUUID(UUID.randomUUID());
        }
        // After the load, which restores the position and motion the vehicle was packed at.
        vehicle.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        vehicle.setDeltaMovement(Vec3.ZERO);
        level.addFreshEntity(vehicle);
        return vehicle;
    }

    /**
     * Puts a token few rounds in a hull's container — enough to be worth looting and to make the
     * vehicle briefly useful if it is recovered, nowhere near enough to fight with.
     *
     * <p>The counterpart to {@link #stockAmmo}, which fills every slot for a hull that is about to
     * go into action. This one exists so {@code DerelictVehicleEvent} can reuse the ammo
     * <em>resolution</em> — which is not obvious logic (it walks every seat, every weapon on that
     * seat, and asks each weapon's own {@code AmmoConsumer} what item it eats) and should exist
     * exactly once — without also inheriting "fill it up".
     *
     * <p>No creative-box fallback here, deliberately: a hull whose ammunition cannot be resolved
     * is left empty rather than handed an infinite supply. A derelict with a bottomless magazine
     * would be a strictly better prize than a working tank.
     */
    public static void stockTokenAmmo(VehicleEntity tank, int count) {
        if (count <= 0) return;
        if (!tank.hasContainer() || tank.getContainerSize() <= 0) return;

        String id = entityId(tank);
        if (AshAmmoCompat.isMissileSystemHull(id)) return;

        List<Item> ammo = resolveWithFallback(tank, id);
        if (ammo.isEmpty()) return;

        tank.setItem(0, new ItemStack(ammo.get(0), count));
    }

    private static final String TAG_AMMO_STOCKED = "sewv:ammo_stocked";

    /**
     * Stocks an empty hull when an RU/US unit takes the driver's seat — the path
     * {@link SeekAbandonedVehicleGoal} / player-placed tanks use, which never goes through
     * {@link #stockAmmo}. Gated on {@code factionInfiniteAmmo}, an empty container, and a
     * one-shot persistent flag so emptying a captured hull does not restock forever.
     */
    public static void maybeStockFactionBoardAmmo(VehicleEntity hull, AbstractUnit driver) {
        if (hull.level().isClientSide()) return;
        if (!(driver instanceof RUunitEntity) && !(driver instanceof USunitEntity)) return;
        if (!SewvConfig.FACTION_INFINITE_AMMO.get()) return;
        if (hull.getFirstPassenger() != driver) return;
        if (!hull.hasContainer() || hull.getContainerSize() <= 0) return;
        if (hull.getPersistentData().getBoolean(TAG_AMMO_STOCKED)) return;
        if (!containerEmpty(hull)) return;

        TankFaction faction = driver instanceof RUunitEntity ? TankFaction.RU : TankFaction.US;
        stockAmmo(hull, faction);
        hull.getPersistentData().putBoolean(TAG_AMMO_STOCKED, true);
    }

    private static boolean containerEmpty(VehicleEntity hull) {
        int size = hull.getContainerSize();
        for (int i = 0; i < size; i++) {
            if (!hull.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    /**
     * Stocks a freshly-spawned hull's container with the actual ammunition its weapons
     * consume, so an AI crew fires finite, lootable rounds instead of a bottomless
     * creative box. The container is divided evenly across the ammo types the hull uses
     * (one full stack per slot, cycled), which SBW's own AI auto-reload then draws from.
     *
     * <p>When {@code factionInfiniteAmmo} is on and the faction is RU/US, the hull gets a
     * creative ammo box instead — same unlimited supply ground and air opposition share.
     * <b>Exception:</b> MCSP and ASH hulls always get native ammo items — those packs do not
     * honour SBW's creative box for tank magazines, so a creative-only fill leaves them dry.
     *
     * <p>When no item ammo can be resolved — an all-energy hull (already charged above),
     * an infinite-ammo weapon, or unreadable modded gun data — it falls back to a creative
     * ammo box so the vehicle can still fire, unless {@code creativeAmmoFallback} is off,
     * in which case a strict survival world gets an empty container. MCSP/ASH never take
     * that creative fallback either: they use a registry-id softcompat list instead.
     *
     * <p>ASH Sapsan-style missile systems have no gun ammo and leave the container empty.
     */
    /**
     * Stocks a hull for combat (full magazine stacks). Public for emplacement / Fixed AT
     * spawn paths that do not go through {@link #spawnCrewedVehicle}.
     */
    public static void stockCombatAmmo(VehicleEntity tank, TankFaction faction) {
        stockAmmo(tank, faction);
    }

    private static void stockAmmo(VehicleEntity tank, TankFaction faction) {
        if (!tank.hasContainer() || tank.getContainerSize() <= 0) return;

        String id = entityId(tank);
        if (AshAmmoCompat.isMissileSystemHull(id)) return; // Sapsan: ballistic spawn, no magazine

        boolean addonNative = McspAmmoCompat.isMcspHull(id) || AshAmmoCompat.isAshHull(id)
                || VvpAmmoCompat.isVvpHull(id);

        if (!addonNative && faction != TankFaction.PMC && SewvConfig.FACTION_INFINITE_AMMO.get()) {
            tank.setItem(0, new ItemStack(ModItems.CREATIVE_AMMO_BOX.get()));
            return;
        }

        List<Item> ammo = resolveWithFallback(tank, id);
        if (ammo.isEmpty()) {
            if (!addonNative && SewvConfig.CREATIVE_AMMO_FALLBACK.get()) {
                tank.setItem(0, new ItemStack(ModItems.CREATIVE_AMMO_BOX.get()));
            }
            return;
        }
        int size = tank.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            Item item = ammo.get(slot % ammo.size());
            tank.setItem(slot, new ItemStack(item, item.getMaxStackSize()));
        }
    }

    /**
     * The ammo <em>resolution</em> chain shared by {@link #stockAmmo} and
     * {@link #stockTokenAmmo}: what the hull's own weapons eat first, then the addon
     * softcompat fallbacks (MCSP / ASH / VVP) for packs that ignore SBW's container ammo.
     * The slot-filling and creative-box policy around it differ per caller.
     */
    private static List<Item> resolveWithFallback(VehicleEntity tank, String id) {
        List<Item> ammo = resolveAmmo(tank);
        if (ammo.isEmpty() && McspAmmoCompat.isMcspHull(id)) {
            ammo = McspAmmoCompat.fallbackAmmo();
        }
        if (ammo.isEmpty() && AshAmmoCompat.isAshHull(id)) {
            ammo = AshAmmoCompat.fallbackAmmo();
        }
        if (ammo.isEmpty() && VvpAmmoCompat.isVvpHull(id)) {
            ammo = VvpAmmoCompat.fallbackAmmo();
        }
        return ammo;
    }

    @Nullable
    private static String entityId(VehicleEntity tank) {
        try {
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(tank.getType());
            return key == null ? null : key.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    // The distinct ammo items every weapon on the hull consumes. Reads GunData the same
    // way VehicleWeapons does; energy/infinite/empty consumers carry an empty stack and
    // contribute nothing. Defensive — unreadable modded weapon data must never abort a
    // spawn, it just leaves that slot out (and, if nothing resolves, the creative fallback).
    private static List<Item> resolveAmmo(VehicleEntity tank) {
        List<Item> ammo = new ArrayList<>();
        int seats = Math.max(1, tank.getMaxPassengers());
        for (int seat = 0; seat < seats; seat++) {
            SeatInfo info = tank.getSeat(seat);
            int weapons = info == null ? 0 : info.weapons().size();
            for (int w = 0; w < weapons; w++) {
                try {
                    GunData gun = tank.getGunData(seat, w);
                    if (gun == null) continue;
                    List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
                    if (consumers == null) continue;
                    for (AmmoConsumer c : consumers) {
                        if (c == null) continue;
                        ItemStack stack = c.stack();
                        if (stack.isEmpty()) continue;
                        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                        if (key != null && AshAmmoCompat.isBannedMunition(key.toString())) continue;
                        if (!ammo.contains(stack.getItem())) ammo.add(stack.getItem());
                    }
                } catch (Exception ignored) {
                    // exotic/modded weapon data — skip this slot, keep spawning
                }
            }
        }
        return ammo;
    }

    /** Package-visible so {@link EmplacementSpawner} crews mortars/TOWs the same way. */
    static AbstractUnit createCrewUnit(ServerLevel level, TankFaction faction, @Nullable UUID ownerId) {
        switch (faction) {
            case RU: {
                RUunitEntity unit = new RUunitEntity(ModEntities.RUUNIT.get(), level);
                unit.setRole(UnitRole.DEFAULT);
                return unit;
            }
            case US: {
                USunitEntity unit = new USunitEntity(ModEntities.USUNIT.get(), level);
                unit.setRole(UnitRole.DEFAULT);
                return unit;
            }
            default: {
                // FRIENDLY_DEFAULT mirrors SEM's plain PMC spawn egg; the owner makes
                // the crew respond to that player's SEM command menu.
                PmcUnitEntity unit = new PmcUnitEntity(ModEntities.PMCUNIT.get(), level);
                unit.setRole(UnitRole.FRIENDLY_DEFAULT);
                if (ownerId != null) unit.setOwner(ownerId);
                return unit;
            }
        }
    }

    // Resolve the vehicle to spawn: a specific pooled id when one is requested,
    // otherwise a random pick from the pool. A requested id that isn't actually in
    // the configured pool is rejected (returns null) — the command must only spawn
    // what the config allows.
    @Nullable
    private static EntityType<?> selectVehicleType(List<? extends String> pool, @Nullable String requestedId, RandomSource random) {
        if (requestedId == null) return pickVehicleType(pool, random);
        if (!pool.contains(requestedId)) return null; // not a configured pool entry
        ResourceLocation rl = ResourceLocation.tryParse(requestedId);
        if (rl == null || !ForgeRegistries.ENTITY_TYPES.containsKey(rl)) return null;
        return ForgeRegistries.ENTITY_TYPES.getValue(rl);
    }

    // Random pick among the pool entries that resolve to a real entity type.
    // containsKey() guard matters: the entity-type registry is defaulted, so a
    // bare getValue() on a typo'd id would silently return minecraft:pig.
    @Nullable
    private static EntityType<?> pickVehicleType(List<? extends String> pool, RandomSource random) {
        List<EntityType<?>> candidates = new ArrayList<>(pool.size());
        for (String id : pool) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && ForgeRegistries.ENTITY_TYPES.containsKey(rl)) {
                candidates.add(ForgeRegistries.ENTITY_TYPES.getValue(rl));
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    /** Max Chebyshev distance findClearSpawn will snap a blocked target to. */
    private static final int SNAP_RADIUS = 10;

    /**
     * Nearest surface spawn point around {@code pos} whose footprint is clear, spiralling out
     * to {@link #SNAP_RADIUS}. Spawns one block above ground so the hull settles by physics
     * instead of clipping terrain, and snaps past a blocked target (a hull dropped inside a
     * structure building, on a tree). Ring 0 (the target column) is tried first, so a clear
     * target costs one collision test. Returns a feet-Y BlockPos, or null when nothing fits.
     *
     * <p>Replaces the old full-AABB-at-feet {@code noCollision} check, which rejected any hull
     * over the slightest terrain undulation and silently dropped the spawn.
     */
    @Nullable
    public static BlockPos findClearSpawn(ServerLevel level, BlockPos pos, EntityType<?> type) {
        for (int r = 0; r <= SNAP_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring perimeter only, nearest-first
                    int x = pos.getX() + dx, z = pos.getZ() + dz;
                    int gy = groundY(level, x, z, pos.getY()) + 1; // +1 lift: hull drops onto the surface
                    var box = type.getDimensions().makeBoundingBox(x + 0.5, gy, z + 0.5);
                    if (level.noCollision(box)) return new BlockPos(x, gy, z);
                }
            }
        }
        return null;
    }

    /**
     * Water-surface counterpart to {@link #findClearSpawn}, for a hull that must float rather
     * than stand — same spiral search and the same {@code +1} lift, but a candidate column only
     * qualifies when it actually IS water at the surface, checked explicitly (the same two-level
     * fluid read {@link com.neoalive.tacz_sewv.entity.ai.navigation.GroundMobility#waterDepth} uses,
     * just asserting the opposite here). {@code groundY}'s heightmap already stops at a lake's surface rather
     * than its bed (verified: {@code MOTION_BLOCKING_NO_LEAVES}'s predicate counts any non-empty
     * fluid state), but it has no PREFERENCE for water over land — without this check, a ship
     * spawn request next to a shore is just as likely to land on the bank as on the lake.
     */
    @Nullable
    public static BlockPos findClearWaterSpawn(ServerLevel level, BlockPos pos, EntityType<?> type) {
        for (int r = 0; r <= SNAP_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = pos.getX() + dx, z = pos.getZ() + dz;
                    int rawY = groundY(level, x, z, pos.getY());
                    if (!isWaterSurface(level, x, rawY, z)) continue; // dry column — never fall back to land
                    int gy = rawY + 1;
                    var box = type.getDimensions().makeBoundingBox(x + 0.5, gy, z + 0.5);
                    if (level.noCollision(box)) return new BlockPos(x, gy, z);
                }
            }
        }
        return null;
    }

    // Checked at the raw heightmap Y and one below it — a heightmap's exact "one above the
    // counted block" convention is easy to be off by one on, and a spurious miss here only costs
    // trying the next ring position, not a crash.
    private static boolean isWaterSurface(ServerLevel level, int x, int y, int z) {
        return level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER)
                || level.getFluidState(new BlockPos(x, y - 1, z)).is(FluidTags.WATER);
    }

    public static BlockPos adjustHeight(ServerLevel level, BlockPos pos) {
        return new BlockPos(pos.getX(), groundY(level, pos.getX(), pos.getZ(), pos.getY()), pos.getZ());
    }

    // Surface Y at (x,z). Level.getHeight answers getMinBuildHeight() for an UNLOADED chunk,
    // so a probe during/right after worldgen (berezka structures far from a player) would drop
    // to bedrock — fall back to the caller's reference Y (e.g. the generator-projected anchor)
    // instead. See VehicleFormation.groundY for the same sentinel.
    private static int groundY(ServerLevel level, int x, int z, int fallbackY) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return y <= level.getMinBuildHeight() ? fallbackY : y;
    }
}

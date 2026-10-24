package com.neoalive.tacz_sewv.compat.lv;

import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullProfile;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;

/**
 * Soft-compat facade for <b>Limitless Vehicles</b> ({@code ywzj_vehicle}).
 *
 * <p>This is the <b>only</b> class shared code may call for LV, and it imports no
 * {@code org.ywzj.*} type: every method is {@code present() && LvHulls.x(...)}, and the JVM
 * resolves {@link LvHulls} — which does import LV — only when that branch first runs. With the
 * mod absent every answer is the "not an LV hull" one and no LV class is ever loaded.
 *
 * <p>Scope is ground, boats and rotorcraft: an LV hull counts as <em>crewable</em> when it is a
 * Wheeled, Tracked, Vessel or RotaryWing vehicle. Fixed-wing and fixed mounts are left to players.
 * LV vehicles share the ordinary vehicle pools (GROUND / SURFACE / ROTOR) with SBW ones.
 */
public final class LvCompat {

    public static final String MODID = "ywzj_vehicle";

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Boolean present;

    private LvCompat() {}

    public static boolean present() {
        Boolean p = present;
        if (p == null) {
            p = ModList.get() != null && ModList.get().isLoaded(MODID);
            present = p;
        }
        return p;
    }

    /** Crews may drive and gun LV hulls (installed and enabled in config). */
    public static boolean aiEnabled() {
        if (!present()) return false;
        try {
            return SewvConfig.LV_AI_ENABLED.get();
        } catch (Throwable unbaked) {
            return true;
        }
    }

    public static void reportAvailability() {
        if (present()) {
            LOGGER.info("Limitless Vehicles soft-compat active (mod id {}): crews drive ground, boat and rotor hulls", MODID);
        } else {
            LOGGER.info("Limitless Vehicles absent: soft-compat facade idle");
        }
    }

    /** Any LV hull, whatever its class. */
    public static boolean isLvHull(@Nullable Entity e) {
        return e != null && present() && LvHulls.isHull(e);
    }

    /** An LV hull our crews can work: wheeled / tracked / boat, alive and not knocked out. */
    public static boolean isCrewableHull(@Nullable Entity e) {
        return e != null && present() && LvHulls.isCrewable(e);
    }

    /** {@code mob} sits in an LV hull our crews can work. */
    public static boolean isLvCrew(@Nullable Mob mob) {
        return mob != null && isCrewableHull(mob.getVehicle());
    }

    /** Driver, or a seat whose part is a weapon: busy with the hull, so no leaning out with a rifle. */
    public static boolean controlsWeapon(@Nullable Mob mob) {
        return mob != null && isLvHull(mob.getVehicle()) && LvHulls.controlsWeapon(mob);
    }

    /** Riding this means "is armour" to a target scorer, the same rule an SBW hull gets. */
    public static boolean isArmouredHull(@Nullable Entity e) {
        return e != null && present() && LvHulls.isHull(e) && !LvHulls.isDestroyed(e);
    }

    /** Size and mobility of a crewable hull, or null for anything else. */
    @Nullable
    public static HullProfile profileOf(@Nullable Entity e) {
        return e != null && present() ? LvHulls.profileOf(e) : null;
    }

    /** Could a unit be ordered onto it right now (crewable, a free seat, not knocked out). */
    public static boolean boardable(@Nullable Entity e) {
        return e != null && present() && LvHulls.boardable(e);
    }

    /** The nearest empty LV hull an RU/US unit may scavenge, or null. */
    @Nullable
    public static Entity findAbandoned(AbstractUnit unit, double radius) {
        return aiEnabled() ? LvHulls.findAbandoned(unit, radius) : null;
    }

    /** A same-faction-crewed LV hull (not {@code self}) crossing the shot from {@code from} to {@code to}. */
    public static boolean alliedHullInCorridor(AbstractUnit shooter, Entity self, AABB corridor,
                                               Vec3 from, Vec3 to, double margin) {
        return present() && LvHulls.alliedHullInCorridor(shooter, self, corridor, from, to, margin);
    }

    /**
     * {@code id} names a Limitless Vehicles vehicle (an LV data id). Such ids live in the ordinary
     * GROUND / SURFACE / ROTOR pools beside SBW ones; this is how {@code TankSpawner} tells them apart.
     * False whenever LV is absent, so an LV id left in a pool is simply never spawnable there.
     */
    public static boolean isLvVehicleId(@Nullable String id) {
        return id != null && present() && LvHulls.isVehicleId(id);
    }

    /** {@code id} is an LV vehicle our crews can work in a pool of {@code category}. */
    public static boolean isCrewableId(@Nullable String id, WorldVehiclePools.Category category) {
        return id != null && present() && LvHulls.isCrewableId(id, category);
    }

    /**
     * Spawn a crewed LV hull by data id into the pool class it was picked from. The crew boards a
     * tick later — see {@link LvSpawner}. Null when the id is not of that class or nothing fit.
     */
    @Nullable
    public static Entity spawnCrewed(ServerLevel level, BlockPos pos, TankFaction faction, @Nullable UUID ownerId,
                                     String vehicleId, WorldVehiclePools.Category category) {
        return aiEnabled() ? LvSpawner.spawnCrewed(level, pos, faction, ownerId, vehicleId, category) : null;
    }

    /** Every LV vehicle id a pool could hold, for the pool editor's add catalog. Empty without LV. */
    public static java.util.List<String> catalogIds() {
        return present() ? LvHulls.catalogIds() : java.util.List.of();
    }

    /**
     * Pool-editor label for an LV id: its display name and hull class ("Wheel", "Track", "Boat",
     * "Rotor", "Plane", "Fixed"), or null when {@code id} is not an LV vehicle.
     */
    @Nullable
    public static String[] describe(String id) {
        return present() ? LvHulls.describe(id) : null;
    }

    /** The pool class an LV id belongs in, or null (aircraft, fixed mounts, not an LV id). */
    @Nullable
    public static WorldVehiclePools.Category categoryOf(String id) {
        return present() ? LvHulls.categoryOf(id) : null;
    }

    /** Any LV hull inside {@code box} — for dedupe checks that otherwise only see SBW hulls. */
    public static boolean anyHullIn(net.minecraft.world.level.Level level, AABB box) {
        return present() && LvHulls.anyIn(level, box);
    }
}

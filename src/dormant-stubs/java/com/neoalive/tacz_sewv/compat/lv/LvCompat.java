// NeoForge port branch: DORMANT-INTEGRATION STUB. The real class (same path under src/main/java)
// is excluded from compilation by dormantSources in build.gradle. Same public surface, constant
// "absent" answers; selfCheckDormantStubs keeps the two in step.
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
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.entity.ai.core.HullProfile;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;

public final class LvCompat {

    public static final String MODID = "ywzj_vehicle";

    private static final Logger LOGGER = LogUtils.getLogger();

    private LvCompat() {}

    public static boolean present() {
        return false;
    }

    public static boolean aiEnabled() {
        return false;
    }

    public static void reportAvailability() {
        LOGGER.info("Limitless Vehicles soft-compat dormant on this build (not ported to NeoForge yet)");
    }

    public static boolean isLvHull(@Nullable Entity e) {
        return false;
    }

    public static boolean isCrewableHull(@Nullable Entity e) {
        return false;
    }

    public static boolean isLvCrew(@Nullable Mob mob) {
        return false;
    }

    public static boolean controlsWeapon(@Nullable Mob mob) {
        return false;
    }

    public static boolean isArmouredHull(@Nullable Entity e) {
        return false;
    }

    @Nullable
    public static HullProfile profileOf(@Nullable Entity e) {
        return null;
    }

    public static boolean boardable(@Nullable Entity e) {
        return false;
    }

    @Nullable
    public static Entity findAbandoned(AbstractUnit unit, double radius) {
        return null;
    }

    public static boolean alliedHullInCorridor(AbstractUnit shooter, Entity self, AABB corridor,
                                               Vec3 from, Vec3 to, double margin) {
        return false;
    }

    public static boolean isLvVehicleId(@Nullable String id) {
        return false;
    }

    public static boolean isCrewableId(@Nullable String id, WorldVehiclePools.Category category) {
        return false;
    }

    @Nullable
    public static Entity spawnCrewed(ServerLevel level, BlockPos pos, TankFaction faction, @Nullable UUID ownerId,
                                     String vehicleId, WorldVehiclePools.Category category) {
        return null;
    }

    public static java.util.List<String> catalogIds() {
        return java.util.List.of();
    }

    @Nullable
    public static String[] describe(String id) {
        return null;
    }

    @Nullable
    public static WorldVehiclePools.Category categoryOf(String id) {
        return null;
    }

    public static boolean anyHullIn(net.minecraft.world.level.Level level, AABB box) {
        return false;
    }
}

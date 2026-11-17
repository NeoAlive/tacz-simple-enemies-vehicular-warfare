// NeoForge port branch: DORMANT-INTEGRATION STUB. The real class (same path under src/main/java)
// is excluded from compilation by dormantSources in build.gradle. Same public surface, constant
// "absent" answers; selfCheckDormantStubs keeps the two in step.
package com.neoalive.tacz_sewv.compat;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.LoadedClip;

public final class TripodCompat {

    public static final String MODID = "xx_tripod_invasion";
    public static final double MARGIN = 16.0;

    private TripodCompat() {}

    public static boolean present() {
        return false;
    }

    public static boolean isTripod(@Nullable Entity entity) {
        return false;
    }

    public static boolean isShield(BlockState state) {
        return false;
    }

    public static double standoff() {
        return SewvConfig.TRIPOD_STANDOFF_DISTANCE.get();
    }

    public static boolean appliesStandoff(Entity unit) {
        return unit instanceof RUunitEntity || unit instanceof USunitEntity;
    }

    @Nullable
    public static Entity nearestWithin(Level level, Vec3 pos, double radius) {
        return null;
    }

    public static List<Entity> tripods(Level level) {
        return List.of();
    }

    @Nullable
    public static Entity threatening(Entity unit, Entity body, double radius) {
        return null;
    }

    public static boolean blocksClaim(Entity unit, Vec3 pos) {
        return false;
    }

    public static double standoffRing(Entity unit, @Nullable LivingEntity target, double ring) {
        return ring;
    }

    public static boolean holdsStandoff(Mob mob) {
        return false;
    }

    public static boolean passesShield(@Nullable Entity entity, BlockPos pos) {
        return false;
    }

    public static boolean shieldInLine(LivingEntity target, Vec3 muzzle, Vec3 dir) {
        return false;
    }

    public static double homingY(Entity target, boolean addsHalfHeight) {
        return target.getY();
    }

    public static void heatRayHullHits(Entity tripod, Vec3 start, Vec3 end, int beam) {}

    public static boolean clearShot(Mob shooter, LivingEntity target) {
        return LoadedClip.clip(shooter.level(), new ClipContext(shooter.getEyePosition(),
                target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter))
                .getType() == HitResult.Type.MISS;
    }

    public static final class Events {
        private Events() {}
    }
}

package com.neoalive.tacz_sewv.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * {@code /sewv debug cleanup all|nearby|target}. Drops entities from Superb Warfare, Simple Enemy
 * Mod, this bridge, and the vehicle/unit addons those two are crewed with. Players stay.
 */
public final class EntityCleanup {

    /** Horizontal radius for NEARBY: 4 chunks. */
    private static final double NEARBY_BLOCKS = 4 * 16.0;
    private static final double NEARBY_BLOCKS_SQR = NEARBY_BLOCKS * NEARBY_BLOCKS;
    /** Same look distance as the other debug rays. */
    private static final double LOOK_RANGE = 64.0;

    /**
     * Registry namespaces whose entities are part of the fight. {@link VehicleEntity} and
     * {@link AbstractUnit} are also taken whatever namespace they were registered under, so an
     * addon hull or unit this list misses still goes.
     */
    private static final Set<String> NAMESPACES = Set.of(
            "superbwarfare",
            "simpleenemymod",
            "simple_enemy_extended",
            "tacz_sewv",
            "tacz",
            "fcp",
            "mcsp",
            "ashvehicle",
            "vvp",
            "neoarms",
            "doggievehicle",
            "ywzj_vehicle");

    private EntityCleanup() {}

    /** {@code all} sweeps every dimension's loaded entities; otherwise a 4-chunk radius around the source. */
    public static int run(CommandSourceStack source, boolean all) {
        int removed = 0;
        if (all) {
            for (ServerLevel level : source.getServer().getAllLevels()) {
                removed += sweep(level, null);
            }
        } else {
            removed += sweep(source.getLevel(), source.getPosition());
        }
        int count = removed;
        String key = all
                ? "command.tacz_sewv.debug.cleanup.all"
                : "command.tacz_sewv.debug.cleanup.nearby";
        source.sendSuccess(() -> Component.translatable(key, count), true);
        return removed;
    }

    /** The related entity under the crosshair. A looked-at hull takes its related passengers too. */
    public static int target(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.cleanup.target.player_only"));
            return 0;
        }
        Entity hit = lookedAt(player);
        if (hit == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.cleanup.target.none"));
            return 0;
        }
        List<Entity> doomed = new ArrayList<>();
        doomed.add(hit);
        if (hit instanceof VehicleEntity) {
            for (Entity passenger : hit.getPassengers()) {
                if (related(passenger)) doomed.add(passenger);
            }
        }
        int removed = discard(doomed);
        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.debug.cleanup.target", removed), true);
        return removed;
    }

    @Nullable
    private static Entity lookedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(LOOK_RANGE));
        HitResult block = player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        double reachSqr = eye.distanceToSqr(stop);
        AABB sweep = player.getBoundingBox().expandTowards(stop.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, stop, sweep, e -> related(e) && e.isAlive(), reachSqr);
        return hit == null ? null : hit.getEntity();
    }

    // ponytail: loaded entities only. Unloaded chunks keep theirs until the chunk loads;
    // a region sweep is the upgrade if a full-save purge is needed.
    private static int sweep(ServerLevel level, @Nullable Vec3 near) {
        List<Entity> doomed = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!related(entity)) continue;
            if (near != null && horizontalSqr(entity, near) > NEARBY_BLOCKS_SQR) continue;
            doomed.add(entity);
        }
        return discard(doomed);
    }

    private static int discard(List<Entity> doomed) {
        int removed = 0;
        for (Entity entity : doomed) {
            if (entity.isRemoved()) continue;
            entity.discard();
            removed++;
        }
        return removed;
    }

    private static boolean related(Entity entity) {
        if (entity instanceof Player) return false;
        if (entity instanceof VehicleEntity || entity instanceof AbstractUnit) return true;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && NAMESPACES.contains(id.getNamespace());
    }

    private static double horizontalSqr(Entity entity, Vec3 near) {
        double dx = entity.getX() - near.x;
        double dz = entity.getZ() - near.z;
        return dx * dx + dz * dz;
    }
}

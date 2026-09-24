package com.neoalive.tacz_sewv.territory;

import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;

/**
 * "Is there a hostile unit inside this layer?" for the Advance Plan gate.
 *
 * <p>The scan is DUPLICATED from Sweep &amp; Advance's private {@code scanContestants} / {@code isHostileContestant}, not
 * extracted from it: S&amp;A's internals stay untouched, and its version is welded to an operation's rectangle and first
 * assignee. Same doctrine: only units count (vanilla mobs never contest), medics are neutral, and hostility is judged
 * from a live plan unit's point of view ({@code VehicleTargeting.isNonHostile}, which goes through invasion
 * ally/enemy, diplomacy and the friendly flags). A hull's crew counts at the hull's position.
 *
 * <p>One bounding-box query over the layer (a layer is at most the plan cap, 25 chunks) filtered by chunk membership,
 * once per pass per running plan.
 */
public final class AdvanceHostiles {

    private AdvanceHostiles() {}

    /**
     * Whether every chunk is loaded. An unloaded layer cannot be scanned, and "no hostiles seen" there would be a lie
     * that lets the plan claim ground it never looked at, so the caller holds the layer instead. (S&amp;A never checked.)
     */
    public static boolean allLoaded(ServerLevel level, Set<Long> chunks) {
        for (long key : chunks) {
            FrontlineMath.Chunk c = FrontlineMath.unpack(key);
            if (!level.hasChunk(c.x(), c.z())) return false;
        }
        return true;
    }

    /** True if a contesting unit stands in any of {@code chunks}. {@code probe} is a live plan unit; {@code commander} owns the plan. */
    public static boolean anyIn(ServerLevel level, Set<Long> chunks, PmcUnitEntity probe, ServerPlayer commander) {
        if (chunks.isEmpty()) return false;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : chunks) {
            FrontlineMath.Chunk c = FrontlineMath.unpack(key);
            minX = Math.min(minX, c.x());
            minZ = Math.min(minZ, c.z());
            maxX = Math.max(maxX, c.x());
            maxZ = Math.max(maxZ, c.z());
        }
        AABB box = new AABB(minX * 16.0, level.getMinBuildHeight(), minZ * 16.0,
                (maxX + 1) * 16.0, level.getMaxBuildHeight(), (maxZ + 1) * 16.0);

        for (AbstractUnit unit : level.getEntitiesOfClass(AbstractUnit.class, box, LivingEntity::isAlive)) {
            if (inChunks(chunks, unit) && contests(unit, probe, commander)) return true;
        }
        for (VehicleEntity hull : level.getEntitiesOfClass(VehicleEntity.class, box, h -> true)) {
            if (!inChunks(chunks, hull)) continue;
            for (Entity passenger : hull.getPassengers()) {
                if (passenger instanceof AbstractUnit crew && crew.isAlive() && contests(crew, probe, commander)) return true;
            }
        }
        return false;
    }

    private static boolean inChunks(Set<Long> chunks, Entity e) {
        return chunks.contains(FrontlineMath.pack(e.getBlockX() >> 4, e.getBlockZ() >> 4));
    }

    private static boolean contests(AbstractUnit unit, PmcUnitEntity probe, ServerPlayer commander) {
        if (unit == probe) return false;
        // The player's own units never contest their own layer, whatever the friendly flags say.
        if (unit instanceof PmcUnitEntity pmc && PmcOwnerSupport.isOwner(commander, pmc)) return false;
        if (VehicleTargeting.isMedic(unit)) return false;
        return !VehicleTargeting.isNonHostile(probe, unit);
    }
}

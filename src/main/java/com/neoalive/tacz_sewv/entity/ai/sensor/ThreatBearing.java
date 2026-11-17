package com.neoalive.tacz_sewv.entity.ai.sensor;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Where the enemy most likely is, seen from one of the player's own units. Reads the combatant index
 * and the contact board only: it never sets a target, so asking it changes nothing about the fight.
 */
public final class ThreatBearing {

    private ThreatBearing() {}

    /** Nearest hostile position within {@code radius} of {@code from}, or null when nothing is known. */
    @Nullable
    public static Vec3 nearest(ServerLevel level, AbstractUnit perspective, Vec3 from, double radius) {
        Vec3 best = null;
        double bestD = radius * radius;
        CombatantIndex.Snapshot snap = CombatantIndex.snapshot(level);
        if (snap != null) {
            for (CombatantIndex.Entry e : snap.query(from.x, from.z, radius, level.getMinBuildHeight(),
                    level.getMaxBuildHeight())) {
                if (!hostile(perspective, e.living)) continue;
                double d = from.distanceToSqr(e.x, e.y, e.z);
                if (d < bestD) {
                    bestD = d;
                    best = new Vec3(e.x, e.y, e.z);
                }
            }
            for (CombatantIndex.Entry e : snap.hulls()) {
                if (!hostile(perspective, e.living)) continue;
                double d = from.distanceToSqr(e.x, e.y, e.z);
                if (d < bestD) {
                    bestD = d;
                    best = new Vec3(e.x, e.y, e.z);
                }
            }
        }
        if (best != null) return best;
        for (LivingEntity c : ContactBoard.contactsFor(perspective, ContactBoard.Source.RELAYED)) {
            if (!c.isAlive()) continue;
            double d = from.distanceToSqr(c.position());
            if (d < bestD) {
                bestD = d;
                best = c.position();
            }
        }
        return best;
    }

    /**
     * A PMC never fights a player on SEM's ladder, only through diplomacy or an invasion, and
     * {@code isNonHostile} has no branch for that pair, so players are asked through diplomacy.
     */
    private static boolean hostile(AbstractUnit perspective, @Nullable LivingEntity target) {
        if (target == null || target == perspective || !target.isAlive()) return false;
        if (target instanceof Player p) {
            if (p.isCreative() || p.isSpectator()) return false;
            return perspective instanceof PmcUnitEntity
                    ? VehicleTargeting.isDiplomacyEnemy(perspective, p)
                    : !VehicleTargeting.isNonHostile(perspective, p);
        }
        if (!(target instanceof AbstractUnit)) return false;
        return !VehicleTargeting.isNonHostile(perspective, target);
    }
}

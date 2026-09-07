package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.Collection;
import java.util.EnumSet;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * Pre-gate for Combined Arms formations: a selection must be entirely infantry, entirely
 * ground vehicles, or entirely ships — never mixed, never air, never FIXED emplacements.
 */
public final class FormationComposition {

    public enum Kind {
        INFANTRY,
        GROUND,
        SHIP
    }

    /** Hardcoded slot baselines — infantry tight, hulls wide. */
    public static final double SPACING_INFANTRY = 2.5;
    public static final double SPACING_VEHICLE = 12.0;

    public static final String MSG_INVALID = "message.tacz_sewv.formation.composition_invalid";
    public static final String MSG_OVERLAP = "message.tacz_sewv.formation.slots_overlap";
    public static final String MSG_USE_QUICKWHEEL = "message.tacz_sewv.formation.use_quickwheel";

    private FormationComposition() {}

    /**
     * Classify one PMC. {@code null} means ineligible (heli / plane / FIXED / unknown mount).
     */
    @Nullable
    public static Kind classify(PmcUnitEntity pmc) {
        Entity mount = pmc.getVehicle();
        if (!(mount instanceof VehicleEntity hull)) {
            return Kind.INFANTRY;
        }
        if (HullFacts.isShipHull(hull)) return Kind.SHIP;
        if (HullFacts.isGroundMobileHull(hull)) return Kind.GROUND;
        return null;
    }

    public static double baselineSpacing(Kind kind) {
        return kind == Kind.INFANTRY ? SPACING_INFANTRY : SPACING_VEHICLE;
    }

    /**
     * Returns the single shared kind when every unit classifies the same and none reject;
     * {@code null} when empty, mixed, or any unit is ineligible.
     */
    @Nullable
    public static Kind resolve(Collection<PmcUnitEntity> units) {
        if (units == null || units.isEmpty()) return null;
        EnumSet<Kind> seen = EnumSet.noneOf(Kind.class);
        for (PmcUnitEntity pmc : units) {
            Kind kind = classify(pmc);
            if (kind == null) return null;
            seen.add(kind);
            if (seen.size() > 1) return null;
        }
        return seen.size() == 1 ? seen.iterator().next() : null;
    }
}

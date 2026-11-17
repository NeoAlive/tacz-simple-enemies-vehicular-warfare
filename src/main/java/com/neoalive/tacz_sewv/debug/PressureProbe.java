package com.neoalive.tacz_sewv.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IAcquisition;
import com.neoalive.tacz_sewv.entity.ai.suppression.HullPressure;

/**
 * {@code /sewv debug pressure}: what each nearby unit is feeling — SEM's suppression level, its
 * tactical state and threat pressure, plus the hull's own pressure for a crewman. The in-game check
 * for the suppression bus; SEM keeps its three itself, this only reads them.
 */
public final class PressureProbe {

    private static final double RADIUS = 48.0;
    private static final int MAX_LINES = 16;

    private PressureProbe() {}

    public static List<String> report(ServerLevel level, Vec3 at) {
        List<AbstractUnit> units = level.getEntitiesOfClass(AbstractUnit.class,
                new AABB(at, at).inflate(RADIUS), AbstractUnit::isAlive);
        units.sort(Comparator.comparingDouble(u -> u.distanceToSqr(at)));
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "%d units within %.0f blocks (nearest %d):",
                units.size(), RADIUS, Math.min(units.size(), MAX_LINES)));
        for (int i = 0; i < units.size() && i < MAX_LINES; i++) {
            out.add(line(units.get(i), at));
        }
        return out;
    }

    private static String line(AbstractUnit u, Vec3 at) {
        String where = u.getVehicle() instanceof VehicleEntity hull
                ? String.format(Locale.ROOT, "crew(fire=%.2f)", HullPressure.read(hull, u.level().getGameTime()))
                : "foot";
        String tactical;
        String pressure;
        String supp;
        try {
            supp = String.format(Locale.ROOT, "%.2f", u.getGenericSuppressionLevel());
            pressure = String.format(Locale.ROOT, "%.2f", u.getThreatPressure());
            tactical = String.valueOf(u.getTacticalCombatState());
        } catch (LinkageError e) {
            // A SEM build without the 0.1.6 tactical layer.
            supp = pressure = tactical = "n/a";
        }
        IAcquisition acq = (IAcquisition) u;
        long left = acq.sewv$getAcqReadyAt() - u.level().getGameTime();
        String acquiring = acq.sewv$getAcqTargetId() < 0 ? "-"
                : "#" + acq.sewv$getAcqTargetId() + (left > 0 ? "(" + left + "t)" : "(ready)");
        return String.format(Locale.ROOT, "#%d %s %s %.0fm state=%s tac=%s supp=%s press=%s acq=%s target=%s",
                u.getId(), u.getType().toShortString(), where, Math.sqrt(u.distanceToSqr(at)),
                u.getSoldierState(), tactical, supp, pressure, acquiring,
                u.getTarget() == null ? "-" : u.getTarget().getType().toShortString());
    }
}

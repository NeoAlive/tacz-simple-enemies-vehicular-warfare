package com.neoalive.tacz_sewv.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.core.gunnery.FireCommand.Option;
import com.neoalive.tacz_sewv.entity.ai.core.gunnery.SeatWeaponPicker;
import com.neoalive.tacz_sewv.entity.ai.core.gunnery.SeatWeaponPicker.Decision;

/**
 * {@code /sewv debug gunnery}: the last fire command each crewed seat nearby ran. Reads what the
 * picker kept; never runs it.
 */
public final class GunneryProbe {

    private static final double RADIUS = 96.0;
    private static final int MAX_HULLS = 8;

    private GunneryProbe() {}

    public static List<String> report(ServerLevel level, Vec3 at) {
        List<VehicleEntity> hulls = level.getEntitiesOfClass(VehicleEntity.class,
                new AABB(at, at).inflate(RADIUS), h -> h.getFirstPassenger() instanceof AbstractUnit);
        hulls.sort(Comparator.comparingDouble(h -> h.distanceToSqr(at)));
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "%d crewed hulls within %.0f blocks:", hulls.size(), RADIUS));
        long now = level.getGameTime();
        for (int i = 0; i < hulls.size() && i < MAX_HULLS; i++) {
            VehicleEntity hull = hulls.get(i);
            for (Entity rider : hull.getPassengers()) {
                if (!(rider instanceof AbstractUnit)) continue;
                int seat = hull.getSeatIndex(rider);
                Decision d = SeatWeaponPicker.last(hull, seat);
                if (d == null) continue;
                out.add(String.format(Locale.ROOT, "%s#%d seat %d vs %s: %s mg=%s %s %s (%dt ago)",
                        hull.getType().toShortString(), hull.getId(), seat, d.target(), d.cls(),
                        Double.isNaN(d.mgFraction()) ? "-" : String.format(Locale.ROOT, "%.2f", d.mgFraction()),
                        d.stepName(), d.pick() == null ? "-" : d.pick().option().round().shortAmmo(),
                        now - d.gameTime()));
                StringBuilder opts = new StringBuilder("  ");
                for (Option o : d.options()) {
                    opts.append(o.round().slot()).append(':').append(o.round().shortAmmo())
                            .append('[').append(o.round().kind());
                    if (o.chambered()) opts.append(",in");
                    if (o.why() != null) opts.append(',').append(o.why());
                    opts.append("] ");
                }
                out.add(opts.toString());
            }
        }
        return out;
    }

    /** Short tag for the crew diag line. */
    public static String tag(VehicleEntity hull, int seat) {
        Decision d = SeatWeaponPicker.last(hull, seat);
        if (d == null) return "-";
        return d.cls() + ":" + (d.pick() == null ? "-" : d.pick().option().round().shortAmmo()) + ":" + d.stepName();
    }
}

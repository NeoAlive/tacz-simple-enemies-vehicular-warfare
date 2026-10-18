package com.neoalive.tacz_sewv.entity.ai.march;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.formation.SlotMatcher;
import com.neoalive.tacz_sewv.entity.ai.support.InfantryOrders;

/**
 * The march under a CohesiveLine play: the group's hulls abreast, advancing on the enemy at the march pace, with a
 * file of the infantry at the hulls walking behind each one.
 *
 * <p>The play decides; this lays it. The hulls keep the line order the play's formation dealt them, so a crew is
 * not sent across the line to a different place than the one it was formed into. Infantry are dealt to hulls by
 * least total walk, which keeps each rifleman behind the hull it was already near.
 */
public final class CohesiveMarches {

    /** Riflemen behind each hull at most: enough to be screened, few enough to stay in its lee. */
    static final int MAX_FILE = 4;

    private CohesiveMarches() {}

    /**
     * Form the line. {@code hulls} are driver ids in line-slot order; {@code (cx, cz)} the line's centre; {@code
     * (fx, fz)} toward the enemy; {@code length} how far it advances before it has arrived; {@code atHulls} how
     * close to a hull a rifleman must be to be taken. Null when there is no drivable ground ahead.
     */
    @Nullable
    public static March form(ServerLevel level, CrewFacts.Faction faction, int[] hulls, double cx, double cy,
                             double cz, double fx, double fz, double length, double spacing, double atHulls,
                             long now) {
        if (hulls.length == 0 || length < RouteScout.LEG) return null;
        double bearing = Math.atan2(fx, fz);
        MarchRoute route = new MarchRoute(cx, cz, fx, fz);
        RouteScout.extend(level, route, bearing, Math.min(length, MarchRegistry.ROAD_AHEAD), length);
        if (route.length() < RouteScout.LEG) {
            SewvDiag.command("cohesive line refused hulls={} (no drivable ground toward the enemy)",
                    Arrays.toString(hulls));
            return null;
        }
        int[] infantry = recruitFiles(level, faction, hulls, route, spacing, atHulls);
        March march = MarchRegistry.register(level, faction, March.Kind.COHESIVE_LINE, hulls, infantry, route,
                bearing, length, spacing, MarchRegistry.pace(), 0.0, now);
        SewvDiag.command("march formed id={} kind={} faction={} hulls={} infantry={} bearing={} spacing={} road={}/{}",
                march.id, march.kind, faction, Arrays.toString(hulls), Arrays.toString(infantry),
                String.format(Locale.ROOT, "%.0f", Math.toDegrees(bearing)),
                String.format(Locale.ROOT, "%.0f", spacing), String.format(Locale.ROOT, "%.0f", route.length()),
                String.format(Locale.ROOT, "%.0f", length));
        return march;
    }

    /**
     * Our riflemen near the hulls, ordered so that index i files behind hull {@code i % n} at rank {@code i / n}
     * ({@link MarchGeometry#fileHull}): each is matched to a file place by least total walk. In a fight, so a
     * target does not exclude anyone — only another order does.
     */
    private static int[] recruitFiles(ServerLevel level, CrewFacts.Faction faction, int[] hulls, MarchRoute route,
                                      double spacing, double atHulls) {
        List<Entity> hullEntities = new ArrayList<>();
        for (int id : hulls) {
            Entity e = level.getEntity(id);
            if (e != null && e.getVehicle() != null) hullEntities.add(e.getVehicle());
        }
        if (hullEntities.isEmpty()) return new int[0];
        AABB box = hullEntities.get(0).getBoundingBox();
        for (Entity h : hullEntities) box = box.minmax(h.getBoundingBox());
        box = box.inflate(atHulls, 12.0, atHulls);
        List<AbstractUnit> found = level.getEntitiesOfClass(AbstractUnit.class, box, u -> u.isAlive()
                && !(u instanceof PmcUnitEntity)
                && CrewFacts.factionOfCrew(u) == faction
                && InfantryOrders.unordered(u)
                && !MarchRegistry.isMarching(u.getId())
                && near(u, hullEntities, atHulls));
        if (found.isEmpty()) return new int[0];
        int n = hulls.length;
        // A file of a few riflemen per hull; the rest of the infantry fight as SEM's own, nearest first kept.
        double lcx = 0.0;
        double lcz = 0.0;
        for (Entity h : hullEntities) {
            lcx += h.getX();
            lcz += h.getZ();
        }
        double fcx = lcx / hullEntities.size();
        double fcz = lcz / hullEntities.size();
        found.sort(Comparator.comparingDouble((AbstractUnit u) -> u.distanceToSqr(fcx, u.getY(), fcz))
                .thenComparingInt(AbstractUnit::getId));
        if (found.size() > n * MAX_FILE) found = new ArrayList<>(found.subList(0, n * MAX_FILE));
        found.sort(Comparator.comparingInt(AbstractUnit::getId));
        int m = found.size();

        double[] mx = new double[m];
        double[] mz = new double[m];
        double[] sx = new double[m];
        double[] sz = new double[m];
        int[] prev = new int[m];
        Arrays.fill(prev, -1);
        for (int i = 0; i < m; i++) {
            mx[i] = found.get(i).getX();
            mz[i] = found.get(i).getZ();
            double[] place = MarchGeometry.file(route, 0.0, MarchGeometry.fileHull(i, n), n, spacing,
                    MarchGeometry.fileRank(i, n));
            sx[i] = place[0];
            sz[i] = place[1];
        }
        int[] placeOf = SlotMatcher.match(mx, mz, sx, sz, prev, 0.0);
        int[] out = new int[m];
        for (int i = 0; i < m; i++) out[placeOf[i]] = found.get(i).getId();
        return out;
    }

    private static boolean near(Entity u, List<Entity> hulls, double r) {
        for (Entity h : hulls) {
            if (u.distanceToSqr(h.getX(), u.getY(), h.getZ()) <= r * r) return true;
        }
        return false;
    }
}

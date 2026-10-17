package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Pure battle-group clustering — no world types, no allocation beyond the result.
 *
 * <p>Membership is distance-to-<b>centroid</b> (not single-linkage neighbor), capped by half of
 * {@link GroupParams#maxDiameter}, with a join/leave hysteresis band so a crew hovering at the
 * edge does not flicker. New arrivals join the nearest eligible existing group; a group below
 * {@code minSize} dissolves; a lone unit never forms a group.
 */
public final class Grouping {

    private Grouping() {}

    /**
     * Recompute group membership from the live unit list and last scan's groups.
     *
     * @param units         candidates already battle-gated and capped by the world layer
     * @param existing      sticky groups from the previous scan (may be empty)
     * @param params        join/leave/diameter/minSize
     * @param nextGroupId   supplies fresh ids for newly formed groups (monotonic)
     */
    public static List<AssignedGroup> groupAssignments(List<UnitPos> units,
                                                       List<ExistingGroup> existing,
                                                       GroupParams params,
                                                       IntSupplier nextGroupId) {
        Map<Integer, UnitPos> byId = new HashMap<>(units.size() * 2);
        Map<Integer, List<UnitPos>> byFaction = new HashMap<>();
        for (UnitPos u : units) {
            byId.put(u.id, u);
            byFaction.computeIfAbsent(u.faction, f -> new ArrayList<>()).add(u);
        }
        for (List<UnitPos> list : byFaction.values()) {
            list.sort(Comparator.comparingInt(u -> u.id));
        }

        Map<Integer, List<ExistingGroup>> existingByFaction = new HashMap<>();
        for (ExistingGroup g : existing) {
            existingByFaction.computeIfAbsent(g.faction, f -> new ArrayList<>()).add(g);
        }
        for (List<ExistingGroup> list : existingByFaction.values()) {
            list.sort(Comparator.comparingInt(g -> g.groupId));
        }

        List<AssignedGroup> out = new ArrayList<>();
        Set<Integer> factions = new HashSet<>();
        factions.addAll(byFaction.keySet());
        factions.addAll(existingByFaction.keySet());
        List<Integer> factionOrder = new ArrayList<>(factions);
        factionOrder.sort(Integer::compareTo);

        double maxRadius = params.maxRadius();
        for (int faction : factionOrder) {
            List<UnitPos> factionUnits = byFaction.getOrDefault(faction, List.of());
            List<ExistingGroup> factionExisting = existingByFaction.getOrDefault(faction, List.of());
            out.addAll(assignFaction(faction, factionUnits, factionExisting, byId,
                    params, maxRadius, nextGroupId));
        }
        return out;
    }

    private static List<AssignedGroup> assignFaction(int faction,
                                                     List<UnitPos> units,
                                                     List<ExistingGroup> existing,
                                                     Map<Integer, UnitPos> byId,
                                                     GroupParams params,
                                                     double maxRadius,
                                                     IntSupplier nextGroupId) {
        Set<Integer> assigned = new HashSet<>();
        List<WorkingGroup> working = new ArrayList<>();

        // Phase 1: retain members of existing groups (leave band + diameter).
        for (ExistingGroup eg : existing) {
            List<UnitPos> staying = new ArrayList<>();
            int[] ids = eg.memberIds.clone();
            Arrays.sort(ids);
            for (int memberId : ids) {
                UnitPos u = byId.get(memberId);
                if (u == null || u.faction != faction) continue;
                double d = dist(u, eg.centroidX, eg.centroidZ);
                // Leave hysteresis: already-members stay out to leaveRadius.
                // Diameter is a hard gate against the last centroid too.
                if (d <= params.leaveRadius && d <= maxRadius) {
                    staying.add(u);
                }
            }
            if (staying.size() < params.minSize) continue;

            double[] c = centroid(staying);
            // After the centroid moves, diameter may eject the far edge.
            staying = withinRadius(staying, c[0], c[1], maxRadius);
            if (staying.size() < params.minSize) continue;
            c = centroid(staying);

            WorkingGroup g = new WorkingGroup(eg.groupId, faction, staying, c[0], c[1]);
            working.add(g);
            for (UnitPos u : staying) assigned.add(u.id);
        }

        // Phase 2: unassigned join nearest existing eligible group (join band).
        List<UnitPos> unassigned = new ArrayList<>();
        for (UnitPos u : units) {
            if (!assigned.contains(u.id)) unassigned.add(u);
        }
        unassigned.sort(Comparator.comparingInt(u -> u.id));

        for (UnitPos u : unassigned) {
            WorkingGroup best = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (WorkingGroup g : working) {
                double d = dist(u, g.centroidX, g.centroidZ);
                if (d > params.joinRadius || d > maxRadius) continue;
                if (!canAdd(g, u, maxRadius)) continue;
                if (d < bestDist || (d == bestDist && (best == null || g.groupId < best.groupId))) {
                    best = g;
                    bestDist = d;
                }
            }
            if (best != null) {
                best.add(u, maxRadius);
                assigned.add(u.id);
            }
        }

        // Phase 3: form new groups from leftovers — seed by min id, grow by nearest-to-centroid.
        List<UnitPos> remaining = new ArrayList<>();
        for (UnitPos u : unassigned) {
            if (!assigned.contains(u.id)) remaining.add(u);
        }
        remaining.sort(Comparator.comparingInt(u -> u.id));

        while (!remaining.isEmpty()) {
            UnitPos seed = remaining.remove(0);
            WorkingGroup g = new WorkingGroup(nextGroupId.getAsInt(), faction,
                    new ArrayList<>(List.of(seed)), seed.x, seed.z);
            boolean grew = true;
            while (grew) {
                grew = false;
                UnitPos best = null;
                double bestDist = Double.POSITIVE_INFINITY;
                for (UnitPos u : remaining) {
                    double d = dist(u, g.centroidX, g.centroidZ);
                    if (d > params.joinRadius || d > maxRadius) continue;
                    if (!canAdd(g, u, maxRadius)) continue;
                    if (d < bestDist || (d == bestDist && (best == null || u.id < best.id))) {
                        best = u;
                        bestDist = d;
                    }
                }
                if (best != null) {
                    remaining.remove(best);
                    g.add(best, maxRadius);
                    grew = true;
                }
            }
            if (g.members.size() >= params.minSize) {
                working.add(g);
                for (UnitPos u : g.members) assigned.add(u.id);
            }
            // else: discard — lone / undersized cluster forms no group
        }

        List<AssignedGroup> out = new ArrayList<>(working.size());
        working.sort(Comparator.comparingInt(g -> g.groupId));
        for (WorkingGroup g : working) {
            int[] ids = new int[g.members.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = g.members.get(i).id;
            Arrays.sort(ids);
            out.add(new AssignedGroup(g.groupId, g.faction, ids, g.centroidX, g.centroidZ));
        }
        return out;
    }

    private static boolean canAdd(WorkingGroup g, UnitPos u, double maxRadius) {
        int n = g.members.size();
        double ncx = (g.centroidX * n + u.x) / (n + 1);
        double ncz = (g.centroidZ * n + u.z) / (n + 1);
        if (dist(u, ncx, ncz) > maxRadius) return false;
        for (UnitPos m : g.members) {
            if (dist(m, ncx, ncz) > maxRadius) return false;
        }
        return true;
    }

    private static List<UnitPos> withinRadius(List<UnitPos> members, double cx, double cz, double maxRadius) {
        List<UnitPos> kept = new ArrayList<>(members.size());
        for (UnitPos u : members) {
            if (dist(u, cx, cz) <= maxRadius) kept.add(u);
        }
        return kept;
    }

    private static double[] centroid(List<UnitPos> members) {
        double sx = 0, sz = 0;
        for (UnitPos u : members) {
            sx += u.x;
            sz += u.z;
        }
        double n = members.size();
        return new double[]{sx / n, sz / n};
    }

    static double dist(UnitPos u, double cx, double cz) {
        double dx = u.x - cx;
        double dz = u.z - cz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static final class WorkingGroup {
        final int groupId;
        final int faction;
        final List<UnitPos> members;
        double centroidX;
        double centroidZ;

        WorkingGroup(int groupId, int faction, List<UnitPos> members, double cx, double cz) {
            this.groupId = groupId;
            this.faction = faction;
            this.members = members;
            this.centroidX = cx;
            this.centroidZ = cz;
        }

        void add(UnitPos u, double maxRadius) {
            this.members.add(u);
            double[] c = centroid(this.members);
            this.centroidX = c[0];
            this.centroidZ = c[1];
            // Diameter prune after add — should be a no-op when canAdd held, but keeps the
            // invariant if floating point nudges a boundary case.
            Iterator<UnitPos> it = this.members.iterator();
            while (it.hasNext()) {
                if (dist(it.next(), this.centroidX, this.centroidZ) > maxRadius) it.remove();
            }
            if (!this.members.isEmpty()) {
                c = centroid(this.members);
                this.centroidX = c[0];
                this.centroidZ = c[1];
            }
        }
    }
}

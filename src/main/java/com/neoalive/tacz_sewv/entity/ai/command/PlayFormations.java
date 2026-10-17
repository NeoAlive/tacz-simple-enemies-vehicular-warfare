package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.formation.FormationFrame;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationGeometry;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationTemplates;
import com.neoalive.tacz_sewv.entity.ai.formation.SlotMatcher;

/**
 * Turns a play's role points into formations: every member sharing a role and a point gets its own
 * slot around that point, and the heading to hold on it.
 *
 * <p>A play deals roles and one point per role; it never had per-member geometry, so a whole flank
 * wing used to drive at the same mark. This runs where the firing line did — after leapfrog, before
 * commander-back and cover — so play selection, sticky roles and the leapfrog all keep working on
 * role points exactly as before, and only the final spread changes. Which shape a role takes is
 * {@link FormationTemplates}' call; this class only lays it. Pure: headless-testable.
 */
final class PlayFormations {

    /**
     * Leaving last pass's slot must save this many squared spacings of travel, or the member stays put
     * — the hysteresis that stops a re-laid formation from shuffling as its anchor drifts.
     */
    static final double STICKINESS = 1.0;

    private PlayFormations() {}

    /**
     * @param previous last pass's committed roles, for slot stickiness; may be null
     * @param scale    {@code TacticalScale}, applied to template spacing
     */
    static Roles lay(@Nullable PlayId play, BattleField bf, Roles roles, @Nullable Roles previous,
                     GroupSnapshot g, FormationTemplates templates, double scale) {
        if (roles == null) return null;
        Map<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < g.memberIds.length; i++) index.put(g.memberIds[i], i);
        Map<Integer, Assignment> prev = new HashMap<>();
        if (previous != null) {
            for (Assignment a : previous.assignments) prev.put(a.unitId, a);
        }

        // Members sharing a role, side and point form one formation. Insertion order keeps it deterministic.
        Map<String, List<Integer>> formations = new LinkedHashMap<>();
        Assignment[] in = roles.assignments;
        for (int k = 0; k < in.length; k++) {
            Assignment a = in[k];
            if (!laid(a.role) || !Double.isFinite(a.destX) || !Double.isFinite(a.destZ)) continue;
            if (!index.containsKey(a.unitId)) continue;
            String key = a.role + "/" + a.flankSide + "/" + Double.doubleToLongBits(a.destX)
                    + "/" + Double.doubleToLongBits(a.destZ);
            formations.computeIfAbsent(key, x -> new ArrayList<>()).add(k);
        }
        if (formations.isEmpty()) return roles;

        Assignment[] out = in.clone();
        for (List<Integer> members : formations.values()) {
            layOne(play, bf, in, out, members, index, prev, g, templates, scale);
        }
        return new Roles(out);
    }

    private static void layOne(@Nullable PlayId play, BattleField bf, Assignment[] in, Assignment[] out,
                               List<Integer> members, Map<Integer, Integer> index, Map<Integer, Assignment> prev,
                               GroupSnapshot g, FormationTemplates templates, double scale) {
        members.sort((p, q) -> Integer.compare(in[p].unitId, in[q].unitId));
        Assignment head = in[members.get(0)];
        FormationTemplates.Template template = templates.lookup(play == null ? null : play.key,
                roleKey(head.role, head.flankSide));
        FormationFrame frame = frame(bf, head.destX, head.destZ, template.facing());
        if (frame == null) return;

        int n = members.size();
        Vec3[] slots = FormationGeometry.slots(frame, template.spec(n, scale), n);
        double[] mx = new double[n];
        double[] mz = new double[n];
        double[] sx = new double[n];
        double[] sz = new double[n];
        int[] prevSlot = new int[n];
        for (int m = 0; m < n; m++) {
            Assignment a = in[members.get(m)];
            int i = index.get(a.unitId);
            mx[m] = g.x[i];
            mz[m] = g.z[i];
            sx[m] = slots[m].x;
            sz[m] = slots[m].z;
            Assignment p = prev.get(a.unitId);
            prevSlot[m] = p != null && p.role == a.role && p.flankSide == a.flankSide && p.slot < n ? p.slot : -1;
        }
        double spacing = template.spacing() * scale;
        int[] slotOf = SlotMatcher.match(mx, mz, sx, sz, prevSlot, STICKINESS * spacing * spacing);
        for (int m = 0; m < n; m++) {
            int k = members.get(m);
            int s = slotOf[m];
            out[k] = in[k].withSlot(s, sx[s], sz[s], frame.fx(), frame.fz());
        }
    }

    /** The frame a template faces, or null when the battlefield has no direction to give it. */
    @Nullable
    private static FormationFrame frame(BattleField bf, double x, double z, FormationTemplates.Facing facing) {
        boolean hasAxis = bf.axisX * bf.axisX + bf.axisZ * bf.axisZ > 1.0e-12;
        double ex = bf.enemyCentroidX - x;
        double ez = bf.enemyCentroidZ - z;
        boolean hasEnemy = ex * ex + ez * ez > 1.0e-6;
        if (facing == FormationTemplates.Facing.ENEMY && hasEnemy) {
            return FormationFrame.facing(x, 0.0, z, ex, ez);
        }
        if (hasAxis) return FormationFrame.ofBattleAxis(x, 0.0, z, bf.axisX, bf.axisZ);
        return hasEnemy ? FormationFrame.facing(x, 0.0, z, ex, ez) : null;
    }

    /** Idle roles have their own geometry in {@code IdleGroupSupport}. */
    private static boolean laid(Assignment.Role role) {
        return role != Assignment.Role.IDLE_HOLD && role != Assignment.Role.IDLE_TRAVEL;
    }

    /** Template key for a role: {@code base_of_fire}, {@code maneuver}, {@code maneuver_left}, … */
    static String roleKey(Assignment.Role role, @Nullable Assignment.FlankSide side) {
        String key = role.name().toLowerCase(Locale.ROOT);
        return side == null ? key : key + "_" + side.name().toLowerCase(Locale.ROOT);
    }
}

package com.neoalive.tacz_sewv.entity.ai.support;

import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.formation.FormationFrame;
import com.neoalive.tacz_sewv.entity.ai.goal.EscortGoal;

/**
 * Headless check of the on-foot formations: how event infantry split into squads, where a squad member stands
 * behind its leader, and the two escort files beside a vehicle. Run via {@code ./gradlew selfCheckInfantryFormation}.
 */
public final class InfantryFormationSelfCheck {

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea");
        squadSplits();
        squadSlots();
        escortFiles();
        System.out.println("InfantryFormationSelfCheck OK");
    }

    /** As few squads as fit, as even as possible, nobody left out. */
    private static void squadSplits() {
        assert InfantrySquads.squadSizes(0).length == 0;
        for (int n = 1; n <= 40; n++) {
            int[] sizes = InfantrySquads.squadSizes(n);
            int sum = 0;
            int min = Integer.MAX_VALUE;
            int max = 0;
            for (int s : sizes) {
                sum += s;
                min = Math.min(min, s);
                max = Math.max(max, s);
            }
            assert sum == n : "n=" + n + " lost riflemen";
            assert max <= InfantrySquads.MAX_SQUAD : "n=" + n + " oversize squad";
            assert max - min <= 1 : "n=" + n + " uneven squads";
            assert sizes.length == (n + InfantrySquads.MAX_SQUAD - 1) / InfantrySquads.MAX_SQUAD : "n=" + n + " too many squads";
        }
    }

    /** Behind the leader, never on it; a wedge at rest, a single file on the move; no two members share a spot. */
    private static void squadSlots() {
        Vec3 leader = new Vec3(100.0, 64.0, -50.0);
        double fx = 0.6;
        double fz = 0.8;
        FormationFrame frame = FormationFrame.facing(leader.x, leader.y, leader.z, fx, fz);
        for (boolean moving : new boolean[] {false, true}) {
            int n = 7;
            Vec3[] slots = new Vec3[n];
            for (int i = 0; i < n; i++) {
                slots[i] = InfantrySquads.slot(leader, fx, fz, i, n, moving);
                assert frame.ahead(slots[i].x, slots[i].z) < -1.0 : "member " + i + " not behind the leader";
                if (moving) assert Math.abs(frame.lateral(slots[i].x, slots[i].z)) < 1e-9 : "column is a single file";
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    assert slots[i].distanceTo(slots[j]) >= InfantrySquads.SPACING - 1e-9 : "members " + i + "/" + j + " crowd";
                }
            }
        }
    }

    /**
     * Two files clear of the hull's sides by the same gap, ranks level with its middle and running aft, the
     * left and right files mirror images — for a narrow and a wide hull alike.
     */
    private static void escortFiles() {
        Vec3 vip = new Vec3(10.0, 70.0, 10.0);
        float yaw = 180.0f; // facing north (-Z): right is +X
        FormationFrame frame = FormationFrame.facing(vip.x, vip.y, vip.z, 0.0, -1.0);
        for (double width : new double[] {1.4, 4.6}) {
            double side = width / 2.0 + 2.5;
            for (int rank = 0; rank < 6; rank++) {
                Vec3 s = EscortGoal.escortSlot(vip, yaw, width, rank);
                double lat = frame.lateral(s.x, s.z);
                assert Math.abs(Math.abs(lat) - side) < 1e-9 : "w=" + width + " rank " + rank + " file offset " + lat;
                assert (rank % 2 == 0) == (lat > 0) : "even ranks on the left file";
                assert Math.abs(frame.ahead(s.x, s.z) + 3.0 * (rank / 2)) < 1e-9 : "rank " + rank + " row position";
            }
            Vec3 left = EscortGoal.escortSlot(vip, yaw, width, 0);
            Vec3 right = EscortGoal.escortSlot(vip, yaw, width, 1);
            assert Math.abs(frame.lateral(left.x, left.z) + frame.lateral(right.x, right.z)) < 1e-9 : "files mirror";
        }
    }

    private InfantryFormationSelfCheck() {}
}

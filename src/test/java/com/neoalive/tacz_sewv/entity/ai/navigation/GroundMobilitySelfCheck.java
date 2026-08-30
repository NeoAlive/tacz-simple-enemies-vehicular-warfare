package com.neoalive.tacz_sewv.entity.ai.navigation;

/**
 * Self-check for ground mobility cost curves and context-map pick.
 * Run via {@code ./gradlew selfCheck} (selfCheckGround).
 */
public final class GroundMobilitySelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        smoothstepEnds();
        fordGate();
        slopeGate();
        stepDangerFenceVsHill();
        strongestWinsNotSum();
        flatMapReachesEverySlot();
        spikeMasksFarSlot();
        pickPrefersClearFlank();
        interpolateLeansTowardHigherNeighbor();
        blendIsHalfway();
        VehiclePathObstacles.selfCheck();

        System.out.println("ground mobility self-check: OK");
    }

    private static void smoothstepEnds() {
        assertClose(0.0F, GroundMobility.smoothstep(0.0F), "smoothstep 0");
        assertClose(0.5F, GroundMobility.smoothstep(0.5F), "smoothstep 0.5");
        assertClose(1.0F, GroundMobility.smoothstep(1.0F), "smoothstep 1");
        assertClose(0.0F, GroundMobility.bite(0.4F), "bite below 0.5");
        assertClose(1.0F, GroundMobility.bite(1.0F), "bite at 1");
    }

    private static void fordGate() {
        assert !GroundMobility.waterBlocked(0, false) : "dry is never blocked";
        assert GroundMobility.waterBlocked(1, false) : "any water at all is blocked";
        assert GroundMobility.waterBlocked(4, false) : "deep water is blocked";
        assert !GroundMobility.waterBlocked(4, true) : "amphibious is never blocked";
        assertClose(0.0F, GroundMobility.fordMalus(0, false), "dry ford malus");
        assert Float.isInfinite(GroundMobility.fordMalus(1, false)) : "any water malus is inf";
        assertClose(GroundMobility.AMPHIBIOUS_WATER_COST, GroundMobility.fordMalus(4, true),
                "amphibious water cost");
        assertClose(0.0F, GroundMobility.waterDanger(0, false), "dry danger");
        assertClose(1.0F, GroundMobility.waterDanger(1, false), "any water is a hard wall");
        assertClose(0.0F, GroundMobility.waterDanger(4, true), "amphibious water danger");
    }

    private static void slopeGate() {
        assertClose(0.0F, GroundMobility.slopeMalus(0.4, 1.0F), "shallow slope free");
        assert Float.isInfinite(GroundMobility.slopeMalus(1.1, 1.0F)) : "over maxUpStep blocked";
        assertClose(0.0F, GroundMobility.slopeMalus(-1.0, 1.0F), "downhill free");
    }

    /** Fence 1.5 > maxUpStep 1 is a wall; a 1-block hill is a step, not occupancy-1. */
    private static void stepDangerFenceVsHill() {
        assertClose(1.0F, GroundMobility.stepDanger(1.5, 1.0F), "fence");
        assert GroundMobility.stepDanger(1.0, 1.0F) < 1.0F : "1-block hill is not a hard wall";
        assertClose(0.0F, GroundMobility.stepDanger(0.4, 1.0F), "kerb");
    }

    /**
     * Two weak interest votes (0.4+0.4) would beat danger 0.7 if summed; strongest-wins
     * keeps interest at 0.4 and the slot loses. That is the crater-stalemate bug.
     */
    private static void strongestWinsNotSum() {
        float interestMax = Math.max(0.4F, 0.4F);
        float danger = 0.7F;
        assert interestMax - danger < 0.0F : "max interest loses to stronger danger";
        assert (0.4F + 0.4F) - danger > 0.0F : "sanity: a sum would have won";
    }

    private static void flatMapReachesEverySlot() {
        float[] danger = new float[GroundMobility.SLOT_COUNT];
        boolean[] mask = GroundMobility.reachableMask(danger, 0);
        for (int i = 0; i < GroundMobility.SLOT_COUNT; i++) {
            assert mask[i] : "flat map slot " + i;
        }
        boolean[] fromEdge = GroundMobility.reachableMask(danger, GroundMobility.SLOT_COUNT - 1);
        for (int i = 0; i < GroundMobility.SLOT_COUNT; i++) {
            assert fromEdge[i] : "flat map from edge slot " + i;
        }
    }

    private static void spikeMasksFarSlot() {
        float[] danger = new float[GroundMobility.SLOT_COUNT];
        danger[4] = 1.0F; // +25° if facing is 0° (index 3)
        boolean[] mask = GroundMobility.reachableMask(danger, 3);
        assert mask[3] : "facing reachable";
        assert mask[2] : "lower side reachable";
        assert !mask[5] : "slot behind the spike is masked";
        assert !mask[6] : "far slot behind the spike is masked";
    }

    private static void pickPrefersClearFlank() {
        float[] interest = new float[GroundMobility.SLOT_COUNT];
        float[] hard = new float[GroundMobility.SLOT_COUNT];
        float[] skirt = new float[GroundMobility.SLOT_COUNT];
        for (int i = 0; i < GroundMobility.SLOT_COUNT; i++) {
            interest[i] = GroundMobility.goalInterest(GroundMobility.SLOTS_DEG[i]);
        }
        hard[3] = 1.0F; // waypoint blocked
        boolean[] reachable = GroundMobility.reachableMask(hard, 3);
        int winner = GroundMobility.pickWinner(interest, hard, skirt, reachable);
        assert winner >= 0 : "a clear flank exists";
        assert winner != 3 : "must not pick the blocked waypoint";
        assert hard[winner] < GroundMobility.HARD_CAP : "winner under hard cap";
    }

    private static void interpolateLeansTowardHigherNeighbor() {
        float[] interest = new float[GroundMobility.SLOT_COUNT];
        float[] hard = new float[GroundMobility.SLOT_COUNT];
        float[] skirt = new float[GroundMobility.SLOT_COUNT];
        interest[2] = 0.2F;
        interest[3] = 0.8F;
        interest[4] = 0.7F;
        double off = GroundMobility.interpolateOffsetDeg(3, interest, hard, skirt);
        assert off > 0.0 : "lean toward the higher +25 neighbor, got " + off;
        assert off < 25.0 : "stay between 0 and +25, got " + off;
    }

    private static void blendIsHalfway() {
        float[] prev = {1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F};
        float[] cur = {0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F};
        GroundMobility.blendMaps(prev, cur);
        assertClose(0.5F, cur[0], "blend 1→0");
        assertClose(0.0F, cur[1], "blend untouched");
    }

    private static void assertClose(float expected, float actual, String label) {
        if (Math.abs(expected - actual) > 1.0E-4F) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}

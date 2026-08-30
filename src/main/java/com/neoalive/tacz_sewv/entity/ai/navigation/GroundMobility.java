package com.neoalive.tacz_sewv.entity.ai.navigation;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.util.WarnOnce;

/**
 * Shared ground-mobility numbers: water, slope cost, context-map pick. A non-amphibious hull
 * never enters water at all — no fording, no depth grading, no already-wet exception. Used by
 * {@link GroundVehicleNodeEvaluator} (A* malus) and
 * {@link com.neoalive.tacz_sewv.entity.ai.sensor.GroundTerrainSensor} (whisker maps).
 */
public final class GroundMobility {

    /** Soft cost reach around water; never a hard block. */
    public static final int DEEP_WATER_MARGIN = 3;

    /** Path malus for a dry node sitting next to water. */
    public static final float DEEP_MARGIN_PENALTY = 3.0F;

    /** Path malus at maxUpStep (smoothstep from 0.5×). */
    public static final float SLOPE_PENALTY = 4.0F;

    /** Amphibious still slightly prefers land when the dry detour is equal. */
    public static final float AMPHIBIOUS_WATER_COST = 0.5F;

    /** Context-map slots, angular order around the desired bearing. */
    public static final double[] SLOTS_DEG = {-75.0, -50.0, -25.0, 0.0, 25.0, 50.0, 75.0};
    public static final int SLOT_COUNT = SLOTS_DEG.length;

    /** Blend of previous map into the current one (Fray 18.6.1). */
    public static final float MAP_BLEND = 0.5F;

    /** Weak "keep facing" interest — strongest-wins, never enough to beat the waypoint. */
    public static final float FACING_INTEREST = 0.3F;

    /** Peer-skirt ranking weight. Must not be able to push a slot to the hard cap. */
    public static final float PEER_SKIRT_WEIGHT = 0.35F;

    /** Hard danger at or above this is impassable (mask + headingClear). */
    public static final float HARD_CAP = 1.0F;

    public static final double NO_FLOOR = Double.NEGATIVE_INFINITY;

    private GroundMobility() {}

    public static float smoothstep(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** Bite from 0.5× to 1× of the limit. */
    public static float bite(float t) {
        if (t <= 0.5F) return 0.0F;
        return smoothstep((t - 0.5F) / 0.5F);
    }

    public static boolean waterBlocked(int depth, boolean amphibious) {
        return !amphibious && depth > 0;
    }

    public static float fordMalus(int depth, boolean amphibious) {
        if (depth <= 0) return 0.0F;
        if (amphibious) return AMPHIBIOUS_WATER_COST;
        return Float.POSITIVE_INFINITY;
    }

    public static float slopeMalus(double rise, float maxUpStep) {
        if (rise <= 0.0 || maxUpStep <= 0.0F) return 0.0F;
        if (rise > maxUpStep) return Float.POSITIVE_INFINITY;
        return SLOPE_PENALTY * bite((float) (rise / maxUpStep));
    }

    /** 0–1 danger from a vertical jump. 1 iff {@code delta > maxUpStep}. */
    public static float stepDanger(double delta, float maxUpStep) {
        if (delta <= 0.0 || maxUpStep <= 0.0F) return 0.0F;
        if (delta > maxUpStep) return 1.0F;
        return Math.min(0.99F, bite((float) (delta / maxUpStep)));
    }

    public static float waterDanger(int depth, boolean amphibious) {
        return waterBlocked(depth, amphibious) ? 1.0F : 0.0F;
    }

    public static float maxUpStepOf(VehicleEntity v) {
        return Math.max(1.0F, v.maxUpStep());
    }

    public static boolean isAmphibious(VehicleEntity v) {
        try {
            EngineInfo engine = v.getEngineInfo();
            if (engine == null) return false;
            return engine instanceof EngineInfo.Ship || engine.getBuoyancy() > 0.0;
        } catch (Exception e) {
            WarnOnce.warn(SewvDiag.LOG, "amphibious:" + v.getId(),
                    "Failed to read engine buoyancy for " + v.getType().getDescriptionId(), e);
            return false;
        }
    }

    /** Consecutive water cells from {@code y} downward, capped at 3 (diagnostic value only —
     * any depth above 0 is equally blocked). */
    public static int waterDepth(BlockGetter level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        int depth = 0;
        for (int dy = 0; dy <= 3; dy++) {
            if (!level.getFluidState(pos.set(x, y - dy, z)).is(FluidTags.WATER)) break;
            depth++;
        }
        return depth;
    }

    /**
     * First non-empty collision scanning {@code fromY} down to {@code toY}, as an unfloored
     * world Y (block Y + shape max). {@link #NO_FLOOR} if nothing solid.
     */
    public static double footingTop(BlockGetter level, BlockPos.MutableBlockPos pos,
                                   int x, int z, int fromY, int toY) {
        for (int y = fromY; y >= toY; y--) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                return y + shape.max(Direction.Axis.Y);
            }
        }
        return NO_FLOOR;
    }

    public static void blendMaps(float[] prev, float[] cur) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            cur[i] = MAP_BLEND * prev[i] + (1.0F - MAP_BLEND) * cur[i];
        }
    }

    /**
     * F1 walk: from the facing slot, expand to a neighbor while its hard danger does not
     * increase. A flat map (all zeros) therefore reaches every slot; a spike still masks
     * whatever sits behind it.
     */
    public static boolean[] reachableMask(float[] hardDanger, int facingSlot) {
        boolean[] mask = new boolean[SLOT_COUNT];
        mask[facingSlot] = true;
        for (int i = facingSlot; i < SLOT_COUNT - 1; i++) {
            if (hardDanger[i + 1] <= hardDanger[i]) mask[i + 1] = true;
            else break;
        }
        for (int i = facingSlot; i > 0; i--) {
            if (hardDanger[i - 1] <= hardDanger[i]) mask[i - 1] = true;
            else break;
        }
        return mask;
    }

    public static float slotScore(int i, float[] interest, float[] hardDanger, float[] peerSkirt) {
        return interest[i] - hardDanger[i] - PEER_SKIRT_WEIGHT * peerSkirt[i];
    }

    /**
     * Highest {@code interest - hard - skirt} among reachable slots under the hard cap.
     * {@code -1} when nothing is drivable.
     */
    public static int pickWinner(float[] interest, float[] hardDanger, float[] peerSkirt, boolean[] reachable) {
        int best = -1;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!reachable[i] || hardDanger[i] >= HARD_CAP) continue;
            float score = slotScore(i, interest, hardDanger, peerSkirt);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Gradient around the winning slot → a heading between discrete offsets. */
    public static double interpolateOffsetDeg(int winner, float[] interest, float[] hardDanger, float[] peerSkirt) {
        double off = SLOTS_DEG[winner];
        if (winner <= 0 || winner >= SLOT_COUNT - 1) return off;
        float wL = Math.max(0.0F, slotScore(winner - 1, interest, hardDanger, peerSkirt));
        float w0 = Math.max(0.0F, slotScore(winner, interest, hardDanger, peerSkirt));
        float wR = Math.max(0.0F, slotScore(winner + 1, interest, hardDanger, peerSkirt));
        float sum = wL + w0 + wR;
        if (sum < 1.0E-6F) return off;
        return (wL * SLOTS_DEG[winner - 1] + w0 * SLOTS_DEG[winner] + wR * SLOTS_DEG[winner + 1]) / sum;
    }

    /** Goal interest: cosine peak at 0° (the waypoint), zero past 90°. */
    public static float goalInterest(double slotDeg) {
        return (float) Math.max(0.0, Math.cos(Math.toRadians(slotDeg)));
    }
}

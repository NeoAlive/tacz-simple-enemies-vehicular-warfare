package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.support.BailOutSupport;
import com.neoalive.tacz_sewv.invasion.InvasionTags;

/**
 * Crew survival: when the hull a unit is riding is shot to pieces, the crew stops
 * fighting it, bails out, and scrambles clear of the wreck-to-be.
 *
 * <p>This is the last step of an escalation that starts in {@link DriveVehicleGoal}:
 * that goal breaks contact (smoke + fall back past the standoff ring) below a quarter
 * health while still crewing the vehicle. Below {@link #BAIL_HEALTH_FRACTION} the
 * vehicle is written off — the tank is a coffin and a coffin isn't worth crewing, so
 * everyone gets out. It applies to the whole crew without iterating passengers: every
 * unit that can crew carries its own instance of this goal (see
 * {@link VehicleAiGoals#addDriveGoals}), so each rolls its own escape bearing and the
 * crew fans out rather than clumping on one point.
 *
 * <p>Faction split, mirroring the rest of this mod: PmcUnitEntity is player-owned and
 * has an order queue, so it scrambles by taking a MOVE_TO_POSITION order (SEM's own
 * order goal walks it, and the player can see and override where it went). RU/US units
 * have no order system, so this goal drives their navigation directly.
 *
 * <p>Sandbag leave reuses the same scramble for RU/US only ({@link #requestSandbagScramble})
 * so auto-leave / dismiss does not stack infantry on the bag they just vacated.
 */
public class BailOutVehicleGoal extends Goal {

    // Bail below this fraction of the hull's max health. Under DriveVehicleGoal's
    // retreat threshold (0.25) on purpose: a crew retreats first and abandons only if
    // retreating didn't save it.
    private static final float BAIL_HEALTH_FRACTION = 0.15F;

    /** Pending RU/US scramble after leaving a sandbag — absolute, no vehicle required. */
    public static final String TAG_SANDBAG_SCRAMBLE = "sewv:sandbag_scramble";

    // Scramble just clear of the hull hitbox — get out of the wreck volume fast without
    // running across the map. Radii are added on top of the hull's half-extent.
    private static final double MIN_CLEARANCE = 2.0;
    private static final double MAX_CLEARANCE = 6.0;
    private static final int ESCAPE_CANDIDATES = 12;
    // Reject candidates this far above/below the hull — pathing onto a clifftop or
    // down a ravine burns the whole timeout going nowhere.
    private static final int MAX_ESCAPE_ELEVATION = 8;

    private static final double ARRIVE_DISTANCE_SQ = 4.0; // 2 blocks
    private static final double SCRAMBLE_SPEED = 1.3;     // navigation multiplier for RU/US
    // Transient attribute boost so PMC (order-driven) and RU/US both scramble faster;
    // restored in stop() so it cannot leak past the goal.
    private static final UUID SCRAMBLE_SPEED_UUID =
            UUID.fromString("a7c3e91f-4b2d-4e8a-9f01-6d5c8b3a2e14");
    private static final AttributeModifier SCRAMBLE_SPEED_MOD = new AttributeModifier(
            SCRAMBLE_SPEED_UUID, "sewv_bail_scramble", 0.4D, AttributeModifier.Operation.MULTIPLY_TOTAL);
    // Goals tick every other game tick, so these constants are ~2x wall clock:
    // ~20 s to reach the escape point, repath attempts ~1 s apart.
    private static final int MAX_SCRAMBLE_TICKS = 200;
    private static final int REPATH_INTERVAL = 10;

    private final AbstractUnit unit;
    private final boolean commandable;
    private BlockPos escapePos;
    private int scrambleTicks;
    /** True when this run was queued by a sandbag leave, not a dying hull. */
    private boolean sandbagScramble;

    public BailOutVehicleGoal(AbstractUnit unit) {
        this.unit = unit;
        this.commandable = unit instanceof PmcUnitEntity;
        // PMC scrambles through SEM's order goal, which needs the MOVE flag itself —
        // claiming it here would deadlock the very movement this goal asks for. RU/US
        // units are moved by this goal directly, so it takes MOVE to preempt the SEM
        // combat goals that would otherwise walk them back into the fight.
        this.setFlags(this.commandable ? EnumSet.noneOf(Flag.class) : EnumSet.of(Flag.MOVE));
    }

    /**
     * Queue the vehicle-bail scramble for an RU/US unit that just left a sandbag.
     * PMC is excluded — the player retasks them; stacking is not the failure mode.
     */
    public static void requestSandbagScramble(AbstractUnit unit) {
        if (unit instanceof PmcUnitEntity) return;
        if (!(unit instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity
                || unit instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity)) {
            return;
        }
        unit.getPersistentData().putBoolean(TAG_SANDBAG_SCRAMBLE, true);
    }

    private static boolean hasSandbagScramble(AbstractUnit unit) {
        return unit.getPersistentData().getBoolean(TAG_SANDBAG_SCRAMBLE);
    }

    private static void clearSandbagScramble(AbstractUnit unit) {
        unit.getPersistentData().remove(TAG_SANDBAG_SCRAMBLE);
    }

    @Override
    public boolean canUse() {
        if (hasSandbagScramble(this.unit) && this.unit.getVehicle() == null) {
            return true;
        }
        if (BailOutSupport.hasManualBail(this.unit)
                && this.unit.getVehicle() instanceof VehicleEntity) {
            return true;
        }
        if (!(this.unit.getVehicle() instanceof VehicleEntity vehicle)) return false;
        if (vehicle.getPersistentData().getBoolean(InvasionTags.SPAWN)) return false;
        return isWrittenOff(vehicle);
    }

    private static boolean isWrittenOff(VehicleEntity vehicle) {
        if (vehicle.isWreck()) return true;
        float max = vehicle.getMaxHealth();
        // A hull that reports no max health gives no fraction to compare against;
        // don't bail the crew out on a garbage reading.
        if (max <= 0.0F) return false;
        return vehicle.getHealth() <= max * BAIL_HEALTH_FRACTION;
    }

    @Override
    public void start() {
        this.scrambleTicks = 0;
        this.sandbagScramble = hasSandbagScramble(this.unit);
        clearSandbagScramble(this.unit);
        BailOutSupport.clearManualBail(this.unit);

        if (this.sandbagScramble) {
            this.escapePos = findEscapePosNear(this.unit.getX(), this.unit.getY(), this.unit.getZ(),
                    null);
            applyScrambleSpeed();
            if (this.escapePos != null) moveToEscapePos();
            return;
        }

        VehicleEntity vehicle = (VehicleEntity) this.unit.getVehicle();
        this.escapePos = BailOutSupport.triggerVehicleBail(this.unit, vehicle);
        applyScrambleSpeed();
        if (!this.commandable && this.escapePos != null) {
            moveToEscapePos();
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (this.escapePos == null) return false;
        if (this.scrambleTicks > MAX_SCRAMBLE_TICKS) return false;
        // Back in a seat (player re-boarded it, or it climbed into another hull) —
        // that's a live decision by something else, don't fight it.
        if (this.unit.getVehicle() != null) return false;
        if (this.unit instanceof PmcUnitEntity pmc && !ownsOrder(pmc)) return false;
        return this.unit.distanceToSqr(escapeTarget()) > ARRIVE_DISTANCE_SQ;
    }

    // True while the PMC unit is still running the escape order this goal issued. Any
    // other order — or a different move-to point — means the player has retasked it,
    // and their order outranks the scramble.
    private boolean ownsOrder(PmcUnitEntity pmc) {
        return pmc.getOrder() == OrderType.MOVE_TO_POSITION
                && escapeTarget().equals(pmc.getMoveToTarget());
    }

    @Override
    public void tick() {
        this.scrambleTicks++;
        if (this.commandable) return; // SEM's order goal is doing the walking

        // An unreachable escape point leaves navigation permanently "done", which
        // would repath every tick; throttle it the way BoardVehicleGoal does.
        if (this.unit.getNavigation().isDone() && this.scrambleTicks % REPATH_INTERVAL == 0) {
            moveToEscapePos();
        }
    }

    @Override
    public void stop() {
        clearScrambleSpeed();
        // The PMC order stands after arrival: the unit holds where it scrambled to,
        // and the player retasks it from there.
        if (!this.commandable) this.unit.getNavigation().stop();
        this.escapePos = null;
        this.scrambleTicks = 0;
    }

    private void applyScrambleSpeed() {
        AttributeInstance speed = this.unit.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        speed.removeModifier(SCRAMBLE_SPEED_UUID);
        speed.addTransientModifier(SCRAMBLE_SPEED_MOD);
    }

    private void clearScrambleSpeed() {
        AttributeInstance speed = this.unit.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SCRAMBLE_SPEED_UUID);
    }

    private Vec3 escapeTarget() {
        return Vec3.atBottomCenterOf(this.escapePos);
    }

    private void moveToEscapePos() {
        Vec3 target = escapeTarget();
        this.unit.getNavigation().moveTo(target.x, target.y, target.z, SCRAMBLE_SPEED);
    }

    /** Sandbag leave: scramble a few blocks clear of the bag with no hull volume to avoid. */
    private BlockPos findEscapePosNear(double cx, double cy, double cz,
                                       @Nullable net.minecraft.world.phys.AABB hullBox) {
        return findEscapePosNear(cx, cy, cz, hullBox, MIN_CLEARANCE, MAX_CLEARANCE);
    }

    private BlockPos findEscapePosNear(double cx, double cy, double cz,
                                       @Nullable net.minecraft.world.phys.AABB hullBox,
                                       double minR, double maxR) {
        Level level = this.unit.level();
        RandomSource random = this.unit.getRandom();

        double exitX = this.unit.getX() - cx;
        double exitZ = this.unit.getZ() - cz;
        // If somehow still at the centre, any bearing is "same side".
        boolean hasExitDir = exitX * exitX + exitZ * exitZ > 1.0e-4;

        BlockPos bestSame = null;
        double bestSameDistSq = Double.MAX_VALUE;
        BlockPos bestAny = null;
        double bestAnyDistSq = Double.MAX_VALUE;
        int refY = Mth.floor(cy);

        for (int i = 0; i < ESCAPE_CANDIDATES; i++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double radius = minR + random.nextDouble() * (maxR - minR);
            double dx = Math.cos(angle) * radius;
            double dz = Math.sin(angle) * radius;
            int x = Mth.floor(cx + dx);
            int z = Mth.floor(cz + dz);

            BlockPos candidate = standableGroundAt(level, x, z, refY);
            if (candidate == null) continue;
            // Must sit outside the hull volume — standing inside invites a path through it.
            if (hullBox != null && hullBox.intersects(candidate.getX(), candidate.getY(), candidate.getZ(),
                    candidate.getX() + 1.0, candidate.getY() + 2.0, candidate.getZ() + 1.0)) {
                continue;
            }

            double distSq = this.unit.distanceToSqr(Vec3.atBottomCenterOf(candidate));
            if (distSq < bestAnyDistSq) {
                bestAny = candidate;
                bestAnyDistSq = distSq;
            }
            if (hasExitDir && dx * exitX + dz * exitZ > 0.0 && distSq < bestSameDistSq) {
                bestSame = candidate;
                bestSameDistSq = distSq;
            }
        }
        return bestSame != null ? bestSame : bestAny;
    }

    // Surface position at (x, z) a unit can stand in, or null. Unloaded chunks are
    // rejected rather than loaded: a chunk load for a bail-out bearing we may well
    // discard is a lot of work for a die roll.
    private static BlockPos standableGroundAt(Level level, int x, int z, int hullY) {
        BlockPos column = new BlockPos(x, hullY, z);
        if (!level.isLoaded(column)) return null;

        BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        if (Math.abs(pos.getY() - hullY) > MAX_ESCAPE_ELEVATION) return null;

        BlockPos below = pos.below();
        BlockState ground = level.getBlockState(below);
        if (!ground.isFaceSturdy(level, below, Direction.UP)) return null; // water/leaves/air surface
        if (ground.getFluidState().is(FluidTags.LAVA)) return null;

        // Body clearance: the unit needs the surface block and the one above it free.
        return isPassable(level, pos) && isPassable(level, pos.above()) ? pos : null;
    }

    private static boolean isPassable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty()
                && !state.getFluidState().is(FluidTags.LAVA);
    }
}

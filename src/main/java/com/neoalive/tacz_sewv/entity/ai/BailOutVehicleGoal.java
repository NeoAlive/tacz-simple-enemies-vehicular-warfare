package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;

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
 */
public class BailOutVehicleGoal extends Goal {

    // Bail below this fraction of the hull's max health. Under DriveVehicleGoal's
    // retreat threshold (0.25) on purpose: a crew retreats first and abandons only if
    // retreating didn't save it.
    private static final float BAIL_HEALTH_FRACTION = 0.15F;

    // Scramble ring: far enough out that the vehicle brewing up (and whatever killed
    // it) is someone else's problem.
    private static final double MIN_ESCAPE_DISTANCE = 20.0;
    private static final double MAX_ESCAPE_DISTANCE = 28.0;
    // Random bearings sampled per bail-out; the nearest standable one wins, so the
    // direction is random but the unit doesn't cross the map to reach it.
    private static final int ESCAPE_CANDIDATES = 8;
    // Reject candidates this far above/below the hull — pathing onto a clifftop or
    // down a ravine burns the whole timeout going nowhere.
    private static final int MAX_ESCAPE_ELEVATION = 8;

    private static final double ARRIVE_DISTANCE_SQ = 4.0; // 2 blocks
    private static final double SCRAMBLE_SPEED = 1.3;     // running, not walking away
    // Goals tick every other game tick, so these constants are ~2x wall clock:
    // ~20 s to reach the escape point, repath attempts ~1 s apart.
    private static final int MAX_SCRAMBLE_TICKS = 200;
    private static final int REPATH_INTERVAL = 10;

    private final AbstractUnit unit;
    private final boolean commandable;
    private BlockPos escapePos;
    private int scrambleTicks;

    public BailOutVehicleGoal(AbstractUnit unit) {
        this.unit = unit;
        this.commandable = unit instanceof PmcUnitEntity;
        // PMC scrambles through SEM's order goal, which needs the MOVE flag itself —
        // claiming it here would deadlock the very movement this goal asks for. RU/US
        // units are moved by this goal directly, so it takes MOVE to preempt the SEM
        // combat goals that would otherwise walk them back into the fight.
        this.setFlags(this.commandable ? EnumSet.noneOf(Flag.class) : EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity vehicle)) return false;
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
        // canUse() just established the ride is a crippled VehicleEntity.
        VehicleEntity vehicle = (VehicleEntity) this.unit.getVehicle();
        this.escapePos = findEscapePos(vehicle);
        this.scrambleTicks = 0;

        this.unit.stopRiding();

        if (this.unit instanceof PmcUnitEntity pmc) {
            // Drop any pending board order, or BoardVehicleGoal would march the unit
            // straight back to the hull it just abandoned.
            IVehicleBoarder boarder = (IVehicleBoarder) pmc;
            boarder.tacz_sewv$setBoarding(false);
            boarder.tacz_sewv$setMountTargetId(-1);
            // setMoveToTarget flips the order to MOVE_TO_POSITION itself.
            if (this.escapePos != null) pmc.setMoveToTarget(escapeTarget());
        } else if (this.escapePos != null) {
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
        // The PMC order stands after arrival: the unit holds where it scrambled to,
        // and the player retasks it from there.
        if (!this.commandable) this.unit.getNavigation().stop();
        this.escapePos = null;
        this.scrambleTicks = 0;
    }

    private Vec3 escapeTarget() {
        return Vec3.atBottomCenterOf(this.escapePos);
    }

    private void moveToEscapePos() {
        Vec3 target = escapeTarget();
        this.unit.getNavigation().moveTo(target.x, target.y, target.z, SCRAMBLE_SPEED);
    }

    // A random bearing off the hull, at least MIN_ESCAPE_DISTANCE out, that the unit
    // can actually stand on. Samples several and keeps the nearest; null when nothing
    // around the vehicle is standable (canUse then simply won't hold the goal).
    private BlockPos findEscapePos(VehicleEntity vehicle) {
        Level level = this.unit.level();
        RandomSource random = this.unit.getRandom();

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < ESCAPE_CANDIDATES; i++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double radius = MIN_ESCAPE_DISTANCE
                    + random.nextDouble() * (MAX_ESCAPE_DISTANCE - MIN_ESCAPE_DISTANCE);
            int x = Mth.floor(vehicle.getX() + Math.cos(angle) * radius);
            int z = Mth.floor(vehicle.getZ() + Math.sin(angle) * radius);

            BlockPos candidate = standableGroundAt(level, x, z, vehicle.getBlockY());
            if (candidate == null) continue;

            double distSq = this.unit.distanceToSqr(Vec3.atBottomCenterOf(candidate));
            if (distSq < bestDistSq) {
                best = candidate;
                bestDistSq = distSq;
            }
        }
        return best;
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

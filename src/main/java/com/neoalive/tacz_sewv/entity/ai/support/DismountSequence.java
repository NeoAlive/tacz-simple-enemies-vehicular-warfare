package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.joml.Vector3d;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.goal.BailOutVehicleGoal;

/**
 * Puts an IFV's squad on the ground against ARMOUR: the hull halts, then the men step off its rear
 * corners in a staggered file and scramble clear. Owned by the driver's {@code DriveVehicleGoal},
 * which hands it every tick while {@link #active()} and issues nothing but a halt meanwhile.
 *
 * <p>Only against a SuperbWarfare hull, which is what {@code TargetCategory.VEHICLE} means:
 * <b>a VehicleEntity is not a LivingEntity and can never be a mob's target</b>, so the test that
 * matters is whether the thing we are shooting at is RIDING one ({@link VehicleWeapons#classifyTarget}).
 * Against infantry the squad stays aboard — the hull's own guns cover that, and it is armour that
 * makes a loaded troop compartment a liability worth emptying. Who stays aboard is
 * {@link HullFacts#crewSeats} (driver + turret); players are never put out, whatever seat they took.
 *
 * <p>The sequence, modelled on the helicopter rappel:
 * <ol>
 * <li><b>HALTING</b> — every steering input released until the hull has bled off its way (or
 *     {@link #HALT_TIMEOUT_TICKS}, so a hull creeping on a slope still unloads). Nobody leaves a
 *     moving hull.
 * <li><b>EXITING</b> — one step every {@link DismountGeometry#EXIT_INTERVAL_TICKS}. An EVEN squad
 *     leaves in pairs, one from each rear corner; an ODD one in single file from the corner AWAY
 *     from the target, so the hull screens it. Each man is placed half a block above the ground
 *     at the corner, facing out along the diagonal.
 * <li>RU/US then run the point scramble ({@link BailOutVehicleGoal#requestDismountScramble}) out
 *     from the hull. A PMC keeps its standing order — the player owns where it goes next.
 * </ol>
 *
 * <p>One or two men draw an anti-tank launcher on the way out ({@link SmallArmsSupport}): the
 * first always, the second on {@code atSecondGunnerChance}, nobody after. That is what makes the
 * feature worth having — a TACZ rifle cannot scratch the armour the squad was put down to fight.
 *
 * <p>Once out they stay out: ordinary SEM infantry, picked up again by whatever puts a unit in a
 * seat. There is no recall, deliberately. This survives vehicle scavenging only because
 * {@code SeekAbandonedVehicleGoal} takes <em>completely empty</em> hulls — the IFV keeps its
 * driver and gunner, so the squad cannot drift back aboard.
 *
 * <p>Cost: idle is a phase read. The footprint is computed once per sequence (the hull is about to
 * stand still), and an exit spot is a bounded column probe — no OBB test, since
 * {@link DismountGeometry#MARGIN} keeps the corner off the hull by construction.
 */
public final class DismountSequence {

    /** Halt is done below this horizontal speed (blocks/tick). */
    private static final double HALTED_SPEED_SQ = 0.05 * 0.05;
    private static final long HALT_TIMEOUT_TICKS = 60L;
    /**
     * No new sequence this long after one ends: a rear seat refilled mid-fight would otherwise halt
     * the hull again at once. Also spaces out the retries while nobody aboard is eligible.
     */
    private static final long RETRIGGER_COOLDOWN_TICKS = 100L;
    /** Extra blocks tried outward along the diagonal when the corner column is fouled. */
    private static final int OUTWARD_STEPS = 2;
    /** Exit feet searched from hull feet + 1 down to hull feet - 2. */
    private static final int PROBE_UP = 1;
    private static final int PROBE_DOWN = 2;
    /** The "slab higher" the exit is placed at, which abstracts the climb down. */
    private static final double EXIT_LIFT = 0.5;
    /** Blocks past the hull's half-diagonal the scramble runs to. */
    private static final double SCRAMBLE_PAST_HULL = 5.0;
    /** An OBB vertex further than this from the hull centre is not a real hull part. */
    private static final double MAX_VERTEX_REACH_SQ = 32.0 * 32.0;

    private enum Phase { IDLE, HALTING, EXITING }

    private Phase phase = Phase.IDLE;
    /** Entity ids in exit order; allocated by {@link #begin} only. */
    private int[] queue;
    private int head;
    private boolean paired;
    /** Corner an odd file uses. */
    private int side;
    private double fwdX;
    private double fwdZ;
    private DismountGeometry.Footprint footprint;
    private long haltStarted;
    private long nextExitAt;
    private long cooldownUntil = Long.MIN_VALUE;
    private int armed;

    public boolean active() {
        return this.phase != Phase.IDLE;
    }

    /**
     * Start a sequence if anyone aboard is eligible. Refuses while cooling down, and while the hull
     * is in water — an amphibious hull afloat keeps its squad until it is ashore. ({@code onGround}
     * is deliberately not the test: OBB-collided hulls are not guaranteed to report it.)
     */
    public boolean begin(AbstractUnit driver, VehicleEntity hull, Set<Integer> crewSeats,
                         @Nullable LivingEntity target) {
        if (active()) return true;
        long now = driver.level().getGameTime();
        if (now < this.cooldownUntil) return false;
        if (hull.isInWater()) return false;

        List<int[]> riders = new ArrayList<>();
        for (Entity passenger : hull.getPassengers()) {
            if (!(passenger instanceof AbstractUnit rider) || passenger == driver) continue;
            int seat = hull.getSeatIndex(rider);
            if (seat < 0 || crewSeats.contains(seat)) continue;
            riders.add(new int[] {seat, rider.getId()});
        }
        if (riders.isEmpty()) {
            // Only players (or nothing readable) in the back — don't re-scan every tick.
            this.cooldownUntil = now + RETRIGGER_COOLDOWN_TICKS;
            return false;
        }
        riders.sort((a, b) -> Integer.compare(a[0], b[0]));
        this.queue = new int[riders.size()];
        for (int i = 0; i < this.queue.length; i++) this.queue[i] = riders.get(i)[1];
        this.head = 0;
        this.paired = this.queue.length % 2 == 0;

        Vector3f f = hull.getForwardDirection();
        double len = Math.sqrt(f.x() * f.x() + f.z() * f.z());
        if (len < 1.0E-6) {
            float yaw = hull.getYRot() * Mth.DEG_TO_RAD;
            this.fwdX = -Mth.sin(yaw);
            this.fwdZ = Mth.cos(yaw);
        } else {
            this.fwdX = f.x() / len;
            this.fwdZ = f.z() / len;
        }
        this.footprint = footprint(hull, this.fwdX, this.fwdZ);

        int away = target == null ? 0 : DismountGeometry.awaySide(this.fwdX, this.fwdZ,
                target.getX() - hull.getX(), target.getZ() - hull.getZ());
        this.side = away != 0 ? away : DismountGeometry.paritySide(hull.getId());

        this.armed = 0;
        this.haltStarted = now;
        this.phase = Phase.HALTING;
        return true;
    }

    /** One tick; the caller has already released every steering input this tick. */
    public void tick(AbstractUnit driver, VehicleEntity hull) {
        long now = driver.level().getGameTime();
        if (this.phase == Phase.HALTING) {
            boolean halted = hull.getDeltaMovement().horizontalDistanceSqr() < HALTED_SPEED_SQ;
            if (!halted && now - this.haltStarted < HALT_TIMEOUT_TICKS) return;
            this.phase = Phase.EXITING;
            this.nextExitAt = now;
        }
        if (now < this.nextExitAt) return;

        if (this.paired) {
            boolean right = exitNext(hull, 1);
            boolean left = exitNext(hull, -1);
            if (!right && !left) { finish(now); return; }
        } else if (!exitNext(hull, this.side)) {
            finish(now);
            return;
        }
        this.nextExitAt = now + DismountGeometry.EXIT_INTERVAL_TICKS;
        if (this.head >= this.queue.length) finish(now);
    }

    /** Drop the sequence; whoever is still aboard stays aboard until the next armour contact. */
    public void abort(long now) {
        if (active()) finish(now);
    }

    private void finish(long now) {
        this.phase = Phase.IDLE;
        this.queue = null;
        this.footprint = null;
        this.cooldownUntil = now + RETRIGGER_COOLDOWN_TICKS;
    }

    /** Put the next still-aboard man out at {@code corner}. False when the queue has run dry. */
    private boolean exitNext(VehicleEntity hull, int corner) {
        while (this.head < this.queue.length) {
            Entity e = hull.level().getEntity(this.queue[this.head++]);
            if (!(e instanceof AbstractUnit rider) || !rider.isAlive() || rider.getVehicle() != hull) continue;
            exitOne(hull, rider, corner);
            return true;
        }
        return false;
    }

    private void exitOne(VehicleEntity hull, AbstractUnit rider, int corner) {
        // issueAtWeapon answers false for a PMC (the player's loadout), an already-armed man and a
        // blank config id, so the count tracks weapons actually handed out rather than attempts.
        if (this.armed == 0 || (this.armed < EasyMode.maxAtGunners()
                && rider.getRandom().nextDouble() < EasyMode.atSecondGunnerChance())) {
            if (SmallArmsSupport.issueAtWeapon(rider)) this.armed++;
        }

        int used = corner;
        Vec3 spot = exitSpot(hull, corner);
        if (spot == null) {
            used = -corner;
            spot = exitSpot(hull, used);
        }
        rider.stopRiding(); // with no spot, SBW's own seat dismount point stands
        if (spot != null) {
            double[] out = DismountGeometry.outward(used, this.fwdX, this.fwdZ);
            float yaw = (float) (Mth.atan2(-out[0], out[1]) * Mth.RAD_TO_DEG);
            rider.moveTo(spot.x, spot.y, spot.z, yaw, rider.getXRot());
            rider.setYBodyRot(yaw);
            rider.setYHeadRot(yaw);
            rider.setDeltaMovement(Vec3.ZERO);
            rider.fallDistance = 0.0F;
        }

        if (!(rider instanceof PmcUnitEntity)) {
            DismountGeometry.Footprint fp = this.footprint;
            double clearance = Math.sqrt(fp.rear() * fp.rear() + fp.halfWidth() * fp.halfWidth())
                    + SCRAMBLE_PAST_HULL;
            BailOutVehicleGoal.requestDismountScramble(rider, hull.position(), clearance);
        }
    }

    /**
     * The {@code corner} exit, or one or two blocks further out along its diagonal if that column
     * is fouled. Feet height comes from a short scan around the hull's own feet rather than the
     * heightmap, which answers the roof under a bridge or a canopy.
     */
    @Nullable
    private Vec3 exitSpot(VehicleEntity hull, int corner) {
        double[] off = DismountGeometry.cornerOffset(this.footprint, corner, this.fwdX, this.fwdZ);
        double[] out = DismountGeometry.outward(corner, this.fwdX, this.fwdZ);
        Level level = hull.level();
        int hullFeet = Mth.floor(hull.getY());
        for (int step = 0; step <= OUTWARD_STEPS; step++) {
            double x = hull.getX() + off[0] + out[0] * step;
            double z = hull.getZ() + off[1] + out[1] * step;
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            for (int y = hullFeet + PROBE_UP; y >= hullFeet - PROBE_DOWN; y--) {
                BlockPos feet = new BlockPos(bx, y, bz);
                if (!level.isLoaded(feet)) return null;
                // Three blocks clear: the lift puts the head into the third.
                if (BailOutSupport.isStandable(level, feet) && BailOutSupport.isPassable(level, feet.above(2))) {
                    return new Vec3(x, y + EXIT_LIFT, z);
                }
            }
        }
        return null;
    }

    /**
     * The hull's extent in its own frame, from SBW's per-part OBBs. The turret is left out: a barrel
     * traversed over the engine deck would otherwise push the rear corner metres back.
     */
    private static DismountGeometry.Footprint footprint(VehicleEntity hull, double fwdX, double fwdZ) {
        double fallback = hull.getBbWidth() * 0.5;
        List<OBB> obbs;
        try {
            obbs = hull.getOBBs();
        } catch (Throwable ignored) {
            obbs = null;
        }
        if (obbs == null || obbs.isEmpty()) return DismountGeometry.footprint(null, 0, fwdX, fwdZ, fallback);
        double[] xz = new double[obbs.size() * 16];
        int n = 0;
        for (OBB obb : obbs) {
            if (obb.part == OBB.Part.TURRET || obb.part == OBB.Part.EMPTY) continue;
            for (Vector3d v : obb.getVertices()) {
                if (n * 2 + 1 >= xz.length) break;
                double dx = v.x - hull.getX();
                double dz = v.z - hull.getZ();
                // A cache not yet placed in the world (fresh hull) reads as garbage: use the box.
                if (dx * dx + dz * dz > MAX_VERTEX_REACH_SQ) {
                    return DismountGeometry.footprint(null, 0, fwdX, fwdZ, fallback);
                }
                xz[n * 2] = dx;
                xz[n * 2 + 1] = dz;
                n++;
            }
        }
        return DismountGeometry.footprint(xz, n, fwdX, fwdZ, fallback);
    }
}

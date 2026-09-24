package com.neoalive.tacz_sewv.entity.ai.support;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.bridge.ITerritoryPost;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.invasion.SweepAdvancement;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;
import com.neoalive.tacz_sewv.territory.TerritoryManager;

/**
 * Mechanics of a Territory Mode post: the 3x3-chunk leash, the on-foot hold band, and posting / releasing
 * a unit. Deciding <i>who</i> is posted <i>where</i> is {@code TerritoryManager}'s job; this only knows what
 * a posted unit does.
 *
 * <p>Every reader here is behind {@link ITerritoryPost#sewv$hasTerritoryPost}, a cached field read, so
 * the chunk maths is only ever paid by a unit that actually has a post.
 */
public final class TerritorySupport {

    /** On foot: within 3 blocks of the chunk centre = arrived. */
    public static final double ARRIVE_SQ = 3.0 * 3.0;
    /** On foot: once arrived, only drift past 6 blocks pulls the unit back (3-6 hysteresis band). */
    public static final double HOLD_SQ = 6.0 * 6.0;

    private TerritorySupport() {}

    public static double centreX(ITerritoryPost post) {
        return post.sewv$getTerritoryChunkX() * 16 + 8;
    }

    public static double centreZ(ITerritoryPost post) {
        return post.sewv$getTerritoryChunkZ() * 16 + 8;
    }

    /** Horizontal distance squared from {@code e} to the post's chunk centre. */
    public static double distSqToCentre(ITerritoryPost post, Entity e) {
        double dx = e.getX() - centreX(post);
        double dz = e.getZ() - centreZ(post);
        return dx * dx + dz * dz;
    }

    /**
     * Whose post governs {@code pmc}'s targeting: its own, or — for any other crewman or passenger of a
     * hull whose DRIVER is posted — the driver's. Every seat has its own target (SBW fires each seat from
     * {@code mob.target}, the turret aims from its controller's, and SEM's target goals set them directly),
     * so the leash has to be asked of the hull, not of the crewman. {@code null} = no post applies.
     *
     * <p>Cost for a unit with no post: an on-foot unit pays two field reads; a mounted one adds a
     * passenger-list read. No lookups, no allocation, no chunk maths.
     */
    @Nullable
    public static ITerritoryPost holder(PmcUnitEntity pmc) {
        ITerritoryPost own = (ITerritoryPost) pmc;
        if (own.sewv$hasTerritoryPost()) return own;
        if (!(pmc.getVehicle() instanceof VehicleEntity hull)) return null;
        return hull.getFirstPassenger() instanceof ITerritoryPost driver
                && driver != pmc && driver.sewv$hasTerritoryPost() ? driver : null;
    }

    /** The leash: the assigned chunk plus its 8 neighbours. */
    public static boolean inLeash(ITerritoryPost post, double x, double z) {
        return Math.abs((Mth.floor(x) >> 4) - post.sewv$getTerritoryChunkX()) <= 1
                && Math.abs((Mth.floor(z) >> 4) - post.sewv$getTerritoryChunkZ()) <= 1;
    }

    public static boolean inLeash(ITerritoryPost post, Entity e) {
        return inLeash(post, e.getX(), e.getZ());
    }

    /**
     * A posted unit holding a live target outside its leash. The leash bounds pursuit, not acquisition (spec 8.3): the
     * unit fires from where it stands, and while this is true SEM's cover / maneuver goals must not move it, or they
     * would walk it out of the leash toward the target by another door ({@link FollowLeash#enRouteToMove}).
     */
    public static boolean holdsOutOfLeashTarget(PmcUnitEntity pmc, ITerritoryPost post) {
        LivingEntity target = pmc.getTarget();
        return target != null && target.isAlive() && !inLeash(post, target);
    }

    /**
     * The one rule for "the post stands aside and SEM's own chase / combat may run" (Option B), pure so it can be
     * checked headlessly: only a unit that has REACHED its post, with a target inside the leash while itself inside
     * it. A unit still walking there never yields — it fires opportunistically and does not divert or chase, however
     * near or far the target — so a target it acquired before being posted cannot hold it off its route.
     */
    public static boolean yields(boolean reached, boolean targetInLeash, boolean selfInLeash) {
        return reached && targetInLeash && selfInLeash;
    }

    /**
     * {@link FollowLeash#leashed} for a posted on-foot unit ({@code true} = suppress SEM's chase). The chase is
     * allowed only under {@link #yields}: arrived, and target and unit both inside the leash. En route, or drifted
     * out of the leash, the unit fights from where it stands and lets the post goal walk it (back).
     */
    public static boolean suppressesChase(PmcUnitEntity pmc, ITerritoryPost post) {
        LivingEntity target = pmc.getTarget();
        if (target == null) return true;
        return !yields(post.sewv$hasReachedTerritoryPost(), inLeash(post, target), inLeash(post, pmc));
    }

    /** Records the arrival (within 3 blocks of the centre) that unlocks {@link #yields}. Sticky until re-post. */
    public static void noteArrival(PmcUnitEntity pmc, ITerritoryPost post) {
        if (!post.sewv$hasReachedTerritoryPost() && distSqToCentre(post, pmc) <= ARRIVE_SQ) {
            post.sewv$setReachedTerritoryPost(true);
        }
    }

    /**
     * Has {@code pmc} arrived at its post? On foot that is the sticky {@code reached} flag. A mounted driver never
     * gets the flag ({@code TerritoryPostGoal} does not run for a passenger, and a hull parks at least 8 blocks short
     * of the centre anyway), so it is the same test {@code DriveVehicleGoal.driveTo} parks on: horizontal distance from
     * the hull to the chunk centre within {@link VehicleTargeting#arrivalDistance}. Read-only: it sets no flag, so
     * nothing about yielding to contact changes.
     */
    public static boolean hasArrived(PmcUnitEntity pmc) {
        ITerritoryPost post = (ITerritoryPost) pmc;
        if (pmc.getVehicle() instanceof VehicleEntity hull) {
            double dx = centreX(post) - hull.getX();
            double dz = centreZ(post) - hull.getZ();
            double arrive = VehicleTargeting.arrivalDistance(pmc, hull);
            return dx * dx + dz * dz <= arrive * arrive;
        }
        return post.sewv$hasReachedTerritoryPost();
    }

    /**
     * Hold-band hysteresis, pure so it can be checked headlessly: holding once within 3 blocks of the
     * centre, and staying held until drift passes 6 blocks. {@code false} = the unit should be walking.
     */
    public static boolean nextHolding(boolean holding, double distSq) {
        return distSq <= ARRIVE_SQ || (holding && distSq <= HOLD_SQ);
    }

    /**
     * A live target and {@link #yields}: the post stands aside and SEM's own chase and combat behaviour run.
     * Shared by the post goal and {@link #enRoute}.
     */
    public static boolean yieldsToContact(PmcUnitEntity pmc, ITerritoryPost post) {
        LivingEntity target = pmc.getTarget();
        return target != null && target.isAlive()
                && yields(post.sewv$hasReachedTerritoryPost(), inLeash(post, target), inLeash(post, pmc));
    }

    /**
     * Walking (back) to the post out of contact — used ONLY to keep SEM's cover / flank / tactical-manager
     * MOVE from stealing the walk. Deliberately narrow: not while the post yields to an in-leash fight
     * (cover and flanking stay available there) and not inside the 6-block hold band, where the unit is
     * simply holding. It gates nothing else: the pull itself lives in {@code TerritoryPostGoal} and never asks.
     */
    public static boolean enRoute(PmcUnitEntity pmc, ITerritoryPost post) {
        return !post.sewv$isTerritoryLost()
                && !yieldsToContact(pmc, post)
                && distSqToCentre(post, pmc) > HOLD_SQ;
    }

    /**
     * Where a posted, mounted driver should head: the chunk centre, or {@code null} to hold (no post, a lost
     * post, or a centre chunk that is not loaded — a hull must not path into ground that has no height yet).
     *
     * <p>Always the centre, never "stay if close enough": {@code DriveVehicleGoal} already parks a hull at
     * {@code arrivalDistance} (hull width - 1 + 8, so at least 8 blocks, always past the 6-block on-foot
     * band), which is exactly the spec's {@code max(6, arrivalDistance)} hold radius and its arrival gate.
     * Mounted crews then fight FROM the post: {@link PatrolSupport#holdsCourseThroughContact} keeps the wheel
     * on this destination through contact.
     */
    @Nullable
    public static BlockPos mountedDestination(PmcUnitEntity pmc) {
        ITerritoryPost post = (ITerritoryPost) pmc;
        if (!post.sewv$hasTerritoryPost() || post.sewv$isTerritoryLost()) return null;
        int x = (int) Math.floor(centreX(post));
        int z = (int) Math.floor(centreZ(post));
        Level level = pmc.level();
        if (!level.hasChunkAt(x, z)) return null;
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /**
     * Posts {@code pmc} on chunk (cx, cz): drops every other state that owns this unit's movement, so a
     * stale cruise / patrol / escort / pathway cannot fight the post for MOVE or the destination, then
     * takes the hold stance.
     *
     * <p><b>Refuses (returns false, changes nothing) while the unit is an assignee of a running
     * Sweep &amp; Advance operation</b> — posting would silently pull it out of the sweep and could cancel
     * the whole operation. The caller reports that to the player; they must stop the sweep first.
     */
    public static boolean post(PmcUnitEntity pmc, int chunkX, int chunkZ) {
        if (SweepAdvancement.isAssignee(pmc)) return false;
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        PatrolSupport.clearSweepMembership(pmc, "TerritoryPost");
        ((IPathwayInfantry) pmc).sewv$clearPathway();
        EntrenchSupport.clear(pmc);
        GuardSupport.clearReach(pmc);
        ((ITerritoryPost) pmc).sewv$setTerritoryPost(chunkX, chunkZ);
        pmc.setOrder(OrderType.HOLD_POSITION);
        pmc.getNavigation().stop();
        return true;
    }

    /**
     * Order gate shared by every entry point that would hand a posted unit movement of its own: reports the
     * refusal (console via {@code OrderReport}, plus a rate-limited toast) and returns true. A posted unit is
     * commandable only through the RTS panel, and Cancel / dismount / bail stay exempt — see {@link #dropPost}.
     * {@code channel} names the order that was refused; it selects the toast text (lang key
     * {@code notification.tacz_sewv.territory.refused.<channel>}), so the player can tell what they cannot do.
     */
    public static boolean refuses(ServerPlayer issuer, PmcUnitEntity pmc, String channel) {
        if (!((ITerritoryPost) pmc).sewv$hasTerritoryPost()) return false;
        OrderReport.fail(issuer, OrderFailure.TERRITORY_POST, pmc);
        TerritoryManager.notifyRefused(issuer, pmc, channel);
        return true;
    }

    /**
     * Stand-down path (dismiss, downed lock, bail): stand-down always wins, so the post is simply removed —
     * no order, no navigation change; the caller owns those. Its coverage drops on the next sync.
     */
    public static void dropPost(PmcUnitEntity pmc) {
        ITerritoryPost post = (ITerritoryPost) pmc;
        if (post.sewv$hasTerritoryPost()) post.sewv$clearTerritoryPost();
    }

    /** Releases the post; the unit holds where it stands and does not path back or resume an order. */
    public static void release(PmcUnitEntity pmc) {
        ((ITerritoryPost) pmc).sewv$clearTerritoryPost();
        pmc.setOrder(OrderType.HOLD_POSITION);
        pmc.getNavigation().stop();
    }
}

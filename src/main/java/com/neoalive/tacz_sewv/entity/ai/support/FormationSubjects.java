package com.neoalive.tacz_sewv.entity.ai.support;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * The UNIT subject of a formation: a formation laid around an elected unit instead of the player, and the
 * executor of {@link FormationOrderPolicy} for every formation.
 *
 * <p><b>Shape.</b> The leader stands on the anchor the player would occupy and holds no slot of its own
 * ({@code layoutFor} is null for it), so it runs whatever order it is given exactly like any other unit. Its
 * only mark is {@link IFormationMember#sewv$getFormationLeads} and a formation index pinned to 0 — SEM's
 * MOVE_TO_POSITION sends index 0 straight to the target and everyone else looking for "the" index-0 man, who
 * must never be one of our followers. Followers carry the leader's UUID (stable across sessions, so it
 * persists) and keep their formation's FORM_* order for the formation's whole life; their anchor is the
 * leader's live position ({@link VehicleFormation#resolveAnchor}).
 *
 * <p><b>Orders.</b> A follower never takes an order itself: it goes to the leader, the formation re-heads on
 * a move, and Cease / Free Fire set the followers' hold-fire posture (their own order stays FORM_*, so that
 * posture is the only way they can hear it — {@link #ceaseFire}). A mod task (refill, entrench, escort, ...)
 * borrows a follower by changing its order; {@link #rejoin} puts it back once the task lets go. Only Cancel
 * ends a formation ({@link #release}); a leader lost to death, a downed lock or a change of kind is succeeded
 * in {@link FormationReform}.
 *
 * <p>Server thread only, except {@link #leaderAnchor}, which answers null on the client.
 */
public final class FormationSubjects {

    private FormationSubjects() {}

    public static final String MSG_TOO_FEW = "message.tacz_sewv.formation.too_few_for_leader";

    /** Follower → its resolved leader. Weak both ways: an unloaded unit takes its entry with it. */
    private static final Map<PmcUnitEntity, WeakReference<PmcUnitEntity>> LEADER_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** What a lost leader was doing, so its successor carries on with it. */
    private record LostLeader(OrderType order, @Nullable Vec3 moveTo, int attackTargetId) {}

    private static final Map<Long, LostLeader> LOST = new HashMap<>();
    /** Groups the re-form pass found empty; a leader still marked as leading one stops leading on its next order. */
    private static final Set<Long> ORPHANED = new HashSet<>();

    /** Leader id → game time and order it last took, so N followers routing one order in a tick apply it once. */
    private static final Map<Integer, String> LAST_APPLIED = new HashMap<>();

    // ---------------------------------------------------------------- dispatch

    /** A formed UNIT formation: who leads it and how many formed on it. */
    public record Led(PmcUnitEntity leader, int formed) {}

    /**
     * Forms {@code units} (already owned, alive and of one kind) around a leader elected from among them.
     *
     * @return the leader and how many formed on it; null when refused (the player has been told why)
     */
    @Nullable
    public static Led assignLed(ServerPlayer player, List<PmcUnitEntity> units, FormationShape shape, Direction axis,
                                float yaw, int rowSize, float width, float length, FormationComposition.Kind kind) {
        List<PmcUnitEntity> holders = holdersOf(units);
        if (holders.size() < 2) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(MSG_TOO_FEW)
                    .withStyle(net.minecraft.ChatFormatting.GRAY), true);
            return null;
        }
        PmcUnitEntity leader = elect(holders, units, player.getRandom().nextInt());

        // A unit is in one formation at a time: whatever each one was in before, it leaves now.
        for (PmcUnitEntity pmc : units) release(pmc);

        Entity leaderHull = leader.getVehicle();
        List<PmcUnitEntity> followers = new ArrayList<>(units.size());
        for (PmcUnitEntity pmc : units) {
            if (pmc == leader) continue;
            // The leader's own riders ride with it: they are on the anchor, not on a slot.
            if (leaderHull instanceof VehicleEntity && pmc.getVehicle() == leaderHull) continue;
            followers.add(pmc);
        }
        long group = VehicleFormation.nextGroup(player);
        makeLeader(leader, group);
        // "Post up": the leader holds where it stands and the formation is laid on it.
        applySemOrder(leader, OrderType.HOLD_POSITION, null, -1);
        int formed = VehicleFormation.assignAroundLeader(followers, shape, axis, yaw, rowSize, width, length, kind,
                leader.getRootVehicle().position(), leader.getUUID(), group);
        SewvDiag.command("unit-led formation {}: leader {} ({}), {} followers", group, leader.getId(),
                leader instanceof PmcCommanderEntity ? "commander" : "elected", followers.size());
        return new Led(leader, formed);
    }

    /** The election rule, shared by {@link #assignLed} and succession: a commander first, otherwise {@code roll}. */
    static PmcUnitEntity elect(List<PmcUnitEntity> holders, List<PmcUnitEntity> selection, int roll) {
        List<PmcUnitEntity> sorted = new ArrayList<>(holders);
        sorted.sort(Comparator.comparingInt(PmcUnitEntity::getId));
        boolean[] commander = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            PmcUnitEntity h = sorted.get(i);
            commander[i] = h instanceof PmcCommanderEntity || ridingCommander(h, selection);
        }
        return sorted.get(FormationOrderPolicy.electIndex(commander, roll));
    }

    /** A commander riding {@code driver}'s hull makes the driver the one who leads. */
    private static boolean ridingCommander(PmcUnitEntity driver, List<PmcUnitEntity> selection) {
        Entity hull = driver.getVehicle();
        if (!(hull instanceof VehicleEntity) || hull.getFirstPassenger() != driver) return false;
        for (PmcUnitEntity pmc : selection) {
            if (pmc != driver && pmc instanceof PmcCommanderEntity && pmc.getVehicle() == hull) return true;
        }
        return false;
    }

    /** Drivers and loose infantry: the units that stand for a slot. Riders never lead. */
    private static List<PmcUnitEntity> holdersOf(List<PmcUnitEntity> units) {
        List<PmcUnitEntity> out = new ArrayList<>(units.size());
        for (PmcUnitEntity pmc : units) {
            Entity hull = pmc.getVehicle();
            if (hull instanceof VehicleEntity && hull.getFirstPassenger() != pmc) continue;
            out.add(pmc);
        }
        return out;
    }

    private static void makeLeader(PmcUnitEntity leader, long group) {
        VehicleFormation.clear(leader);
        ((IFormationMember) leader).sewv$setFormationLeads(group);
        ORPHANED.remove(group);
        pinLeaderIndex(leader);
    }

    private static void pinLeaderIndex(PmcUnitEntity leader) {
        // No arm needed: the wipe this triggers clears follower tags the leader does not have, never LEADS.
        if (leader.getFormationIndex() != 0) leader.setFormationIndex(0);
    }

    // ---------------------------------------------------------------- order policy

    /** Where {@code pmc} stands in a formation, for {@link FormationOrderPolicy#decide}. */
    public static FormationOrderPolicy.Role roleOf(PmcUnitEntity pmc) {
        IFormationMember member = (IFormationMember) pmc;
        long leads = member.sewv$getFormationLeads();
        if (leads != 0L) {
            if (!ORPHANED.remove(leads)) return FormationOrderPolicy.Role.LEADER;
            member.sewv$setFormationLeads(0L);
        }
        FormationAnchorMode mode = VehicleFormation.anchorModeOf(pmc);
        if (mode == null) return FormationOrderPolicy.Role.NONE;
        return mode == FormationAnchorMode.UNIT ? FormationOrderPolicy.Role.FOLLOWER
                : FormationOrderPolicy.Role.PLAYER_MEMBER;
    }

    /**
     * Runs a player's SEM order through the formation policy. Every entry point calls this before it applies
     * an order itself.
     *
     * @param viaSemPacket true from SEM's own order packet, whose handler goes on to call setFormationIndex —
     *                     the one path that needs the carry flag armed
     * @return true when the order was handled here (applied to a leader, or refused): the caller must not apply
     *         it to {@code pmc} as well; false to let the caller apply it as usual
     */
    public static boolean routeOrder(ServerPlayer sender, PmcUnitEntity pmc, OrderType order,
                                     @Nullable Vec3 targetPos, int targetEntityId, boolean viaSemPacket) {
        FormationOrderPolicy.Role role = roleOf(pmc);
        switch (FormationOrderPolicy.decide(role, order)) {
            case APPLY:
                return false;
            case CLEAR:
                VehicleFormation.clear(pmc);
                return false;
            case CARRY:
                if (role == FormationOrderPolicy.Role.LEADER) {
                    leaderTakes(pmc, ((IFormationMember) pmc).sewv$getFormationLeads(), order, targetPos,
                            targetEntityId);
                    return true;
                }
                if (viaSemPacket) carryPlayerFormation(pmc, order, targetPos);
                return false;
            case ROUTE_TO_LEADER:
            default: {
                // A new order ends whatever task had borrowed this follower, and it goes back on its slot now.
                OrderStandDown.clearForNewOrder(pmc, "FormationSubjects.route");
                rejoin(pmc, true);
                // The rejoin may have found its formation gone (Cancelled while this unit was unloaded); it is a
                // free unit again and takes the order itself.
                if (!VehicleFormation.isUnitFollower(pmc)) return false;
                PmcUnitEntity leader = leaderOf(pmc);
                if (leader == null) {
                    OrderReport.fail(sender, OrderFailure.LEADER_UNREACHABLE, pmc);
                    return true;
                }
                leaderTakes(leader, ((IFormationMember) pmc).sewv$getFormationGroup(), order, targetPos,
                        targetEntityId);
                return true;
            }
        }
    }

    /**
     * FOLLOW_COMMANDER and MOVE_TO_POSITION inherit a standing player formation instead of dropping it: arm the
     * one-shot carry flag before SEM's handler calls setFormationIndex. A POSITION-anchored MOVE also re-freezes
     * the anchor to the new destination, so a fresh order relocates a parked formation.
     */
    private static void carryPlayerFormation(PmcUnitEntity pmc, OrderType order, @Nullable Vec3 targetPos) {
        IFormationMember member = (IFormationMember) pmc;
        member.sewv$armFormationCarry();
        if (order == OrderType.MOVE_TO_POSITION
                && FormationAnchorMode.byId(member.sewv$getFormationAnchorMode()) == FormationAnchorMode.POSITION
                && targetPos != null && !targetPos.equals(Vec3.ZERO)) {
            VehicleFormation.freezeAnchor(pmc, targetPos);
        }
    }

    /** The leader of {@code group} takes {@code order}; the formation turns and sets its fire posture with it. */
    private static void leaderTakes(PmcUnitEntity leader, long group, OrderType order, @Nullable Vec3 targetPos,
                                    int targetEntityId) {
        String stamp = leader.level().getGameTime() + ":" + order + ":" + targetPos + ":" + targetEntityId;
        if (stamp.equals(LAST_APPLIED.put(leader.getId(), stamp))) return;

        applySemOrder(leader, order, targetPos, targetEntityId);
        if (group == 0L) return;
        Boolean holdFire = FormationOrderPolicy.followerHoldFire(order);
        List<PmcUnitEntity> followers = followersOf(leader.level(), group);
        if (holdFire != null) {
            for (PmcUnitEntity f : followers) {
                ((IFormationMember) f).sewv$setFormationHoldFire(holdFire);
                if (holdFire) {
                    f.setTarget(null);
                    f.setAttackTargetId(-1);
                }
            }
        }
        if (FormationOrderPolicy.reheads(order) && targetPos != null && !targetPos.equals(Vec3.ZERO)) {
            Vec3 from = leader.getRootVehicle().position();
            float yaw = FormationOrderPolicy.bearingYaw(from.x, from.z, targetPos.x, targetPos.z);
            if (Float.isFinite(yaw)) rehead(followers, yaw, from);
        }
    }

    /**
     * What SEM's own order handler does to the unit it is addressed to, without its setFormationIndex — a
     * leader keeps index 0 — plus the stand-downs every player order brings.
     */
    static void applySemOrder(PmcUnitEntity pmc, OrderType order, @Nullable Vec3 targetPos, int targetEntityId) {
        OrderStandDown.clearForNewOrder(pmc, "FormationSubjects.leader");
        switch (order) {
            case ATTACK_THAT_TARGET -> pmc.setAttackTargetId(targetEntityId);
            case MOVE_TO_POSITION -> {
                if (targetPos != null) pmc.setMoveToTarget(targetPos);
            }
            case FREE_FIRE -> pmc.setAttackTargetId(-1);
            case CEASE_FIRE -> {
                pmc.setTarget(null);
                pmc.setAttackTargetId(-1);
                pmc.setLastHurtByMob(null);
            }
            default -> {}
        }
        pmc.releaseMovementLock();
        pinLeaderIndex(pmc);
        pmc.resetCommanderGoalCooldown();
        pmc.setOrder(order);
        if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == pmc) {
            CrewRadio.play(hull, CrewRadio.Line.ORDER_DISPATCH);
        }
    }

    /** Turns a formation onto {@code yaw} and re-deals its slots by least travel, keeping who can where they are. */
    private static void rehead(List<PmcUnitEntity> followers, float yaw, Vec3 anchor) {
        if (followers.isEmpty()) return;
        for (PmcUnitEntity f : followers) VehicleFormation.setHeading(f, yaw);
        VehicleFormation.rematch(followers, anchor);
    }

    // ---------------------------------------------------------------- leader resolution

    /** The live leader of a UNIT follower, or null when it is not loaded in the follower's level (or no longer leads). */
    @Nullable
    public static PmcUnitEntity leaderOf(PmcUnitEntity follower) {
        IFormationMember member = (IFormationMember) follower;
        return leaderOf(follower, member.sewv$getFormationLeader(), member.sewv$getFormationGroup());
    }

    @Nullable
    private static PmcUnitEntity leaderOf(PmcUnitEntity follower, @Nullable UUID uuid, long group) {
        if (uuid == null || !(follower.level() instanceof ServerLevel level)) return null;
        WeakReference<PmcUnitEntity> ref = LEADER_CACHE.get(follower);
        PmcUnitEntity cached = ref == null ? null : ref.get();
        if (cached != null && leads(cached, uuid, group, level)) return cached;
        if (level.getEntity(uuid) instanceof PmcUnitEntity found && leads(found, uuid, group, level)) {
            LEADER_CACHE.put(follower, new WeakReference<>(found));
            return found;
        }
        LEADER_CACHE.remove(follower);
        return null;
    }

    private static boolean leads(PmcUnitEntity pmc, UUID uuid, long group, Level level) {
        return pmc.isAlive() && !pmc.isRemoved() && pmc.level() == level && pmc.getUUID().equals(uuid)
                && ((IFormationMember) pmc).sewv$getFormationLeads() == group;
    }

    /** The anchor {@link VehicleFormation#resolveAnchor} lays a UNIT follower's slot on, or null to hold. */
    @Nullable
    static Vec3 leaderAnchor(PmcUnitEntity follower, @Nullable UUID uuid) {
        PmcUnitEntity leader = leaderOf(follower, uuid, ((IFormationMember) follower).sewv$getFormationGroup());
        return leader == null ? null : leader.getRootVehicle().position();
    }

    private static List<PmcUnitEntity> followersOf(Level level, long group) {
        List<PmcUnitEntity> out = new ArrayList<>();
        for (int id : FormationReform.members(group)) {
            if (level.getEntity(id) instanceof PmcUnitEntity pmc && pmc.isAlive()
                    && ((IFormationMember) pmc).sewv$getFormationGroup() == group) {
                out.add(pmc);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- fire posture

    /** Whether this unit is under a cease-fire: its own CEASE_FIRE order, or its formation leader's. */
    public static boolean ceaseFire(PmcUnitEntity pmc) {
        return pmc.getOrder() == OrderType.CEASE_FIRE || ((IFormationMember) pmc).sewv$getFormationHoldFire();
    }

    // ---------------------------------------------------------------- rejoin

    /** The re-form pass's call for every loaded member of a UNIT group. */
    static void rejoin(PmcUnitEntity pmc) {
        rejoin(pmc, false);
    }

    /**
     * Puts a UNIT follower back on its formation order once nothing else holds it. Every mod task hands a unit
     * back by dropping it to FREE_FIRE — an order a follower can otherwise never hold, since its SEM orders go
     * to the leader — so FREE_FIRE with no standing task is "the task is over". {@code force} skips that test:
     * the player has just given an order, which ends any task that was holding the unit.
     *
     * <p>A follower whose leader is loaded but no longer leads its group (released by Cancel while the follower
     * was unloaded) leaves the formation here.
     */
    static void rejoin(PmcUnitEntity pmc, boolean force) {
        IFormationMember member = (IFormationMember) pmc;
        if (!VehicleFormation.isUnitFollower(pmc) || !(pmc.level() instanceof ServerLevel level)) return;
        UUID uuid = member.sewv$getFormationLeader();
        if (leaderOf(pmc) == null && uuid != null && level.getEntity(uuid) instanceof PmcUnitEntity other
                && other.isAlive()) {
            VehicleFormation.clear(pmc);
            pmc.setOrder(OrderType.FREE_FIRE);
            return;
        }
        OrderType want = VehicleFormation.formationOrderOf(pmc);
        if (want == null || pmc.getOrder() == want) return;
        if (!force && pmc.getOrder() != OrderType.FREE_FIRE) return;
        if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) return;
        if (OrderStandDown.hasStandingTask(pmc)) return;
        pmc.releaseMovementLock();
        pmc.resetCommanderGoalCooldown();
        pmc.setOrder(want);
    }

    // ---------------------------------------------------------------- ending

    /**
     * Cancel: {@code pmc} leaves whatever formation it is in. A leader takes its whole formation with it —
     * every follower drops to FREE_FIRE — while a follower leaves alone and the rest close up.
     */
    public static void release(PmcUnitEntity pmc) {
        IFormationMember member = (IFormationMember) pmc;
        long leads = member.sewv$getFormationLeads();
        if (leads != 0L) {
            member.sewv$setFormationLeads(0L);
            dissolve(pmc.level(), leads);
        }
        VehicleFormation.clear(pmc);
    }

    private static void dissolve(Level level, long group) {
        for (PmcUnitEntity f : followersOf(level, group)) {
            VehicleFormation.clear(f);
            f.setOrder(OrderType.FREE_FIRE);
            if (f.getVehicle() == null) f.getNavigation().stop();
        }
        LOST.remove(group);
    }

    /**
     * A downed lock stands a unit down from everything. A downed follower leaves its formation; a downed leader
     * is succeeded, exactly as if it had died — it cannot lead from the ground.
     */
    public static void onDowned(PmcUnitEntity pmc) {
        IFormationMember member = (IFormationMember) pmc;
        long leads = member.sewv$getFormationLeads();
        if (leads != 0L) {
            noteLeaderLost(leads, pmc);
            member.sewv$setFormationLeads(0L);
            FormationReform.markDirty(leads);
        }
        VehicleFormation.clear(pmc);
    }

    // ---------------------------------------------------------------- succession (FormationReform)

    static void noteLeaderLost(long group, PmcUnitEntity leader) {
        LOST.put(group, snapshot(leader));
    }

    private static LostLeader snapshot(PmcUnitEntity leader) {
        return new LostLeader(leader.getOrder(), leader.getMoveToTarget(), leader.getAttackTargetId());
    }

    /** Server start/stop: nothing transient survives a session. */
    static void reset() {
        LEADER_CACHE.clear();
        LOST.clear();
        ORPHANED.clear();
        LAST_APPLIED.clear();
    }

    /** The re-form pass found {@code group} empty. */
    static void forgetGroup(long group) {
        LOST.remove(group);
        ORPHANED.add(group);
    }

    /**
     * Settles who leads {@code group} before its survivors are re-matched. Returns the leader, or null when it
     * is not loaded and not known to be lost (wait for it) — or when nobody is left who could lead, in which
     * case the survivors have been released and {@code survivors} is empty. A successor is removed from
     * {@code survivors}/{@code holders}, its own riders leave the formation, and everyone left is re-pointed.
     */
    @Nullable
    static PmcUnitEntity settleLeader(long group, List<PmcUnitEntity> survivors, List<PmcUnitEntity> holders,
                                      List<PmcUnitEntity> riders) {
        PmcUnitEntity any = survivors.get(0);
        LostLeader lost = LOST.remove(group);
        if (lost == null) {
            PmcUnitEntity leader = leaderOf(any);
            if (leader == null) return null;
            FormationComposition.Kind want = VehicleFormation.kindForUnit(any);
            if (want == null || FormationComposition.classify(leader) == want) return leader;
            // A driver who bailed out cannot lead a column of hulls: it steps down and keeps its order.
            lost = snapshot(leader);
            ((IFormationMember) leader).sewv$setFormationLeads(0L);
        }
        if (holders.isEmpty()) {
            for (PmcUnitEntity pmc : survivors) {
                VehicleFormation.clear(pmc);
                pmc.setOrder(OrderType.FREE_FIRE);
            }
            survivors.clear();
            riders.clear();
            return null;
        }
        PmcUnitEntity next = elect(holders, survivors, any.getRandom().nextInt());
        holders.remove(next);
        survivors.remove(next);
        Entity hull = next.getVehicle();
        if (hull instanceof VehicleEntity) {
            for (PmcUnitEntity rider : new ArrayList<>(riders)) {
                if (rider.getVehicle() != hull) continue;
                riders.remove(rider);
                survivors.remove(rider);
                VehicleFormation.clear(rider);
            }
        }
        makeLeader(next, group);
        OrderType order = lost.order() == null ? OrderType.HOLD_POSITION : lost.order();
        applySemOrder(next, order, lost.moveTo(), lost.attackTargetId());
        for (PmcUnitEntity pmc : survivors) VehicleFormation.setLeader(pmc, next.getUUID());
        SewvDiag.command("unit-led formation {}: leader succeeded by {} ({} survivors)", group, next.getId(),
                survivors.size());
        return next;
    }
}

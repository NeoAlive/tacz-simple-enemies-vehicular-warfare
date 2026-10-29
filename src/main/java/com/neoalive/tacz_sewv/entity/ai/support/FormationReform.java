package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.debug.SewvDiag;

/**
 * Closes the gaps a player formation is left with when it loses members.
 *
 * <p>Slots are dealt once, at the order, so a hull killed out of a wedge used to leave its slot empty for the
 * rest of the formation's life. Groups are tracked by the persistent id {@link VehicleFormation#assign} stamps
 * (transient here — re-learned as each formed unit's layout is first decoded, so a reload rebuilds it as units
 * tick). Nothing is polled while a formation is intact: a group is only re-examined after one of its members
 * dies, mounts or dismounts, or is ordered into something else ({@link VehicleFormation#clear}).
 *
 * <p>A dirty group's survivors are re-matched onto slots {@code 0..k-1} by the same least-travel matcher the
 * order used, with each holding its current slot where it can. A survivor whose mount no longer matches what
 * the formation was ordered as (a driver who bailed out of a hull formation) leaves it outright: its tags are
 * cleared and it falls back to SEM's own formation, rather than standing on a hull-scale slot as a rifleman.
 * Riders follow their hull's slot. A group whose owner is offline stays dirty and is retried — its anchor is
 * the owner. Server thread only.
 *
 * <p>A UNIT-anchored group ({@link FormationSubjects}) is anchored on its leader instead, so the owner does
 * not matter to it. A leader that dies, or stops being the kind its formation was ordered as, is succeeded
 * here by one of the survivors; a leader that is merely unloaded is waited for. These groups also get the
 * rejoin pass every interval, which puts a follower back on its formation order once a task that borrowed
 * it has ended — that pass, not an event, is what "pause, then rejoin" rests on.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class FormationReform {

    private static final int INTERVAL_TICKS = 20;

    private static final Map<Long, Set<Integer>> GROUPS = new HashMap<>();
    private static final Set<Long> DIRTY = new HashSet<>();
    /** The tracked groups that are UNIT-anchored — the only ones the rejoin pass walks. */
    private static final Set<Long> UNIT_GROUPS = new HashSet<>();
    private static int nextPass;

    private FormationReform() {}

    /** Note {@code pmc} as a member of {@code group}. Idempotent. */
    static void track(long group, PmcUnitEntity pmc, boolean unitAnchored) {
        if (group == 0L) return;
        GROUPS.computeIfAbsent(group, g -> new HashSet<>()).add(pmc.getId());
        if (unitAnchored) UNIT_GROUPS.add(group);
    }

    /** The unit ids currently known to be in {@code group} (a copy). */
    static List<Integer> members(long group) {
        Set<Integer> ids = GROUPS.get(group);
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    /** Re-examine {@code group} on the next pass. */
    static void markDirty(long group) {
        if (group != 0L && GROUPS.containsKey(group)) DIRTY.add(group);
    }

    private static void forget(long group) {
        GROUPS.remove(group);
        if (UNIT_GROUPS.remove(group)) FormationSubjects.forgetGroup(group);
    }

    /** {@code unitId} has left {@code group}; its neighbours close up on the next pass. */
    static void departed(long group, int unitId) {
        Set<Integer> members = GROUPS.get(group);
        if (members == null) return;
        members.remove(unitId);
        DIRTY.add(group);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof PmcUnitEntity pmc && !pmc.level().isClientSide) {
            long group = ((IFormationMember) pmc).sewv$getFormationGroup();
            if (group != 0L) departed(group, pmc.getId());
            long leads = ((IFormationMember) pmc).sewv$getFormationLeads();
            if (leads != 0L) {
                FormationSubjects.noteLeaderLost(leads, pmc);
                markDirty(leads);
            }
        }
    }

    /** A mount change can change what a member is; the pass decides whether it still belongs. */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getEntityMounting() instanceof PmcUnitEntity pmc && !pmc.level().isClientSide) {
            markDirty(((IFormationMember) pmc).sewv$getFormationGroup());
            markDirty(((IFormationMember) pmc).sewv$getFormationLeads());
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reset();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        reset();
    }

    private static void reset() {
        GROUPS.clear();
        DIRTY.clear();
        UNIT_GROUPS.clear();
        FormationSubjects.reset();
        nextPass = 0;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || (DIRTY.isEmpty() && UNIT_GROUPS.isEmpty())) return;
        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        if (now < nextPass) return;
        nextPass = now + INTERVAL_TICKS;
        for (Long group : new ArrayList<>(DIRTY)) {
            if (reform(server, group)) DIRTY.remove(group);
        }
        for (Long group : new ArrayList<>(UNIT_GROUPS)) {
            for (int id : members(group)) {
                PmcUnitEntity pmc = find(server, id);
                if (pmc != null) FormationSubjects.rejoin(pmc);
            }
        }
    }

    /** @return true when the group is settled (re-formed, intact or gone); false to retry next pass */
    private static boolean reform(MinecraftServer server, long group) {
        Set<Integer> ids = GROUPS.get(group);
        if (ids == null || ids.isEmpty()) {
            forget(group);
            return true;
        }
        List<PmcUnitEntity> survivors = new ArrayList<>();
        for (Integer id : new ArrayList<>(ids)) {
            PmcUnitEntity pmc = find(server, id);
            if (pmc == null || !pmc.isAlive() || ((IFormationMember) pmc).sewv$getFormationGroup() != group
                    || VehicleFormation.layoutFor(pmc) == null) {
                // Dead, unloaded, or already re-ordered. A group is only examined after something changed, so
                // an unknown member is treated as gone rather than waited on.
                ids.remove(id);
                continue;
            }
            survivors.add(pmc);
        }
        // Members whose mount no longer matches the formation leave it.
        for (PmcUnitEntity pmc : new ArrayList<>(survivors)) {
            VehicleFormation.Layout layout = VehicleFormation.layoutFor(pmc);
            FormationComposition.Kind now = FormationComposition.classify(pmc);
            if (layout.kind() != null && now != layout.kind()) {
                survivors.remove(pmc);
                ids.remove(pmc.getId());
                VehicleFormation.clear(pmc);
            }
        }

        List<PmcUnitEntity> holders = new ArrayList<>();
        List<PmcUnitEntity> riders = new ArrayList<>();
        for (PmcUnitEntity pmc : survivors) {
            Entity hull = pmc.getVehicle();
            if (hull instanceof VehicleEntity && hull.getFirstPassenger() != pmc) riders.add(pmc);
            else holders.add(pmc);
        }
        // A UNIT group's anchor is its leader. Settle who that is before anything is matched: a successor
        // leaves the slots for the anchor, which changes how many there are.
        Vec3 unitAnchor = null;
        if (UNIT_GROUPS.contains(group) && !survivors.isEmpty()) {
            PmcUnitEntity leader = FormationSubjects.settleLeader(group, survivors, holders, riders);
            if (leader == null) {
                // Not loaded and not known to be lost: wait for it. A group left with nobody to lead is gone.
                if (survivors.isEmpty()) {
                    forget(group);
                    return true;
                }
                return false;
            }
            unitAnchor = leader.getRootVehicle().position();
        }
        if (holders.isEmpty()) {
            if (survivors.isEmpty()) forget(group);
            return true;
        }
        holders.sort(Comparator.comparingInt(PmcUnitEntity::getId));
        int k = holders.size();
        int[] prev = new int[k];
        boolean contiguous = true;
        boolean[] seen = new boolean[k];
        for (int i = 0; i < k; i++) {
            int slot = holders.get(i).getFormationIndex();
            prev[i] = slot < k ? slot : -1;
            if (slot < 0 || slot >= k || seen[slot]) contiguous = false;
            else seen[slot] = true;
        }
        if (contiguous) return true;

        PmcUnitEntity first = holders.get(0);
        VehicleFormation.Layout layout = VehicleFormation.layoutFor(first);
        Vec3 anchor = unitAnchor;
        if (anchor == null) {
            ServerPlayer owner = first.getOwnerUUID() == null ? null
                    : server.getPlayerList().getPlayer(first.getOwnerUUID());
            if (owner == null) return false;
            anchor = VehicleFormation.resolveAnchor(first, owner.position());
            if (anchor == null) return false;
        }
        int[] slotOf = VehicleFormation.match(layout, anchor, holders, prev);
        SewvDiag.command("player formation {} re-forms: {} holders, slots {} -> {}", group, k,
                java.util.Arrays.toString(prev), java.util.Arrays.toString(slotOf));

        Map<Entity, Integer> hullSlots = new HashMap<>();
        for (int i = 0; i < k; i++) {
            PmcUnitEntity pmc = holders.get(i);
            if (pmc.getVehicle() instanceof VehicleEntity hull) hullSlots.put(hull, slotOf[i]);
            VehicleFormation.reslot(pmc, slotOf[i]);
        }
        for (PmcUnitEntity rider : riders) {
            Integer slot = hullSlots.get(rider.getVehicle());
            if (slot != null) VehicleFormation.reslot(rider, slot);
        }
        return true;
    }

    @Nullable
    static PmcUnitEntity find(MinecraftServer server, int id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof PmcUnitEntity pmc) return pmc;
        }
        return null;
    }
}

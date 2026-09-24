package com.neoalive.tacz_sewv.territory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.network.PacketHudNotification;

/**
 * Stands down units still carrying a Sweep &amp; Advance from before its invoker was removed.
 *
 * <p>The operation itself lives only in memory ({@code SweepAdvancement.ACTIVE}), so after a restart it is already
 * gone; what survives in an old save is the per-unit state: the infantry sweep tags, or a mounted crew's sweep area
 * task. Nothing would ever finish or clear those, so a unit would wander its rectangle forever, leashed to it and
 * refusing targets outside it. This clears them as each unit loads in (its NBT is already read at that point),
 * mirroring how {@code TerritoryManager.onJoin} drops a stale post, and tells the owner once.
 *
 * <p><b>Known consequence, deliberate:</b> AI-commander search &amp; destroy shares the infantry sweep tags
 * ({@code CommanderOrderDispatch}), so one that is live when its unit unloads is cleared on reload too. It cannot be
 * told apart at join: the commander's bookkeeping is keyed by network id, and a reloaded unit is a new entity with a
 * new id. That order is a 50 s one that its own timer would have ended anyway, and the commander re-issues it. Every
 * clear is logged server-side (PMC uuid, owner uuid, mode) so a report of AI-commander sweeps dying can be traced.
 *
 * <p>The toast is best-effort; the state change is not. It is held in memory only, so an owner who is offline across a
 * server restart never sees it, but their units are cleared all the same.
 */
public final class LegacySweepCleanup {

    /** Units cleared per owner, held until that owner is online to be told (in memory: at worst a toast is missed). */
    private static final Map<UUID, Integer> PENDING = new HashMap<>();

    private LegacySweepCleanup() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PmcUnitEntity pmc)) return;
        IVehiclePatrol patrol = (IVehiclePatrol) pmc;
        boolean infantry = ((ISweepInfantry) pmc).sewv$hasInfantrySweep();
        boolean mounted = patrol.sewv$hasSweepRect()
                || (patrol.sewv$getPatrolOrigin() != null && patrol.sewv$getPatrolMode() == IVehiclePatrol.MODE_SWEEP);
        if (!infantry && !mounted) return;

        PatrolSupport.clearSweepMembership(pmc, "legacyLoad");
        UUID owner = pmc.getOwnerUUID();
        TaczSewv.LOGGER.info("Cleared legacy sweep on PMC {} (owner {}, mode {})", pmc.getUUID(), owner,
                infantry ? "infantry" : "mounted");
        if (owner != null) PENDING.merge(owner, 1, Integer::sum);
    }

    /** Tells each owner once, as soon as they are online. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty() || event.getServer().getTickCount() % 100 != 0) return;
        for (Iterator<Map.Entry<UUID, Integer>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> e = it.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(e.getKey());
            if (player == null) continue;
            PacketHudNotification.sendTo(player,
                    Component.translatable("notification.tacz_sewv.legacy_sweep.title"),
                    Component.translatable("notification.tacz_sewv.legacy_sweep.body", e.getValue()));
            it.remove();
        }
    }
}

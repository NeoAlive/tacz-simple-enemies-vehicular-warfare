package com.neoalive.tacz_sewv.compat.lv;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Crews waiting for their LV hull to get past its first server tick — see {@link LvSpawner}.
 * Drained at the END of a server tick, by which point every hull spawned during the tick before
 * has run its own first tick (and its passenger purge). Imports no LV type, so it is safe to
 * register on the bus unconditionally: without the mod the queue is simply never written.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class LvPendingCrews {

    /** Give up on a hull that has not ticked in this long (its chunk unloaded under it). */
    private static final int MAX_WAIT_TICKS = 200;

    private record Pending(ServerLevel level, Entity hull, TankFaction faction, @Nullable UUID ownerId, long queuedAt) {}

    private static final List<Pending> QUEUE = new ArrayList<>();

    private LvPendingCrews() {}

    static void enqueue(ServerLevel level, Entity hull, TankFaction faction, @Nullable UUID ownerId) {
        QUEUE.add(new Pending(level, hull, faction, ownerId, level.getGameTime()));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || QUEUE.isEmpty()) return;
        // Copy first: mounting adds entities, and a mod reacting to that could enqueue again.
        List<Pending> ready = new ArrayList<>();
        for (Iterator<Pending> it = QUEUE.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.hull().isRemoved()) {
                it.remove();
            } else if (p.hull().tickCount >= 1) {
                ready.add(p);
                it.remove();
            } else if (p.level().getGameTime() - p.queuedAt() > MAX_WAIT_TICKS) {
                p.hull().discard(); // never crewed: don't leave a scavenge magnet behind
                it.remove();
            }
        }
        for (Pending p : ready) {
            LvSpawner.mountCrew(p.level(), p.hull(), p.faction(), p.ownerId());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUE.clear();
    }
}

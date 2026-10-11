package com.neoalive.tacz_sewv.util;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Keeps a radio mission's IMPACT chunk ticking for a while. A shell or bomb that flies into an
 * unloaded chunk freezes there until someone walks over, so a grid mission past simulation
 * distance would designate and then never land. The crew's own chunk is {@link ChunkTicket}'s job;
 * this is only the far end.
 *
 * <p>All holds share one owner UUID that no entity has, so a hold that outlives a save is released
 * by {@link ChunkTicketSweep} on the next load — the same orphan path a dead crew's ticket takes.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class ImpactTickets {

    private static final UUID OWNER = UUID.nameUUIDFromBytes("tacz_sewv:radio_impact".getBytes());

    private record Hold(ServerLevel level, long chunk) {}

    /** Hold → game time it expires at. */
    private static final Map<Hold, Long> HOLDS = new HashMap<>();

    private ImpactTickets() {}

    /** Force-loads {@code chunk} until {@code untilGameTime}; a later deadline extends an existing hold. */
    public static void hold(ServerLevel level, ChunkPos chunk, long untilGameTime) {
        Hold key = new Hold(level, chunk.toLong());
        Long previous = HOLDS.get(key);
        if (previous == null) {
            ForgeChunkManager.forceChunk(level, TaczSewv.MODID, OWNER, chunk.x, chunk.z, true, true);
        }
        HOLDS.put(key, previous == null ? untilGameTime : Math.max(previous, untilGameTime));
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (HOLDS.isEmpty()) return;
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now % 20 != 0) return;
        for (Iterator<Map.Entry<Hold, Long>> it = HOLDS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Hold, Long> e = it.next();
            if (e.getKey().level() != level || e.getValue() > now) continue;
            ChunkPos pos = new ChunkPos(e.getKey().chunk());
            ForgeChunkManager.forceChunk(level, TaczSewv.MODID, OWNER, pos.x, pos.z, false, true);
            it.remove();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HOLDS.clear();
    }
}

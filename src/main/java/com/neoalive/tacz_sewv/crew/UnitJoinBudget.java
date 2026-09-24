package com.neoalive.tacz_sewv.crew;

import java.util.ArrayDeque;
import java.util.Queue;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Runs work at the end of the current server tick. {@code MinecraftServer.execute} is NOT a
 * deferral: on the server thread it runs the task inline, i.e. from inside whatever event called it
 * (e.g. {@code EntityJoinLevelEvent} mid chunk promotion, where a heightmap read deadlocks).
 */
public final class UnitJoinBudget {

    private static final Queue<Runnable> NEXT_TICK = new ArrayDeque<>();

    private UnitJoinBudget() {}

    public static void afterTick(Runnable task) {
        NEXT_TICK.add(task);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (int n = NEXT_TICK.size(); n > 0; n--) NEXT_TICK.poll().run();
    }
}

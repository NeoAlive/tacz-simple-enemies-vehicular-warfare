package com.neoalive.tacz_sewv.crew;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * Spreads {@code EntityJoinLevelEvent} work when many SEM units stream in at once — e.g. a
 * structure chunk or event dumping a full squad. Doing armor/NVG/identity (and companion rolls)
 * for every unit in the same tick turns that into a multi-second hitch next to heavy worldgen.
 *
 * <p>First few joins in a tick run immediately; the rest are drained a few per server tick.
 * Companion rolls are skipped entirely during a flood — they would enqueue yet more joins.
 */
public final class UnitJoinBudget {

    private static final int IMMEDIATE_PER_TICK = 2;
    private static final int DRAIN_PER_TICK = 2;
    private static final int MAX_DEFERRED = 256;

    private static int joinsThisTick;
    private static final Queue<Deferred> DEFERRED = new ArrayDeque<>();

    private record Deferred(int entityId, Consumer<AbstractUnit> work) {}

    private UnitJoinBudget() {}

    /** Call once per AbstractUnit join, before deciding what to do. */
    public static void noteJoin() {
        joinsThisTick++;
    }

    /** True once this tick has already spent its immediate budget — treat as a mass load. */
    public static boolean isFlooded() {
        return joinsThisTick > IMMEDIATE_PER_TICK;
    }

    /**
     * Runs {@code work} now if under budget, otherwise queues it against the unit's network id.
     * Dropped silently if the queue is full or the unit is gone when drained.
     */
    public static void runOrDefer(AbstractUnit unit, Consumer<AbstractUnit> work) {
        if (!isFlooded()) {
            work.accept(unit);
            return;
        }
        if (DEFERRED.size() >= MAX_DEFERRED) return;
        DEFERRED.add(new Deferred(unit.getId(), work));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        joinsThisTick = 0;

        if (DEFERRED.isEmpty()) return;
        var server = event.getServer();
        for (int i = 0; i < DRAIN_PER_TICK && !DEFERRED.isEmpty(); i++) {
            Deferred d = DEFERRED.poll();
            if (d == null) break;
            for (var level : server.getAllLevels()) {
                Entity e = level.getEntity(d.entityId());
                if (e instanceof AbstractUnit unit && unit.isAlive()) {
                    d.work().accept(unit);
                    break;
                }
            }
        }
    }
}

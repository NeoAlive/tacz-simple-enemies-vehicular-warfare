package com.neoalive.tacz_sewv.fpas;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * The targets a signalled unit takes after its current one dies. Transient, keyed by unit network id
 * like every id-bearing order here. Any new order drops it ({@code OrderStandDown},
 * {@code MixinPacketIssueOrder}), so the player always wins over the queue.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SignalQueue {

    private static final int SWEEP_INTERVAL_TICKS = 10;

    private record Entry(ServerLevel level, Deque<Integer> next) {}

    private static final Map<Integer, Entry> QUEUES = new HashMap<>();

    private SignalQueue() {}

    public static void set(PmcUnitEntity unit, List<Integer> next) {
        if (next.isEmpty() || !(unit.level() instanceof ServerLevel level)) {
            QUEUES.remove(unit.getId());
            return;
        }
        QUEUES.put(unit.getId(), new Entry(level, new ArrayDeque<>(next)));
    }

    public static void clear(PmcUnitEntity unit) {
        QUEUES.remove(unit.getId());
    }

    public static void clearAll() {
        QUEUES.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || QUEUES.isEmpty()) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SWEEP_INTERVAL_TICKS != 0) return;
        Iterator<Map.Entry<Integer, Entry>> it = QUEUES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Entry> e = it.next();
            Entry q = e.getValue();
            if (!(q.level.getEntity(e.getKey()) instanceof PmcUnitEntity pmc) || !pmc.isAlive()
                    || pmc.getOrder() != OrderType.ATTACK_THAT_TARGET) {
                it.remove();
                continue;
            }
            if (alive(q.level, pmc.getAttackTargetId())) continue;
            Integer next;
            while ((next = q.next.poll()) != null && !alive(q.level, next)) {}
            if (next == null) {
                it.remove();
                continue;
            }
            pmc.setAttackTargetId(next);
            pmc.setOrder(OrderType.ATTACK_THAT_TARGET);
            if (q.next.isEmpty()) it.remove();
        }
    }

    private static boolean alive(ServerLevel level, int id) {
        Entity t = level.getEntity(id);
        return t instanceof LivingEntity l && l.isAlive();
    }
}

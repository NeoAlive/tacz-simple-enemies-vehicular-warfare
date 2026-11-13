package com.neoalive.tacz_sewv.fpas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.goal.VehicleTargetScanGoal;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * A Signal order: SEM's own MOVE_TO_POSITION to a standoff point near the contact, watched here and
 * nothing more. It ends on the first of: the unit acquires a combatant, it arrives and has had
 * {@link #EVALUATE_TICKS} to look around, or the time limit. Every end leaves the unit on FREE_FIRE,
 * so its own AI fights whatever is there or goes back to what it was doing. No target is pinned.
 *
 * <p>Transient, keyed by unit network id. Any other order drops it silently ({@code OrderStandDown},
 * {@code MixinPacketIssueOrder}, or simply the order no longer being MOVE_TO_POSITION).
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SignalTasks {

    private static final int SWEEP_INTERVAL_TICKS = 10;
    /** Time at the point for the unit's own scan to notice something (acquisition delay tops out at 100). */
    private static final long EVALUATE_TICKS = 100;
    private static final double FOOT_ARRIVE = 3.0;

    private static final class Batch {
        final UUID owner;
        int members;
        int contacted;
        int clear;

        Batch(UUID owner) {
            this.owner = owner;
        }
    }

    private static final class Task {
        final ServerLevel level;
        final Batch batch;
        final Vec3 dest;
        final long deadline;
        long arrivedAt = Long.MIN_VALUE;

        Task(ServerLevel level, Batch batch, Vec3 dest, long deadline) {
            this.level = level;
            this.batch = batch;
            this.dest = dest;
            this.deadline = deadline;
        }
    }

    private static final Map<Integer, Task> TASKS = new HashMap<>();
    private static final List<Batch> BATCHES = new ArrayList<>();

    private SignalTasks() {}

    /** One dispatch: every unit's point, reported back as one batch. */
    public static void begin(UUID owner, Map<PmcUnitEntity, Vec3> plan) {
        Batch batch = new Batch(owner);
        for (Map.Entry<PmcUnitEntity, Vec3> e : plan.entrySet()) {
            PmcUnitEntity pmc = e.getKey();
            if (!(pmc.level() instanceof ServerLevel level)) continue;
            clear(pmc);
            pmc.setOrder(OrderType.MOVE_TO_POSITION);
            pmc.setMoveToTarget(e.getValue());
            long deadline = level.getGameTime() + SewvConfig.SIGNAL_MOVE_SECONDS.get() * 20L;
            TASKS.put(pmc.getId(), new Task(level, batch, e.getValue(), deadline));
            batch.members++;
        }
        if (batch.members > 0) BATCHES.add(batch);
    }

    public static boolean has(PmcUnitEntity pmc) {
        return TASKS.containsKey(pmc.getId());
    }

    public static void clear(Entity pmc) {
        Task t = TASKS.remove(pmc.getId());
        if (t != null) t.batch.members--;
    }

    public static void clearAll() {
        TASKS.clear();
        BATCHES.clear();
    }

    static boolean aircrew(PmcUnitEntity pmc) {
        return pmc.getVehicle() instanceof VehicleEntity v && (HullFacts.isPlaneHull(v) || HullFacts.isHelicopterHull(v));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SWEEP_INTERVAL_TICKS != 0) return;
        if (!TASKS.isEmpty()) sweep();
        if (!BATCHES.isEmpty()) closeBatches(server);
    }

    private static void sweep() {
        Iterator<Map.Entry<Integer, Task>> it = TASKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Task> e = it.next();
            Task t = e.getValue();
            if (!(t.level.getEntity(e.getKey()) instanceof PmcUnitEntity pmc) || !pmc.isAlive()
                    || pmc.getOrder() != OrderType.MOVE_TO_POSITION) {
                t.batch.members--;
                it.remove();
                continue;
            }
            LivingEntity target = pmc.getTarget();
            if (target != null && target.isAlive() && VehicleTargetScanGoal.isCombatant(target)) {
                t.batch.contacted++;
            } else if (!done(pmc, t)) {
                continue;
            } else {
                t.batch.clear++;
            }
            pmc.setOrder(OrderType.FREE_FIRE);
            if (pmc.getVehicle() == null) pmc.getNavigation().stop();
            t.batch.members--;
            it.remove();
        }
    }

    /** Arrived and looked around, or out of time. */
    private static boolean done(PmcUnitEntity pmc, Task t) {
        long now = t.level.getGameTime();
        if (now >= t.deadline) return true;
        Entity root = pmc.getRootVehicle();
        double arrive = root instanceof VehicleEntity v ? VehicleTargeting.arrivalDistance(pmc, v) : FOOT_ARRIVE;
        double dx = root.getX() - t.dest.x, dz = root.getZ() - t.dest.z;
        if (dx * dx + dz * dz > arrive * arrive) return false;
        if (t.arrivedAt == Long.MIN_VALUE) t.arrivedAt = now;
        return now - t.arrivedAt >= EVALUATE_TICKS;
    }

    private static void closeBatches(MinecraftServer server) {
        Iterator<Batch> it = BATCHES.iterator();
        while (it.hasNext()) {
            Batch b = it.next();
            if (b.members > 0) continue;
            it.remove();
            if (b.contacted + b.clear == 0) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(b.owner);
            if (p == null) continue;
            OrderReport.ok(p, Component.translatable("message.tacz_sewv.signal.complete", b.contacted, b.clear)
                    .withStyle(ChatFormatting.GREEN));
        }
    }
}

package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * The player's Suppress Area order (Steve's Army's {@code SUPPRESS_AREA}): put fire on a point for a
 * fixed budget, whether or not anyone there can be seen.
 *
 * <p>Transient, keyed by unit network id — the same rule as any id-bearing order here: an order from
 * before a reload is over. Cleared by any new order ({@code OrderStandDown}, {@code MixinPacketIssueOrder})
 * and by a bail-out.
 *
 * <p><b>One order, one report.</b> Every unit given the order in one go shares a {@link Batch}, and the
 * batch reports once when its budget runs out — "N units finished suppressing: R rounds" — rather than
 * one chat line per unit, which buried the chat for anyone ordering a platoon. A unit taken off the
 * order by a new one drops out of the count; if they all do, nothing is reported. The batch is closed
 * by a server-tick sweep rather than by its units, so a unit that died or unloaded mid-order cannot
 * hold the report back.
 *
 * <p><b>Self-defence wins.</b> A crew whose own target is inside {@link #CREW_SELF_DEFENCE} blocks, or a
 * rifleman whose target is inside {@link #FOOT_SELF_DEFENCE}, fights it instead; the order resumes
 * when that is over. Executed by {@code DriveVehicleGoal} for a crew (through {@link SuppressiveFire})
 * and by {@code SuppressAreaGoal} on foot.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SuppressOrders {

    /** How long an order puts fire on its point. */
    public static final long BUDGET_TICKS = 300;
    public static final double CREW_SELF_DEFENCE = 24.0;
    public static final double FOOT_SELF_DEFENCE = 5.0;
    private static final int SWEEP_INTERVAL_TICKS = 20;

    /** The units given one order together, and what they achieved between them. */
    private static final class Batch {
        @Nullable final UUID owner;
        /** Server tick count, not game time: one clock for a batch spanning dimensions. */
        final long dueTick;
        int members;
        int rounds;

        Batch(@Nullable UUID owner, long dueTick) {
            this.owner = owner;
            this.dueTick = dueTick;
        }
    }

    private record Order(BlockPos pos, long deadline, Batch batch) {}

    private static final Map<Integer, Order> ORDERS = new HashMap<>();
    private static final List<Batch> BATCHES = new ArrayList<>();

    private SuppressOrders() {}

    /** Give {@code units} one order on {@code pos}; they report back together. */
    public static void assign(Collection<? extends AbstractUnit> units, BlockPos pos, @Nullable UUID owner,
                              long gameTime, long serverTick) {
        if (units.isEmpty()) return;
        Batch batch = new Batch(owner, serverTick + BUDGET_TICKS);
        BlockPos at = pos.immutable();
        for (AbstractUnit unit : units) {
            clear(unit); // re-ordered: leaves its old batch's count
            ORDERS.put(unit.getId(), new Order(at, gameTime + BUDGET_TICKS, batch));
            batch.members++;
        }
        BATCHES.add(batch);
    }

    /** Silent cancel: a new order replaced it, and the unit drops out of its batch's report. */
    public static void clear(AbstractUnit unit) {
        Order o = ORDERS.remove(unit.getId());
        if (o != null) o.batch.members--;
    }

    /** The point {@code unit} is ordered to suppress, or null once there is none or its budget is spent. */
    @Nullable
    public static BlockPos active(AbstractUnit unit, long now) {
        Order o = ORDERS.get(unit.getId());
        return o == null || now >= o.deadline ? null : o.pos;
    }

    /** Whether {@code unit} should put the order aside to deal with its own target. */
    public static boolean selfDefence(AbstractUnit unit, double radius) {
        LivingEntity t = unit.getTarget();
        return t != null && t.isAlive() && t.distanceToSqr(unit) <= radius * radius;
    }

    /** A live on-foot order outranks SEM's rifle goal, except in self-defence. */
    public static boolean preemptsRifle(Mob mob) {
        return mob instanceof AbstractUnit unit && unit.getVehicle() == null
                && active(unit, unit.level().getGameTime()) != null
                && !selfDefence(unit, FOOT_SELF_DEFENCE);
    }

    public static void noteRound(AbstractUnit unit) {
        Order o = ORDERS.get(unit.getId());
        if (o != null) o.batch.rounds++;
    }

    public static void clearAll() {
        ORDERS.clear();
        BATCHES.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || BATCHES.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long tick = server.getTickCount();
        if (tick % SWEEP_INTERVAL_TICKS != 0) return;
        Iterator<Batch> it = BATCHES.iterator();
        while (it.hasNext()) {
            Batch batch = it.next();
            if (tick < batch.dueTick) continue;
            it.remove();
            ORDERS.values().removeIf(o -> o.batch == batch);
            report(server, batch);
        }
    }

    private static void report(MinecraftServer server, Batch batch) {
        if (batch.owner == null || batch.members <= 0) return;
        ServerPlayer player = server.getPlayerList().getPlayer(batch.owner);
        if (player == null) return;
        Component what = batch.rounds > 0
                ? Component.translatable("message.tacz_sewv.suppress.complete", batch.rounds)
                        .withStyle(ChatFormatting.GREEN)
                : Component.translatable("message.tacz_sewv.suppress.no_lane").withStyle(ChatFormatting.GRAY);
        OrderReport.ok(player, NetworkHandler.orderLine("suppress", batch.members, what));
    }
}

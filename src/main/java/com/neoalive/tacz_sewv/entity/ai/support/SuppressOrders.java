package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.HashMap;
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
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * The player's Suppress Area order (Steve's Army's {@code SUPPRESS_AREA}): put fire on a point for a
 * fixed budget, whether or not anyone there can be seen.
 *
 * <p>Transient, keyed by unit network id — the same rule as any id-bearing order here: an order from
 * before a reload is over. Cleared by any new order ({@code OrderStandDown}, {@code MixinPacketIssueOrder})
 * and by a bail-out. It ends on its own at {@link #BUDGET_TICKS} and reports once to the owner: rounds
 * fired, or that nothing could be fired.
 *
 * <p><b>Self-defence wins.</b> A crew whose own target is inside {@link #CREW_SELF_DEFENCE} blocks, or a
 * rifleman whose target is inside {@link #FOOT_SELF_DEFENCE}, fights it instead; the order resumes
 * when that is over. Executed by {@code DriveVehicleGoal} for a crew (through {@link SuppressiveFire})
 * and by {@code SuppressAreaGoal} on foot.
 */
public final class SuppressOrders {

    /** How long an order puts fire on its point. */
    public static final long BUDGET_TICKS = 300;
    public static final double CREW_SELF_DEFENCE = 24.0;
    public static final double FOOT_SELF_DEFENCE = 5.0;

    private static final class Order {
        final BlockPos pos;
        @Nullable final UUID owner;
        final long deadline;
        int rounds;

        Order(BlockPos pos, @Nullable UUID owner, long deadline) {
            this.pos = pos;
            this.owner = owner;
            this.deadline = deadline;
        }
    }

    private static final Map<Integer, Order> ORDERS = new HashMap<>();

    private SuppressOrders() {}

    public static void assign(AbstractUnit unit, BlockPos pos, @Nullable UUID owner, long now) {
        ORDERS.put(unit.getId(), new Order(pos.immutable(), owner, now + BUDGET_TICKS));
    }

    /** Silent cancel: a new order replaced it. */
    public static void clear(AbstractUnit unit) {
        ORDERS.remove(unit.getId());
    }

    /**
     * The point {@code unit} is ordered to suppress, or null. An order past its budget is closed here,
     * with its report, the first time anyone asks.
     */
    @Nullable
    public static BlockPos active(AbstractUnit unit, long now) {
        Order o = ORDERS.get(unit.getId());
        if (o == null) return null;
        if (now >= o.deadline) {
            ORDERS.remove(unit.getId());
            report(unit, o);
            return null;
        }
        return o.pos;
    }

    /** Whether {@code unit} should put the order aside to deal with its own target. */
    public static boolean selfDefence(AbstractUnit unit, double radius) {
        LivingEntity t = unit.getTarget();
        return t != null && t.isAlive() && t.distanceToSqr(unit) <= radius * radius;
    }

    /** A live on-foot order outranks SEM's rifle goal, except in self-defence. */
    public static boolean preemptsRifle(Mob mob) {
        return mob instanceof AbstractUnit unit && unit.getVehicle() == null
                && ORDERS.containsKey(unit.getId())
                && active(unit, unit.level().getGameTime()) != null
                && !selfDefence(unit, FOOT_SELF_DEFENCE);
    }

    public static void noteRound(AbstractUnit unit) {
        Order o = ORDERS.get(unit.getId());
        if (o != null) o.rounds++;
    }

    public static void clearAll() {
        ORDERS.clear();
    }

    private static void report(AbstractUnit unit, Order o) {
        if (o.owner == null || !unit.isAlive()) return;
        MinecraftServer server = unit.getServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(o.owner);
        if (player == null) return;
        Component what = o.rounds > 0
                ? Component.translatable("message.tacz_sewv.suppress.complete", o.rounds).withStyle(ChatFormatting.GREEN)
                : Component.translatable("message.tacz_sewv.suppress.no_lane").withStyle(ChatFormatting.GRAY);
        OrderReport.ok(player, NetworkHandler.orderLine("suppress", unit.getDisplayName(), what));
    }
}

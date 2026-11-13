package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MineKinds;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MineSites;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MineSpawner;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Deploy Minefield runner. One run per player; each engineer has a leg of planned mines it plants in
 * order. The walking and kneeling is {@code LayMineGoal}'s, which asks {@link #legFor} every tick;
 * this class owns the plan, the plant itself and the single report when the last leg ends.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class MinefieldTracker {

    private static final int POLL_INTERVAL = 20;

    private static final Map<UUID, Run> ACTIVE = new HashMap<>();
    private static final Map<Integer, Leg> BY_UNIT = new HashMap<>();

    private MinefieldTracker() {}

    /** One planned mine: feet block for a ground mine, the hanging cell plus face for a wall charge. */
    public record Planned(BlockPos pos, @Nullable Direction face, float yaw) {
        public Vec3 standAt() {
            return Vec3.atBottomCenterOf(this.pos);
        }
    }

    public static final class Leg {
        final Run run;
        public final int unitId;
        final Deque<Planned> todo;
        long slotDeadline = Long.MIN_VALUE;
        long plantAt = Long.MIN_VALUE;
        boolean done;

        Leg(Run run, int unitId, List<Planned> plan) {
            this.run = run;
            this.unitId = unitId;
            this.todo = new ArrayDeque<>(plan);
        }

        @Nullable
        public Planned current() {
            return this.todo.peekFirst();
        }

        public long plantAt() {
            return this.plantAt;
        }
    }

    static final class Run {
        final UUID playerId;
        final MineKinds.Kind kind;
        final List<Leg> legs = new ArrayList<>();
        long deadline;
        int laid;
        int skipped;

        Run(UUID playerId, MineKinds.Kind kind) {
            this.playerId = playerId;
            this.kind = kind;
        }
    }

    static Run newRun(ServerPlayer issuer, MineKinds.Kind kind) {
        return new Run(issuer.getUUID(), kind);
    }

    static void addLeg(Run run, PmcUnitEntity pmc, List<Planned> plan) {
        if (plan.isEmpty()) return;
        Leg leg = new Leg(run, pmc.getId(), plan);
        run.legs.add(leg);
    }

    static void start(Run run, long now, int skippedAtPlan) {
        run.deadline = now + com.neoalive.tacz_sewv.config.SewvConfig.MINEFIELD_TIMEOUT_TICKS.get();
        run.skipped += skippedAtPlan;
        ACTIVE.put(run.playerId, run);
        for (Leg leg : run.legs) BY_UNIT.put(leg.unitId, leg);
    }

    /** The leg the unit is working, or null. Called every tick by the goal, so it is one map read. */
    @Nullable
    public static Leg legFor(PmcUnitEntity pmc) {
        if (BY_UNIT.isEmpty()) return null;
        Leg leg = BY_UNIT.get(pmc.getId());
        return leg == null || leg.done ? null : leg;
    }

    public static boolean isActive(UUID playerId) {
        return ACTIVE.containsKey(playerId);
    }

    public static boolean isLaying(PmcUnitEntity pmc) {
        return legFor(pmc) != null;
    }

    public static boolean cancelPlayer(UUID playerId) {
        Run run = ACTIVE.remove(playerId);
        if (run == null) return false;
        for (Leg leg : run.legs) end(leg);
        return true;
    }

    /** A new order took the unit: what it had left to plant stays in its pockets. */
    public static void release(PmcUnitEntity pmc) {
        Leg leg = legFor(pmc);
        if (leg != null) end(leg);
    }

    private static void end(Leg leg) {
        if (!leg.done) leg.run.skipped += leg.todo.size();
        leg.todo.clear();
        leg.done = true;
        BY_UNIT.remove(leg.unitId, leg);
    }

    /** The slot is being walked to; a slot that takes too long is given up. */
    public static boolean slotExpired(Leg leg, long now, long budget) {
        if (leg.slotDeadline == Long.MIN_VALUE) leg.slotDeadline = now + budget;
        return now >= leg.slotDeadline;
    }

    public static void startPlant(Leg leg, long now, int plantTicks) {
        if (leg.plantAt == Long.MIN_VALUE) leg.plantAt = now + plantTicks;
    }

    /** Gives up on the current slot and moves to the next. */
    public static void skip(Leg leg) {
        if (leg.todo.pollFirst() != null) leg.run.skipped++;
        nextSlot(leg);
    }

    /**
     * Kneeling done: re-check the spot, take one mine out of the pockets and put it down. A spot
     * that went bad is skipped; empty pockets end the leg, since nothing more can be planted.
     */
    public static void plant(ServerLevel level, PmcUnitEntity pmc, Leg leg) {
        Planned p = leg.current();
        if (p == null) {
            end(leg);
            return;
        }
        MineKinds.Kind kind = leg.run.kind;
        boolean wall = kind.placement() == MineKinds.Placement.WALL;
        if (wall ? !MineSpawner.wallSurvives(level, kind, p.pos(), p.face(), pmc) : !MineSites.stillGround(level, p.pos())) {
            skip(leg);
            return;
        }
        IItemHandlerModifiable inv = RoleKit.inventory(pmc);
        int slot = inv == null ? -1 : findMine(inv, kind);
        if (slot < 0) {
            end(leg);
            return;
        }
        ItemStack one = inv.extractItem(slot, 1, false);
        if (one.isEmpty()) {
            end(leg);
            return;
        }
        boolean ok = wall
                ? MineSpawner.wall(level, kind, p.pos(), p.face(), pmc, leg.run.playerId)
                : MineSpawner.ground(level, kind, p.pos(), p.yaw(), pmc, leg.run.playerId);
        if (ok) {
            leg.run.laid++;
            leg.todo.pollFirst();
            nextSlot(leg);
        } else {
            ItemHandlerHelper.insertItem(inv, one, false);
            skip(leg);
        }
        if (leg.todo.isEmpty()) end(leg);
    }

    private static void nextSlot(Leg leg) {
        leg.slotDeadline = Long.MIN_VALUE;
        leg.plantAt = Long.MIN_VALUE;
        if (leg.todo.isEmpty()) end(leg);
    }

    /** Pocket slot holding the run's mine item, or -1. */
    static int findMine(IItemHandlerModifiable inv, MineKinds.Kind kind) {
        for (int slot = RoleKit.FIRST_POCKET; slot < inv.getSlots(); slot++) {
            if (isMine(inv.getStackInSlot(slot), kind)) return slot;
        }
        return -1;
    }

    static int countMines(@Nullable IItemHandlerModifiable inv, MineKinds.Kind kind) {
        if (inv == null) return 0;
        int n = 0;
        for (int slot = RoleKit.FIRST_POCKET; slot < inv.getSlots(); slot++) {
            ItemStack s = inv.getStackInSlot(slot);
            if (isMine(s, kind)) n += s.getCount();
        }
        return n;
    }

    static boolean isMine(ItemStack stack, MineKinds.Kind kind) {
        return !stack.isEmpty() && kind.item().equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % POLL_INTERVAL != 0) return;

        Iterator<Map.Entry<UUID, Run>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Run run = it.next().getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(run.playerId);
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                for (Leg leg : run.legs) end(leg);
                it.remove();
                continue;
            }
            boolean timedOut = level.getGameTime() >= run.deadline;
            boolean open = false;
            for (Leg leg : run.legs) {
                if (leg.done) continue;
                if (timedOut || !(level.getEntity(leg.unitId) instanceof PmcUnitEntity pmc) || !pmc.isAlive()
                        || pmc.getVehicle() != null || !PmcOwnerSupport.isOwner(player, pmc)) {
                    end(leg);
                    continue;
                }
                open = true;
            }
            if (!open) {
                it.remove();
                report(player, run);
            }
        }
    }

    static void report(ServerPlayer player, Run run) {
        int engineers = run.legs.size();
        net.minecraft.world.item.Item mine = ForgeRegistries.ITEMS.getValue(run.kind.item());
        Component item = mine != null ? mine.getDescription() : Component.literal(run.kind.item().toString());
        NetworkHandler.orderFeedback(player, "minefield", "message.tacz_sewv.minefield.laid", run.laid,
                ChatFormatting.GREEN, run.laid, item, engineers);
        if (run.skipped > 0) {
            OrderReport.ok(player, NetworkHandler.orderLine("minefield", run.skipped,
                    Component.translatable("message.tacz_sewv.minefield.skipped", run.skipped)
                            .withStyle(ChatFormatting.GRAY)));
        }
    }
}

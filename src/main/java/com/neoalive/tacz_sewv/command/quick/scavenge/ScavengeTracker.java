package com.neoalive.tacz_sewv.command.quick.scavenge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.notify.HudNotify;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Scavenge runner. Each unit works the stages in order (ammo, emplacements, utilities): find the
 * best source, walk to it, take what the stage wants, repeat, and move on once the stage has
 * nothing left in reach. Exclusive sources are claimed per run, shared ones spread units by a crowd
 * penalty. One summary for the whole run when the last unit finishes.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class ScavengeTracker {

    private static final int POLL_INTERVAL = 10;
    /** A source not reached in this long is given up on, so one bad path cannot eat the run. */
    private static final long SOURCE_WALK_TICKS = 400;

    private static final Map<UUID, Run> ACTIVE = new HashMap<>();

    private ScavengeTracker() {}

    static final class Run {
        final UUID playerId;
        final ScavengeSources.Context ctx;
        final List<Leg> legs = new ArrayList<>();
        final Reservations reserved = new Reservations();
        final ScavengeTally tally = new ScavengeTally();
        long deadline;
        int full;
        int empty;

        Run(UUID playerId, ScavengeSources.Context ctx) {
            this.playerId = playerId;
            this.ctx = ctx;
        }

        void claim(Leg leg) {
            if (leg.source != null) this.reserved.add(leg.source.key(), leg.plan);
        }

        void unclaim(Leg leg) {
            if (leg.source != null) this.reserved.remove(leg.source.key(), leg.plan);
        }
    }

    static final class Leg {
        final int unitId;
        ScavengeStage stage = ScavengeStage.AMMO;
        @Nullable
        ScavengeSource source;
        Map<String, Integer> plan = Map.of();
        long sourceSince;
        final Set<Object> tried = new HashSet<>();
        int taken;
        boolean done;

        Leg(int unitId) {
            this.unitId = unitId;
        }
    }

    static Run newRun(ServerLevel level, ServerPlayer issuer, @Nullable net.minecraft.core.BlockPos ownStockpile) {
        return new Run(issuer.getUUID(), ScavengeSources.Context.of(level, issuer.getUUID(), ownStockpile));
    }

    /** Adds the unit with its first source already chosen. */
    static void addLeg(Run run, PmcUnitEntity pmc, ScavengeStage stage, ScavengeSources.Pick first, long now) {
        Leg leg = new Leg(pmc.getId());
        leg.stage = stage;
        run.legs.add(leg);
        assign(run, leg, pmc, first, now);
    }

    static void start(Run run, long now) {
        run.deadline = now + SewvConfig.SCAVENGE_TIMEOUT_TICKS.get();
        ACTIVE.put(run.playerId, run);
    }

    /** First source of the earliest stage that has one, or null when the unit has nothing to do. */
    @Nullable
    static ScavengeSources.Pick firstSource(Run run, ScavengeWants wants, ScavengeStage[] stageOut) {
        double radius = SewvConfig.SCAVENGE_RADIUS.get();
        for (ScavengeStage stage : ScavengeStage.values()) {
            ScavengeSources.Pick src = ScavengeSources.find(run.ctx, wants, stage, radius, run.reserved, Set.of());
            if (src != null) {
                stageOut[0] = stage;
                return src;
            }
        }
        return null;
    }

    public static boolean cancelPlayer(UUID playerId, @Nullable ServerLevel level) {
        Run run = ACTIVE.remove(playerId);
        if (run == null) return false;
        if (level != null) {
            for (Leg leg : run.legs) {
                if (!leg.done && level.getEntity(leg.unitId) instanceof PmcUnitEntity pmc) dropWalk(pmc);
            }
        }
        return true;
    }

    public static boolean isActive(UUID playerId) {
        return ACTIVE.containsKey(playerId);
    }

    public static boolean isScavenging(PmcUnitEntity pmc) {
        for (Run run : ACTIVE.values()) {
            for (Leg leg : run.legs) {
                if (!leg.done && leg.unitId == pmc.getId()) return true;
            }
        }
        return false;
    }

    /** A new order took the unit: end its leg, keep what it took, leave its new order alone. */
    public static void release(PmcUnitEntity pmc) {
        if (ACTIVE.isEmpty()) return;
        for (Run run : ACTIVE.values()) {
            for (Leg leg : run.legs) {
                if (!leg.done && leg.unitId == pmc.getId()) finish(run, leg);
            }
        }
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
            if (player == null || player.level() != run.ctx.level()) {
                it.remove();
                continue;
            }
            if (tick(run.ctx.level(), player, run)) {
                it.remove();
                report(player, run);
            }
        }
    }

    private static boolean tick(ServerLevel level, ServerPlayer player, Run run) {
        long now = level.getGameTime();
        boolean timedOut = now >= run.deadline;
        for (Leg leg : run.legs) {
            if (leg.done) continue;
            Entity e = level.getEntity(leg.unitId);
            if (!(e instanceof PmcUnitEntity pmc) || !pmc.isAlive() || !PmcOwnerSupport.isOwner(player, pmc)) {
                finish(run, leg);
                continue;
            }
            if (timedOut || pmc.getVehicle() != null) {
                dropWalk(pmc);
                finish(run, leg);
                continue;
            }
            tickLeg(level, run, leg, pmc, now);
        }
        for (Leg leg : run.legs) {
            if (!leg.done) return false;
        }
        return true;
    }

    private static void tickLeg(ServerLevel level, Run run, Leg leg, PmcUnitEntity pmc, long now) {
        ScavengeSource src = leg.source;
        Vec3 at = src == null ? null : ScavengeSources.position(level, src);
        if (src == null || at == null || now - leg.sourceSince > SOURCE_WALK_TICKS) {
            if (src != null) leg.tried.add(src.key());
            next(run, leg, pmc, now);
            return;
        }
        if (!ScavengeSources.inReach(level, pmc, src, at)) {
            pmc.setOrder(OrderType.MOVE_TO_POSITION);
            pmc.setMoveToTarget(at);
            return;
        }
        ScavengeWants wants = ScavengeWants.of(level, pmc);
        if (wants == null) {
            dropWalk(pmc);
            finish(run, leg);
            return;
        }
        int got = ScavengeSources.take(run.ctx, wants, leg.stage, src, leg.plan, run.tally);
        leg.taken += got;
        if (got > 0 && leg.stage == ScavengeStage.AMMO) HudNotify.clearAmmoOut(pmc);
        // Whatever the source still holds for this stage, this unit cannot use it.
        leg.tried.add(src.key());
        if (wants.pocketsFull()) {
            dropWalk(pmc);
            finish(run, leg);
            return;
        }
        next(run, leg, pmc, now);
    }

    /** Next source in this stage, else the first stage after it that has one, else done. */
    private static void next(Run run, Leg leg, PmcUnitEntity pmc, long now) {
        run.unclaim(leg);
        leg.source = null;
        leg.plan = Map.of();
        ServerLevel level = run.ctx.level();
        ScavengeWants wants = ScavengeWants.of(level, pmc);
        if (wants == null || wants.pocketsFull()) {
            dropWalk(pmc);
            finish(run, leg);
            return;
        }
        double radius = SewvConfig.SCAVENGE_RADIUS.get();
        for (ScavengeStage stage = leg.stage; stage != null; stage = ScavengeRules.next(stage)) {
            // A source already worked in an earlier stage may still hold what this one wants.
            Set<Object> tried = stage == leg.stage ? leg.tried : Set.of();
            ScavengeSources.Pick src = ScavengeSources.find(run.ctx, wants, stage, radius, run.reserved, tried);
            if (src != null) {
                if (stage != leg.stage) leg.tried.clear();
                leg.stage = stage;
                assign(run, leg, pmc, src, now);
                return;
            }
        }
        dropWalk(pmc);
        finish(run, leg);
    }

    private static void assign(Run run, Leg leg, PmcUnitEntity pmc, ScavengeSources.Pick pick, long now) {
        leg.source = pick.source();
        leg.plan = pick.plan();
        leg.sourceSince = now;
        run.claim(leg);
        Vec3 at = ScavengeSources.position(run.ctx.level(), pick.source());
        if (at != null) {
            pmc.setOrder(OrderType.MOVE_TO_POSITION);
            pmc.setMoveToTarget(at);
        }
    }

    private static void finish(Run run, Leg leg) {
        run.unclaim(leg);
        leg.source = null;
        leg.plan = Map.of();
        leg.done = true;
    }

    static void report(ServerPlayer player, Run run) {
        NetworkHandler.orderFeedback(player, "scavenge", "message.tacz_sewv.scavenge", run.legs.size(),
                ChatFormatting.GREEN);
        for (Component line : run.tally.lines()) {
            OrderReport.ok(player, line);
        }
        if (run.tally.isEmpty() && !run.legs.isEmpty()) {
            OrderReport.ok(player, Component.translatable("message.tacz_sewv.scavenge.nothing_taken")
                    .withStyle(ChatFormatting.GRAY));
        }
        reportRefused(player, run);
    }

    static void reportRefused(ServerPlayer player, Run run) {
        if (run.full > 0) {
            OrderReport.ok(player, NetworkHandler.orderLine("scavenge", run.full,
                    Component.translatable("message.tacz_sewv.scavenge.full").withStyle(ChatFormatting.GRAY)));
        }
        if (run.empty > 0) {
            OrderReport.ok(player, NetworkHandler.orderLine("scavenge", run.empty,
                    Component.translatable("message.tacz_sewv.scavenge.nothing").withStyle(ChatFormatting.GRAY)));
        }
    }

    private static void dropWalk(PmcUnitEntity pmc) {
        if (pmc.getOrder() == OrderType.MOVE_TO_POSITION) {
            pmc.setOrder(OrderType.FREE_FIRE);
            pmc.getNavigation().stop();
        }
    }
}

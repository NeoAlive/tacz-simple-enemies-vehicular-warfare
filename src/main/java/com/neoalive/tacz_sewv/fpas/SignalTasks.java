package com.neoalive.tacz_sewv.fpas;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.ZoneBeaconBlockEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;
import com.neoalive.tacz_sewv.order.OrderReport;
import com.neoalive.tacz_sewv.util.LoadedClip;

/**
 * A Signal engagement: the unit goes to a spot it can actually shoot its target from, fights it
 * there, and works down its queue. SEM's ATTACK_THAT_TARGET only pins the target choice (its
 * priority-0 target goal is the one thing that keeps other goals from swapping it); everything SEM
 * does not do is here: the radar track when the target is out of the unit's own sight, the firing
 * spot, repositioning when the shot is lost, and the next target.
 *
 * <p>Two phases. MOVE: on foot {@code SignalEngageGoal} walks to the spot, a crew is driven there by
 * {@code DriveVehicleGoal.signalTick}. ENGAGE: in range with a line of fire, the unit's own combat AI
 * fights; this class only watches for the shot being lost. Aircraft skip MOVE, their attack runs are
 * the approach.
 *
 * <p>Transient, keyed by unit network id, like every id-bearing order here. Any new order drops it
 * ({@code OrderStandDown}, {@code MixinPacketIssueOrder}). A finished queue leaves the unit on
 * FREE_FIRE where it stands.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SignalTasks {

    public enum Phase { MOVE, ENGAGE }

    private static final int SWEEP_INTERVAL_TICKS = 10;
    /** Shot lost this long in ENGAGE: go and find another spot. */
    private static final long LOS_LOST_TICKS = 60;
    /** The track moved this far from the spot's target: re-pick the spot. */
    private static final double TRACK_DRIFT = 10.0;
    private static final double EYE = 1.6;
    private static final double HULL_EYE = 2.5;

    public record Pending(int id, Vec3 track) {}

    private record Source(ServerLevel level, BlockPos pos) {}

    private static final class Batch {
        final UUID owner;
        final List<Source> radars;
        int members;
        final java.util.Set<Integer> kills = new java.util.HashSet<>();

        Batch(UUID owner, List<Source> radars) {
            this.owner = owner;
            this.radars = radars;
        }
    }

    public static final class Task {
        final ServerLevel level;
        final Batch batch;
        final Deque<Pending> queue;
        int target;
        Vec3 track;
        Phase phase = Phase.MOVE;
        @Nullable Vec3 spot;
        @Nullable Vec3 spotFor;
        long deadline;
        long losLostSince = Long.MIN_VALUE;
        int stalls;

        Task(ServerLevel level, Batch batch, Pending first, List<Pending> rest) {
            this.level = level;
            this.batch = batch;
            this.target = first.id();
            this.track = first.track();
            this.queue = new ArrayDeque<>(rest);
        }

        public Phase phase() {
            return this.phase;
        }

        @Nullable
        public Vec3 spot() {
            return this.spot;
        }

        public Vec3 track() {
            return this.track;
        }
    }

    private static final Map<Integer, Task> TASKS = new HashMap<>();
    private static final List<Batch> BATCHES = new ArrayList<>();

    private SignalTasks() {}

    /** One dispatch: every unit's ordered targets, reported back as one batch. */
    public static void begin(UUID owner, List<ZoneBeaconBlockEntity> radars, Map<PmcUnitEntity, List<Pending>> plan) {
        List<Source> sources = new ArrayList<>();
        for (ZoneBeaconBlockEntity be : radars) {
            if (be.getLevel() instanceof ServerLevel l) sources.add(new Source(l, be.getBlockPos()));
        }
        Batch batch = new Batch(owner, sources);
        for (Map.Entry<PmcUnitEntity, List<Pending>> e : plan.entrySet()) {
            List<Pending> list = e.getValue();
            PmcUnitEntity pmc = e.getKey();
            if (list.isEmpty() || !(pmc.level() instanceof ServerLevel level)) continue;
            clear(pmc);
            Task t = new Task(level, batch, list.get(0), list.subList(1, list.size()));
            t.deadline = level.getGameTime() + targetTicks();
            if (ownApproach(pmc)) t.phase = Phase.ENGAGE;
            TASKS.put(pmc.getId(), t);
            batch.members++;
            pin(pmc, t.target);
        }
        if (batch.members > 0) BATCHES.add(batch);
    }

    @Nullable
    public static Task get(PmcUnitEntity pmc) {
        return TASKS.get(pmc.getId());
    }

    public static boolean has(PmcUnitEntity pmc) {
        return TASKS.containsKey(pmc.getId());
    }

    /** True while the unit should be travelling to its firing spot rather than fighting. */
    public static boolean moving(PmcUnitEntity pmc) {
        Task t = TASKS.get(pmc.getId());
        return t != null && t.phase == Phase.MOVE;
    }

    public static void clear(Entity pmc) {
        Task t = TASKS.remove(pmc.getId());
        if (t != null) t.batch.members--;
    }

    public static void clearAll() {
        TASKS.clear();
        BATCHES.clear();
    }

    /** The unit got to its spot, or close enough that the spot no longer matters. */
    public static void arrive(PmcUnitEntity pmc) {
        Task t = TASKS.get(pmc.getId());
        if (t == null) return;
        t.phase = Phase.ENGAGE;
        t.losLostSince = Long.MIN_VALUE;
        t.stalls = 0;
    }

    /** The unit cannot reach its spot. A few re-picks, then it fights from where it stands. */
    public static void stalled(PmcUnitEntity pmc) {
        Task t = TASKS.get(pmc.getId());
        if (t == null) return;
        if (++t.stalls >= 3) {
            arrive(pmc);
        } else {
            t.spot = null;
        }
    }

    /**
     * The spot to move to, picked on demand and kept until the track drifts. Null when no candidate
     * has footing and a line of fire; the caller then closes on the track itself.
     */
    @Nullable
    public static Vec3 spot(PmcUnitEntity pmc) {
        Task t = TASKS.get(pmc.getId());
        if (t == null) return null;
        if (t.spot != null && t.spotFor != null && t.spotFor.distanceTo(t.track) < TRACK_DRIFT) return t.spot;
        Entity root = pmc.getRootVehicle();
        boolean hull = root instanceof VehicleEntity;
        double range = range(pmc);
        List<double[]> claimed = claimedSpots(pmc);
        Vec3 aim = t.track;
        double[] p = FiringSpots.pick(root.getX(), root.getZ(), aim.x, aim.z, range, claimed,
                c -> usable(t.level, c, aim, hull));
        t.spotFor = t.track;
        if (p == null) {
            t.spot = null;
            return null;
        }
        t.spot = new Vec3(p[0], groundY(t.level, p[0], p[1]), p[1]);
        return t.spot;
    }

    /** Engagement range for this unit against its current target. */
    public static double range(PmcUnitEntity pmc) {
        Task t = TASKS.get(pmc.getId());
        Entity target = t == null ? null : t.level.getEntity(t.target);
        if (pmc.getVehicle() instanceof VehicleEntity) {
            return Facts.preferredRange(target instanceof LivingEntity l ? VehicleWeapons.classifyTarget(l) : null);
        }
        double r = SewvConfig.SIGNAL_INFANTRY_RANGE.get();
        return SmallArmsSupport.holdsAntiArmor(pmc) ? r * 1.5 : r;
    }

    private static List<double[]> claimedSpots(PmcUnitEntity self) {
        List<double[]> out = new ArrayList<>();
        for (Map.Entry<Integer, Task> e : TASKS.entrySet()) {
            if (e.getKey() == self.getId() || e.getValue().spot == null) continue;
            out.add(new double[] {e.getValue().spot.x, e.getValue().spot.z});
        }
        return out;
    }

    private static boolean usable(ServerLevel level, double[] c, Vec3 aim, boolean hull) {
        int cx = (int) Math.floor(c[0]) >> 4, cz = (int) Math.floor(c[1]) >> 4;
        if (!level.hasChunk(cx, cz)) return false;
        double y = groundY(level, c[0], c[1]);
        BlockPos below = BlockPos.containing(c[0], y - 1, c[1]);
        if (!level.getFluidState(below).isEmpty() || !level.getFluidState(below.above()).isEmpty()) return false;
        return clear(level, new Vec3(c[0], y + (hull ? HULL_EYE : EYE), c[1]), aim, hull);
    }

    private static double groundY(ServerLevel level, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return level.getSeaLevel();
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    private static boolean clear(ServerLevel level, Vec3 from, Vec3 to, boolean hull) {
        HitResult hit = hull
                ? LoadedClip.clipAsVehicle(level, from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE)
                : LoadedClip.clip(level, new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                        null));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) < 4.0;
    }

    /** Aircraft and ships run their own approach (attack runs, water routing); only the target is pinned. */
    static boolean ownApproach(PmcUnitEntity pmc) {
        return aircrew(pmc) || pmc.getVehicle() instanceof VehicleEntity v && HullFacts.isShipHull(v);
    }

    static boolean aircrew(PmcUnitEntity pmc) {
        return pmc.getVehicle() instanceof VehicleEntity v && (HullFacts.isPlaneHull(v) || HullFacts.isHelicopterHull(v));
    }

    private static long targetTicks() {
        return SewvConfig.SIGNAL_TARGET_SECONDS.get() * 20L;
    }

    /** Lock the target through SEM's own specific-target goal. */
    private static void pin(PmcUnitEntity pmc, int target) {
        if (pmc.getAttackTargetId() != target || pmc.getOrder() != OrderType.ATTACK_THAT_TARGET) {
            pmc.setAttackTargetId(target);
            pmc.setOrder(OrderType.ATTACK_THAT_TARGET);
        }
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
            if (!(t.level.getEntity(e.getKey()) instanceof PmcUnitEntity pmc) || !pmc.isAlive()) {
                t.batch.members--;
                it.remove();
                continue;
            }
            if (!advance(pmc, t)) {
                // Queue done: FREE_FIRE where it stands.
                pmc.setAttackTargetId(-1);
                pmc.setOrder(OrderType.FREE_FIRE);
                t.batch.members--;
                it.remove();
            }
        }
    }

    /** @return false once the unit has nothing left to engage */
    private static boolean advance(PmcUnitEntity pmc, Task t) {
        long now = t.level.getGameTime();
        while (true) {
            Entity target = t.level.getEntity(t.target);
            boolean dead = target != null && !target.isAlive();
            boolean gone = target == null && !onRadar(t);
            if (dead) t.batch.kills.add(t.target);
            if (!dead && !gone && now < t.deadline) {
                if (target instanceof LivingEntity l) {
                    Entity root = l.getRootVehicle();
                    t.track = root.getBoundingBox().getCenter();
                    pin(pmc, t.target);
                }
                break;
            }
            Pending next = t.queue.poll();
            if (next == null) return false;
            t.target = next.id();
            t.track = next.track();
            t.deadline = now + targetTicks();
            t.spot = null;
            t.stalls = 0;
            if (!ownApproach(pmc)) t.phase = Phase.MOVE;
        }
        if (ownApproach(pmc)) return true;
        Entity target = t.level.getEntity(t.target);
        Entity root = pmc.getRootVehicle();
        boolean hull = root instanceof VehicleEntity;
        double range = range(pmc);
        boolean inRange = root.position().distanceTo(t.track) <= range * 1.15;
        boolean sees = target != null && inRange
                && clear(t.level, root.position().add(0, hull ? HULL_EYE : EYE, 0), t.track, hull);
        if (t.phase == Phase.MOVE) {
            if (sees) arrive(pmc);
        } else if (sees) {
            t.losLostSince = Long.MIN_VALUE;
        } else if (t.losLostSince == Long.MIN_VALUE) {
            t.losLostSince = now;
        } else if (now - t.losLostSince > LOS_LOST_TICKS) {
            t.phase = Phase.MOVE;
            t.spot = null;
            t.losLostSince = Long.MIN_VALUE;
        }
        return true;
    }

    /** The target out of every lookup: still on a bound radar means unloaded or unseen, not destroyed. */
    private static boolean onRadar(Task t) {
        for (Source s : t.batch.radars) {
            if (!s.level.isLoaded(s.pos) || !(s.level.getBlockEntity(s.pos) instanceof ZoneBeaconBlockEntity be)) continue;
            for (RadarContact c : be.contacts()) {
                if (c.targetId() == t.target) {
                    t.track = new Vec3(c.x(), c.y() + 1.0, c.z());
                    return true;
                }
            }
        }
        return false;
    }

    private static void closeBatches(MinecraftServer server) {
        Iterator<Batch> it = BATCHES.iterator();
        while (it.hasNext()) {
            Batch b = it.next();
            if (b.members > 0) continue;
            it.remove();
            ServerPlayer p = server.getPlayerList().getPlayer(b.owner);
            if (p == null) continue;
            OrderReport.ok(p, Component.translatable("message.tacz_sewv.signal.complete", b.kills.size())
                    .withStyle(ChatFormatting.GREEN));
        }
    }
}

package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldTargetPriority;

/**
 * Per-level spatial index of the things a crew can fight, rebuilt on a budget (never on demand) and
 * shared by every hull scan and by {@link FactionWideScan}. It replaces the per-hull
 * {@code getEntitiesOfClass} AABB queries.
 *
 * <p><b>Full rebuild, not incremental.</b> Two O(N) passes over the level's entities every
 * {@code combatantIndexIntervalTicks}: cheaper and harder to get wrong than tracking join/leave
 * membership (which would duplicate {@code CacheEviction} and can drift). The same tick handler then
 * runs {@link FactionWideScan}, so contacts are always published <i>before</i> the next tick's goals
 * read them.
 *
 * <p><b>What is in it.</b> Living entries (grid-indexed, id-sorted): {@code AbstractUnit}s, and a SOFT
 * tier of players, iron golems and mobs whose MobCategory is not excluded for every faction — the close
 * scan still engages monsters, and only the close scan reads SOFT. Hull entries (crewed, not wrecked)
 * are kept apart: subjects and observers of the wide pass, never returned by {@link Snapshot#query}.
 * Nothing is excluded for not ticking: a non-ticking entity's position is still valid, and TTL plus reader
 * re-validation handle staleness. Only removed / dead entities are dropped.
 *
 * <p>Snapshots hold live entity references until the next rebuild; consumers re-check
 * {@code isAlive()}. Absent snapshot (first tick of a level, after stop/reload) means "nothing yet": readers
 * get an empty answer and must not cache it.
 */
public final class CombatantIndex {

    private static final Logger LOG = LogUtils.getLogger();

    public enum Kind { HULL, MORTAR_CREW, UNIT, SOFT }

    /** One indexed thing. For {@link Kind#HULL} the position is the hull's and {@code living} its first passenger. */
    public static final class Entry {
        public final int id;
        public final Kind kind;
        public final double x, y, z;
        /** The subject a lock would name; null only in headless self-checks. */
        @Nullable public final LivingEntity living;
        /** HULL only: the driver, when it is an {@code AbstractUnit} (an observer). */
        @Nullable public final AbstractUnit observer;
        public final boolean canObserve;
        /** HULL only: how far above the terrain surface an airborne hull is (0 for ground hulls). */
        public final double altitudeSlack;

        Entry(int id, Kind kind, double x, double y, double z, @Nullable LivingEntity living,
              @Nullable AbstractUnit observer, boolean canObserve, double altitudeSlack) {
            this.id = id;
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.living = living;
            this.observer = observer;
            this.canObserve = canObserve;
            this.altitudeSlack = altitudeSlack;
        }

        /** A wide-pass subject: a crewed hull, or a claimed mortar / emplacement crew. */
        public boolean wideSubject() {
            return this.kind == Kind.HULL || this.kind == Kind.MORTAR_CREW;
        }
    }

    /** Immutable-after-build view of one level. Package-visible constructor so the self-check can build one. */
    public static final class Snapshot {
        private static final int CELL = 64;

        final long builtAt;
        /** Grid-indexed living entries, ascending by id. */
        final List<Entry> living;
        /** Crewed hulls, ascending by id. */
        final List<Entry> hulls;
        /** Hulls + mortar crews: the wide pass's subjects, ascending by id. */
        final List<Entry> subjects;
        private final Map<Long, List<Entry>> grid = new HashMap<>();

        Snapshot(long builtAt, List<Entry> living, List<Entry> hulls) {
            this.builtAt = builtAt;
            this.living = sortedById(living);
            this.hulls = sortedById(hulls);
            List<Entry> subs = new ArrayList<>(this.hulls);
            for (Entry e : this.living) if (e.kind == Kind.MORTAR_CREW) subs.add(e);
            this.subjects = sortedById(subs);
            for (Entry e : this.living) {
                grid.computeIfAbsent(cell(e.x, e.z), k -> new ArrayList<>()).add(e);
            }
        }

        public List<Entry> hulls() {
            return this.hulls;
        }

        public List<Entry> subjects() {
            return this.subjects;
        }

        /**
         * Living entries inside the square {@code |dx|,|dz| <= radius} (the shape the old AABB query had)
         * and {@code minY..maxY}, ascending by entity id.
         */
        public List<Entry> query(double cx, double cz, double radius, double minY, double maxY) {
            int x0 = Math.floorDiv((int) Math.floor(cx - radius), CELL);
            int x1 = Math.floorDiv((int) Math.floor(cx + radius), CELL);
            int z0 = Math.floorDiv((int) Math.floor(cz - radius), CELL);
            int z1 = Math.floorDiv((int) Math.floor(cz + radius), CELL);
            List<Entry> out = new ArrayList<>();
            for (int gx = x0; gx <= x1; gx++) {
                for (int gz = z0; gz <= z1; gz++) {
                    List<Entry> bucket = grid.get(key(gx, gz));
                    if (bucket == null) continue;
                    for (Entry e : bucket) {
                        if (Math.abs(e.x - cx) <= radius && Math.abs(e.z - cz) <= radius
                                && e.y >= minY && e.y <= maxY) {
                            out.add(e);
                        }
                    }
                }
            }
            out.sort(BY_ID);
            return out;
        }

        private static long cell(double x, double z) {
            return key(Math.floorDiv((int) Math.floor(x), CELL), Math.floorDiv((int) Math.floor(z), CELL));
        }

        private static long key(int gx, int gz) {
            return ((long) gx << 32) ^ (gz & 0xffffffffL);
        }
    }

    static final Comparator<Entry> BY_ID = Comparator.comparingInt(e -> e.id);

    static List<Entry> sortedById(List<Entry> in) {
        List<Entry> out = new ArrayList<>(in);
        out.sort(BY_ID);
        return out;
    }

    /** Per-level mutable state: the current snapshot and the wide pass's two deadlines. */
    static final class State {
        @Nullable Snapshot snapshot;
        long nextRebuild = Long.MIN_VALUE;
        long nextClosePass = Long.MIN_VALUE;
        long nextWidePass = Long.MIN_VALUE;
    }

    private static final Map<ServerLevel, State> STATES = new IdentityHashMap<>();

    // Diagnostics, split so "how much did it publish" and "how long did evaluation take" cannot be confused.
    private static long rebuilds;
    private static long lastRebuildNanos;
    private static long lastEntryCount;

    private CombatantIndex() {}

    /** The level's current snapshot, or null before its first rebuild. Never builds one. */
    @Nullable
    public static Snapshot snapshot(net.minecraft.world.level.Level level) {
        if (!(level instanceof ServerLevel sl)) return null;
        State s = STATES.get(sl);
        return s == null ? null : s.snapshot;
    }

    public static String stats() {
        return "rebuilds=" + rebuilds + " lastRebuildNanos=" + lastRebuildNanos + " entries=" + lastEntryCount
                + " | " + FactionWideScan.stats();
    }

    // --- tick handler ------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int interval = intervalTicks();
        java.util.Set<ServerLevel> live = new java.util.HashSet<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            live.add(level);
            State st = STATES.computeIfAbsent(level, l -> new State());
            long now = level.getGameTime();
            if (FactionWideScan.due(now, st.nextRebuild, interval)) {
                st.nextRebuild = now + interval;
                st.snapshot = rebuild(level, now);
            }
            if (st.snapshot != null) FactionWideScan.run(level, st, now);
        }
        STATES.keySet().retainAll(live);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        STATES.clear();
    }

    /** The reach depends on the host: say so once, naming the settings to raise. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            if (!SewvConfig.WIDE_SCAN_ENABLED.get()) return;
            double w = SewvConfig.WIDE_SCAN_RADIUS.get();
            int view = server.getPlayerList().getViewDistance();
            int sim = server.getPlayerList().getSimulationDistance();
            if (Math.min(view, sim) * 16.0 < w) {
                LOG.warn("[sewv] wideScanRadius is {} but view-distance={} / simulation-distance={} chunks: "
                        + "vehicles only detect each other inside loaded, ticking chunks, so effective detection is "
                        + "about {} blocks. Raise both server settings to {} chunks for the full range.",
                        (int) w, view, sim, Math.min(view, sim) * 16, (int) Math.ceil(w / 16.0));
            }
        } catch (Throwable unbaked) {
            // config not readable this early: nothing to warn about
        }
    }

    private static int intervalTicks() {
        try {
            return SewvConfig.COMBATANT_INDEX_INTERVAL_TICKS.get();
        } catch (Throwable unbaked) {
            return 20;
        }
    }

    // --- rebuild -----------------------------------------------------------------------------------

    private static Snapshot rebuild(ServerLevel level, long now) {
        long t0 = System.nanoTime();
        WorldTargetPriority priority = WorldTargetPriority.get(level);
        Map<String, Boolean> softCategory = new HashMap<>();

        List<Entry> living = new ArrayList<>();
        for (LivingEntity e : level.getEntities(EntityTypeTest.forClass(LivingEntity.class), x -> true)) {
            if (!e.isAlive()) continue;
            if (e instanceof AbstractUnit unit) {
                boolean crew = unit instanceof IMortarCrew c
                        && c.sewv$getMortarTargetId() != IMortarCrew.NO_MORTAR && !unit.isPassenger();
                living.add(new Entry(e.getId(), crew ? Kind.MORTAR_CREW : Kind.UNIT,
                        e.getX(), e.getY(), e.getZ(), e, unit, false, 0.0));
            } else if (isSoft(e, priority, softCategory)) {
                living.add(new Entry(e.getId(), Kind.SOFT, e.getX(), e.getY(), e.getZ(), e, null, false, 0.0));
            }
        }

        List<Entry> hulls = new ArrayList<>();
        for (VehicleEntity hull : level.getEntities(EntityTypeTest.forClass(VehicleEntity.class), h -> true)) {
            if (hull.isRemoved() || hull.isWreck()) continue;
            if (!(hull.getFirstPassenger() instanceof LivingEntity first) || !first.isAlive()) continue;
            AbstractUnit observer = first instanceof AbstractUnit u ? u : null;
            hulls.add(new Entry(hull.getId(), Kind.HULL, hull.getX(), hull.getY(), hull.getZ(), first,
                    observer, observer != null, altitudeSlack(level, hull)));
        }

        Snapshot snap = new Snapshot(now, living, hulls);
        rebuilds++;
        lastRebuildNanos = System.nanoTime() - t0;
        lastEntryCount = (long) living.size() + hulls.size();
        return snap;
    }

    /**
     * Players, iron golems, and mobs of a category at least one faction may still target. Memoised per
     * rebuild: the category lookup is the only per-mob cost.
     */
    private static boolean isSoft(LivingEntity e, WorldTargetPriority priority, Map<String, Boolean> memo) {
        if (e instanceof Player p) return !p.isSpectator();
        if (e instanceof IronGolem) return true;
        MobCategory category = e.getType().getCategory();
        return memo.computeIfAbsent(category.getName(), name -> {
            for (TankFaction f : TankFaction.values()) {
                if (!priority.isExcluded(f, name)) return true;
            }
            return false;
        });
    }

    /**
     * Height above the terrain surface, for airborne hulls only. Read from the cached engine type
     * ({@link HullFacts#engineType}), never the per-call {@code isHelicopterHull/isPlaneHull}, which each
     * re-run {@code computed()}.
     */
    private static double altitudeSlack(ServerLevel level, VehicleEntity hull) {
        EngineType type = HullFacts.engineType(hull);
        if (type != EngineType.HELICOPTER && type != EngineType.AIRCRAFT) return 0.0;
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, hull.getBlockX(), hull.getBlockZ());
        return Math.max(0.0, hull.getY() - surface);
    }
}

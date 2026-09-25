package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Server-side, in-memory, faction-keyed contact board: what one unit has spotted, the rest of its
 * side may act on. Any unit publishes ({@link #publish}); crews read ({@link #waivesLos},
 * {@link #contactsFor}) through their existing scan goals. See {@code docs/fire-los-audit.md}.
 *
 * <p><b>What a contact is.</b> A network id (transient — never saved, never sent to a client, cleared
 * on server stop), the last published position, a {@link Source} (which carries the confidence) and
 * three game-time stamps. Position is a hint only: readers always resolve the id to the live entity.
 *
 * <p><b>Who shares.</b> Keyed by dimension + faction and, for PMC, by <b>owner</b>
 * ({@link PmcUnitEntity#getOwnerUUID}), so one player's PMC never learns what another's saw. Ownerless
 * PMC (a camp garrison) share one board of their own.
 *
 * <p><b>Push-on-change.</b> {@link #upsert} answers whether it wrote. It writes for a new contact, a
 * source upgrade, a move past the threshold, or a contact past half its life — never per tick — so cost
 * is O(observer events), not O(observers x contacts). Hearsay ({@link Source#RELAYED}) never refreshes
 * anything: a contact one unit locked because another told it to must not be kept alive by that echo.
 *
 * <p><b>The board never calls {@code setTarget}.</b> It only tells a scan goal "this candidate is
 * already known to your side", so the goal may skip its own raycast beyond
 * {@code contactBoardCloseBand}. The lock still goes through {@code MixinAbstractUnit} / SEM's own
 * {@code setTarget}, so every friendly veto still applies. Firing is unaffected: the fire-time gate in
 * {@code MixinVehicleFireCooldown} still demands current line of sight.
 *
 * <p>Deadlines are {@code level.getGameTime()}. Game time rewinds on a world reload, so a contact whose
 * refresh stamp is in the future is treated as expired, and {@link #clearAll} runs on server stop.
 */
public final class ContactBoard {

    /** Where a contact came from. Ordered by confidence; {@code ttlPercent} scales the configured TTL. */
    public enum Source {
        /** Told by someone else (radio, delegation) or locked without the observer's own eyes. */
        RELAYED(1, 30),
        /** Aware without line of sight: an outer-ring spot. */
        PROXIMITY(2, 60),
        /** The observer had line of sight when it published. */
        DIRECT_SIGHT(3, 100);

        public final int confidence;
        final int ttlPercent;

        Source(int confidence, int ttlPercent) {
            this.confidence = confidence;
            this.ttlPercent = ttlPercent;
        }

        public boolean atLeast(Source min) {
            return this.confidence >= min.confidence;
        }

        int ttl(int baseTicks) {
            return Math.max(1, baseTicks * this.ttlPercent / 100);
        }
    }

    public record Key(String dimension, CrewFacts.Faction faction, @Nullable UUID owner) {}

    public static final class Contact {
        public final int entityId;
        public double x, y, z;
        public Source source;
        /** Game time the contact first went on the board. */
        public final long firstSeen;
        /** Game time of the last write that counted as fresh evidence. */
        public long lastRefresh;
        public long expiresAt;
        /** Throttle for the line-of-sight probe {@link #onTargetAcquired} makes to upgrade a contact. */
        long lastProbe = Long.MIN_VALUE;

        Contact(int entityId, double x, double y, double z, Source source, long now, long expiresAt) {
            this.entityId = entityId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.source = source;
            this.firstSeen = now;
            this.lastRefresh = now;
            this.expiresAt = expiresAt;
        }
    }

    /** Sorted by entity id, so anything the board exposes iterates in a stable, id-derived order. */
    private static final Map<Key, TreeMap<Integer, Contact>> BOARD = new HashMap<>();
    private static final long SWEEP_INTERVAL_TICKS = 600;
    private static final long PROBE_INTERVAL_TICKS = 20;
    private static long lastSweep = Long.MIN_VALUE;

    private ContactBoard() {}

    // --- pure store (no world state; exercised by ContactBoardSelfCheck) ---------------------------

    /** @return true if the board was written */
    static boolean upsert(Key key, int id, double x, double y, double z, Source src, long now,
                          int baseTtl, double moveThreshold) {
        TreeMap<Integer, Contact> m = BOARD.computeIfAbsent(key, k -> new TreeMap<>());
        Contact c = m.get(id);
        if (c != null && expired(c, now)) {
            m.remove(id);
            c = null;
        }
        if (c == null) {
            m.put(id, new Contact(id, x, y, z, src, now, now + src.ttl(baseTtl)));
            return true;
        }
        boolean upgrade = src.confidence > c.source.confidence;
        if (!upgrade && (src == Source.RELAYED || src.confidence < c.source.confidence)) return false;

        double dx = x - c.x, dy = y - c.y, dz = z - c.z;
        boolean moved = dx * dx + dy * dy + dz * dz > moveThreshold * moveThreshold;
        boolean ageing = (c.expiresAt - now) * 2 <= src.ttl(baseTtl);
        if (!upgrade && !moved && !ageing) return false;

        c.x = x;
        c.y = y;
        c.z = z;
        if (upgrade) c.source = src;
        c.lastRefresh = now;
        c.expiresAt = now + src.ttl(baseTtl);
        return true;
    }

    /** Live contacts for a key in ascending entity-id order; expired ones are dropped on the way. */
    static List<Contact> live(Key key, long now) {
        TreeMap<Integer, Contact> m = BOARD.get(key);
        if (m == null) return List.of();
        m.values().removeIf(c -> expired(c, now));
        return new ArrayList<>(m.values());
    }

    @Nullable
    static Contact find(Key key, int id, long now) {
        TreeMap<Integer, Contact> m = BOARD.get(key);
        if (m == null) return null;
        Contact c = m.get(id);
        if (c == null) return null;
        if (expired(c, now)) {
            m.remove(id);
            return null;
        }
        return c;
    }

    static void remove(Key key, int id) {
        TreeMap<Integer, Contact> m = BOARD.get(key);
        if (m != null) m.remove(id);
    }

    /** Drop every expired contact and every emptied key, so an owner who logged out leaves nothing. */
    static void sweep(long now) {
        for (Iterator<TreeMap<Integer, Contact>> it = BOARD.values().iterator(); it.hasNext();) {
            TreeMap<Integer, Contact> m = it.next();
            m.values().removeIf(c -> expired(c, now));
            if (m.isEmpty()) it.remove();
        }
    }

    static int keyCount() {
        return BOARD.size();
    }

    public static void clearAll() {
        BOARD.clear();
        lastSweep = Long.MIN_VALUE;
    }

    /** {@code lastRefresh > now} means game time went backwards (world reload, /time set). */
    private static boolean expired(Contact c, long now) {
        return now >= c.expiresAt || c.lastRefresh > now;
    }

    // --- glue ------------------------------------------------------------------------------------

    private static boolean enabled() {
        try {
            return SewvConfig.CONTACT_BOARD_ENABLED.get();
        } catch (Throwable unbaked) {
            return false;
        }
    }

    private static int ttl() {
        try {
            return SewvConfig.CONTACT_BOARD_TTL_TICKS.get();
        } catch (Throwable unbaked) {
            return 200;
        }
    }

    private static double moveThreshold() {
        try {
            return SewvConfig.CONTACT_BOARD_MOVE_THRESHOLD.get();
        } catch (Throwable unbaked) {
            return 6.0;
        }
    }

    private static double closeBand() {
        try {
            return SewvConfig.CONTACT_BOARD_CLOSE_BAND.get();
        } catch (Throwable unbaked) {
            return 24.0;
        }
    }

    /** The board this unit reads and writes, or null for anything that is not a faction unit. */
    @Nullable
    static Key keyOf(AbstractUnit unit) {
        CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);
        if (faction == null) return null;
        UUID owner = unit instanceof PmcUnitEntity pmc ? pmc.getOwnerUUID() : null;
        return new Key(unit.level().dimension().location().toString(), faction, owner);
    }

    /**
     * Put {@code target} on {@code observer}'s board. Hostility is asked of {@link VehicleTargeting#isNonHostile}
     * (creative/spectator shielding and the friendly veto live only there) — a contact the observer's own
     * side would never engage is not intelligence.
     */
    public static void publish(AbstractUnit observer, LivingEntity target, Source source) {
        if (VehicleTargeting.isNonHostile(observer, target)) return;
        publishVetted(observer, target, source);
    }

    /**
     * {@link #publish} without the hostility gate.
     *
     * <p><b>Contract: the caller has already established, for THIS observer and THIS target, that the
     * target is one the observer may engage</b> (in practice {@code VehicleTargeting.isValidHostileTarget}).
     * That filter is the one thing the board relies on to never hold a friendly or shielded contact, so this
     * is package-private on purpose: {@link FactionWideScan}, which has just run it per direction, is the
     * only intended caller. Anything else must go through {@link #publish}.
     *
     * @return true if the board was written
     */
    static boolean publishVetted(AbstractUnit observer, LivingEntity target, Source source) {
        if (!enabled() || observer.level().isClientSide() || !target.isAlive()) return false;
        Key key = keyOf(observer);
        if (key == null) return false;

        long now = observer.level().getGameTime();
        if (lastSweep == Long.MIN_VALUE || now < lastSweep || now - lastSweep >= SWEEP_INTERVAL_TICKS) {
            lastSweep = now;
            sweep(now);
        }
        boolean wrote = upsert(key, target.getId(), target.getX(), target.getY(), target.getZ(),
                source, now, ttl(), moveThreshold());
        if (wrote) {
            SewvDiag.scan("ContactBoard.publish {}#{} -> {} #{} source={}",
                    observer.getClass().getSimpleName(), observer.getId(),
                    key.faction(), target.getId(), source);
        }
        return wrote;
    }

    /**
     * Called from {@code setTarget}'s tail for every accepted lock — infantry, crews, radio orders and
     * delegation alike, which is why no unit type needs a bridge interface. DIRECT_SIGHT if the unit can
     * see what it just locked, RELAYED if it was told. A contact already known at full confidence costs
     * one map lookup and no raycast; an upgrade probe is throttled per contact, because SEM re-forces a
     * radio-ordered target every few ticks.
     */
    public static void onTargetAcquired(AbstractUnit unit, LivingEntity target) {
        if (!enabled()) return;
        Key key = keyOf(unit);
        if (key == null) return;
        long now = unit.level().getGameTime();
        Contact known = find(key, target.getId(), now);
        if (known != null) {
            if (known.source == Source.DIRECT_SIGHT && known.expiresAt - now > ttl() / 2) return;
            if (known.lastProbe != Long.MIN_VALUE && now - known.lastProbe < PROBE_INTERVAL_TICKS
                    && now >= known.lastProbe) {
                return;
            }
            known.lastProbe = now;
        }
        publish(unit, target,
                unit.getSensing().hasLineOfSight(target) ? Source.DIRECT_SIGHT : Source.RELAYED);
    }

    /**
     * Does {@code reader}'s side know {@code target} at {@code min} confidence or better? Distance-agnostic:
     * this is membership, used to keep a lock that is beyond scan range from being dropped for distance.
     */
    public static boolean holds(AbstractUnit reader, LivingEntity target, Source min) {
        if (!enabled()) return false;
        Key key = keyOf(reader);
        if (key == null) return false;
        Contact c = find(key, target.getId(), reader.level().getGameTime());
        return c != null && c.source.atLeast(min);
    }

    /**
     * Should {@code reader} accept {@code target} without a raycast of its own? {@link #holds} AND beyond the
     * close-in band. Inside the band the reader's own line of sight is still required: being "aware" of a
     * soldier through the wall next to you is noise.
     */
    public static boolean waivesLos(AbstractUnit reader, LivingEntity target, Source min) {
        if (!holds(reader, target, min)) return false;
        double band = closeBand();
        return reader.distanceToSqr(target) > band * band;
    }

    /**
     * Live entities on {@code reader}'s board at {@code min} confidence or better, in ascending id order.
     * A scan goal merges these into its candidate list; each still goes through that goal's own validity
     * filter. Contacts that no longer resolve to a living entity are dropped here.
     */
    public static List<LivingEntity> contactsFor(AbstractUnit reader, Source min) {
        if (!enabled()) return List.of();
        Key key = keyOf(reader);
        if (key == null) return List.of();
        long now = reader.level().getGameTime();
        List<LivingEntity> out = null;
        for (Contact c : live(key, now)) {
            if (!c.source.atLeast(min)) continue;
            Entity e = reader.level().getEntity(c.entityId);
            if (!(e instanceof LivingEntity living) || !living.isAlive()) {
                remove(key, c.entityId);
                continue;
            }
            if (out == null) out = new ArrayList<>();
            out.add(living);
        }
        return out == null ? List.of() : out;
    }
}

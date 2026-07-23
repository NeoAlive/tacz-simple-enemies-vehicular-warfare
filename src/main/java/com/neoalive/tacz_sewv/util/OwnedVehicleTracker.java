package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.VehicleTargeting;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOwnedVehicles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends each player the crewed vehicles their side can see, for the map markers in
 * {@code client.xaero}: their own PMC hulls always, and other factions' hulls only where somebody
 * on their side is close enough to have noticed.
 *
 * <p>This exists because the client only knows about entities the server is tracking it — roughly
 * view distance — while the map draws the whole explored world. Without the sync a hull two chunks
 * past the tracking edge simply has no client entity to read, so the marker would blink out exactly
 * when the map becomes the useful way to look at it.
 *
 * <p><b>Shape of the work, which is what keeps it cheap:</b> one pass over each level collects every
 * crewed hull into a small candidate list (crewed hulls number in the tens, not thousands), and each
 * player is then served out of that list rather than out of the world. Per player the cost is
 * candidates × (1 + their own hulls) distance checks — a few hundred comparisons once a second. The
 * expensive per-hull fact, its symbol class, is computed once and cached in the hull's own NBT,
 * because {@code computed()} is a full vehicle-data compute and the answer can never change.
 */
public final class OwnedVehicleTracker {

    /**
     * Hard cap per player. Well past any plausible sighting picture; it is here so a creative-mode
     * spawn spree cannot turn a per-second packet into a bandwidth problem. Own hulls are added
     * first, so what gets dropped is distant contacts, never something you command.
     */
    private static final int MAX_MARKERS = 128;

    /** Cached symbol class, +1 so an absent tag reads as 0. The hull's class never changes. */
    private static final String KIND_KEY = "sewv:map_kind";

    /**
     * Deadline on the server's own tick counter, not on world game time: game time rewinds when a
     * different world is loaded in the same session, and a deadline set from the previous world's
     * clock would then sit in the future for hours.
     */
    private static int nextSend = Integer.MIN_VALUE;

    private OwnedVehicleTracker() {}

    /** A crewed hull, resolved once per sync and then served to every player from this. */
    private record Candidate(VehicleMarker.Kind kind, CrewFacts.Faction faction, UUID pmcOwner,
                             boolean factionFriendly, int driverId, int vehicleId,
                             double x, double y, double z, float yaw,
                             ResourceKey<Level> dimension) {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !SewvConfig.MAP_MARKERS_ENABLED.get()) return;

        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        int now = event.getServer().getTickCount();
        if (now < nextSend) return;
        nextSend = now + SewvConfig.MAP_SYNC_INTERVAL_TICKS.get();

        List<Candidate> candidates = new ArrayList<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            collect(level, candidates);
        }

        double spotRadius = SewvConfig.MAP_SPOT_RADIUS.get();
        double spotRadiusSq = spotRadius * spotRadius;
        for (ServerPlayer player : players) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new PacketOwnedVehicles(markersFor(player, candidates, spotRadiusSq)));
        }
    }

    // ponytail: one whole-level entity scan per interval (once a second by default). Fine at the
    // entity counts this mod produces; if it ever shows on a profile, index crewed hulls as they
    // are crewed instead of rediscovering them.
    private static void collect(ServerLevel level, List<Candidate> candidates) {
        for (VehicleEntity hull : level.getEntities(EntityTypeTest.forClass(VehicleEntity.class), h -> true)) {
            CrewFacts.Faction faction = CrewFacts.factionOf(hull);
            if (faction == null) continue; // empty, mixed, or a player's own ride — not a marker

            Entity driver = hull.getFirstPassenger();
            if (!(driver instanceof AbstractUnit crew)) continue;

            candidates.add(new Candidate(
                    kindOf(hull), faction, CrewFacts.pmcOwner(hull),
                    VehicleTargeting.isFactionFriendly(crew),
                    driver.getId(), hull.getId(),
                    hull.getX(), hull.getY(), hull.getZ(), hull.getYRot(), level.dimension()));
        }
    }

    /**
     * What one player may see. Their own hulls unconditionally — they command those, and hiding one
     * that drove out of sight is the whole reason this sync exists. Everything else has to be
     * <b>spotted</b>: within the configured radius of the player or of one of their own crews,
     * which is "your side noticed it" in the cheapest form that still reads as a sighting picture.
     *
     * <p>Another player's PMC hulls are never sent, spotted or not — that would hand out their unit
     * positions, which no part of this feature is allowed to do.
     */
    private static List<VehicleMarker> markersFor(ServerPlayer player, List<Candidate> candidates,
                                                  double spotRadiusSq) {
        List<VehicleMarker> markers = new ArrayList<>();
        List<Candidate> own = new ArrayList<>();

        for (Candidate c : candidates) {
            if (player.getUUID().equals(c.pmcOwner())) {
                own.add(c);
                markers.add(marker(c, VehicleMarker.Allegiance.OWN));
            }
        }

        if (spotRadiusSq <= 0.0) return markers;

        for (Candidate c : candidates) {
            if (markers.size() >= MAX_MARKERS) break;
            if (c.pmcOwner() != null) continue; // yours (already added) or another player's (never sent)
            if (!spotted(player, own, c, spotRadiusSq)) continue;
            markers.add(marker(c, allegianceOf(c)));
        }
        return markers;
    }

    private static boolean spotted(ServerPlayer player, List<Candidate> own, Candidate c, double radiusSq) {
        if (player.level().dimension().equals(c.dimension())
                && player.distanceToSqr(c.x(), c.y(), c.z()) <= radiusSq) {
            return true;
        }
        for (Candidate eye : own) {
            if (!eye.dimension().equals(c.dimension())) continue;
            double dx = eye.x() - c.x(), dy = eye.y() - c.y(), dz = eye.z() - c.z();
            if (dx * dx + dy * dy + dz * dz <= radiusSq) return true;
        }
        return false;
    }

    /**
     * A faction is friendly when SEM's own {@code ruUnitsFriendly}/{@code usUnitsFriendly} toggle
     * says so — read through {@link VehicleTargeting}, which resolves those fields by class-name
     * across SEM versions rather than hard-linking them. An ownerless PMC crew (a structure or
     * village garrison) is friendly by definition; it is on your side and has no commander.
     */
    private static VehicleMarker.Allegiance allegianceOf(Candidate c) {
        if (c.faction() == CrewFacts.Faction.PMC) return VehicleMarker.Allegiance.FRIENDLY;
        return c.factionFriendly() ? VehicleMarker.Allegiance.FRIENDLY : VehicleMarker.Allegiance.HOSTILE;
    }

    private static VehicleMarker marker(Candidate c, VehicleMarker.Allegiance allegiance) {
        return new VehicleMarker(c.driverId(), c.vehicleId(), c.x(), c.y(), c.z(), c.yaw(),
                c.kind(), allegiance, c.dimension());
    }

    /**
     * Which NATO symbol this hull draws as, cached in its persistent NBT: the answer comes from
     * {@code computed()} — a full vehicle-data compute — and a hull cannot change class, so paying
     * for it once per hull instead of once per hull per second is free accuracy.
     *
     * <p>{@code computed()} is the static datapack data, never {@code getEngineInfo()}: that field
     * is populated lazily on the hull's first {@code travel()}, so it is null for a hull that has
     * not moved yet, which on a parked emplacement is forever.
     */
    private static VehicleMarker.Kind kindOf(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        byte cached = data.getByte(KIND_KEY);
        if (cached > 0) return VehicleMarker.Kind.byId(cached - 1);

        VehicleMarker.Kind kind = computeKind(hull);
        data.putByte(KIND_KEY, (byte) (kind.ordinal() + 1));
        return kind;
    }

    private static VehicleMarker.Kind computeKind(VehicleEntity hull) {
        EngineType engine;
        try {
            engine = hull.computed().getEngineType();
        } catch (Throwable ignored) {
            engine = null;
        }
        if (engine == EngineType.SHIP) return VehicleMarker.Kind.SURFACE_COMBATANT;
        if (engine == EngineType.HELICOPTER || engine == EngineType.AIRCRAFT) return VehicleMarker.Kind.ROTARY_WING;
        if (engine == EngineType.FIXED) return VehicleMarker.Kind.EMPLACEMENT;
        // Everything that drives is armour, and an IFV is mechanized infantry — the one distinction
        // the vehicle data cannot make, so it comes off the id clue list HullFacts already owns.
        return HullFacts.isIfvHull(hull) ? VehicleMarker.Kind.MECHANIZED : VehicleMarker.Kind.ARMOR;
    }
}

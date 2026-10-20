package com.neoalive.tacz_sewv.map;

import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.invasion.InvasionTags;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;

/**
 * Per-viewer sticky combat identification for map fog of war.
 *
 * <p>Presence on the map is still proximity ({@link OwnedVehicleTracker}); this store only answers
 * "has this player IDd that contact?". Keys are the same entity network ids the marker packet uses
 * ({@code vehicleId} — hull id for crewed vehicles, unit id for on-foot infantry). Deadlines use
 * {@link MinecraftServer#getTickCount()}, matching the map sync clock.
 *
 * <p>Never saved, never sent on its own — the next marker sync rewrites {@link VehicleMarker.Kind}.
 */
public final class MapIdentity {

    private static final Object2ObjectMap<UUID, Int2LongOpenHashMap> BY_VIEWER =
            new Object2ObjectOpenHashMap<>();

    private MapIdentity() {}

    public static boolean enabled() {
        return SewvConfig.MAP_FOG_OF_WAR_ENABLED.get();
    }

    /**
     * Map-marker entity id for a locked living target: the hull when riding an SBW vehicle,
     * otherwise the entity itself (infantry markers use the unit id as {@code vehicleId}).
     */
    public static int mapTargetId(LivingEntity target) {
        Entity vehicle = target.getVehicle();
        if (vehicle instanceof VehicleEntity) return vehicle.getId();
        return target.getId();
    }

    /** True if {@code viewer} still holds a non-expired combat ID on {@code targetId}. */
    public static boolean identified(UUID viewer, int targetId, int nowTick) {
        if (viewer == null) return false;
        Int2LongOpenHashMap row = BY_VIEWER.get(viewer);
        if (row == null) return false;
        long expires = row.get(targetId);
        if (expires == 0L && !row.containsKey(targetId)) return false;
        if (expires <= nowTick) {
            row.remove(targetId);
            if (row.isEmpty()) BY_VIEWER.remove(viewer);
            return false;
        }
        return true;
    }

    public static void identify(ServerPlayer viewer, int targetId) {
        if (viewer == null || !enabled()) return;
        stamp(viewer.getUUID(), targetId, viewer.server.getTickCount());
    }

    /**
     * Stamp every online player who is {@code owner} or an OpenPAC ally of {@code owner}.
     * Offline viewers are skipped — logout already drops their row, and an offline UUID would
     * otherwise sit until server stop.
     */
    public static void identifyAllied(MinecraftServer server, UUID owner, int targetId) {
        if (server == null || owner == null || !enabled()) return;
        int now = server.getTickCount();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            UUID q = other.getUUID();
            if (q.equals(owner) || OpenPacCompat.allied(server, owner, q)) {
                stamp(q, targetId, now);
            }
        }
    }

    /**
     * Called after an accepted {@code setTarget}: grant sticky identity to the viewers who share
     * that observer's side (PMC owner + OpenPAC allies, or RU/US-friendly / invasion teammates).
     */
    public static void onTargetAcquired(AbstractUnit observer, LivingEntity target) {
        if (!enabled() || observer.level().isClientSide() || target == null || !target.isAlive()) return;
        MinecraftServer server = observer.getServer();
        if (server == null) return;

        int targetId = mapTargetId(target);

        if (observer instanceof PmcUnitEntity pmc) {
            UUID owner = pmc.getOwnerUUID();
            if (owner != null) {
                identifyAllied(server, owner, targetId);
                return;
            }
            String team = PmcOwnerSupport.ownerTeamOf(pmc);
            if (team != null && observer.level() instanceof ServerLevel level) {
                for (ServerPlayer member : PmcOwnerSupport.teamMembers(level, team)) {
                    identifyAllied(server, member.getUUID(), targetId);
                }
            }
            return;
        }

        if (observer instanceof RUunitEntity || observer instanceof USunitEntity) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (receivesFactionScoutIntel(player, observer)) {
                    identify(player, targetId);
                }
            }
        }
    }

    /**
     * RU/US lock helps a player only when that faction is "their side's scout": SEM friendly toggle,
     * or same invasion team. Hostile RU identifying US must not light up a PMC player's map.
     */
    private static boolean receivesFactionScoutIntel(ServerPlayer player, AbstractUnit observer) {
        if (VehicleTargeting.isFactionFriendly(observer)) return true;
        String invTeam = invasionTeamOf(observer);
        return invTeam != null && PmcOwnerSupport.playerOnTeam(player, invTeam);
    }

    @Nullable
    private static String invasionTeamOf(AbstractUnit crew) {
        String team = crew.getPersistentData().getString(InvasionTags.TEAM);
        return team == null || team.isEmpty() ? null : team;
    }

    private static void stamp(UUID viewer, int targetId, int nowTick) {
        int ttl = SewvConfig.MAP_IDENTITY_TTL_TICKS.get();
        long expires = (long) nowTick + ttl;
        Int2LongOpenHashMap row = BY_VIEWER.computeIfAbsent(viewer, u -> new Int2LongOpenHashMap());
        long prev = row.get(targetId);
        // Refresh only extends — never shorten an existing longer ID.
        if (row.containsKey(targetId) && prev >= expires) return;
        row.put(targetId, expires);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            BY_VIEWER.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BY_VIEWER.clear();
    }

    /** Test / debug: how many sticky ids a viewer currently holds (unexpired not guaranteed). */
    static int sizeOf(UUID viewer) {
        Int2LongMap row = BY_VIEWER.get(viewer);
        return row == null ? 0 : row.size();
    }
}

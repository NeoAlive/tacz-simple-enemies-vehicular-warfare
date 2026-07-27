package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.entity.ai.DroneSupport;
import com.neoalive.tacz_sewv.entity.ai.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.SupportRole;
import com.neoalive.tacz_sewv.entity.ai.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.command.CommandCoordinator;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOwnedVehicles;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
     *
     * <p>Still reset on {@link ServerStartingEvent}: the tick counter itself also restarts at 0 on
     * a new integrated server, and a leftover deadline from the previous run would suppress every
     * sync until that old tick number came around again — leaving the client on cleared/empty
     * markers (or, before the client clear, on ghosts from the last world).
     */
    private static int nextSend = Integer.MIN_VALUE;

    private OwnedVehicleTracker() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        nextSend = Integer.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        nextSend = Integer.MIN_VALUE;
    }

    /** Flush on join so a reconnect / new world gets a picture on the first tick, not after the interval. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            nextSend = Integer.MIN_VALUE;
        }
    }

    /** A crewed hull, resolved once per sync and then served to every player from this. */
    private record Candidate(VehicleMarker.Kind kind, CrewFacts.Faction faction, UUID pmcOwner,
                             boolean factionFriendly, MarkerOrder order, int driverId, int vehicleId,
                             double x, double y, double z, float yaw,
                             ResourceKey<Level> dimension, float healthFrac, float energyFrac) {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        int now = event.getServer().getTickCount();
        if (now < nextSend) return;
        nextSend = now + SewvConfig.MAP_SYNC_INTERVAL_TICKS.get();

        boolean infantry = SewvConfig.MAP_INFANTRY_ENABLED.get();
        List<Candidate> candidates = new ArrayList<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            collect(level, candidates);
            if (infantry) collectInfantry(level, candidates);
        }

        double spotRadius = SewvConfig.MAP_SPOT_RADIUS.get();
        double spotRadiusSq = spotRadius * spotRadius;
        List<CommandCoordinator.BattleFieldDebug> allFields = CommandCoordinator.battleFieldsDebug();
        for (ServerPlayer player : players) {
            List<VehicleMarker> markers = markersFor(player, candidates, spotRadiusSq);
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new PacketOwnedVehicles(markers, battleFieldsFor(markers, allFields)));
        }
    }

    /**
     * Package populated battlefields whose group appears in this player's marker list. Flank
     * marker world positions are decided here from the already-computed axis — the client only
     * draws. Does not call into influence rebuild.
     */
    private static List<BattleFieldMarker> battleFieldsFor(
            List<VehicleMarker> markers, List<CommandCoordinator.BattleFieldDebug> allFields) {
        if (allFields.isEmpty() || markers.isEmpty()) return List.of();
        Set<Integer> visibleGroups = new HashSet<>();
        double ySum = 0.0;
        int yN = 0;
        for (VehicleMarker m : markers) {
            if (m.groupId() == VehicleMarker.NO_GROUP) continue;
            visibleGroups.add(m.groupId());
            ySum += m.y();
            yN++;
        }
        if (visibleGroups.isEmpty()) return List.of();
        double y = yN > 0 ? ySum / yN : 64.0;

        List<BattleFieldMarker> out = new ArrayList<>();
        for (CommandCoordinator.BattleFieldDebug d : allFields) {
            if (!visibleGroups.contains(d.groupId())) continue;
            // Per-group Y from markers in that group when available.
            double gy = y;
            double gYSum = 0.0;
            int gYN = 0;
            for (VehicleMarker m : markers) {
                if (m.groupId() != d.groupId()) continue;
                gYSum += m.y();
                gYN++;
            }
            if (gYN > 0) gy = gYSum / gYN;

            // Left = rotate axis 90° CCW in XZ (same convention as InfluenceMap.scoreFlanks).
            double flX = 0.0, flZ = 0.0, frX = 0.0, frZ = 0.0;
            if (d.openFlankLeft()) {
                flX = BattleFieldMarker.flankMarkX(d.enemyX(), d.axisX(), d.axisZ(), +1);
                flZ = BattleFieldMarker.flankMarkZ(d.enemyZ(), d.axisX(), d.axisZ(), +1);
            }
            if (d.openFlankRight()) {
                frX = BattleFieldMarker.flankMarkX(d.enemyX(), d.axisX(), d.axisZ(), -1);
                frZ = BattleFieldMarker.flankMarkZ(d.enemyZ(), d.axisX(), d.axisZ(), -1);
            }
            out.add(new BattleFieldMarker(
                    d.groupId(), d.dimension(), gy,
                    d.friendlyX(), d.friendlyZ(),
                    d.enemyX(), d.enemyZ(),
                    d.axisX(), d.axisZ(),
                    d.openFlankLeft(), flX, flZ,
                    d.openFlankRight(), frX, frZ));
        }
        return out;
    }

    // ponytail: one whole-level entity scan per interval (once a second by default). Fine at the
    // entity counts this mod produces; if it ever shows on a profile, index crewed hulls as they
    // are crewed instead of rediscovering them.
    private static void collect(ServerLevel level, List<Candidate> candidates) {
        for (VehicleEntity hull : level.getEntities(EntityTypeTest.forClass(VehicleEntity.class), h -> true)) {
            // A mortar IS a VehicleEntity but has no seats, so it never has a crew to read a faction
            // off — that is the whole reason it needs its own branch rather than falling through the
            // passenger test as "empty". Same shape for recon drones (seatless, owned by NBT tag).
            if (hull instanceof MortarEntity mortar) {
                collectMortar(level, mortar, candidates);
                continue;
            }
            if (hull instanceof DroneEntity drone) {
                collectDrone(level, drone, candidates);
                continue;
            }

            CrewFacts.Faction faction = CrewFacts.factionOf(hull);
            if (faction == null) continue; // empty, mixed, or a player's own ride — not a marker

            Entity driver = hull.getFirstPassenger();
            if (!(driver instanceof AbstractUnit crew)) continue;

            candidates.add(new Candidate(
                    kindOf(hull), faction, CrewFacts.pmcOwner(hull),
                    VehicleTargeting.isFactionFriendly(crew), orderPreviewOf(crew),
                    driver.getId(), hull.getId(),
                    hull.getX(), hull.getY(), hull.getZ(), hull.getYRot(), level.dimension(),
                    healthFrac(hull), energyFrac(hull)));
        }
    }

    /**
     * A mortar is marked from its <b>crew</b>, which stands beside it: the tube has no owner field
     * and no seats, so the claim on the unit ({@code IMortarCrew}) is the only record that this
     * mortar is anybody's. Position still comes from the tube — that is the thing on the map — but
     * faction, owner and the entity an order would name all come from the crewman.
     *
     * <p>An unclaimed mortar is nobody's and is not shown, which also keeps a dropped tube out of
     * the enemy sighting picture.
     */
    // ponytail: MortarSupport.crewOf scans nearby units per mortar. Mortars are rare enough that
    // once a second costs nothing; if that stops being true, keep the claim on the tube instead.
    private static void collectMortar(ServerLevel level, MortarEntity mortar, List<Candidate> candidates) {
        AbstractUnit crew = MortarSupport.crewOf(mortar, null);
        if (crew == null) return;

        CrewFacts.Faction faction = CrewFacts.factionOfCrew(crew);
        if (faction == null) return;

        candidates.add(new Candidate(
                VehicleMarker.Kind.EMPLACEMENT, faction,
                crew instanceof PmcUnitEntity pmc ? pmc.getOwnerUUID() : null,
                VehicleTargeting.isFactionFriendly(crew), orderPreviewOf(crew),
                crew.getId(), mortar.getId(),
                mortar.getX(), mortar.getY(), mortar.getZ(), mortar.getYRot(), level.dimension(),
                healthFrac(mortar), energyFrac(mortar)));
    }

    /**
     * Recon drones are seatless, like mortars: faction/owner come from the engineer tagged on the
     * hull ({@link DroneSupport#crewOf}), position from the drone. Drawn as
     * {@link VehicleMarker.Kind#ROTARY_WING} — SBW's datapack {@code Type} is {@code Drone}, not
     * {@code HELICOPTER}.
     */
    private static void collectDrone(ServerLevel level, DroneEntity drone, List<Candidate> candidates) {
        AbstractUnit crew = DroneSupport.crewOf(drone);
        if (crew == null) return;

        CrewFacts.Faction faction = CrewFacts.factionOfCrew(crew);
        if (faction == null) return;

        candidates.add(new Candidate(
                VehicleMarker.Kind.ROTARY_WING, faction,
                crew instanceof PmcUnitEntity pmc ? pmc.getOwnerUUID() : null,
                VehicleTargeting.isFactionFriendly(crew), MarkerOrder.NONE,
                crew.getId(), drone.getId(),
                drone.getX(), drone.getY(), drone.getZ(), drone.getYRot(), level.dimension(),
                healthFrac(drone), energyFrac(drone)));
    }

    /**
     * On-foot units as their own markers, behind {@code mapInfantryEnabled}. Unlike the hull scan
     * this walks EVERY unit rather than the handful of crewed hulls, so it is the one part of this
     * sync whose cost scales with the size of a fight — which is why it has its own switch. A unit
     * riding a hull is skipped (the hull's marker already stands for it), and so is a mortar crew
     * (already shown as its EMPLACEMENT). The medic/engineer variant is read fresh every scan, not
     * cached like a hull's class, because a PMC's support role is what it is holding and can change.
     */
    // ponytail: O(all SEM units) per level per interval. Bounded by MAX_MARKERS on the spotted output
    // and the once-a-second cadence; the toggle is the escape hatch if a huge fight shows on a profile.
    private static void collectInfantry(ServerLevel level, List<Candidate> candidates) {
        for (AbstractUnit unit : level.getEntities(EntityTypeTest.forClass(AbstractUnit.class),
                u -> u.isAlive() && !u.isPassenger() && !MortarSupport.hasMortarClaim(u))) {
            CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);
            if (faction == null) continue;

            candidates.add(new Candidate(
                    infantryKind(unit), faction,
                    unit instanceof PmcUnitEntity pmc ? pmc.getOwnerUUID() : null,
                    VehicleTargeting.isFactionFriendly(unit), orderPreviewOf(unit),
                    unit.getId(), unit.getId(),
                    unit.getX(), unit.getY(), unit.getZ(), unit.getYRot(), level.dimension(),
                    Mth.clamp(unit.getHealth() / unit.getMaxHealth(), 0.0F, 1.0F),
                    VehicleMarker.NO_ENERGY));
        }
    }

    /**
     * Which infantry symbol a unit draws as. Covers both the dedicated RU/US medic/engineer entity
     * types ({@link VehicleTargeting#isMedic}/{@link VehicleTargeting#isEngineer}) and a PMC the
     * player has field-assigned by handing it a kit or a repair tool ({@link SupportRole#of}). Medic
     * wins a tie, matching {@code SupportRole.of}.
     */
    private static VehicleMarker.Kind infantryKind(AbstractUnit unit) {
        if (VehicleTargeting.isMedic(unit) || SupportRole.of(unit) == SupportRole.MEDIC) {
            return VehicleMarker.Kind.INFANTRY_MEDIC;
        }
        if (VehicleTargeting.isEngineer(unit) || SupportRole.of(unit) == SupportRole.ENGINEER) {
            return VehicleMarker.Kind.INFANTRY_ENGINEER;
        }
        return VehicleMarker.Kind.INFANTRY;
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
        // The order preview is only for units you command — a FRIENDLY garrison or a HOSTILE crew
        // has no player order to draw, and sending one would leak intent it should not.
        MarkerOrder order = allegiance == VehicleMarker.Allegiance.OWN ? c.order() : MarkerOrder.NONE;
        VehicleMarker.CommandRole role = VehicleMarker.CommandRole.NONE;
        int groupId = VehicleMarker.NO_GROUP;
        // Read-only: tagForDriver never mutates grouping/election.
        CommandCoordinator.CommandTag tag = CommandCoordinator.tagForDriver(c.driverId());
        if (tag != null) {
            groupId = tag.groupId();
            role = tag.commander() ? VehicleMarker.CommandRole.COMMANDER : VehicleMarker.CommandRole.MEMBER;
        }
        return new VehicleMarker(c.driverId(), c.vehicleId(), c.x(), c.y(), c.z(), c.yaw(),
                c.kind(), allegiance, c.faction(), order, c.dimension(),
                c.healthFrac(), c.energyFrac(), role, groupId);
    }

    private static float healthFrac(VehicleEntity hull) {
        float max = hull.getMaxHealth();
        return max > 0.0F ? Mth.clamp(hull.getHealth() / max, 0.0F, 1.0F) : 1.0F;
    }

    /**
     * Live energy fraction, or {@link VehicleMarker#NO_ENERGY} when the hull has no storage —
     * {@code getEnergy}/{@code getMaxEnergy} warn on every call for those hulls.
     */
    private static float energyFrac(VehicleEntity hull) {
        try {
            if (!hull.hasEnergyStorage()) return VehicleMarker.NO_ENERGY;
            int max = hull.getMaxEnergy();
            if (max <= 0) return VehicleMarker.NO_ENERGY;
            return Mth.clamp(hull.getEnergy() / (float) max, 0.0F, 1.0F);
        } catch (Throwable ignored) {
            return VehicleMarker.NO_ENERGY;
        }
    }

    /**
     * A commandable unit's current standing order, for the map's preview overlay. Only a PMC has
     * orders; everything else (and a PMC with none) answers {@link MarkerOrder#NONE}. An area task
     * (patrol / search / cruise) takes precedence, because it outranks the SEM order queue — the same
     * order {@code resolveDestination} reads them in.
     */
    private static MarkerOrder orderPreviewOf(AbstractUnit unit) {
        if (!(unit instanceof PmcUnitEntity pmc)) return MarkerOrder.NONE;
        IVehiclePatrol patrol = (IVehiclePatrol) pmc;
        if (patrol.sewv$isPatrolling()) {
            if (patrol.sewv$getPatrolMode() == IVehiclePatrol.MODE_CRUISE) {
                return MarkerOrder.cruise(patrol.sewv$getCruiseRoute());
            }
            BlockPos origin = patrol.sewv$getPatrolOrigin();
            if (origin != null) {
                MarkerOrder.Type mode = patrol.sewv$getPatrolMode() == IVehiclePatrol.MODE_SEARCH
                        ? MarkerOrder.Type.SEARCH : MarkerOrder.Type.PATROL;
                return MarkerOrder.area(mode, origin, patrol.sewv$getPatrolRadius());
            }
        }
        if (pmc.getOrder() == OrderType.MOVE_TO_POSITION) {
            Vec3 t = pmc.getMoveToTarget();
            if (t != null && !t.equals(Vec3.ZERO)) return MarkerOrder.move(BlockPos.containing(t));
        }
        return MarkerOrder.NONE;
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
        // Datapack Type "Drone" is not EngineType.HELICOPTER — pin it before the engine switch.
        if (hull instanceof DroneEntity) return VehicleMarker.Kind.ROTARY_WING;
        EngineType engine;
        try {
            engine = hull.computed().getEngineType();
        } catch (Throwable ignored) {
            engine = null;
        }
        if (engine == EngineType.SHIP) return VehicleMarker.Kind.SURFACE_COMBATANT;
        if (engine == EngineType.AIRCRAFT) return VehicleMarker.Kind.FIXED_WING;
        if (engine == EngineType.HELICOPTER) return VehicleMarker.Kind.ROTARY_WING;
        if (engine == EngineType.FIXED) return VehicleMarker.Kind.EMPLACEMENT;
        if (HullFacts.isMissileSystemHull(hull)) return VehicleMarker.Kind.MISSILE_SYSTEM;
        if (HullFacts.isAntiAirHull(hull)) return VehicleMarker.Kind.ANTI_AIR;
        // Everything that drives is armour, and an IFV is mechanized infantry — the one distinction
        // the vehicle data cannot make, so it comes off the id clue list HullFacts already owns.
        return HullFacts.isIfvHull(hull) ? VehicleMarker.Kind.MECHANIZED : VehicleMarker.Kind.ARMOR;
    }
}

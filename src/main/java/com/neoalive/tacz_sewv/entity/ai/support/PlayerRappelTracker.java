package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketPlayerRappelWires;
import com.neoalive.tacz_sewv.network.PacketPlayerSelfRappelLock;

/**
 * Player-driven rappel outside {@code DriveHelicopterGoal}'s AI path:
 * <ul>
 *   <li>Self: approach hover → settle → rope (same station as AI RAPPEL), optional unit drop.</li>
 *   <li>Crew keybind: immediate dual-rope drops while the player keeps flying.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class PlayerRappelTracker {

    private static final double MIN_AGL = 2.0;
    private static final double RAPPEL_HOVER_AGL = 10.0;
    private static final double ALT_DEADBAND = 2.5;
    private static final double RAPPEL_STABLE_XZ = 1.0;
    private static final double CAPTURE_GAIN = 0.15;
    private static final double CAPTURE_BLEND = 0.35;
    private static final double CAPTURE_MAX_SPEED = 0.15;
    private static final double CLIMB_RATE_CAP = 0.22;
    private static final double DESCEND_RATE_CAP = 0.22;
    private static final int MAX_AT_GUNNERS = 2;
    private static final int RAPPEL_TIMEOUT_TICKS = 6000;

    private static final Map<UUID, SelfSession> SELF = new HashMap<>();
    private static final Map<Integer, CrewSession> CREW = new HashMap<>();

    private PlayerRappelTracker() {}

    public static void startSelf(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (SELF.containsKey(player.getUUID())) {
            feedback(serverPlayer, "message.tacz_sewv.rappel.already");
            return;
        }
        if (!(player.getVehicle() instanceof VehicleEntity hull) || !HullFacts.isHelicopterHull(hull)) {
            feedback(serverPlayer, "message.tacz_sewv.rappel.not_heli");
            return;
        }
        if (hull.isWreck() || !hull.isAlive()) {
            feedback(serverPlayer, "message.tacz_sewv.rappel.not_heli");
            return;
        }
        double ground = RappelSupport.groundY(player.level(), hull.getX(), hull.getZ());
        if (hull.getY() - ground <= MIN_AGL) {
            feedback(serverPlayer, "message.tacz_sewv.rappel.too_low");
            return;
        }

        boolean playerDriver = hull.getFirstPassenger() == player;
        boolean withUnits = SewvConfig.PLAYER_SELF_RAPPEL_WITH_UNITS.get();
        long now = player.level().getGameTime();

        SelfSession session = new SelfSession(
                hull.getId(),
                player.getUUID(),
                hull.getX(),
                hull.getZ(),
                playerDriver,
                withUnits,
                now);
        SELF.put(player.getUUID(), session);

        syncWires(hull, true);
        sendLock(serverPlayer, PacketPlayerSelfRappelLock.hover(hull.getId()));

        if (!playerDriver && hull.getFirstPassenger() instanceof AbstractUnit) {
            // AI pilot owns the station-hover; we wait for stability then drop the player.
            DriveHelicopterGoal.setForcedRappel(hull);
        }

        feedback(serverPlayer, "message.tacz_sewv.rappel.self_ok");
    }

    public static void startCrew(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (SELF.containsKey(player.getUUID())) {
            feedback(serverPlayer, "message.tacz_sewv.rappel.already");
            return;
        }
        if (!(player.getVehicle() instanceof VehicleEntity hull) || !HullFacts.isHelicopterHull(hull)) {
            feedback(serverPlayer, "message.tacz_sewv.rappel.not_heli");
            return;
        }
        if (hull.getFirstPassenger() != player) {
            feedback(serverPlayer, "message.tacz_sewv.rappel.not_driver");
            return;
        }
        if (hull.isWreck() || !hull.isAlive()) {
            feedback(serverPlayer, "message.tacz_sewv.rappel.not_heli");
            return;
        }
        if (!RappelSupport.hasCrewRappelEligible(hull)) {
            feedback(serverPlayer, "message.tacz_sewv.rappel.no_cargo");
            return;
        }

        int hullId = hull.getId();
        CrewSession existing = CREW.get(hullId);
        if (existing != null) {
            existing.acceptNew = true;
            existing.driverUuid = player.getUUID();
            feedback(serverPlayer, "message.tacz_sewv.rappel.crew_ok");
            return;
        }

        CREW.put(hullId, new CrewSession(hullId, player.getUUID()));
        syncWires(hull, true);
        feedback(serverPlayer, "message.tacz_sewv.rappel.crew_ok");
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (SELF.isEmpty() && CREW.isEmpty()) return;

        tickSelf();
        tickCrew();
    }

    private static void tickSelf() {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            SELF.clear();
            return;
        }
        Iterator<Map.Entry<UUID, SelfSession>> it = SELF.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SelfSession> entry = it.next();
            SelfSession session = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isAlive()) {
                abortSelf(session, player, null);
                it.remove();
                continue;
            }

            Entity hullEntity = findEntity(session.hullId);
            VehicleEntity hull = hullEntity instanceof VehicleEntity v
                    && HullFacts.isHelicopterHull(v)
                    && v.isAlive()
                    && !v.isWreck() ? v : null;

            long now = player.level().getGameTime();
            if (now - session.startedAt >= RAPPEL_TIMEOUT_TICKS) {
                abortSelf(session, player, hull);
                it.remove();
                continue;
            }

            switch (session.phase) {
                case APPROACH, SETTLE -> {
                    if (hull == null) {
                        abortSelf(session, player, null);
                        it.remove();
                        continue;
                    }
                    if (player.getVehicle() != hull) {
                        abortSelf(session, player, hull);
                        it.remove();
                        continue;
                    }
                    if (session.playerDriver) {
                        stationHover(hull, session.lockX, session.lockZ);
                    }
                    if (!hoverStable(hull, session.lockX, session.lockZ)) {
                        session.stableAt = Long.MIN_VALUE;
                        break;
                    }
                    if (session.phase == SelfPhase.APPROACH) {
                        session.phase = SelfPhase.SETTLE;
                        session.stableAt = now;
                        break;
                    }
                    if (now - session.stableAt < RappelSupport.SETTLE_TICKS) {
                        break;
                    }
                    // Settle done — player onto a rope; optional units fill the other / follow.
                    beginPlayerRope(player, hull, session);
                    session.phase = SelfPhase.DESCENDING;
                    // Passenger + AI pilot: cargo is DriveHelicopterGoal's job. Only the
                    // player-driver sequence drops units from this tracker.
                    if (session.withUnits && session.playerDriver) {
                        session.dropUnits = true;
                    }
                }
                case DESCENDING -> {
                    // Finish in-flight unit slides even if the hull is gone.
                    if (hull != null && session.dropUnits) {
                        if (session.ropeMinusId < 0) tryStartUnitRope(hull, session, false);
                        if (session.ropePlusId < 0) tryStartUnitRope(hull, session, true);
                    }
                    if (hull != null) {
                        advanceUnitRope(hull, session, false);
                        advanceUnitRope(hull, session, true);
                    } else {
                        session.ropeMinusId = -1;
                        session.ropePlusId = -1;
                    }

                    boolean playerDone = true;
                    if (session.playerOnRope) {
                        playerDone = !RappelSupport.tickDescent(player, session.playerAx, session.playerAz);
                        if (playerDone) {
                            session.playerOnRope = false;
                            sendLock(player, PacketPlayerSelfRappelLock.off());
                        }
                    }

                    boolean unitsIdle = session.ropeMinusId < 0 && session.ropePlusId < 0;
                    boolean moreUnits = session.dropUnits && hull != null
                            && RappelSupport.hasCrewRappelEligible(hull);
                    if (playerDone && unitsIdle && !moreUnits) {
                        finishSelf(session, player, hull);
                        it.remove();
                    }
                }
            }
        }
    }

    private static void beginPlayerRope(ServerPlayer player, VehicleEntity hull, SelfSession session) {
        boolean plusX = (player.getId() & 1) == 0;
        // Prefer a free rope side if we will also drop units.
        if (session.withUnits) {
            plusX = session.ropePlusId < 0;
            if (session.ropeMinusId < 0 && session.ropePlusId >= 0) plusX = false;
        }
        Vec3 top = RappelSupport.ropeTopWorld(hull, plusX);
        player.stopRiding();
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.setPos(top.x, top.y, top.z);
        session.playerOnRope = true;
        session.playerAx = top.x;
        session.playerAz = top.z;
        sendLock(player, PacketPlayerSelfRappelLock.rope());
        if (session.playerDriver) {
            AirframeSupport.releaseInputs(hull);
        }
    }

    private static void tryStartUnitRope(VehicleEntity hull, SelfSession session, boolean plusX) {
        // Don't put a unit on the side the player just claimed this tick if still reserved — player
        // is already off; both sides are free for units.
        for (Entity passenger : List.copyOf(hull.getPassengers())) {
            if (!RappelSupport.isCrewRappelEligible(hull, passenger)) continue;
            if (!(passenger instanceof AbstractUnit unit)) continue;
            int id = unit.getId();
            if (id == session.ropeMinusId || id == session.ropePlusId) continue;

            if (session.atIssued == 0 || (session.atIssued < MAX_AT_GUNNERS
                    && unit.getRandom().nextDouble() < SewvConfig.AT_SECOND_GUNNER_CHANCE.get())) {
                if (SmallArmsSupport.issueAtWeapon(unit)) session.atIssued++;
            }

            Vec3 top = RappelSupport.ropeTopWorld(hull, plusX);
            unit.stopRiding();
            unit.setDeltaMovement(Vec3.ZERO);
            unit.fallDistance = 0.0F;
            unit.setPos(top.x, top.y, top.z);
            if (plusX) {
                session.ropePlusId = id;
                session.ropePlusAx = top.x;
                session.ropePlusAz = top.z;
            } else {
                session.ropeMinusId = id;
                session.ropeMinusAx = top.x;
                session.ropeMinusAz = top.z;
            }
            return;
        }
    }

    private static void advanceUnitRope(VehicleEntity hull, SelfSession session, boolean plusX) {
        int id = plusX ? session.ropePlusId : session.ropeMinusId;
        if (id < 0) return;
        double ax = plusX ? session.ropePlusAx : session.ropeMinusAx;
        double az = plusX ? session.ropePlusAz : session.ropeMinusAz;
        Entity e = hull.level().getEntity(id);
        boolean still = e instanceof AbstractUnit unit && RappelSupport.tickDescent(unit, ax, az);
        if (!still) {
            if (plusX) {
                session.ropePlusId = -1;
            } else {
                session.ropeMinusId = -1;
            }
        }
    }

    private static void abortSelf(SelfSession session, ServerPlayer player, VehicleEntity hull) {
        if (player != null) {
            sendLock(player, PacketPlayerSelfRappelLock.off());
        }
        if (hull != null) {
            if (session.playerDriver) {
                AirframeSupport.releaseInputs(hull);
            } else {
                DriveHelicopterGoal.clearForcedRappel(hull);
                DriveHelicopterGoal.setRappelRequested(hull, false);
            }
            syncWires(hull, false);
        } else if (session.hullId >= 0) {
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.ALL.noArg(),
                    new PacketPlayerRappelWires(session.hullId, false));
        }
    }

    private static void finishSelf(SelfSession session, ServerPlayer player, VehicleEntity hull) {
        sendLock(player, PacketPlayerSelfRappelLock.off());
        if (hull != null) {
            if (!session.playerDriver) {
                // AI may still be in RAPPEL — clear if no eligible cargo left for it.
                if (!RappelSupport.hasEligiblePassenger(hull)) {
                    DriveHelicopterGoal.clearForcedRappel(hull);
                    DriveHelicopterGoal.setRappelRequested(hull, false);
                }
            }
            syncWires(hull, false);
        } else {
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.ALL.noArg(),
                    new PacketPlayerRappelWires(session.hullId, false));
        }
    }

    private static void stationHover(VehicleEntity hull, double lockX, double lockZ) {
        double targetY = AirframeSupport.surfaceBelow(hull) + RAPPEL_HOVER_AGL;
        double dx = lockX - hull.getX();
        double dz = lockZ - hull.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        hull.setBackInputDown(false);
        hull.setLeftInputDown(false);
        hull.setRightInputDown(false);
        hull.setMouseMoveSpeedX(0.0F);
        hull.setMouseMoveSpeedY(0.0F);
        hull.setHoverMode(true);
        applyCollective(hull, targetY);

        double speed = Math.min(CAPTURE_MAX_SPEED, dist * CAPTURE_GAIN);
        double desX = dist > 1.0E-4 ? dx / dist * speed : 0.0;
        double desZ = dist > 1.0E-4 ? dz / dist * speed : 0.0;
        Vec3 v = hull.getDeltaMovement();
        hull.setDeltaMovement(
                Mth.lerp(CAPTURE_BLEND, v.x, desX),
                v.y,
                Mth.lerp(CAPTURE_BLEND, v.z, desZ));
    }

    private static void applyCollective(VehicleEntity hull, double desiredY) {
        double dy = desiredY - hull.getY();
        double vy = hull.getDeltaMovement().y;
        boolean climb = dy > ALT_DEADBAND && vy < CLIMB_RATE_CAP;
        boolean descend = dy < -ALT_DEADBAND && vy > -DESCEND_RATE_CAP;
        hull.setForwardInputDown(climb);
        hull.setDownInputDown(descend);
    }

    private static boolean hoverStable(VehicleEntity hull, double lockX, double lockZ) {
        double targetY = AirframeSupport.surfaceBelow(hull) + RAPPEL_HOVER_AGL;
        if (Math.abs(hull.getY() - targetY) > ALT_DEADBAND) return false;
        double dx = lockX - hull.getX();
        double dz = lockZ - hull.getZ();
        return dx * dx + dz * dz <= RAPPEL_STABLE_XZ * RAPPEL_STABLE_XZ;
    }

    private static void tickCrew() {
        Iterator<Map.Entry<Integer, CrewSession>> it = CREW.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, CrewSession> entry = it.next();
            CrewSession session = entry.getValue();
            Entity hullEntity = findEntity(session.hullId);
            if (!(hullEntity instanceof VehicleEntity hull)
                    || !hull.isAlive()
                    || hull.isWreck()
                    || !HullFacts.isHelicopterHull(hull)) {
                finishCrew(session, hullEntity instanceof VehicleEntity v ? v : null);
                it.remove();
                continue;
            }

            boolean driverOk = hull.getFirstPassenger() instanceof Player p
                    && session.driverUuid.equals(p.getUUID());
            if (!driverOk) {
                session.acceptNew = false;
            }

            advanceRope(hull, session, false);
            advanceRope(hull, session, true);

            if (session.acceptNew) {
                if (session.ropeMinusId < 0) tryStartRope(hull, session, false);
                if (session.ropePlusId < 0) tryStartRope(hull, session, true);
            }

            boolean ropesIdle = session.ropeMinusId < 0 && session.ropePlusId < 0;
            boolean moreCargo = RappelSupport.hasCrewRappelEligible(hull);
            if (ropesIdle && (!session.acceptNew || !moreCargo)) {
                finishCrew(session, hull);
                it.remove();
            }
        }
    }

    private static void tryStartRope(VehicleEntity hull, CrewSession session, boolean plusX) {
        for (Entity passenger : List.copyOf(hull.getPassengers())) {
            if (!RappelSupport.isCrewRappelEligible(hull, passenger)) continue;
            if (!(passenger instanceof AbstractUnit unit)) continue;
            int id = unit.getId();
            if (id == session.ropeMinusId || id == session.ropePlusId) continue;

            if (session.atIssued == 0 || (session.atIssued < MAX_AT_GUNNERS
                    && unit.getRandom().nextDouble() < SewvConfig.AT_SECOND_GUNNER_CHANCE.get())) {
                if (SmallArmsSupport.issueAtWeapon(unit)) session.atIssued++;
            }

            Vec3 top = RappelSupport.ropeTopWorld(hull, plusX);
            unit.stopRiding();
            unit.setDeltaMovement(Vec3.ZERO);
            unit.fallDistance = 0.0F;
            unit.setPos(top.x, top.y, top.z);
            if (plusX) {
                session.ropePlusId = id;
                session.ropePlusAx = top.x;
                session.ropePlusAz = top.z;
            } else {
                session.ropeMinusId = id;
                session.ropeMinusAx = top.x;
                session.ropeMinusAz = top.z;
            }
            return;
        }
    }

    private static void advanceRope(VehicleEntity hull, CrewSession session, boolean plusX) {
        int id = plusX ? session.ropePlusId : session.ropeMinusId;
        if (id < 0) return;
        double ax = plusX ? session.ropePlusAx : session.ropeMinusAx;
        double az = plusX ? session.ropePlusAz : session.ropeMinusAz;
        Entity e = hull.level().getEntity(id);
        boolean still = e instanceof AbstractUnit unit && RappelSupport.tickDescent(unit, ax, az);
        if (!still) {
            if (plusX) {
                session.ropePlusId = -1;
                session.ropePlusAx = Double.NaN;
                session.ropePlusAz = Double.NaN;
            } else {
                session.ropeMinusId = -1;
                session.ropeMinusAx = Double.NaN;
                session.ropeMinusAz = Double.NaN;
            }
        }
    }

    private static void finishCrew(CrewSession session, VehicleEntity hull) {
        if (hull != null) {
            syncWires(hull, false);
        } else {
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.ALL.noArg(),
                    new PacketPlayerRappelWires(session.hullId, false));
        }
    }

    private static void syncWires(VehicleEntity hull, boolean active) {
        NetworkHandler.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> hull),
                new PacketPlayerRappelWires(hull.getId(), active));
    }

    private static void sendLock(ServerPlayer player, PacketPlayerSelfRappelLock packet) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private static void feedback(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }

    private static Entity findEntity(int id) {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        for (var level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    private enum SelfPhase {
        APPROACH,
        SETTLE,
        DESCENDING
    }

    private static final class SelfSession {
        final int hullId;
        final UUID playerUuid;
        final double lockX;
        final double lockZ;
        final boolean playerDriver;
        final boolean withUnits;
        final long startedAt;

        SelfPhase phase = SelfPhase.APPROACH;
        long stableAt = Long.MIN_VALUE;
        boolean dropUnits;
        boolean playerOnRope;
        double playerAx = Double.NaN;
        double playerAz = Double.NaN;
        int ropeMinusId = -1;
        int ropePlusId = -1;
        double ropeMinusAx = Double.NaN;
        double ropeMinusAz = Double.NaN;
        double ropePlusAx = Double.NaN;
        double ropePlusAz = Double.NaN;
        int atIssued;

        SelfSession(int hullId, UUID playerUuid, double lockX, double lockZ,
                    boolean playerDriver, boolean withUnits, long startedAt) {
            this.hullId = hullId;
            this.playerUuid = playerUuid;
            this.lockX = lockX;
            this.lockZ = lockZ;
            this.playerDriver = playerDriver;
            this.withUnits = withUnits;
            this.startedAt = startedAt;
        }
    }

    private static final class CrewSession {
        final int hullId;
        UUID driverUuid;
        boolean acceptNew = true;
        int ropeMinusId = -1;
        int ropePlusId = -1;
        double ropeMinusAx = Double.NaN;
        double ropeMinusAz = Double.NaN;
        double ropePlusAx = Double.NaN;
        double ropePlusAz = Double.NaN;
        int atIssued;

        CrewSession(int hullId, UUID driverUuid) {
            this.hullId = hullId;
            this.driverUuid = driverUuid;
        }
    }
}

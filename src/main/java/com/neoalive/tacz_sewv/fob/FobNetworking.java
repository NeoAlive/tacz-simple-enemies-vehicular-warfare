package com.neoalive.tacz_sewv.fob;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketFobData;

public final class FobNetworking {

    private FobNetworking() {}

    public static void openCommandGui(ServerPlayer player, BlockPos commandPos) {
        sendSnapshot(player, commandPos, commandPos, FobGuiSnapshot.GuiKind.COMMAND);
    }

    public static void openParkingGui(ServerPlayer player, BlockPos parkingPos) {
        FobInstance fob = resolveFob(player.serverLevel(), parkingPos, player.getUUID());
        if (fob == null) return;
        sendSnapshot(player, fob.commandPos, parkingPos, FobGuiSnapshot.GuiKind.PARKING);
    }

    public static void sendRefresh(ServerPlayer player, BlockPos anchorPos, FobGuiSnapshot.GuiKind kind) {
        FobInstance fob = resolveFob(player.serverLevel(), anchorPos, player.getUUID());
        if (fob == null) return;
        sendSnapshot(player, fob.commandPos, anchorPos, kind);
    }

    public static boolean isOwner(ServerPlayer player, BlockPos anchorPos, ServerLevel level) {
        return resolveFob(level, anchorPos, player.getUUID()) != null;
    }

    @Nullable
    public static FobInstance resolveFob(ServerLevel level, BlockPos anchor, UUID owner) {
        FobManager mgr = FobManager.get(level);
        FobInstance fob = mgr.getFob(anchor);
        if (fob == null) {
            fob = mgr.getFobAt(anchor, level);
        }
        if (fob == null || !owner.equals(fob.owner)) {
            return null;
        }
        return fob;
    }

    private static void sendSnapshot(ServerPlayer player, BlockPos commandPos, BlockPos anchorPos,
                                     FobGuiSnapshot.GuiKind kind) {
        FobGuiSnapshot snap = buildSnapshot(player, commandPos, anchorPos, kind);
        if (snap == null) return;
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PacketFobData(snap));
    }

    @Nullable
    public static FobGuiSnapshot buildSnapshot(ServerPlayer player, BlockPos commandPos, BlockPos anchorPos,
                                                FobGuiSnapshot.GuiKind kind) {
        ServerLevel level = player.serverLevel();
        FobInstance fob = resolveFob(level, commandPos, player.getUUID());
        if (fob == null || !player.getUUID().equals(fob.owner)) return null;
        FobManager.get(level).validate(fob.commandPos, level);
        FobManager.get(level).pruneDeadAssignments(fob, level);

        List<FobGuiSnapshot.LivingRow> living = List.of();
        List<FobGuiSnapshot.VehicleRow> vehicles = List.of();
        if (kind == FobGuiSnapshot.GuiKind.COMMAND) {
            living = collectLiving(player, fob);
        } else {
            vehicles = collectVehicles(player, fob);
        }

        return new FobGuiSnapshot(
                kind,
                fob.commandPos,
                anchorPos,
                fob.valid,
                fob.invalidReason,
                fob.fobCommandActive,
                fob.scrambleActive,
                fob.threatScore,
                fob.stockpilePos,
                fob.parkingPos,
                living,
                vehicles);
    }

    /**
     * The FOB's own AABB, or null when the layout has not resolved one. The parking-field vehicle
     * list is a box query against the FOB's <b>own</b> level (not a cross-dimension sweep). The
     * command GUI living list is deliberately dimension-wide — owned PMCs may be anywhere in the
     * home world, not only inside the master fence.
     */
    @Nullable
    private static AABB masterBox(FobInstance fob, ServerLevel home) {
        FobSupport.refreshCachedAabbs(fob, home);
        return fob.cachedMasterAabb;
    }

    private static List<FobGuiSnapshot.LivingRow> collectLiving(ServerPlayer player, FobInstance fob) {
        List<FobGuiSnapshot.LivingRow> living = new ArrayList<>();
        ServerLevel home = player.serverLevel();
        for (Entity e : home.getAllEntities()) {
            if (!(e instanceof PmcUnitEntity pmc) || !pmc.isAlive()) continue;
            if (!PmcOwnerSupport.isOwner(player, pmc)) continue;
            living.add(new FobGuiSnapshot.LivingRow(
                    pmc.getUUID(),
                    pmc.getName().getString(),
                    fob.assignedLiving.contains(pmc.getUUID())));
        }
        living.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return living;
    }

    private static List<FobGuiSnapshot.VehicleRow> collectVehicles(ServerPlayer player, FobInstance fob) {
        List<FobGuiSnapshot.VehicleRow> vehicles = new ArrayList<>();
        ServerLevel home = player.serverLevel();
        AABB box = masterBox(fob, home);
        if (box == null) return vehicles;
        for (VehicleEntity hull : home.getEntitiesOfClass(VehicleEntity.class, box)) {
            if (!hull.isAlive() || hull.isWreck()) continue;
            // Anything standing in the FOB that is not somebody else's. An uncrewed hull carries
            // no ownership signal at all, so the perimeter is what makes it yours; an RU/US crew,
            // another player's PMC, or another player riding is what makes it theirs.
            if (!FobSupport.vehicleClaimableBy(hull, player.getUUID())) continue;
            String registryId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(hull.getType()).toString();
            vehicles.add(new FobGuiSnapshot.VehicleRow(hull.getUUID(), registryId,
                    fob.assignedVehicles.contains(hull.getUUID()), positionText(hull)));
        }
        vehicles.sort((a, b) -> a.registryId().compareToIgnoreCase(b.registryId()));
        return vehicles;
    }

    private static String positionText(Entity entity) {
        BlockPos p = entity.blockPosition();
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    public static int routeToFob(ServerPlayer player, BlockPos commandPos) {
        ServerLevel level = player.serverLevel();
        FobInstance fob = resolveFob(level, commandPos, player.getUUID());
        if (fob == null) return 0;
        int total = routeToFob(level, fob);
        if (total == 0) {
            com.neoalive.tacz_sewv.order.OrderReport.fail(player,
                    com.neoalive.tacz_sewv.order.OrderFailure.UNREACHABLE);
        }
        return total;
    }

    /**
     * Server-side recall: every assigned crewed hull and assigned on-foot PMC returns to the
     * parking pad. Same orders as the GUI / quick-command path; no player click required.
     * Order feedback to the owner is best-effort when they are online.
     */
    public static int routeToFob(ServerLevel level, FobInstance fob) {
        if (fob.parkingPos == null) return 0;
        Vec3 parkDest = Vec3.atBottomCenterOf(fob.parkingPos);
        BlockPos commandPos = fob.commandPos;

        // Everyone aboard a hull that is driving itself home, driver included. Only the driver
        // takes the order, but the rest of the crew must be left alone: the infantry pass below
        // dismounts what it touches, and dismounting a gunner would send the tank off unmanned.
        java.util.Set<UUID> riding = new java.util.HashSet<>();
        int vehicles = 0;
        for (UUID vehicleId : fob.assignedVehicles) {
            Entity e = findEntity(level, vehicleId);
            if (!(e instanceof VehicleEntity hull)) continue;
            if (!(hull.getFirstPassenger() instanceof PmcUnitEntity driver)) continue;
            // The hull's assignment is the authority here, not the driver's — a crew the player
            // never stamped still drives an assigned tank home.
            if (!fob.owner.equals(driver.getOwnerUUID())) continue;
            orderRouteMove(driver, parkDest, commandPos);
            vehicles++;
            for (Entity passenger : hull.getPassengers()) {
                riding.add(passenger.getUUID());
            }
        }

        int infantry = 0;
        for (UUID livingId : fob.assignedLiving) {
            if (riding.contains(livingId)) continue;
            Entity e = findEntity(level, livingId);
            if (!(e instanceof PmcUnitEntity pmc)) continue;
            if (!fob.owner.equals(pmc.getOwnerUUID())) continue;
            dismountForRoute(pmc);
            orderRouteMove(pmc, parkDest, commandPos);
            infantry++;
        }

        int total = vehicles + infantry;
        if (total > 0) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(fob.owner);
            if (owner != null) {
                com.neoalive.tacz_sewv.order.OrderReport.ok(owner,
                        Component.translatable("message.tacz_sewv.fob.route_sent", vehicles, infantry));
            }
            FobDebug.log("route-to-FOB at {} — {} vehicles, {} infantry",
                    commandPos, vehicles, infantry);
        }
        return total;
    }

    private static void orderRouteMove(PmcUnitEntity pmc, Vec3 dest, BlockPos commandPos) {
        if (PmcDownedSupport.isDowned(pmc)) return;
        // A fresh recall supersedes the scramble stand-down latch (that latch only blocks remount
        // while the alarm is still up; issuing a route clears it so recall is never gated on it).
        FobSupport.clearScrambleStoodDown(pmc);
        pmc.setOrder(net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.MOVE_TO_POSITION);
        pmc.setMoveToTarget(dest);
        clearBoarding(pmc);
        FobSupport.markRoutePending(pmc, commandPos);
        FobDebug.logEntity(pmc, "route started -> parking at {}", dest);
    }

    private static void dismountForRoute(PmcUnitEntity pmc) {
        if (pmc.isPassenger()) {
            pmc.stopRiding();
        }
        clearBoarding(pmc);
    }

    private static void clearBoarding(PmcUnitEntity pmc) {
        if (pmc instanceof com.neoalive.tacz_sewv.bridge.IVehicleBoarder boarder) {
            boarder.tacz_sewv$setBoarding(false);
            boarder.tacz_sewv$setMountTargetId(-1);
        }
    }

    @Nullable
    private static Entity findEntity(ServerLevel level, UUID id) {
        Entity e = level.getEntity(id);
        if (e != null) return e;
        for (ServerLevel dim : level.getServer().getAllLevels()) {
            e = dim.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }
}

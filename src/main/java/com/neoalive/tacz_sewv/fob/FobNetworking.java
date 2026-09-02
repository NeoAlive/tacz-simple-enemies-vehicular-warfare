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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

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

    private static List<FobGuiSnapshot.LivingRow> collectLiving(ServerPlayer player, FobInstance fob) {
        List<FobGuiSnapshot.LivingRow> living = new ArrayList<>();
        ServerLevel home = player.serverLevel();
        FobSupport.refreshCachedAabbs(fob, home);
        for (ServerLevel dim : player.server.getAllLevels()) {
            for (Entity e : dim.getAllEntities()) {
                if (!(e instanceof PmcUnitEntity pmc)) continue;
                if (!PmcOwnerSupport.isOwner(player, pmc)) continue;
                if (!FobSupport.withinMasterAabb(fob, pmc, home)) continue;
                living.add(new FobGuiSnapshot.LivingRow(
                        pmc.getUUID(),
                        pmc.getName().getString(),
                        fob.assignedLiving.contains(pmc.getUUID())));
            }
        }
        living.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return living;
    }

    private static List<FobGuiSnapshot.VehicleRow> collectVehicles(ServerPlayer player, FobInstance fob) {
        List<FobGuiSnapshot.VehicleRow> vehicles = new ArrayList<>();
        ServerLevel home = player.serverLevel();
        FobSupport.refreshCachedAabbs(fob, home);
        for (ServerLevel dim : player.server.getAllLevels()) {
            for (Entity e : dim.getAllEntities()) {
                if (!(e instanceof VehicleEntity hull)) continue;
                if (!FobSupport.vehicleOwnedBy(hull, player.getUUID())) continue;
                if (!FobSupport.withinMasterAabb(fob, hull, home)) continue;
                String reg = hull.getType().toString();
                String posText = positionText(hull);
                boolean assigned = fob.assignedVehicles.contains(hull.getUUID());
                vehicles.add(new FobGuiSnapshot.VehicleRow(hull.getUUID(), reg, assigned, posText));
            }
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
        if (fob == null || fob.parkingPos == null) return 0;
        Vec3 parkDest = Vec3.atBottomCenterOf(fob.parkingPos);

        java.util.Set<UUID> driving = new java.util.HashSet<>();
        int vehicles = 0;
        for (UUID vehicleId : fob.assignedVehicles) {
            Entity e = findEntity(level, vehicleId);
            if (!(e instanceof VehicleEntity hull)) continue;
            Entity driver = hull.getFirstPassenger();
            if (!(driver instanceof PmcUnitEntity pmc)) continue;
            if (!fob.assignedLiving.contains(pmc.getUUID())) continue;
            orderRouteMove(pmc, parkDest, commandPos);
            driving.add(pmc.getUUID());
            vehicles++;
        }

        int infantry = 0;
        for (UUID livingId : fob.assignedLiving) {
            if (driving.contains(livingId)) continue;
            Entity e = findEntity(level, livingId);
            if (!(e instanceof PmcUnitEntity pmc)) continue;
            dismountForRoute(pmc);
            orderRouteMove(pmc, parkDest, commandPos);
            infantry++;
        }

        int total = vehicles + infantry;
        if (total > 0) {
            com.neoalive.tacz_sewv.order.OrderReport.ok(player,
                    Component.translatable("message.tacz_sewv.fob.route_sent", vehicles, infantry));
        } else {
            com.neoalive.tacz_sewv.order.OrderReport.fail(player,
                    com.neoalive.tacz_sewv.order.OrderFailure.UNREACHABLE);
        }
        return total;
    }

    private static void orderRouteMove(PmcUnitEntity pmc, Vec3 dest, BlockPos commandPos) {
        pmc.setOrder(net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.MOVE_TO_POSITION);
        pmc.setMoveToTarget(dest);
        FobSupport.markRoutePending(pmc, commandPos);
        FobDebug.logEntity(pmc, "route started -> parking at {}", dest);
    }

    private static void dismountForRoute(PmcUnitEntity pmc) {
        if (pmc.isPassenger()) {
            pmc.stopRiding();
        }
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

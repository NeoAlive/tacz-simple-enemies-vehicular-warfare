package com.neoalive.tacz_sewv.fob;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketFobData;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

public final class FobNetworking {

    private FobNetworking() {}

    public static void openGui(ServerPlayer player, BlockPos commandPos) {
        FobGuiSnapshot snap = buildSnapshot(player, commandPos);
        if (snap == null) return;
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketFobData(snap));
    }

    public static void sendRefresh(ServerPlayer player, BlockPos commandPos) {
        FobGuiSnapshot snap = buildSnapshot(player, commandPos);
        if (snap == null) return;
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketFobData(snap));
    }

    public static boolean isOwner(ServerPlayer player, BlockPos commandPos, ServerLevel level) {
        FobInstance fob = FobManager.get(level).getFob(commandPos);
        return fob != null && player.getUUID().equals(fob.owner);
    }

    public static FobGuiSnapshot buildSnapshot(ServerPlayer player, BlockPos commandPos) {
        ServerLevel level = player.serverLevel();
        FobManager mgr = FobManager.get(level);
        FobInstance fob = mgr.getFob(commandPos);
        if (fob == null || !player.getUUID().equals(fob.owner)) return null;
        mgr.validate(commandPos, level);
        mgr.pruneDeadAssignments(fob, level);

        List<FobGuiSnapshot.LivingRow> living = new ArrayList<>();
        for (ServerLevel dim : player.server.getAllLevels()) {
            for (Entity e : dim.getAllEntities()) {
                if (!(e instanceof PmcUnitEntity pmc)) continue;
                if (!PmcOwnerSupport.isOwner(player, pmc)) continue;
                living.add(new FobGuiSnapshot.LivingRow(
                        pmc.getUUID(),
                        pmc.getName().getString(),
                        fob.assignedLiving.contains(pmc.getUUID())));
            }
        }

        List<FobGuiSnapshot.VehicleRow> vehicles = new ArrayList<>();
        List<FobGuiSnapshot.AssignedVehicleRow> assignedRows = new ArrayList<>();
        for (ServerLevel dim : player.server.getAllLevels()) {
            for (Entity e : dim.getAllEntities()) {
                if (!(e instanceof VehicleEntity hull)) continue;
                if (!FobSupport.vehicleOwnedBy(hull, player.getUUID())) continue;
                String reg = hull.getType().toString();
                String posText = positionText(hull);
                boolean assigned = fob.assignedVehicles.contains(hull.getUUID());
                vehicles.add(new FobGuiSnapshot.VehicleRow(hull.getUUID(), reg, assigned, posText));
                if (assigned) {
                    assignedRows.add(new FobGuiSnapshot.AssignedVehicleRow(hull.getUUID(), reg, posText));
                }
            }
        }

        return new FobGuiSnapshot(
                fob.commandPos,
                fob.valid,
                fob.invalidReason,
                fob.fobCommandActive,
                fob.scrambleActive,
                fob.threatScore,
                fob.stockpilePos,
                fob.parkingPos,
                living,
                vehicles,
                assignedRows);
    }

    private static String positionText(Entity entity) {
        BlockPos p = entity.blockPosition();
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    public static int routeVehiclesToFob(ServerPlayer player, BlockPos commandPos) {
        ServerLevel level = player.serverLevel();
        FobManager mgr = FobManager.get(level);
        FobInstance fob = mgr.getFob(commandPos);
        if (fob == null || !player.getUUID().equals(fob.owner) || fob.parkingPos == null) return 0;
        Vec3 dest = Vec3.atBottomCenterOf(fob.parkingPos);
        int count = 0;
        for (UUID vehicleId : fob.assignedVehicles) {
            Entity e = level.getEntity(vehicleId);
            if (e == null) {
                for (ServerLevel dim : player.server.getAllLevels()) {
                    e = dim.getEntity(vehicleId);
                    if (e != null) break;
                }
            }
            if (!(e instanceof VehicleEntity hull)) continue;
            Entity driver = hull.getFirstPassenger();
            if (!(driver instanceof PmcUnitEntity pmc)) continue;
            pmc.setOrder(OrderType.MOVE_TO_POSITION);
            pmc.setMoveToTarget(dest);
            count++;
        }
        if (count > 0) {
            OrderReport.ok(player, Component.translatable("message.tacz_sewv.fob.route_sent", count));
        } else {
            OrderReport.fail(player, OrderFailure.UNREACHABLE);
        }
        return count;
    }
}

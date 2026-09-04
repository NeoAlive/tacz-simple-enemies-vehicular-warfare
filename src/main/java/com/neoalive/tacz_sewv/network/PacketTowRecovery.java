package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.ITowRecovery;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * PMC tow-recovery order: victim hull id + tower hull id. The tower's driver executes the pull.
 */
public class PacketTowRecovery {

    private final int victimHullId;
    private final int towerHullId;

    public PacketTowRecovery(int victimHullId, int towerHullId) {
        this.victimHullId = victimHullId;
        this.towerHullId = towerHullId;
    }

    public PacketTowRecovery(FriendlyByteBuf buf) {
        this.victimHullId = buf.readVarInt();
        this.towerHullId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.victimHullId);
        buf.writeVarInt(this.towerHullId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;

            Entity victimEntity = player.level().getEntity(this.victimHullId);
            Entity towerEntity = player.level().getEntity(this.towerHullId);
            if (!(victimEntity instanceof VehicleEntity victim)
                    || !(towerEntity instanceof VehicleEntity tower)) {
                OrderReport.fail(player, OrderFailure.TARGET_GONE);
                return;
            }
            if (!victim.isAlive() || !tower.isAlive() || victim.isWreck() || tower.isWreck()) {
                OrderReport.fail(player, OrderFailure.TARGET_GONE);
                return;
            }
            if (victim == tower) {
                OrderReport.fail(player, OrderFailure.WRONG_HULL);
                return;
            }
            if (!TowRecoverySupport.isOwnedPmcHull(victim, sp) || !TowRecoverySupport.isOwnedPmcHull(tower, sp)) {
                OrderReport.fail(player, OrderFailure.NOT_OWNED);
                return;
            }
            // OrderReport.fail is console-only — a player click on a CIWS/plane/mortar/SPH must
            // land on the action bar, not vanish as a silent no-op.
            if (!TowRecoverySupport.isTowVictimCandidate(victim)) {
                sp.displayClientMessage(Component.translatable("message.tacz_sewv.tow.cannot_be_towed")
                        .withStyle(ChatFormatting.RED), true);
                OrderReport.fail(player, OrderFailure.WRONG_HULL);
                return;
            }
            if (!TowRecoverySupport.isTowTowerCandidate(tower)) {
                sp.displayClientMessage(Component.translatable("message.tacz_sewv.tow.cannot_tow")
                        .withStyle(ChatFormatting.RED), true);
                OrderReport.fail(player, OrderFailure.WRONG_HULL);
                return;
            }
            if (!victim.getTowedByUUID().isBlank() || !tower.getTowedByUUID().isBlank() || tower.isTowingAny()) {
                OrderReport.fail(player, OrderFailure.BUSY_CREWING);
                return;
            }

            Entity driverEntity = tower.getFirstPassenger();
            if (!(driverEntity instanceof PmcUnitEntity driver) || !driver.isOwnedBy(sp)) {
                OrderReport.fail(player, OrderFailure.NOT_A_UNIT);
                return;
            }

            // Assign the victim before clearing any prior link — avoids a tick with no order.
            int priorVictimId = TowRecoverySupport.hasTowOrder(driver)
                    ? ((ITowRecovery) driver).tacz_sewv$getTowVictimId() : -1;
            TowRecoverySupport.assignVictim(driver, victim.getId());
            if (priorVictimId != victim.getId() && tower.isTowingAny()) {
                tower.clearTowingInfo();
            }
            if (tower.distanceTo(victim) <= SewvConfig.TOW_PLAYER_ORDER_MAX_DISTANCE.get()) {
                TowRecoverySupport.tryLink(tower, victim);
            }

            ((IEscort) driver).tacz_sewv$setEscortTargetId(-1);
            IVehicleBoarder boarder = (IVehicleBoarder) driver;
            boarder.tacz_sewv$setBoarding(false);
            boarder.tacz_sewv$setMountTargetId(-1);
            driver.setOrder(OrderType.FREE_FIRE);

            OrderReport.ok(player, net.minecraft.network.chat.Component.translatable(
                            "message.tacz_sewv.tow.ordered", tower.getDisplayName(), victim.getDisplayName())
                    .withStyle(ChatFormatting.GREEN));
        });
        ctx.get().setPacketHandled(true);
    }
}

package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.entity.ai.support.Type63Support;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderGuard;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Assigns a unit to a seatless emplacement — a mortar or Type-63 MLRS. The client broadcasts
 * every owned unit it can see; the server picks at most one.
 */
public class PacketManMortar {

    private final List<Integer> unitIds;
    private final int emplacementId;

    public PacketManMortar(List<Integer> unitIds, int emplacementId) {
        this.unitIds = unitIds;
        this.emplacementId = emplacementId;
    }

    public PacketManMortar(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
        this.emplacementId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.emplacementId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            Entity target = player.level().getEntity(this.emplacementId);
            if (target instanceof MortarEntity mortar && mortar.isAlive()) {
                handleClaim(player, mortar, MortarSupport.isMortarClaimed(mortar, null));
            } else if (target instanceof Type63Entity type63 && type63.isAlive()) {
                handleClaim(player, type63, Type63Support.isClaimed(type63, null));
            } else {
                OrderReport.fail(player, OrderFailure.MORTAR_GONE);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private void handleClaim(Player player, Entity emplacement, boolean taken) {
        if (taken) {
            OrderReport.fail(player, OrderFailure.MORTAR_TAKEN);
            return;
        }

        PmcUnitEntity assigned = nearestFreeUnit(player, emplacement);
        if (assigned == null) {
            NetworkHandler.orderFeedback(player, "message.tacz_sewv.mortar.ordered", 0,
                    ChatFormatting.GRAY);
            return;
        }
        claim(assigned, emplacement);
        NetworkHandler.sendOrderFeedback(player, Component.translatable(
                "message.tacz_sewv.mortar.ordered.single", assigned.getDisplayName())
                .copy().withStyle(ChatFormatting.GREEN));
    }

    private PmcUnitEntity nearestFreeUnit(Player player, Entity emplacement) {
        PmcUnitEntity best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int unitId : this.unitIds) {
            Entity entity = player.level().getEntity(unitId);

            if (!(entity instanceof PmcUnitEntity pmc)) {
                OrderReport.fail(player, OrderFailure.NOT_A_UNIT);
                continue;
            }
            if (!pmc.isOwnedBy(player)) {
                OrderReport.fail(player, OrderFailure.NOT_OWNED);
                continue;
            }
            if (OrderGuard.rejectIfDowned(player, pmc)) continue;
            if (!pmc.isAlive()) {
                OrderReport.fail(player, OrderFailure.UNIT_DEAD);
                continue;
            }
            if (MortarSupport.hasMortarClaim(pmc)) {
                OrderReport.fail(player, OrderFailure.BUSY_MORTAR, pmc);
                continue;
            }
            if (pmc.getVehicle() != null) {
                OrderReport.fail(player, OrderFailure.BUSY_CREWING, pmc);
                continue;
            }

            double distSq = pmc.distanceToSqr(emplacement);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = pmc;
            }
        }
        return best;
    }

    private void claim(PmcUnitEntity unit, Entity emplacement) {
        if (emplacement instanceof MortarEntity mortar) {
            MortarSupport.claim(unit, mortar);
        } else if (emplacement instanceof Type63Entity type63) {
            Type63Support.claim(unit, type63);
        }

        IVehicleBoarder boarder = (IVehicleBoarder) unit;
        boarder.tacz_sewv$setBoarding(false);
        boarder.tacz_sewv$setMountTargetId(-1);
        ((IEscort) unit).tacz_sewv$setEscortTargetId(-1);
        TowRecoverySupport.clearIfTowering(unit);
    }
}

package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
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
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Assigns a unit to a mortar. The client broadcasts every owned unit it can see, the
 * same as a board order; the server picks at most one, because a mortar takes exactly
 * one crew.
 */
public class PacketManMortar {

    private final List<Integer> unitIds;
    private final int mortarId;

    public PacketManMortar(List<Integer> unitIds, int mortarId) {
        this.unitIds = unitIds;
        this.mortarId = mortarId;
    }

    public PacketManMortar(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
        this.mortarId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.mortarId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            Entity target = player.level().getEntity(this.mortarId);
            if (!(target instanceof MortarEntity mortar) || !mortar.isAlive()) {
                OrderReport.fail(player, OrderFailure.MORTAR_GONE);
                return;
            }
            if (MortarSupport.isMortarClaimed(mortar, null)) {
                // One crew per mortar. Without this, every unit in range would converge on
                // the same tube and fight over its aim.
                OrderReport.fail(player, OrderFailure.MORTAR_TAKEN);
                return;
            }

            PmcUnitEntity assigned = nearestFreeUnit(player, mortar);
            if (assigned == null) {
                // Aggregate: the loop has already reported why each candidate was passed over, and
                // OrderReport drops this line whenever it did.
                NetworkHandler.orderFeedback(player, "message.tacz_sewv.mortar.ordered", 0,
                        ChatFormatting.GRAY);
                return;
            }
            claim(assigned, mortar);
            NetworkHandler.sendOrderFeedback(player, Component.translatable(
                    "message.tacz_sewv.mortar.ordered.single", assigned.getDisplayName())
                    .copy().withStyle(ChatFormatting.GREEN));
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * The closest of the player's units that isn't already on a mortar. The client sends
     * everything it owns nearby, but only one unit can take the mortar, so picking here
     * rather than ordering them all avoids marching a squad over to have all but one
     * turn around again.
     */
    private PmcUnitEntity nearestFreeUnit(Player player, MortarEntity mortar) {
        PmcUnitEntity best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int unitId : this.unitIds) {
            Entity entity = player.level().getEntity(unitId);

            // Ownership-check each unit individually so a spoofed packet can't commandeer
            // another player's units by id.
            if (!(entity instanceof PmcUnitEntity pmc)) {
                OrderReport.fail(player, OrderFailure.NOT_A_UNIT);
                continue;
            }
            if (!pmc.isOwnedBy(player)) {
                OrderReport.fail(player, OrderFailure.NOT_OWNED);
                continue;
            }
            if (!pmc.isAlive()) {
                OrderReport.fail(player, OrderFailure.UNIT_DEAD);
                continue;
            }
            // One mortar per unit: a crew already holding one isn't up for reassignment.
            if (MortarSupport.hasMortarClaim(pmc)) {
                OrderReport.fail(player, OrderFailure.BUSY_MORTAR, pmc);
                continue;
            }
            if (pmc.getVehicle() != null) {
                OrderReport.fail(player, OrderFailure.BUSY_CREWING, pmc);
                continue;
            }

            double distSq = pmc.distanceToSqr(mortar);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = pmc;
            }
        }
        return best;
    }

    private void claim(PmcUnitEntity unit, MortarEntity mortar) {
        MortarSupport.claim(unit, mortar);

        // A unit can't walk to a mortar and board a vehicle at once, and the board order
        // is the one being replaced here.
        IVehicleBoarder boarder = (IVehicleBoarder) unit;
        boarder.tacz_sewv$setBoarding(false);
        boarder.tacz_sewv$setMountTargetId(-1);
        // Same for a standing escort order: EscortGoal and ManMortarGoal tie at goal priority 1,
        // and vanilla only yields a held flag to a strictly higher priority — so without this an
        // escorting unit keeps escorting despite the "mortar crewed" feedback saying otherwise.
        ((IEscort) unit).tacz_sewv$setEscortTargetId(-1);
    }
}

package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.MortarSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.List;
import java.util.function.Supplier;

/**
 * "Escort that entity." Carries the owned units to order and the network id of the entity they
 * should stick beside — worked server-side by {@code EscortGoal}. Modelled on
 * {@link PacketBoardVehicle} (a unit-id list plus one target entity id).
 */
public class PacketEscort {

    private final List<Integer> unitIds;
    private final int targetEntityId;

    public PacketEscort(List<Integer> unitIds, int targetEntityId) {
        this.unitIds = unitIds;
        this.targetEntityId = targetEntityId;
    }

    public PacketEscort(FriendlyByteBuf buf) {
        this.unitIds = buf.readList(FriendlyByteBuf::readVarInt);
        this.targetEntityId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.targetEntityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            int ordered = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                // Ownership-check each unit so a spoofed packet can't command another player's units.
                if (!(e instanceof PmcUnitEntity pmc) || !pmc.isOwnedBy(player)) continue;
                // Leave a crew committed to a mortar on its tube, exactly as the board order does.
                if (MortarSupport.hasMortarClaim(pmc)) continue;

                ((IEscort) pmc).tacz_sewv$setEscortTargetId(this.targetEntityId);
                // Escort and board both drive the unit on foot at goal priority 1 — mutually
                // exclusive. Escort wins the moment it's ordered.
                IVehicleBoarder boarder = (IVehicleBoarder) pmc;
                boarder.tacz_sewv$setBoarding(false);
                boarder.tacz_sewv$setMountTargetId(-1);
                // A CEASE_FIRE escort would trail the VIP without defending it — free fire so it
                // fights threats it can reach from the VIP's side.
                pmc.setOrder(OrderType.FREE_FIRE);
                ordered++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.escort.ordered", ordered,
                    ChatFormatting.GREEN, ordered);
        });
        ctx.get().setPacketHandled(true);
    }
}

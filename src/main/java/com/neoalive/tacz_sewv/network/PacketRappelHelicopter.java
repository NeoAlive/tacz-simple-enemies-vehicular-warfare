package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;

/**
 * Player → server: order owned PMC helicopter pilots to run the rappel sequence
 * ({@link DriveHelicopterGoal#setRappelRequested}). Mechanism is unchanged — this only fires it.
 */
public final class PacketRappelHelicopter {

    private final List<Integer> unitIds;

    public PacketRappelHelicopter(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketRappelHelicopter(FriendlyByteBuf buf) {
        this.unitIds = buf.readList(FriendlyByteBuf::readVarInt);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            int ordered = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                if (!(e instanceof PmcUnitEntity pmc) || !pmc.isOwnedBy(player)) continue;
                if (!(pmc.getVehicle() instanceof VehicleEntity v)) continue;
                if (v.getFirstPassenger() != pmc) continue;
                if (!(v.getEngineInfo() instanceof EngineInfo.Helicopter)) continue;
                // Empty cargo still accepts the order — Stage 5 exits promptly after settle.
                DriveHelicopterGoal.setRappelRequested(v, true);
                ordered++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.heli.rappel",
                    ordered, ChatFormatting.GREEN, ordered);
        });
        ctx.get().setPacketHandled(true);
    }
}

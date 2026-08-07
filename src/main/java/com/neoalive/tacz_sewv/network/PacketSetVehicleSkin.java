package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.gun.special.RepairToolItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.skin.VehicleSkinSupport;

/**
 * C→S: player chose the next sticky skin (or stock) after sneak-right-clicking a hull with the
 * repair tool. The client picks from files it actually has; the server only stores the tag.
 */
public final class PacketSetVehicleSkin {

    private static final double MAX_RANGE_SQ = 8.0 * 8.0;

    private final int entityId;
    /** -1 = stock / clear. */
    private final int factionOrdinal;

    public PacketSetVehicleSkin(int entityId, @Nullable CrewFacts.Faction faction) {
        this.entityId = entityId;
        this.factionOrdinal = faction == null ? -1 : faction.ordinal();
    }

    public PacketSetVehicleSkin(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.factionOrdinal = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeVarInt(this.factionOrdinal);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (!holdingRepairTool(player)) return;

            Entity target = player.level().getEntity(this.entityId);
            if (!(target instanceof VehicleEntity vehicle)) return;
            if (player.distanceToSqr(vehicle) > MAX_RANGE_SQ) return;

            CrewFacts.Faction faction = null;
            if (this.factionOrdinal >= 0) {
                if (this.factionOrdinal > CrewFacts.Faction.PMC.ordinal()) return;
                faction = CrewFacts.Faction.byId(this.factionOrdinal);
            }
            VehicleSkinSupport.set(vehicle, faction);
            final CrewFacts.Faction applied = faction;
            player.displayClientMessage(Component.translatable(
                    applied == null
                            ? "message.tacz_sewv.vehicle_skin.stock"
                            : "message.tacz_sewv.vehicle_skin.set",
                    applied == null ? "" : applied.name()), true);
        });
        context.setPacketHandled(true);
    }

    private static boolean holdingRepairTool(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        return main.getItem() instanceof RepairToolItem || off.getItem() instanceof RepairToolItem;
    }
}

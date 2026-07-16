package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.VehicleFormation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Forms a mostly-mounted selection into a wedge or column laid out along a cardinal the player
 * designated. Sent by {@link com.neoalive.tacz_sewv.client.FormationAxisSelection} once the
 * player left-clicks an axis.
 *
 * <p>The wire carries no slot index, unlike SEM's own PacketIssueOrder — the server derives the
 * whole numbering in {@link VehicleFormation#assign}. That is not only the safer shape (SEM
 * writes the client's index straight through with no bounds check, so a forged packet could put
 * every hull on slot 0, or on slot 100000 — a point a million blocks out that a hull would drive
 * at forever); it is the only workable one, since the numbering has to group units by the hull
 * they are riding, and passenger data is a server-side question.
 */
public class PacketVehicleFormation {

    public static final int KIND_WEDGE = 0;
    public static final int KIND_COLUMN = 1;

    private final List<Integer> unitIds;
    private final int kind;
    private final int axis;

    public PacketVehicleFormation(List<Integer> unitIds, int kind, int axis) {
        this.unitIds = unitIds;
        this.kind = kind;
        this.axis = axis;
    }

    public PacketVehicleFormation(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        this.unitIds = new ArrayList<>();
        for (int i = 0; i < size; i++) this.unitIds.add(buf.readVarInt());
        this.kind = buf.readVarInt();
        this.axis = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.unitIds.size());
        for (int id : this.unitIds) buf.writeVarInt(id);
        // Ints rather than writeEnum(OrderType.class): SEM's ordinals are its own business, and
        // our handshake doesn't check that both ends run the same SEM version.
        buf.writeVarInt(this.kind);
        buf.writeVarInt(this.axis);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            Direction axis = IFormationMember.directionOf(this.axis);
            if (axis == null) return; // malformed — nothing worth saying
            OrderType order = this.kind == KIND_COLUMN ? OrderType.FORM_COLUMN : OrderType.FORM_WEDGE;

            // Ownership-check each unit individually so a spoofed packet can't march another
            // player's units into formation by id.
            List<PmcUnitEntity> units = new ArrayList<>();
            for (int unitId : this.unitIds) {
                if (player.level().getEntity(unitId) instanceof PmcUnitEntity pmc
                        && pmc.isOwnedBy(player) && pmc.isAlive()) {
                    units.add(pmc);
                }
            }

            int hulls = VehicleFormation.assign(player, units, order, axis);

            // Server-authoritative feedback — the hulls the server actually formed, not the
            // optimistic client count.
            if (SewvConfig.SHOW_ORDER_FEEDBACK.get()) {
                Component msg = hulls == 0
                        ? Component.translatable("message.tacz_sewv.formation.formed.none")
                                .copy().withStyle(ChatFormatting.GRAY)
                        : Component.translatable(
                                hulls == 1 ? "message.tacz_sewv.formation.formed.single"
                                           : "message.tacz_sewv.formation.formed.multiple",
                                hulls, Component.translatable(axisKey(axis)))
                                .copy().withStyle(ChatFormatting.GREEN);
                player.displayClientMessage(msg, true);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /** Shared with the client-side readout so the axis is named the same on both ends. */
    public static String axisKey(Direction axis) {
        return switch (axis) {
            case NORTH -> "message.tacz_sewv.formation.dir.north";
            case SOUTH -> "message.tacz_sewv.formation.dir.south";
            case WEST -> "message.tacz_sewv.formation.dir.west";
            default -> "message.tacz_sewv.formation.dir.east";
        };
    }
}

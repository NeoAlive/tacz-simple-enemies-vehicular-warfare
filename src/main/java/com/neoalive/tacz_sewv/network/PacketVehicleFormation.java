package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.entity.ai.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.VehicleFormation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Forms the player's owned crews into one of the {@link FormationShape}s, laid out along the cardinal
 * they were facing when they opened the Tactical Data Terminal. Sent by
 * {@link com.neoalive.tacz_sewv.client.TdtScreen}'s formation buttons.
 *
 * <p>The wire carries no slot index, unlike SEM's own PacketIssueOrder — the server derives the
 * whole numbering in {@link VehicleFormation#assign}. That is not only the safer shape (SEM
 * writes the client's index straight through with no bounds check, so a forged packet could put
 * every hull on slot 0, or on slot 100000 — a point a million blocks out that a hull would drive
 * at forever); it is the only workable one, since the numbering has to group units by the hull
 * they are riding, and passenger data is a server-side question.
 */
public class PacketVehicleFormation {

    /** LINE units-per-row bounds; the TDT stepper and the server clamp both use these. */
    public static final int MIN_ROW_SIZE = 1;
    public static final int MAX_ROW_SIZE = 12;
    public static final int DEFAULT_ROW_SIZE = 4;

    private final List<Integer> unitIds;
    private final int shapeId;
    private final int axis;
    private final int rowSize; // units per row; only a LINE reads it

    public PacketVehicleFormation(List<Integer> unitIds, FormationShape shape, int axis, int rowSize) {
        this.unitIds = unitIds;
        this.shapeId = shape.id();
        this.axis = axis;
        this.rowSize = rowSize;
    }

    public PacketVehicleFormation(FriendlyByteBuf buf) {
        this.unitIds = buf.readList(FriendlyByteBuf::readVarInt);
        this.shapeId = buf.readVarInt();
        this.axis = buf.readVarInt();
        this.rowSize = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        // Our own stable shape/axis ids: SEM's enums are its own business, and the handshake
        // doesn't check that both ends run the same SEM version.
        buf.writeVarInt(this.shapeId);
        buf.writeVarInt(this.axis);
        buf.writeVarInt(this.rowSize);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            Direction axis = IFormationMember.directionOf(this.axis);
            if (axis == null) return; // malformed — nothing worth saying
            FormationShape shape = FormationShape.byId(this.shapeId);
            int rowSize = Mth.clamp(this.rowSize, MIN_ROW_SIZE, MAX_ROW_SIZE);

            // Ownership-check each unit individually so a spoofed packet can't march another
            // player's units into formation by id.
            List<PmcUnitEntity> units = new ArrayList<>();
            for (int unitId : this.unitIds) {
                if (player.level().getEntity(unitId) instanceof PmcUnitEntity pmc
                        && pmc.isOwnedBy(player) && pmc.isAlive()) {
                    units.add(pmc);
                }
            }

            int hulls = VehicleFormation.assign(player, units, shape, axis, rowSize);

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.formation.formed", hulls,
                    ChatFormatting.GREEN, hulls, Component.translatable(axisKey(axis)));
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

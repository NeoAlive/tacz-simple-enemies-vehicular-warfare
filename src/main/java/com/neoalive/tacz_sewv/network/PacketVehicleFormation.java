package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.entity.ai.support.FormationComposition;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Forms the player's owned crews into one of the {@link FormationShape}s along a frozen cardinal.
 * Sent from the Quick Wheel Formation category (WIDTH/LENGTH stretch included).
 *
 * <p>The wire carries no slot index — the server derives numbering in
 * {@link VehicleFormation#assign}.
 */
public class PacketVehicleFormation {

    /** LINE units-per-row bounds; internal default only (no UI stepper). */
    public static final int MIN_ROW_SIZE = 1;
    public static final int MAX_ROW_SIZE = 12;
    public static final int DEFAULT_ROW_SIZE = 4;

    private final List<Integer> unitIds;
    private final int shapeId;
    private final int axis;
    private final int rowSize;
    private final float widthStretch;
    private final float lengthStretch;

    public PacketVehicleFormation(List<Integer> unitIds, FormationShape shape, int axis, int rowSize,
                                  float widthStretch, float lengthStretch) {
        this.unitIds = unitIds;
        this.shapeId = shape.id();
        this.axis = axis;
        this.rowSize = rowSize;
        this.widthStretch = widthStretch;
        this.lengthStretch = lengthStretch;
    }

    public PacketVehicleFormation(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
        this.shapeId = buf.readVarInt();
        this.axis = buf.readVarInt();
        this.rowSize = buf.readVarInt();
        this.widthStretch = buf.readFloat();
        this.lengthStretch = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.shapeId);
        buf.writeVarInt(this.axis);
        buf.writeVarInt(this.rowSize);
        buf.writeFloat(this.widthStretch);
        buf.writeFloat(this.lengthStretch);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;

            Direction axis = IFormationMember.directionOf(this.axis);
            if (axis == null) {
                OrderReport.fail(player, OrderFailure.MALFORMED);
                return;
            }
            FormationShape shape = FormationShape.byId(this.shapeId);
            int rowSize = Mth.clamp(this.rowSize, MIN_ROW_SIZE, MAX_ROW_SIZE);
            float width = IFormationMember.clampStretch(this.widthStretch);
            float length = IFormationMember.clampStretch(this.lengthStretch);

            List<PmcUnitEntity> units = new ArrayList<>();
            for (int unitId : this.unitIds) {
                if (!(player.level().getEntity(unitId) instanceof PmcUnitEntity pmc)) {
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
                units.add(pmc);
            }

            FormationComposition.Kind kind = FormationComposition.resolve(units);
            if (kind == null) {
                sp.displayClientMessage(
                        Component.translatable(FormationComposition.MSG_INVALID)
                                .withStyle(ChatFormatting.GRAY),
                        true);
                return;
            }

            int slots = VehicleFormation.slotCountFor(units);
            double baseline = FormationComposition.baselineSpacing(kind);
            if (VehicleFormation.slotsOverlap(player.position(), axis, shape, slots, rowSize,
                    baseline, width, length)) {
                sp.displayClientMessage(
                        Component.translatable(FormationComposition.MSG_OVERLAP)
                                .withStyle(ChatFormatting.GRAY),
                        true);
                return;
            }

            int formed = VehicleFormation.assign(player, units, shape, axis, rowSize,
                    width, length, kind);

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.formation.formed", formed,
                    ChatFormatting.GREEN, formed, Component.translatable(axisKey(axis)));
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

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
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.command.quick.QuickCommandUnits;
import com.neoalive.tacz_sewv.entity.ai.support.FormationAnchorMode;
import com.neoalive.tacz_sewv.entity.ai.support.FormationComposition;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;
import com.neoalive.tacz_sewv.platform.NetworkEvent;

/**
 * Forms the player's owned crews into one of the {@link FormationShape}s along a frozen heading.
 * Sent from the Quick Wheel Formation category (WIDTH/LENGTH stretch included). The heading is the
 * cardinal {@code axis}, refined by {@code yaw} (degrees, the wheel snaps it to 45°) when finite; the
 * axis still travels because it is the "this unit is formed up" marker everything downstream reads.
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
    private final int anchorModeId;
    private final float yaw;

    public PacketVehicleFormation(List<Integer> unitIds, FormationShape shape, int axis, float yaw, int rowSize,
                                  float widthStretch, float lengthStretch, FormationAnchorMode anchorMode) {
        this.unitIds = unitIds;
        this.shapeId = shape.id();
        this.axis = axis;
        this.yaw = yaw;
        this.rowSize = rowSize;
        this.widthStretch = widthStretch;
        this.lengthStretch = lengthStretch;
        this.anchorModeId = anchorMode.id();
    }

    public PacketVehicleFormation(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
        this.shapeId = buf.readVarInt();
        this.axis = buf.readVarInt();
        this.yaw = buf.readFloat();
        this.rowSize = buf.readVarInt();
        this.widthStretch = buf.readFloat();
        this.lengthStretch = buf.readFloat();
        this.anchorModeId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.shapeId);
        buf.writeVarInt(this.axis);
        buf.writeFloat(this.yaw);
        buf.writeVarInt(this.rowSize);
        buf.writeFloat(this.widthStretch);
        buf.writeFloat(this.lengthStretch);
        buf.writeVarInt(this.anchorModeId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (com.neoalive.tacz_sewv.command.quick.OrderQueue.capture(sp, () -> handle(ctx))) return;

            Direction axis = IFormationMember.directionOf(this.axis);
            if (axis == null) {
                OrderReport.fail(player, OrderFailure.MALFORMED);
                return;
            }
            FormationShape shape = FormationShape.byId(this.shapeId);
            int rowSize = Mth.clamp(this.rowSize, MIN_ROW_SIZE, MAX_ROW_SIZE);
            float width = IFormationMember.clampStretch(this.widthStretch);
            float length = IFormationMember.clampStretch(this.lengthStretch);
            FormationAnchorMode anchorMode = FormationAnchorMode.byId(this.anchorModeId);
            // A forged yaw is only a heading — normalise it, and drop anything that is not a number.
            float yaw = Float.isFinite(this.yaw) ? Mth.wrapDegrees(this.yaw) : Float.NaN;

            List<PmcUnitEntity> units = new ArrayList<>();
            for (int unitId : this.unitIds) {
                if (!(player.level().getEntity(unitId) instanceof PmcUnitEntity pmc)) {
                    OrderReport.fail(player, OrderFailure.NOT_A_UNIT);
                    continue;
                }
                if (!com.neoalive.tacz_sewv.crew.OrderAuth.owns(player, pmc, "PacketVehicleFormation")) {
                    OrderReport.fail(player, OrderFailure.NOT_OWNED);
                    continue;
                }
                if (!pmc.isAlive()) {
                    OrderReport.fail(player, OrderFailure.UNIT_DEAD);
                    continue;
                }
                // The same gates every other wheel order passes: a downed unit cannot form up, and a
                // territory post, FOB command or pending route keeps its own orders.
                if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) {
                    OrderReport.fail(player, OrderFailure.UNIT_DOWNED, pmc);
                    continue;
                }
                if (QuickCommandUnits.refuseFobOrRoute(sp, pmc)) continue;
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

            int formed = VehicleFormation.assign(player, units, shape, axis, yaw, rowSize,
                    width, length, kind, anchorMode);

            NetworkHandler.orderFeedback(player, "formation", "message.tacz_sewv.formation.formed", formed,
                    ChatFormatting.GREEN, formed, Component.translatable(headingKey(axis, yaw)));
        });
        ctx.get().setPacketHandled(true);
    }

    /** The heading's name: one of eight 45° points when a yaw was sent, otherwise the axis. */
    public static String headingKey(Direction axis, float yaw) {
        if (!Float.isFinite(yaw)) return axisKey(axis);
        // Minecraft yaw: 0 faces +Z (south), 90 faces -X (west).
        int octant = Math.floorMod(Math.round(yaw / 45.0f), 8);
        return switch (octant) {
            case 0 -> "message.tacz_sewv.formation.dir.south";
            case 1 -> "message.tacz_sewv.formation.dir.southwest";
            case 2 -> "message.tacz_sewv.formation.dir.west";
            case 3 -> "message.tacz_sewv.formation.dir.northwest";
            case 4 -> "message.tacz_sewv.formation.dir.north";
            case 5 -> "message.tacz_sewv.formation.dir.northeast";
            case 6 -> "message.tacz_sewv.formation.dir.east";
            default -> "message.tacz_sewv.formation.dir.southeast";
        };
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

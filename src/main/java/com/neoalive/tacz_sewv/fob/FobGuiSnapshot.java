package com.neoalive.tacz_sewv.fob;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

public record FobGuiSnapshot(
        GuiKind kind,
        BlockPos commandPos,
        BlockPos anchorPos,
        boolean valid,
        String invalidReason,
        boolean fobCommandActive,
        boolean scrambleActive,
        int threatScore,
        BlockPos stockpilePos,
        BlockPos parkingPos,
        List<LivingRow> living,
        List<VehicleRow> vehicles) {

    public enum GuiKind {
        COMMAND,
        PARKING;

        public static GuiKind decode(FriendlyByteBuf buf) {
            return buf.readEnum(GuiKind.class);
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeEnum(this);
        }
    }

    public record LivingRow(UUID uuid, String name, boolean assigned) {}

    public record VehicleRow(UUID uuid, String registryId, boolean assigned, String positionText) {}

    public static void encode(FriendlyByteBuf buf, FobGuiSnapshot snap) {
        snap.kind.encode(buf);
        buf.writeBlockPos(snap.commandPos);
        buf.writeBlockPos(snap.anchorPos);
        buf.writeBoolean(snap.valid);
        buf.writeUtf(snap.invalidReason == null ? "" : snap.invalidReason);
        buf.writeBoolean(snap.fobCommandActive);
        buf.writeBoolean(snap.scrambleActive);
        buf.writeVarInt(snap.threatScore);
        buf.writeBlockPos(snap.stockpilePos == null ? BlockPos.ZERO : snap.stockpilePos);
        buf.writeBlockPos(snap.parkingPos == null ? BlockPos.ZERO : snap.parkingPos);
        buf.writeVarInt(snap.living.size());
        for (LivingRow row : snap.living) {
            buf.writeUUID(row.uuid);
            buf.writeUtf(row.name);
            buf.writeBoolean(row.assigned);
        }
        buf.writeVarInt(snap.vehicles.size());
        for (VehicleRow row : snap.vehicles) {
            buf.writeUUID(row.uuid);
            buf.writeUtf(row.registryId);
            buf.writeBoolean(row.assigned);
            buf.writeUtf(row.positionText);
        }
    }

    public static FobGuiSnapshot decode(FriendlyByteBuf buf) {
        GuiKind kind = GuiKind.decode(buf);
        BlockPos commandPos = buf.readBlockPos();
        BlockPos anchorPos = buf.readBlockPos();
        boolean valid = buf.readBoolean();
        String invalidReason = buf.readUtf();
        boolean commandActive = buf.readBoolean();
        boolean scramble = buf.readBoolean();
        int threat = buf.readVarInt();
        BlockPos stockpile = buf.readBlockPos();
        BlockPos parking = buf.readBlockPos();
        int livingCount = buf.readVarInt();
        List<LivingRow> living = new ArrayList<>(livingCount);
        for (int i = 0; i < livingCount; i++) {
            living.add(new LivingRow(buf.readUUID(), buf.readUtf(), buf.readBoolean()));
        }
        int vehicleCount = buf.readVarInt();
        List<VehicleRow> vehicles = new ArrayList<>(vehicleCount);
        for (int i = 0; i < vehicleCount; i++) {
            vehicles.add(new VehicleRow(buf.readUUID(), buf.readUtf(), buf.readBoolean(), buf.readUtf()));
        }
        return new FobGuiSnapshot(kind, commandPos, anchorPos, valid, invalidReason, commandActive, scramble, threat,
                stockpile.equals(BlockPos.ZERO) ? null : stockpile,
                parking.equals(BlockPos.ZERO) ? null : parking,
                living, vehicles);
    }
}

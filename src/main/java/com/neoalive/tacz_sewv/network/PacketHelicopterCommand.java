package com.neoalive.tacz_sewv.network;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Player → server flight command for owned helicopter crews: takeoff, or land at a
 * looked-at block. Sets the {@link IHelicopterPilot} command that
 * {@link com.neoalive.tacz_sewv.entity.ai.DriveHelicopterGoal} consumes.
 */
public class PacketHelicopterCommand {

    private final List<Integer> unitIds;
    private final int command;
    private final BlockPos landPos; // only meaningful for HELI_CMD_LANDING; may be null otherwise

    public PacketHelicopterCommand(List<Integer> unitIds, int command, BlockPos landPos) {
        this.unitIds = unitIds;
        this.command = command;
        this.landPos = landPos;
    }

    public PacketHelicopterCommand(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        this.unitIds = new ArrayList<>();
        for (int i = 0; i < size; i++) this.unitIds.add(buf.readVarInt());
        this.command = buf.readVarInt();
        this.landPos = buf.readBoolean() ? buf.readBlockPos() : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.unitIds.size());
        for (int id : this.unitIds) buf.writeVarInt(id);
        buf.writeVarInt(this.command);
        buf.writeBoolean(this.landPos != null);
        if (this.landPos != null) buf.writeBlockPos(this.landPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            int ordered = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                // Intentionally PMC-only: RU/US crews also implement IHelicopterPilot,
                // but they are hostile and unowned — they fly autonomously (TankSpawner
                // issues their takeoff on spawn) and take no player flight orders.
                // Only the unit at the stick (seat 0 of a helicopter) takes the order:
                // gunners/passengers/ground units are not flight crews, and counting
                // them reported one "helicopter" per crew member in the feedback.
                if (e instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)
                        && pmc.getVehicle() instanceof VehicleEntity v
                        && v.getFirstPassenger() == pmc
                        && v.getEngineInfo() instanceof EngineInfo.Helicopter) {
                    IHelicopterPilot pilot = (IHelicopterPilot) pmc;
                    pilot.sewv$setHeliCommand(this.command);
                    pilot.sewv$setHeliLandPos(this.command == IHelicopterPilot.HELI_CMD_LANDING ? this.landPos : null);
                    ordered++;
                }
            }

            if (SewvConfig.SHOW_ORDER_FEEDBACK.get()) {
                String base = this.command == IHelicopterPilot.HELI_CMD_LANDING
                        ? "message.tacz_sewv.heli.land" : "message.tacz_sewv.heli.takeoff";
                Component msg = ordered == 0
                        ? Component.translatable(base + ".none")
                        : Component.translatable(base + (ordered == 1 ? ".single" : ".multiple"), ordered);
                player.displayClientMessage(msg.copy().withStyle(ChatFormatting.GREEN), true);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}

package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;

/**
 * Player → server flight command for owned helicopter crews: takeoff, or land at a
 * looked-at block. Sets the {@link IHelicopterPilot} command that
 * {@link com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal} consumes.
 */
public class PacketHelicopterCommand {

    /** The cruise-altitude band the takeoff order carries; mirrors DriveHelicopterGoal's flight band. */
    public static final int MIN_ALTITUDE = 30;
    public static final int MAX_ALTITUDE = 50;

    private final List<Integer> unitIds;
    private final int command;
    private final BlockPos landPos; // only meaningful for HELI_CMD_LANDING; may be null otherwise
    private final int altitude;     // only meaningful for HELI_CMD_TAKEOFF (the live cruise trim)

    public PacketHelicopterCommand(List<Integer> unitIds, int command, BlockPos landPos, int altitude) {
        this.unitIds = unitIds;
        this.command = command;
        this.landPos = landPos;
        this.altitude = altitude;
    }

    public PacketHelicopterCommand(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
        this.command = buf.readVarInt();
        this.landPos = buf.readBoolean() ? buf.readBlockPos() : null;
        this.altitude = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.command);
        buf.writeBoolean(this.landPos != null);
        if (this.landPos != null) buf.writeBlockPos(this.landPos);
        buf.writeVarInt(this.altitude);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (com.neoalive.tacz_sewv.invasion.InvasionOrderGate.denyIfActive(sp)) return;

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
                        && pmc.getVehicle() instanceof VehicleEntity v) {
                    if (v.getFirstPassenger() instanceof PmcUnitEntity driver && driver.isOwnedBy(player)) {
                        pmc = driver;
                    }
                    if (v.getFirstPassenger() != pmc) continue;
                    if (!HullFacts.isHelicopterHull(v)) continue;
                    // LANDING without a pad is immediately cleared by DriveHelicopterGoal and
                    // looks like a successful order that then resumes FOLLOW orbit.
                    if (this.command == IHelicopterPilot.HELI_CMD_LANDING && this.landPos == null) {
                        continue;
                    }
                    IHelicopterPilot pilot = (IHelicopterPilot) pmc;
                    pilot.sewv$setHeliCommand(this.command);
                    if (this.command == IHelicopterPilot.HELI_CMD_LANDING) {
                        pilot.sewv$setHeliLandPos(this.landPos);
                        DriveHelicopterGoal.setForcedLand(v, this.landPos);
                    } else {
                        pilot.sewv$setHeliLandPos(null);
                        DriveHelicopterGoal.clearForcedLand(v);
                    }
                    // Takeoff carries the live cruise trim; clamp to the flight band (never trust the
                    // client) and store it on the pilot for DriveHelicopterGoal to read every tick.
                    if (this.command == IHelicopterPilot.HELI_CMD_TAKEOFF) {
                        pilot.sewv$setCruiseAltitude(Mth.clamp(this.altitude, MIN_ALTITUDE, MAX_ALTITUDE));
                        // Plane-only ack: helicopters stay on the generic ORDERS path (SEM packet)
                        // and spawn/auto takeoffs never come through here.
                        if (HullFacts.isPlaneHull(v)) {
                            CrewRadio.play(v, CrewRadio.Line.TAKEOFF);
                        }
                    }
                    ordered++;
                }
            }

            NetworkHandler.orderFeedback(player,
                    this.command == IHelicopterPilot.HELI_CMD_LANDING
                            ? "message.tacz_sewv.heli.land" : "message.tacz_sewv.heli.takeoff",
                    ordered, ChatFormatting.GREEN, ordered);
        });
        ctx.get().setPacketHandled(true);
    }
}

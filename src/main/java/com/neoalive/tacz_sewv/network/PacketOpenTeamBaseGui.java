package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.client.InvasionEditorClient;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** Server → client: open the team_base config screen. */
public class PacketOpenTeamBaseGui {

    private final BlockPos pos;
    private final String assignedTeam;
    private final boolean playerOwned;
    private final boolean spawnPlayerOwnedTanksWithNpc;
    private final TankFaction crewFaction;
    private final int aiVehicleCount;
    private final int timeToCaptureSeconds;
    private final int radiusInBlocks;
    private final String ownedTeam;
    private final boolean invisible;
    private final boolean endInvasionOnCapture;
    private final List<String> vehiclePool;
    private final List<String> teams;
    private final List<String> catalog;

    public PacketOpenTeamBaseGui(BlockPos pos, String assignedTeam, boolean playerOwned,
                                 boolean spawnPlayerOwnedTanksWithNpc, TankFaction crewFaction,
                                 int aiVehicleCount, int timeToCaptureSeconds, int radiusInBlocks,
                                 String ownedTeam, boolean invisible, boolean endInvasionOnCapture,
                                 List<String> vehiclePool, List<String> teams, List<String> catalog) {
        this.pos = pos;
        this.assignedTeam = assignedTeam == null ? "" : assignedTeam;
        this.playerOwned = playerOwned;
        this.spawnPlayerOwnedTanksWithNpc = spawnPlayerOwnedTanksWithNpc;
        this.crewFaction = crewFaction == null ? TankFaction.US : crewFaction;
        this.aiVehicleCount = aiVehicleCount;
        this.timeToCaptureSeconds = timeToCaptureSeconds;
        this.radiusInBlocks = radiusInBlocks;
        this.ownedTeam = ownedTeam == null ? "" : ownedTeam;
        this.invisible = invisible;
        this.endInvasionOnCapture = endInvasionOnCapture;
        this.vehiclePool = vehiclePool;
        this.teams = teams;
        this.catalog = catalog;
    }

    public PacketOpenTeamBaseGui(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.assignedTeam = buf.readUtf();
        this.playerOwned = buf.readBoolean();
        this.spawnPlayerOwnedTanksWithNpc = buf.readBoolean();
        this.crewFaction = TankFaction.values()[Math.floorMod(buf.readVarInt(), TankFaction.values().length)];
        this.aiVehicleCount = buf.readVarInt();
        this.timeToCaptureSeconds = buf.readVarInt();
        this.radiusInBlocks = buf.readVarInt();
        this.ownedTeam = buf.readUtf();
        this.invisible = buf.readBoolean();
        this.endInvasionOnCapture = buf.readBoolean();
        this.vehiclePool = PacketOpenPoolEditor.readStringList(buf);
        this.teams = PacketOpenPoolEditor.readStringList(buf);
        this.catalog = PacketOpenPoolEditor.readStringList(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeUtf(this.assignedTeam);
        buf.writeBoolean(this.playerOwned);
        buf.writeBoolean(this.spawnPlayerOwnedTanksWithNpc);
        buf.writeVarInt(this.crewFaction.ordinal());
        buf.writeVarInt(this.aiVehicleCount);
        buf.writeVarInt(this.timeToCaptureSeconds);
        buf.writeVarInt(this.radiusInBlocks);
        buf.writeUtf(this.ownedTeam);
        buf.writeBoolean(this.invisible);
        buf.writeBoolean(this.endInvasionOnCapture);
        PacketOpenPoolEditor.writeStringList(buf, this.vehiclePool);
        PacketOpenPoolEditor.writeStringList(buf, this.teams);
        PacketOpenPoolEditor.writeStringList(buf, this.catalog);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                InvasionEditorClient.openTeamBase(
                        this.pos, this.assignedTeam, this.playerOwned, this.spawnPlayerOwnedTanksWithNpc,
                        this.crewFaction, this.aiVehicleCount, this.timeToCaptureSeconds,
                        this.radiusInBlocks, this.ownedTeam, this.invisible, this.endInvasionOnCapture,
                        this.vehiclePool, this.teams, this.catalog)));
        ctx.get().setPacketHandled(true);
    }
}

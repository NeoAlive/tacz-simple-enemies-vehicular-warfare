package com.neoalive.tacz_sewv.network;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.invasion.InvasionEditorClient;
import com.neoalive.tacz_sewv.invasion.PmcOwnerKind;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

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
    private final PmcOwnerKind pmcOwnerKind;
    private final String pmcOwnerValue;
    private final List<String> vehiclePool;
    private final List<String> enemyTeams;
    private final List<String> teams;
    private final List<String> onlinePlayerNames;
    private final List<String> onlinePlayerUuids;
    private final List<String> catalog;
    /** Current world GROUND pools per faction — same source as {@code /sewv pool} Armor. */
    private final Map<TankFaction, List<String>> armorPools;

    public PacketOpenTeamBaseGui(BlockPos pos, String assignedTeam, boolean playerOwned,
                                 boolean spawnPlayerOwnedTanksWithNpc, TankFaction crewFaction,
                                 int aiVehicleCount, int timeToCaptureSeconds, int radiusInBlocks,
                                 String ownedTeam, boolean invisible, boolean endInvasionOnCapture,
                                 PmcOwnerKind pmcOwnerKind, String pmcOwnerValue,
                                 List<String> vehiclePool, List<String> enemyTeams, List<String> teams,
                                 List<String> onlinePlayerNames, List<String> onlinePlayerUuids,
                                 List<String> catalog, Map<TankFaction, List<String>> armorPools) {
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
        this.pmcOwnerKind = pmcOwnerKind == null ? PmcOwnerKind.NONE : pmcOwnerKind;
        this.pmcOwnerValue = pmcOwnerValue == null ? "" : pmcOwnerValue;
        this.vehiclePool = vehiclePool;
        this.enemyTeams = enemyTeams;
        this.teams = teams;
        this.onlinePlayerNames = onlinePlayerNames;
        this.onlinePlayerUuids = onlinePlayerUuids;
        this.catalog = catalog;
        this.armorPools = armorPools == null ? Map.of() : armorPools;
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
        this.pmcOwnerKind = PmcOwnerKind.fromOrdinal(buf.readVarInt());
        this.pmcOwnerValue = buf.readUtf();
        this.vehiclePool = PacketOpenPoolEditor.readStringList(buf);
        this.enemyTeams = PacketOpenPoolEditor.readStringList(buf);
        this.teams = PacketOpenPoolEditor.readStringList(buf);
        this.onlinePlayerNames = PacketOpenPoolEditor.readStringList(buf);
        this.onlinePlayerUuids = PacketOpenPoolEditor.readStringList(buf);
        this.catalog = PacketOpenPoolEditor.readStringList(buf);
        Map<TankFaction, List<String>> armor = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            armor.put(faction, PacketOpenPoolEditor.readStringList(buf));
        }
        this.armorPools = armor;
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
        buf.writeVarInt(this.pmcOwnerKind.ordinal());
        buf.writeUtf(this.pmcOwnerValue);
        PacketOpenPoolEditor.writeStringList(buf, this.vehiclePool);
        PacketOpenPoolEditor.writeStringList(buf, this.enemyTeams);
        PacketOpenPoolEditor.writeStringList(buf, this.teams);
        PacketOpenPoolEditor.writeStringList(buf, this.onlinePlayerNames);
        PacketOpenPoolEditor.writeStringList(buf, this.onlinePlayerUuids);
        PacketOpenPoolEditor.writeStringList(buf, this.catalog);
        for (TankFaction faction : TankFaction.values()) {
            List<String> list = this.armorPools.getOrDefault(faction, List.of());
            PacketOpenPoolEditor.writeStringList(buf, list);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                InvasionEditorClient.openTeamBase(
                        this.pos, this.assignedTeam, this.playerOwned, this.spawnPlayerOwnedTanksWithNpc,
                        this.crewFaction, this.aiVehicleCount, this.timeToCaptureSeconds,
                        this.radiusInBlocks, this.ownedTeam, this.invisible, this.endInvasionOnCapture,
                        this.pmcOwnerKind, this.pmcOwnerValue,
                        this.vehiclePool, this.enemyTeams, this.teams,
                        this.onlinePlayerNames, this.onlinePlayerUuids, this.catalog,
                        this.armorPools)));
        ctx.get().setPacketHandled(true);
    }
}

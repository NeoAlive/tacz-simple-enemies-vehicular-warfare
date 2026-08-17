package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.invasion.InvasionBlockEditor;
import com.neoalive.tacz_sewv.invasion.PmcOwnerKind;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Client → server: write team_base config from the editor. */
public class PacketSaveTeamBase {

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
    private final int spawnDelaySeconds;
    private final boolean pointsHaveToBeConquered;
    private final PmcOwnerKind pmcOwnerKind;
    private final String pmcOwnerValue;
    private final List<String> vehiclePool;
    private final List<String> enemyTeams;

    public PacketSaveTeamBase(BlockPos pos, String assignedTeam, boolean playerOwned,
                              boolean spawnPlayerOwnedTanksWithNpc, TankFaction crewFaction,
                              int aiVehicleCount, int timeToCaptureSeconds, int radiusInBlocks,
                              String ownedTeam, boolean invisible, boolean endInvasionOnCapture,
                              int spawnDelaySeconds, boolean pointsHaveToBeConquered,
                              PmcOwnerKind pmcOwnerKind, String pmcOwnerValue,
                              List<String> vehiclePool, List<String> enemyTeams) {
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
        this.spawnDelaySeconds = spawnDelaySeconds;
        this.pointsHaveToBeConquered = pointsHaveToBeConquered;
        this.pmcOwnerKind = pmcOwnerKind == null ? PmcOwnerKind.NONE : pmcOwnerKind;
        this.pmcOwnerValue = pmcOwnerValue == null ? "" : pmcOwnerValue;
        this.vehiclePool = vehiclePool;
        this.enemyTeams = enemyTeams;
    }

    public PacketSaveTeamBase(FriendlyByteBuf buf) {
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
        this.spawnDelaySeconds = buf.readVarInt();
        this.pointsHaveToBeConquered = buf.readBoolean();
        this.pmcOwnerKind = PmcOwnerKind.fromOrdinal(buf.readVarInt());
        this.pmcOwnerValue = buf.readUtf();
        this.vehiclePool = PacketOpenPoolEditor.readStringList(buf);
        this.enemyTeams = PacketOpenPoolEditor.readStringList(buf);
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
        buf.writeVarInt(this.spawnDelaySeconds);
        buf.writeBoolean(this.pointsHaveToBeConquered);
        buf.writeVarInt(this.pmcOwnerKind.ordinal());
        buf.writeUtf(this.pmcOwnerValue);
        PacketOpenPoolEditor.writeStringList(buf, this.vehiclePool);
        PacketOpenPoolEditor.writeStringList(buf, this.enemyTeams);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !InvasionBlockEditor.mayEdit(player)) return;
            ServerLevel level = player.serverLevel();
            BlockEntity raw = level.getBlockEntity(this.pos);
            if (!(raw instanceof TeamBaseBlockEntity be)) return;

            be.setAssignedTeam(this.assignedTeam);
            be.setPlayerOwned(this.playerOwned);
            be.setSpawnPlayerOwnedTanksWithNpc(this.spawnPlayerOwnedTanksWithNpc);
            be.setCrewFaction(this.crewFaction);
            be.setAiVehicleCount(this.aiVehicleCount);
            be.setTimeToCaptureSeconds(Math.max(1, this.timeToCaptureSeconds));
            be.setRadiusInBlocks(Math.max(1, this.radiusInBlocks));
            be.setOwnedTeam(this.ownedTeam);
            be.setInvisible(this.invisible);
            be.setEndInvasionOnCapture(this.endInvasionOnCapture);
            be.setSpawnDelaySeconds(this.spawnDelaySeconds);
            be.setPointsHaveToBeConquered(this.pointsHaveToBeConquered);
            be.setPmcOwner(
                    this.crewFaction == TankFaction.PMC ? this.pmcOwnerKind : PmcOwnerKind.NONE,
                    this.crewFaction == TankFaction.PMC ? this.pmcOwnerValue : "");

            List<String> cleaned = new ArrayList<>();
            for (String id : this.vehiclePool) {
                if (ResourceLocation.tryParse(id) == null) continue;
                if (!cleaned.contains(id)) cleaned.add(id);
            }
            be.setVehiclePool(cleaned);

            List<String> enemies = new ArrayList<>();
            for (String team : this.enemyTeams) {
                if (team == null || team.isEmpty()) continue;
                if (!enemies.contains(team)) enemies.add(team);
            }
            be.setEnemyTeams(enemies);

            BlockState state = level.getBlockState(this.pos);
            level.sendBlockUpdated(this.pos, state, state, 3);
            player.displayClientMessage(Component.translatable("message.tacz_sewv.invasion.gui.saved"), true);
        });
        ctx.get().setPacketHandled(true);
    }
}

package com.neoalive.tacz_sewv.invasion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenCapturePointGui;
import com.neoalive.tacz_sewv.network.PacketOpenTeamBaseGui;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.PoolEditorAccess;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/** Op-only open path for invasion block config screens. */
public final class InvasionBlockEditor {

    private InvasionBlockEditor() {}

    public static boolean mayEdit(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    public static void deny(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("message.tacz_sewv.invasion.gui.denied"), true);
    }

    public static List<String> scoreboardTeamNames(ServerPlayer player) {
        List<String> names = new ArrayList<>();
        for (PlayerTeam team : player.server.getScoreboard().getPlayerTeams()) {
            names.add(team.getName());
        }
        names.sort(String::compareTo);
        return names;
    }

    public static void openCapturePoint(ServerPlayer player, CapturePointBlockEntity be) {
        if (!mayEdit(player)) {
            deny(player);
            return;
        }
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketOpenCapturePointGui(
                        be.getBlockPos(),
                        be.getPointId(),
                        be.getTimeToCaptureSeconds(),
                        be.getRadiusInBlocks(),
                        be.getOwnedTeam(),
                        be.isInvisible(),
                        scoreboardTeamNames(player)));
    }

    public static void openTeamBase(ServerPlayer player, TeamBaseBlockEntity be) {
        if (!mayEdit(player)) {
            deny(player);
            return;
        }
        List<String> playerNames = new ArrayList<>();
        List<String> playerUuids = new ArrayList<>();
        for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
            playerNames.add(online.getGameProfile().getName());
            playerUuids.add(online.getUUID().toString());
        }
        WorldVehiclePools pools = WorldVehiclePools.get(player.serverLevel());
        Map<TankFaction, List<String>> armor = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            armor.put(faction, new ArrayList<>(pools.list(faction, Category.GROUND)));
        }
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketOpenTeamBaseGui(
                        be.getBlockPos(),
                        be.getAssignedTeam(),
                        be.isPlayerOwned(),
                        be.isSpawnPlayerOwnedTanksWithNpc(),
                        be.getCrewFaction(),
                        be.getAiVehicleCount(),
                        be.getTimeToCaptureSeconds(),
                        be.getRadiusInBlocks(),
                        be.getOwnedTeam(),
                        be.isInvisible(),
                        be.isEndInvasionOnCapture(),
                        be.getSpawnDelaySeconds(),
                        be.isPointsHaveToBeConquered(),
                        be.getPmcOwnerKind(),
                        be.getPmcOwnerValue(),
                        new ArrayList<>(be.getVehiclePool()),
                        new ArrayList<>(be.getEnemyTeams()),
                        scoreboardTeamNames(player),
                        playerNames,
                        playerUuids,
                        PoolEditorAccess.catalog(),
                        armor));
    }
}

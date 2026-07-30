package com.neoalive.tacz_sewv.util;

import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenCapturePointGui;
import com.neoalive.tacz_sewv.network.PacketOpenTeamBaseGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

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
                        new ArrayList<>(be.getVehiclePool()),
                        scoreboardTeamNames(player),
                        PoolEditorAccess.catalog()));
    }
}

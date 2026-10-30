package com.neoalive.tacz_sewv.diplomacy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData.Relation;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData.Subject;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSideRelations;

/**
 * One player's stored relation to the SEM sides {@code ru} and {@code us}. Most specific pair
 * wins: the player's own name, then their OpenPAC party. Nothing stored is not {@link Relation#ALLY}.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class SideRelations {

    public static final String RU = "ru";
    public static final String US = "us";

    private SideRelations() {}

    /** Null when nothing is stored, or the owner cannot be named. */
    @Nullable
    public static Relation toward(ServerPlayer player, String side) {
        return toward(player.getServer(), player.getUUID(), player.getScoreboardName(), side);
    }

    /** A PMC owner's relation, online or from the profile cache. Null owner → null. */
    @Nullable
    public static Relation towardOwner(ServerLevel level, @Nullable UUID ownerId, String side) {
        if (ownerId == null || level.getServer() == null) return null;
        return toward(level.getServer(), ownerId, null, side);
    }

    @Nullable
    public static Relation toward(MinecraftServer server, @Nullable UUID id, @Nullable String name, String side) {
        if (server == null || id == null) return null;
        if (name == null) name = profileName(server, id);
        List<Subject> self = new ArrayList<>(2);
        if (name != null) self.add(Subject.player(name));
        String faction = OpenPacCompat.factionName(server, id);
        if (faction != null) self.add(Subject.faction(faction));
        if (self.isEmpty()) return null;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return null;
        return DiplomacyData.get(overworld).resolve(self, List.of(Subject.side(side)));
    }

    @Nullable
    public static String profileName(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getScoreboardName();
        GameProfileCache cache = server.getProfileCache();
        if (cache == null) return null;
        return cache.get(id).map(GameProfile::getName).orElse(null);
    }

    public static void sync(ServerPlayer player) {
        Relation ru = toward(player, RU);
        Relation us = toward(player, US);
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketSideRelations(ru == null ? Relation.NEUTRAL : ru, us == null ? Relation.NEUTRAL : us));
    }

    public static void syncAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sync(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }
}

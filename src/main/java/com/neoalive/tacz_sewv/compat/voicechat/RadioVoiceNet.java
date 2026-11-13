package com.neoalive.tacz_sewv.compat.voicechat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;
import com.neoalive.tacz_sewv.diplomacy.SideRelations;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.RadioSettings;
import com.neoalive.tacz_sewv.notify.RadioNet;

/**
 * Who hears whom over the radio, rebuilt on the server thread every 10 ticks and published whole. Voice
 * Chat delivers mic packets on its own thread, which must never read an inventory or a level, so the relay
 * only ever looks a speaker up here.
 *
 * <p>A carried radio with voice on both transmits and receives on its net. A listener hears a speaker on the
 * same net, and is an ally: same scoreboard team or a diplomacy ALLY. Listeners inside
 * Voice Chat's proximity distance are left out, since they already hear the speaker directly.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class RadioVoiceNet {

    private static final int REBUILD_TICKS = 10;

    private static volatile Map<UUID, List<UUID>> receivers = Map.of();
    private static volatile Set<UUID> transmitting = Set.of();

    private RadioVoiceNet() {}

    /** The current listeners for this speaker, or empty when they are not transmitting. */
    public static List<UUID> receiversOf(UUID speaker) {
        List<UUID> r = receivers.get(speaker);
        return r == null ? List.of() : r;
    }

    /** Carries a radio with voice on, whether or not anyone is listening. */
    public static boolean isTransmitting(UUID speaker) {
        return transmitting.contains(speaker);
    }

    static void clear() {
        receivers = Map.of();
        transmitting = Set.of();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !VoicechatCompat.serverActive()) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % REBUILD_TICKS != 0) return;
        if (!RadioNet.voiceEnabled()) {
            clear();
            return;
        }
        Set<UUID> tx = new HashSet<>();
        receivers = rebuild(server, VoicechatCompat.proximity(), tx);
        transmitting = Set.copyOf(tx);
    }

    private record Station(ServerPlayer player, int net, List<DiplomacyData.Subject> subjects) {}

    private static Map<UUID, List<UUID>> rebuild(MinecraftServer server, double proximity, Set<UUID> tx) {
        List<Station> stations = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator()) continue;
            ItemStack radio = HandheldRadioItem.findCarried(p, s -> RadioSettings.readComms(s).voice());
            if (radio.isEmpty()) continue;
            tx.add(p.getUUID());
            stations.add(new Station(p, RadioSettings.readComms(radio).net(), null));
        }
        if (stations.size() < 2) return Map.of();
        for (int i = 0; i < stations.size(); i++) {
            ServerPlayer p = stations.get(i).player;
            stations.set(i, new Station(p, stations.get(i).net,
                    SideRelations.subjectsOf(server, p.getUUID(), p.getScoreboardName())));
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        DiplomacyData diplomacy = overworld == null ? null : DiplomacyData.get(overworld);
        double proxSq = proximity * proximity;
        Map<UUID, List<UUID>> out = new HashMap<>();
        for (Station t : stations) {
            List<UUID> rx = new ArrayList<>();
            for (Station l : stations) {
                if (l.player == t.player || l.net != t.net) continue;
                if (l.player.level() == t.player.level() && l.player.distanceToSqr(t.player) <= proxSq) continue;
                if (!allied(t, l, diplomacy)) continue;
                rx.add(l.player.getUUID());
            }
            if (!rx.isEmpty()) out.put(t.player.getUUID(), List.copyOf(rx));
        }
        return Map.copyOf(out);
    }

    private static boolean allied(Station a, Station b, DiplomacyData diplomacy) {
        if (a.player.getTeam() != null && a.player.isAlliedTo(b.player)) return true;
        if (diplomacy == null || a.subjects.isEmpty() || b.subjects.isEmpty()) return false;
        return diplomacy.resolve(a.subjects, b.subjects) == DiplomacyData.Relation.ALLY;
    }
}

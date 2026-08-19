package com.neoalive.tacz_sewv.order;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.debug.SewvDiag;

/**
 * Collects every result of a player's orders during one tick and emits them together at the end of
 * it — successes first, then the reasons anything refused.
 *
 * <p><b>Why it buffers rather than sending as it goes.</b> A packet handler loops over the selected
 * units and rejects them one at a time, so a ten-unit order can produce ten identical refusals in
 * the same tick. The tally collapses those into one line with a count. An end-of-tick flush costs no
 * latency — the refusals and the success they qualify all happen inside the same tick — and it is
 * what lets the aggregate {@code .none} line be dropped once a specific reason is known, since by
 * flush time we know whether one was.
 *
 * <p><b>Successes and failures leave by different doors, on purpose.</b> Successes go to the
 * player, through {@code PacketOrderFeedback}, so {@code ClientConfig.SHOW_ORDER_FEEDBACK} still
 * turns them off. Failures go to the <b>server console</b> and never to chat: a running commentary
 * of every rejected click was noise, and the answer a player actually wants — that the unit heard
 * and refused — is better given by the crew's own spoken reply, which still plays. The written
 * reason is for whoever is diagnosing why, which is a log's job.
 *
 * <p>That is also why the aggregate "nothing took the order" success line is no longer suppressed
 * when a specific reason is known. It used to be, on the grounds that the reason said more; now the
 * reason is not in chat at all, so suppressing it would leave the player with nothing.
 */
public final class OrderReport {

    /** Per unit + reason, so one unit cannot flood; the player floor below then caps the rest. */
    private static final String VETO_KEY = "tacz_sewv:veto_";
    /**
     * Minimum gap between two veto-derived flushes for one player. The per-unit cooldown alone is
     * not enough: a squad of twenty units each obeying its own 200-tick timer still averages a line
     * every ten ticks.
     */
    private static final int VETO_PLAYER_FLOOR_TICKS = 60;

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    /** Survives the flush, unlike {@link #PENDING} — it is the thing being rate-limited. */
    private static final Map<UUID, Long> NEXT_VETO_LINE = new HashMap<>();

    private OrderReport() {}

    private static final class Pending {
        final List<Component> successes = new ArrayList<>(2);
        final Map<String, Integer> accepted = new LinkedHashMap<>();
        final EnumMap<OrderFailure, Integer> failures = new EnumMap<>(OrderFailure.class);
        @Nullable AbstractUnit voice;
        @Nullable SoundEvent clip;
        boolean fromVeto;
    }

    /**
     * One unit took the order, where the caller has no idea how many others will.
     *
     * <p>SEM's order packet carries a single unit, so ordering a section of five arrives as five
     * packets in the same tick. Counting them here and rendering once is the only way that reads as
     * "Order sent to 5 units" rather than the same sentence five times — which is exactly why the
     * client-side ack this replaces guessed the count instead of waiting for the server.
     */
    public static void okEach(Player player, String base, ChatFormatting color) {
        if (!(player instanceof ServerPlayer server)) return;
        pending(server).accepted.merge(base + "\u0000" + color.name(), 1, Integer::sum);
    }

    /** An order took. Routed here from {@code NetworkHandler} so every existing call site joins the flush. */
    public static void ok(Player player, Component message) {
        if (!(player instanceof ServerPlayer server)) {
            player.displayClientMessage(message, false);
            return;
        }
        pending(server).successes.add(message);
    }

    public static void fail(@Nullable Player player, OrderFailure why) {
        fail(player, why, null);
    }

    /**
     * @param speaker the unit doing the refusing, if there is one — it plays the reply, so the
     *                answer comes from the crew rather than out of the air.
     */
    public static void fail(@Nullable Player player, OrderFailure why, @Nullable AbstractUnit speaker) {
        if (!(player instanceof ServerPlayer server)) return;

        Pending p = pending(server);
        p.failures.merge(why, 1, Integer::sum);
        // One clip per flush: a squad refusing in unison should answer once, not eight times over
        // itself. First audible reason wins, which is also the one the player reads first.
        if (p.clip == null && speaker != null) {
            SoundEvent clip = why.sound();
            if (clip != null) {
                p.voice = speaker;
                p.clip = clip;
            }
        }
    }

    /**
     * A unit refused a target on its own account, from the {@code setTarget} veto path.
     *
     * <p>PMC-only, and that needs no faction switch: an RU/US unit has no owner, so there is nobody
     * to report to and the work is dropped before any of it happens. Throttled twice — see the two
     * constants above — because the default target-priority table excludes every category but
     * {@code monster}, so {@code TARGET_EXCLUDED} fires many times a second per unit otherwise.
     */
    public static void veto(AbstractUnit unit, OrderFailure why) {
        if (!(unit instanceof PmcUnitEntity pmc)) return;
        if (unit.level().isClientSide) return;
        // Gates the spoken reply as well as the log line, deliberately: this flag means "report
        // refusals a unit made on its own account", and a squad of medics answering aloud every
        // time they decline a target is the same spam in a different medium.
        if (!com.neoalive.tacz_sewv.init.ModGameRules.server(com.neoalive.tacz_sewv.init.ModGameRules.TARGET_VETO_DEBUG)) return;

        UUID ownerId = pmc.getOwnerUUID();
        if (ownerId == null) return;
        MinecraftServer server = unit.getServer();
        if (server == null) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null) return;

        long now = unit.level().getGameTime();
        if (now < NEXT_VETO_LINE.getOrDefault(ownerId, Long.MIN_VALUE)) return;

        CompoundTag data = unit.getPersistentData();
        String key = VETO_KEY + why.name();
        if (now < data.getLong(key)) return;
        data.putLong(key, now + SewvConfig.TARGET_VETO_COOLDOWN_TICKS.get());

        pending(owner).fromVeto = true;
        fail(owner, why, unit);
    }

    private static Pending pending(ServerPlayer player) {
        return PENDING.computeIfAbsent(player.getUUID(), id -> new Pending());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;

        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        for (Map.Entry<UUID, Pending> entry : PENDING.entrySet()) {
            Pending p = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) emit(player, p);
            // Advanced only for veto traffic: a player issuing orders by hand should never be
            // rate-limited by chatter their units produced on their own.
            if (p.fromVeto) NEXT_VETO_LINE.put(entry.getKey(), now + VETO_PLAYER_FLOOR_TICKS);
        }
        PENDING.clear();
    }

    private static void emit(ServerPlayer player, Pending p) {
        for (Component s : p.successes) {
            com.neoalive.tacz_sewv.network.NetworkHandler.sendRaw(player, s);
        }
        for (Map.Entry<String, Integer> a : p.accepted.entrySet()) {
            String[] parts = a.getKey().split("\u0000", 2);
            int count = a.getValue();
            com.neoalive.tacz_sewv.network.NetworkHandler.sendRaw(player,
                    Component.translatable(parts[0] + (count == 1 ? ".single" : ".multiple"), count)
                            .withStyle(ChatFormatting.valueOf(parts[1])));
        }

        // Named, not translated: the console has no player locale to translate for, and on a
        // dedicated server a Component.translatable of a mod key resolves to the key anyway.
        for (Map.Entry<OrderFailure, Integer> f : p.failures.entrySet()) {
            int count = f.getValue();
            SewvDiag.orderFail("{} refused: {}{}", player.getGameProfile().getName(),
                    f.getKey().name(), count == 1 ? "" : " x" + count);
        }

        if (p.voice != null && p.clip != null && p.voice.isAlive()) {
            CrewRadio.speakRefusal(p.voice, p.clip);
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reset();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        reset();
    }

    private static void reset() {
        PENDING.clear();
        NEXT_VETO_LINE.clear();
    }
}

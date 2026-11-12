package com.neoalive.tacz_sewv.client;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.gui.GuiFit;
import com.neoalive.tacz_sewv.compat.voicechat.RadioVoiceIds;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.RadioSettings;

/**
 * Radio voice on the client, without naming a Voice Chat type: Voice Chat's audio threads report frames
 * here, and the client tick turns them into key-up and key-down clicks and a small TX/RX readout left of
 * the notification stack.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class RadioVoiceHud {

    /** Silence that ends a transmission; the next frame after it starts a new one. */
    private static final long GAP_NANOS = 200_000_000L;
    private static final long TX_NANOS = 200_000_000L;
    private static final long FORGET_NANOS = 10_000_000_000L;
    private static final double SQUELCH_CHANCE = 0.3;
    private static final int COL_TX = 0xFFE85A4A;
    private static final int COL_RX = 0xFF4FD1C5;

    private static final class Entry {
        volatile long last;
        volatile boolean live;
        volatile boolean startPending;
        String name;
    }

    private static final Map<UUID, Entry> STREAMS = new ConcurrentHashMap<>();
    private static volatile long lastTx;

    private RadioVoiceHud() {}

    /** A radio frame arrived. True when it starts a new transmission. Any thread. */
    public static boolean noteReceive(UUID channel, long nanos) {
        Entry e = STREAMS.computeIfAbsent(channel, k -> new Entry());
        boolean fresh = !e.live || nanos - e.last > GAP_NANOS;
        e.last = nanos;
        if (fresh) {
            e.live = true;
            e.startPending = true;
        }
        return fresh;
    }

    public static void noteTransmit(long nanos) {
        lastTx = nanos;
    }

    /** Someone is talking on the net right now. */
    public static boolean receiving() {
        for (Entry e : STREAMS.values()) if (e.live) return true;
        return false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || STREAMS.isEmpty()) return;
        long now = System.nanoTime();
        boolean clicks = ClientConfig.flag(ClientConfig.RADIO_VOICE_CLICKS);
        for (Iterator<Entry> it = STREAMS.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.startPending) {
                e.startPending = false;
                if (clicks) RadioCue.click(0.8F);
            }
            if (e.live && now - e.last > GAP_NANOS) {
                e.live = false;
                if (clicks) {
                    RadioCue.click(0.8F);
                    if (ThreadLocalRandom.current().nextDouble() < SQUELCH_CHANCE) RadioCue.squelch(0.5F);
                }
            }
            if (!e.live && now - e.last > FORGET_NANOS) it.remove();
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !ClientConfig.flag(ClientConfig.NOTIFICATIONS_ENABLED)) return;
        long now = System.nanoTime();
        String tx = null;
        if (now - lastTx < TX_NANOS && RadioFlagsClient.voice()) {
            ItemStack held = HandheldRadioItem.findHeld(mc.player);
            if (!held.isEmpty()) {
                RadioSettings.Comms comms = RadioSettings.readComms(held);
                if (comms.voice()) tx = I18n.get("hud.tacz_sewv.radio.tx", comms.net());
            }
        }
        Map.Entry<UUID, Entry> newest = null;
        for (Map.Entry<UUID, Entry> e : STREAMS.entrySet()) {
            if (e.getValue().live && (newest == null || e.getValue().last > newest.getValue().last)) newest = e;
        }
        String rx = newest == null ? null : I18n.get("hud.tacz_sewv.radio.rx", nameOf(mc, newest.getKey(), newest.getValue()));
        if (tx == null && rx == null) return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        float scale = Math.min(NotificationHud.DISPLAY_SCALE, GuiFit.fitScale(NotificationHud.TICKET_W, screenW));
        int right = (screenW - Math.round(NotificationHud.TICKET_W * scale)) / 2 - 6;
        int y = 4;
        if (tx != null) {
            g.drawString(font, tx, right - font.width(tx), y, COL_TX, true);
            y += font.lineHeight + 2;
        }
        if (rx != null) g.drawString(font, rx, right - font.width(rx), y, COL_RX, true);
    }

    private static String nameOf(Minecraft mc, UUID channel, Entry e) {
        if (e.name != null) return e.name;
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            for (PlayerInfo info : conn.getOnlinePlayers()) {
                if (RadioVoiceIds.of(info.getProfile().getId()).equals(channel)) {
                    e.name = info.getProfile().getName();
                    return e.name;
                }
            }
        }
        return I18n.get("hud.tacz_sewv.radio.unknown");
    }
}

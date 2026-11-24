package com.neoalive.tacz_sewv.client.voice;

import java.util.List;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.nekoyuni.SimpleEnemyMod.client.util.CommanderRayTrace;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.network.ModNetworking;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.RadioVoiceHud;
import com.neoalive.tacz_sewv.client.radial.OrderQueueClient;
import com.neoalive.tacz_sewv.client.radial.QuickCommandWheelScreen;
import com.neoalive.tacz_sewv.client.radial.WheelScope;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.compat.voicechat.VoicechatCompat;
import com.neoalive.tacz_sewv.compat.vosk.VoskCompat;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;

/**
 * Push to talk voice orders. Hold the key, say the order, let go: what was heard is parsed against the
 * phrase table and given through the wheel's own dispatch, so a spoken order and the same wheel pick are
 * the same packets. Positional orders said with "here" or "there" skip the wheel's click pick and use the
 * crosshair, since the player is already looking where they mean.
 *
 * <p>With Voice Chat installed, an order can also be spoken over the handheld radio with no key of its
 * own ({@code voiceOrdersOnRadio}): talking on the net starts the utterance and a pause ends it.
 */
public final class VoiceOrders {

    public enum Notice { NO_MODEL, NO_MIC, FAILED }

    /** How long the result line stays up. */
    private static final long LINE_MS = 3500;
    private static final double ATTACK_PICK_RANGE = 50.0;

    @Nullable
    private static VoicePhrases table;
    private static String tableLang = "";
    private static String grammar = "[]";
    @Nullable
    private static String line;
    private static boolean lineOk;
    private static long lineAt;
    /** The current utterance was started by talking on the radio, not by the order key. */
    private static volatile boolean radioSession;

    /** Built only if touched: a KeyMapping enters vanilla's key table on construction. */
    public static final class Keys {
        public static final KeyMapping PUSH_TO_TALK = new KeyMapping(
                "key." + TaczSewv.MODID + ".voice_order",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                "key.categories." + TaczSewv.MODID);

        private Keys() {}
    }

    private VoiceOrders() {}

    @Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            if (VoskCompat.present()) event.register(Keys.PUSH_TO_TALK);
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            if (VoskCompat.present()) VoskCompat.ensureModelDir();
        }
    }

    @Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
    public static final class Poll {
        private Poll() {}

        /**
         * Polled rather than clicked: SBW cancels raw input in weapon seats, and a keyboard binding read
         * here still works there (see {@code TdtKeybind}).
         */
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || !VoskCompat.present()) return;
            Minecraft mc = Minecraft.getInstance();
            boolean enabled = ClientConfig.flag(ClientConfig.VOICE_ORDERS_ENABLED) && mc.player != null;
            boolean key = enabled && mc.screen == null && Keys.PUSH_TO_TALK.isDown();
            boolean onRadio = enabled && ClientConfig.flag(ClientConfig.VOICE_ORDERS_ON_RADIO)
                    && RadioVoiceHud.transmittingOnRadio(mc);
            boolean active = VoskCompat.active();
            if (!active) radioSession = !key && onRadio;
            boolean down = key || onRadio;
            if (!down && !active) return;
            if (phrases(mc) == null) return;
            VoskCompat.tick(down, grammar, (float) minConfidence(), VoiceOrders::openMic);
        }
    }

    /** Voice Chat's microphone while it is sending, so the device is opened once; else our own capture. */
    private static MicSource openMic() {
        MicSource svc = VoicechatCompat.orderMic();
        return svc != null ? svc : new JavaSoundMic();
    }

    /**
     * Whether Voice Chat should withhold the player's outgoing voice: while a key held order is spoken,
     * unless the player chose to be heard. An order spoken over the radio is always heard. Any thread.
     */
    public static boolean muteOutgoing() {
        return VoskCompat.active() && !radioSession && !ClientConfig.flag(ClientConfig.VOICE_ORDERS_TRANSMIT);
    }

    /** The utterance is over and decoded. Client thread. */
    public static void onHeard(String heard) {
        Minecraft mc = Minecraft.getInstance();
        VoicePhrases phrases = phrases(mc);
        if (mc.player == null || phrases == null) return;
        VoiceOrderParser.Result r = VoiceOrderParser.parse(phrases, heard);
        switch (r.status()) {
            case EMPTY -> show(I18n.get("hud.tacz_sewv.voice.empty"), false);
            case UNKNOWN -> show(I18n.get("hud.tacz_sewv.voice.unknown", heard), false);
            case AMBIGUOUS -> show(I18n.get("hud.tacz_sewv.voice.ambiguous", heard), false);
            case OK -> give(mc.player, r, heard);
        }
    }

    public static void onNotice(Notice notice) {
        String key = switch (notice) {
            case NO_MODEL -> "hud.tacz_sewv.voice.no_model";
            case NO_MIC -> "hud.tacz_sewv.voice.no_mic";
            case FAILED -> "hud.tacz_sewv.voice.failed";
        };
        show(I18n.get(key), false);
    }

    private static void give(Player player, VoiceOrderParser.Result r, String heard) {
        String id = r.pipelineId();
        String label = QuickCommandRegistry.labelKey(id);
        if (!VoiceScope.apply(r.scope())) {
            show(I18n.get("hud.tacz_sewv.voice.nobody", heard), false);
            return;
        }
        if (QuickCommandRegistry.supportSettings(id, 0) != null) {
            if (!HandheldRadioItem.canCall(player)) {
                show(I18n.get("hud.tacz_sewv.voice.no_radio"), false);
                return;
            }
            QuickCommandWheelScreen.seedSupportDelay(player);
        }
        if (QuickCommandRegistry.isFormationPipeline(id)) {
            QuickCommandWheelScreen.matchFormationFilter(WheelScope.units(id, null));
        }
        QuickCommandWheelScreen.Aim aim = QuickCommandWheelScreen.captureAim();
        Entity target = r.aimed() && QuickCommandRegistry.ID_QUICK_ATTACK.equals(id)
                ? CommanderRayTrace.rayTraceEntity(player, ATTACK_PICK_RANGE) : null;
        boolean fired;
        if (r.aimed() && QuickCommandRegistry.ID_QUICK_MOVE.equals(id) && aim.block() != null) {
            fired = moveTo(id, label, aim.block());
        } else if (target != null) {
            fired = attack(id, label, target);
        } else {
            fired = QuickCommandWheelScreen.dispatch(id, label, aim);
        }
        if (fired) {
            show(I18n.get("hud.tacz_sewv.voice.heard", I18n.get(label), heard), true);
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2F));
        } else {
            show(I18n.get("hud.tacz_sewv.voice.rejected", heard), false);
        }
    }

    /** SEM's own Move order per unit, on the block under the crosshair, as the map's line move does. */
    private static boolean moveTo(String id, String label, BlockPos block) {
        List<Integer> units = WheelScope.units(id, null);
        if (units.isEmpty()) return false;
        Vec3 dest = Vec3.atBottomCenterOf(block.above());
        return bracketed(id, label, units, () -> {
            for (int unit : units) {
                ModNetworking.CHANNEL.sendToServer(new PacketIssueOrder(unit, OrderType.MOVE_TO_POSITION, dest, 0, -1));
            }
        });
    }

    private static boolean attack(String id, String label, Entity target) {
        List<Integer> units = WheelScope.units(id, null);
        if (units.isEmpty()) return false;
        return bracketed(id, label, units, () -> {
            for (int unit : units) {
                ModNetworking.CHANNEL.sendToServer(
                        new PacketIssueOrder(unit, OrderType.ATTACK_THAT_TARGET, Vec3.ZERO, 0, target.getId()));
            }
        });
    }

    /** In queue mode the packets are held server side like any wheel order. */
    private static boolean bracketed(String id, String label, List<Integer> units, Runnable send) {
        boolean queued = OrderQueueClient.queueable(id);
        if (queued) OrderQueueClient.begin(id, label, units);
        send.run();
        if (queued) OrderQueueClient.endOrAwaitPick();
        return true;
    }

    private static void show(String text, boolean ok) {
        line = text;
        lineOk = ok;
        lineAt = System.currentTimeMillis();
    }

    /** The HUD line, or null when there is nothing to show. */
    @Nullable
    public static String hudLine() {
        if (!VoskCompat.present() || !ClientConfig.flag(ClientConfig.VOICE_ORDERS_TRANSCRIPT)) return null;
        if (VoskCompat.active()) {
            String partial = VoskCompat.partial();
            return partial.isEmpty() ? I18n.get("hud.tacz_sewv.voice.listening") : partial;
        }
        if (line != null && System.currentTimeMillis() - lineAt < LINE_MS) return line;
        return null;
    }

    /** Live listening, or the last line was an order given. */
    public static boolean hudLive() {
        return VoskCompat.active() || lineOk;
    }

    @Nullable
    private static VoicePhrases phrases(Minecraft mc) {
        String lang = mc.getLanguageManager().getSelected();
        if (table == null || !lang.equals(tableLang)) {
            table = VoicePhrases.load(lang);
            tableLang = lang;
            grammar = table == null ? "[]" : table.grammarJson();
        }
        return table;
    }

    private static double minConfidence() {
        try {
            return ClientConfig.VOICE_ORDERS_MIN_CONFIDENCE.get();
        } catch (Throwable t) {
            return 0.5;
        }
    }
}

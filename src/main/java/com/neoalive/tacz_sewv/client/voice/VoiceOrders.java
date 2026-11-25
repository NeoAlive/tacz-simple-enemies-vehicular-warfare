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
import net.nekoyuni.SimpleEnemyMod.client.util.CommanderRayTrace;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.network.ModNetworking;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.RadioCue;
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
    /** The block pick's reach, so "attack that" names what "move there" would. */
    private static final double ATTACK_PICK_RANGE = com.neoalive.tacz_sewv.client.ClientEvents.BLOCK_PICK_RANGE;

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
    /** The press cue played and the release cue is owed. Client thread. */
    private static boolean cueOpen;

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

    @EventBusSubscriber(modid = TaczSewv.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
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

    @EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
    public static final class Poll {
        private Poll() {}

        /**
         * Polled rather than clicked: SBW cancels raw input in weapon seats, and a keyboard binding read
         * here still works there (see {@code TdtKeybind}).
         */
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (!VoskCompat.present()) return;
            Minecraft mc = Minecraft.getInstance();
            boolean enabled = ClientConfig.flag(ClientConfig.VOICE_ORDERS_ENABLED) && mc.player != null;
            boolean key = enabled && mc.screen == null && Keys.PUSH_TO_TALK.isDown();
            boolean onRadio = enabled && ClientConfig.flag(ClientConfig.VOICE_ORDERS_ON_RADIO)
                    && RadioVoiceHud.transmittingOnRadio(mc);
            boolean active = VoskCompat.active();
            if (!active) radioSession = !key && onRadio;
            boolean down = key || onRadio;
            cues(key);
            if (!down && !active) return;
            if (phrases(mc) == null) return;
            VoskCompat.tick(down, grammar, (float) minConfidence(), VoiceOrders::openMic);
        }

        /**
         * Key-up and key-down for a key held order, so the player knows when to talk and when it is over.
         * The press cue waits for the microphone to be open, not for the key, so speech started on it is
         * never clipped; the release cue plays the moment the key comes up, ahead of the tail. An order
         * spoken over the radio net gets none: the radio's own clicks already mark it.
         */
        private static void cues(boolean key) {
            if (radioSession || !ClientConfig.flag(ClientConfig.VOICE_ORDERS_CUES)) {
                cueOpen = false;
                return;
            }
            boolean listening = VoskCompat.listening();
            if (!cueOpen && key && listening) {
                RadioCue.click(1.0F);
                cueOpen = true;
            } else if (cueOpen && (!key || !listening)) {
                RadioCue.click(0.8F);
                RadioCue.squelch(0.35F);
                cueOpen = false;
            }
        }
    }

    /** Voice Chat's microphone while it is sending, so the device is opened once; else our own capture. */
    private static MicSource openMic() {
        MicSource svc = VoicechatCompat.orderMic();
        if (svc != null) return svc;
        String device;
        try {
            device = ClientConfig.VOICE_ORDERS_MIC_DEVICE.get();
        } catch (Throwable t) {
            device = "";
        }
        return new JavaSoundMic(device);
    }

    /**
     * Whether Voice Chat should withhold the player's outgoing voice: while a key held order is spoken,
     * unless the player chose to be heard. An order spoken over the radio is always heard. Any thread.
     */
    public static boolean muteOutgoing() {
        return VoskCompat.active() && !radioSession && !ClientConfig.flag(ClientConfig.VOICE_ORDERS_TRANSMIT);
    }

    /** The utterance is over and decoded. {@code silent}: the device gave nothing but silence. Client thread. */
    public static void onHeard(String heard, String device, boolean silent) {
        Minecraft mc = Minecraft.getInstance();
        VoicePhrases phrases = phrases(mc);
        if (mc.player == null || phrases == null) return;
        VoiceOrderParser.Result r = VoiceOrderParser.parse(phrases, heard);
        TaczSewv.LOGGER.info("Voice orders: '{}' parsed {} {} scope={} aimed={}",
                heard, r.status(), r.pipelineId(), r.scope(), r.aimed());
        switch (r.status()) {
            case EMPTY -> show(silent ? I18n.get("hud.tacz_sewv.voice.silent", device)
                    : I18n.get("hud.tacz_sewv.voice.empty"), false);
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
        if (r.aimed() && QuickCommandRegistry.ID_QUICK_ATTACK.equals(id) && target == null) {
            // A click pick armed by voice waits silently for a click the player is not expecting.
            show(I18n.get("hud.tacz_sewv.voice.no_target"), false);
            return;
        }
        boolean fired;
        if (r.aimed() && QuickCommandRegistry.ID_QUICK_MOVE.equals(id) && aim.block() != null) {
            fired = moveTo(id, label, aim.block());
        } else if (target != null) {
            fired = attack(id, label, target);
        } else {
            fired = QuickCommandWheelScreen.dispatch(id, label, aim);
        }
        TaczSewv.LOGGER.info("Voice orders: {} {}", id, fired ? "given" : "refused");
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
                ModNetworking.sendToServer(new PacketIssueOrder(unit, OrderType.MOVE_TO_POSITION, dest, 0, -1));
            }
        });
    }

    private static boolean attack(String id, String label, Entity target) {
        List<Integer> units = WheelScope.units(id, null);
        if (units.isEmpty()) return false;
        return bracketed(id, label, units, () -> {
            for (int unit : units) {
                ModNetworking.sendToServer(
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
        // The model's language decides, since it only hears its own words; the game's is the fallback.
        String model = VoicePhrases.langOfModel(VoskCompat.modelName());
        String lang = model != null ? model : mc.getLanguageManager().getSelected();
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

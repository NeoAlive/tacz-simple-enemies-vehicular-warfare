package com.neoalive.tacz_sewv.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHoldRevive;

/**
 * Hold-left-click-while-looking-at-a-downed-PMC-to-revive input, matching SBW's own
 * artillery-indicator trigger (holding the vanilla attack key). Purely a client-side poll and
 * report: every client tick while holding, sends the server whatever it's currently looking at, plus
 * one final packet the tick it stops — {@code PmcDownedSupport.handleHoldRevive} owns all the actual
 * timing/validation/completion server-side, this class never decides anything on its own, and every
 * value it sends is re-checked on arrival rather than trusted.
 *
 * <p>Reads {@link Minecraft#crosshairPickEntity} — vanilla's own "what would left-click hit right
 * now" state, already reach-limited by vanilla's own entity picking, so no separate distance check
 * is needed client-side (the server still enforces its own range check independently).
 *
 * <p>Not sent every tick regardless of state (which would mean every player on the server
 * constantly emitting 20 packets/s during ordinary play) — only while actively holding on a valid
 * target, edge-triggered down to exactly one packet on release.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class ReviveHoldInput {

    private static boolean wasHolding;

    private ReviveHoldInput() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            releaseIfHeld();
            return;
        }

        int targetId = -1;
        if (mc.options.keyAttack.isDown()) {
            Entity looking = mc.crosshairPickEntity;
            if (looking instanceof PmcUnitEntity pmc
                    && pmc instanceof IPmcDowned downed
                    && downed.sewv$isDownedSynced()) {
                targetId = pmc.getId();
            }
        }

        if (targetId != -1) {
            NetworkHandler.CHANNEL.sendToServer(new PacketHoldRevive(targetId));
            wasHolding = true;
        } else {
            releaseIfHeld();
        }
    }

    private static void releaseIfHeld() {
        if (!wasHolding) return;
        wasHolding = false;
        NetworkHandler.CHANNEL.sendToServer(new PacketHoldRevive(-1));
    }
}

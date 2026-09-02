package com.neoalive.tacz_sewv.crew;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSyncPmcIdentity;

public class PlayerJoinHandler {

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;

        UUID playerUuid = player.getUUID();
        PlayerDoctrineData data = PlayerDoctrineData.get(player.level());

        if (!data.hasReceivedBook(playerUuid)) {
            ItemStack ledger = new ItemStack(ModItems.DOCTRINE_LEDGER.get());
            ledger.getOrCreateTag().putBoolean("sewv_is_initial", true);

            if (player.getInventory().add(ledger)) {
                data.setReceivedBook(playerUuid);
            } else {
                // If inventory is full, try to drop it at the player's feet.
                // We still mark it as received so we don't drop it every time they log in.
                player.drop(ledger, false);
                data.setReceivedBook(playerUuid);
            }
        }

        if (player instanceof ServerPlayer sp) {
            String category = NamePreference.get(sp, SewvConfig.DEFAULT_NAME_CATEGORY.get());
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sp),
                    new PacketSyncPmcIdentity(PmcIdentityPreference.get(sp), category));
            NpcIdentity.refreshCompanyName(sp);
        }
    }
}

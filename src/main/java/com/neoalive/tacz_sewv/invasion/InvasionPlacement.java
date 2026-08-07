package com.neoalive.tacz_sewv.invasion;

import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.TeamBaseBlock;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Hard-cap: at most two {@link TeamBaseBlock}s per dimension.
 * Count comes from {@link InvasionLayout} (survives chunk unload); forget only on real break.
 * Disabled when {@link SewvConfig#UNLIMITED_TEAM_BASES} is true.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class InvasionPlacement {

    public static final int MAX_TEAM_BASES = 2;

    private InvasionPlacement() {}

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getPlacedBlock().getBlock() instanceof TeamBaseBlock)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (SewvConfig.SPEC.isLoaded() && SewvConfig.UNLIMITED_TEAM_BASES.get()) return;

        // Forge places the block (BE onLoad → noteTeamBase) before this event fires, so the
        // position under the cursor is already in the set. Count other bases only.
        Set<Long> noted = InvasionLayout.get(level).teamBasePositions();
        int existing = noted.size();
        if (noted.contains(event.getPos().asLong())) {
            existing--;
        }
        if (existing < MAX_TEAM_BASES) return;

        event.setCanceled(true);
        Entity placer = event.getEntity();
        if (placer instanceof ServerPlayer player) {
            player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.invasion.team_base_limit"), true);
        }
    }
}

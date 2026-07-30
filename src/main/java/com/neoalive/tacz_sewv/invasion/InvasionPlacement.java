package com.neoalive.tacz_sewv.invasion;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.TeamBaseBlock;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Hard-cap: at most two {@link TeamBaseBlock}s per dimension.
 * Count comes from {@link InvasionLayout} (survives chunk unload); forget only on real break.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class InvasionPlacement {

    public static final int MAX_TEAM_BASES = 2;

    private InvasionPlacement() {}

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getPlacedBlock().getBlock() instanceof TeamBaseBlock)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        int have = InvasionLayout.get(level).teamBasePositions().size();
        // New block is not noted until BE onLoad — so size is existing count.
        if (have < MAX_TEAM_BASES) return;

        event.setCanceled(true);
        Entity placer = event.getEntity();
        if (placer instanceof ServerPlayer player) {
            player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.invasion.team_base_limit"), true);
        }
    }
}

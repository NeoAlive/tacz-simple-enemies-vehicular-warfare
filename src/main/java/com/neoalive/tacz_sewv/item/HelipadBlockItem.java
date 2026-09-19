package com.neoalive.tacz_sewv.item;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.neoalive.tacz_sewv.airport.HelipadClearance;

/**
 * Refuses to place a helipad unless its whole 3x3x3 is free, and says why on the action bar.
 * The check runs from the east-north corner to the south-west corner around the clicked cell,
 * because the placed block is the centre of the pad.
 */
public class HelipadBlockItem extends BlockItem {

    public HelipadBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Nullable
    @Override
    protected BlockState getPlacementState(BlockPlaceContext context) {
        BlockState state = super.getPlacementState(context);
        if (state == null) return null;
        BlockPos blocker = HelipadClearance.placementBlocker(context.getLevel(), context.getClickedPos());
        if (blocker == null) return state;
        if (context.getPlayer() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.helipad.no_room",
                    blocker.getX(), blocker.getY(), blocker.getZ()), true);
        }
        return null;
    }
}

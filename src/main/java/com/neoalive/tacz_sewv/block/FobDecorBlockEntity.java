package com.neoalive.tacz_sewv.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.neoalive.tacz_sewv.init.ModBlockEntities;

/** Marker block entity so quarters/parking geo models can render via BER. */
public class FobDecorBlockEntity extends BlockEntity {

    public FobDecorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FOB_DECOR.get(), pos, state);
    }
}

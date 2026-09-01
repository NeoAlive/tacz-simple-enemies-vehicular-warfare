package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import com.neoalive.tacz_sewv.fob.FobSupport;

public interface IFobAssigned {

    default boolean sewv$hasFobAssignment() {
        return FobSupport.isStamped(asEntity());
    }

    default BlockPos sewv$fobCommandPos() {
        return FobSupport.stampPos(asEntity());
    }

    Entity asEntity();
}

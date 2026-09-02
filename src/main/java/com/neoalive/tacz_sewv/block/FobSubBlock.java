package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;

final class FobSubBlock {

    private FobSubBlock() {}

    static void onSubPlaced(Level level, BlockPos pos, String linkType, @Nullable LivingEntity placer) {
        if (level.isClientSide()) return;
        ServerLevel server = (ServerLevel) level;
        FobManager mgr = FobManager.get(server);
        FobInstance fob = mgr.getFobAt(pos, level);
        if (fob == null) {
            level.destroyBlock(pos, false);
            if (placer instanceof ServerPlayer player) {
                FobManager.denyPlacement(player, "message.tacz_sewv.fob.no_command_block");
            }
            return;
        }
        mgr.linkSubBlock(fob.commandPos, pos, linkType, level);
    }

    static void onSubRemoved(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        FobManager mgr = FobManager.get((ServerLevel) level);
        FobInstance fob = mgr.getFobAt(pos, level);
        if (fob != null) {
            mgr.unlinkSubBlock(fob.commandPos, pos, level);
        }
    }
}

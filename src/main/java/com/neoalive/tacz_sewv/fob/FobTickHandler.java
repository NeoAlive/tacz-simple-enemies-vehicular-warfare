package com.neoalive.tacz_sewv.fob;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class FobTickHandler {

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;
        if (level.isClientSide()) return;
        FobManager mgr = FobManager.get(level);
        long gameTime = level.getGameTime();
        for (FobInstance fob : mgr.all()) {
            boolean wasScramble = fob.scrambleActive;
            int wasScore = fob.threatScore;
            ThreatEvaluator.evaluate(level, fob, gameTime);
            if (fob.scrambleActive != wasScramble || fob.threatScore != wasScore) {
                mgr.setDirty();
            }
        }
    }
}

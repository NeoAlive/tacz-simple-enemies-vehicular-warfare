package com.neoalive.tacz_sewv.command.quick;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobNetworking;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/**
 * Quick Route to FOB — reinvokes {@link FobNetworking#routeToFob} for the issuer's FOB.
 * Unit ids from the wheel are unused; routing uses FOB assignment lists.
 */
public final class QuickRouteFobPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;
        FobInstance fob = FobManager.get(level).getFobForOwner(issuer.getUUID());
        if (fob == null || fob.commandPos == null) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_route_fob.none", 0,
                    ChatFormatting.RED);
            return;
        }
        int routed = FobNetworking.routeToFob(issuer, fob.commandPos);
        NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_route_fob.started", routed,
                ChatFormatting.GREEN, routed);
    }
}

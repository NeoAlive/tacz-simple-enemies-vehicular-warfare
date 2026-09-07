package com.neoalive.tacz_sewv.command.quick;

import java.util.Comparator;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.compat.PlayerReviveCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.support.ReviveClaims;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/**
 * Downed player → assign exactly one owned on-foot PMC to revive them.
 * Unit ids from the wheel are unused; the server picks the nearest eligible crew.
 */
public final class QuickReviveCallPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;
        if (!PlayerReviveCompat.isLoaded() || !SewvConfig.PMC_REVIVE_ENABLED.get()) {
            NetworkHandler.sendOrderFeedback(issuer,
                    Component.translatable("message.tacz_sewv.revive_call.unavailable")
                            .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!PlayerReviveCompat.isDowned(issuer)) {
            NetworkHandler.sendOrderFeedback(issuer,
                    Component.translatable("message.tacz_sewv.revive_call.not_downed")
                            .withStyle(ChatFormatting.GRAY));
            return;
        }

        double radius = SewvConfig.PMC_REVIVE_SEARCH_RADIUS.get();
        PmcUnitEntity helper = level.getEntitiesOfClass(
                        PmcUnitEntity.class,
                        issuer.getBoundingBox().inflate(radius),
                        u -> ReviveClaims.isEligibleReviver(u)
                                && PmcOwnerSupport.isOwner(issuer, u)
                                && OrderAuth.check(issuer, u, "ReviveCall"))
                .stream()
                .min(Comparator.comparingDouble(issuer::distanceToSqr))
                .orElse(null);

        if (helper == null) {
            NetworkHandler.sendOrderFeedback(issuer,
                    Component.translatable("message.tacz_sewv.revive_call.none")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        ReviveClaims.forceClaim(issuer.getId(), helper.getId());
        NetworkHandler.sendOrderFeedback(issuer,
                Component.translatable("message.tacz_sewv.revive_call.started")
                        .withStyle(ChatFormatting.GREEN));
    }
}

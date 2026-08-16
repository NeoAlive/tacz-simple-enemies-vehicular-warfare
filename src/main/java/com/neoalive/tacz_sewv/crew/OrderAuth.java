package com.neoalive.tacz_sewv.crew;

import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;

/**
 * Shared ownership gate + diag for every order-dispatch path.
 */
public final class OrderAuth {

    private OrderAuth() {}

    /**
     * @return true if {@code player} owns {@code pmc}
     */
    public static boolean check(ServerPlayer player, PmcUnitEntity pmc, String channel) {
        boolean authorized = PmcOwnerSupport.isOwner(player, pmc);
        String dispatcherFaction = OpenPacCompat.factionName(player.getServer(), player.getUUID());
        String ownerFaction = pmc.getOwnerUUID() == null ? null
                : OpenPacCompat.factionName(player.getServer(), pmc.getOwnerUUID());
        SewvDiag.orderAuth(
                "channel={} dispatcher={} dispatcherFaction={} unit={} ownerUuid={} ownerFaction={} authorized={}",
                channel,
                player.getGameProfile().getName(),
                dispatcherFaction,
                pmc.getId(),
                pmc.getOwnerUUID(),
                ownerFaction,
                authorized);
        return authorized;
    }
}

package com.neoalive.tacz_sewv.territory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ITerritoryPost;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath;
import com.neoalive.tacz_sewv.entity.ai.support.TerritorySupport;

/**
 * {@code /sewv territory post|clear|dump} (operators): insurance for debugging the RTS panel without it. Goes
 * through the same {@link TerritorySupport} / {@link TerritoryManager} paths the panel does, so what it shows is
 * what the panel would have caused. Not a feature: dead code if the panel works.
 */
public final class TerritoryDebug {

    private static final double POST_RADIUS = 48.0;

    private TerritoryDebug() {}

    /** Posts every eligible owned unit within 48 blocks on the chunk the player stands in, and turns the mode on. */
    public static int post(ServerPlayer player) {
        TerritoryData.get(player.server).set(player.getUUID(), true);
        int cx = player.getBlockX() >> 4;
        int cz = player.getBlockZ() >> 4;
        int posted = 0;
        for (PmcUnitEntity u : TerritoryManager.ownedLoaded(player.serverLevel(), player)) {
            if (u.distanceTo(player) > POST_RADIUS || TerritoryManager.status(u) != TerritoryManager.Status.OK) continue;
            if (TerritorySupport.post(u, cx, cz)) {
                u.getPersistentData().putUUID(ITerritoryPost.TAG_BY, player.getUUID());
                posted++;
            }
        }
        TerritoryManager.pass(player);
        return posted;
    }

    /** Releases every loaded post of the player's and turns the mode off. Returns how many were posted. */
    public static int clear(ServerPlayer player) {
        int posted = 0;
        for (PmcUnitEntity u : TerritoryManager.ownedLoaded(player.serverLevel(), player)) {
            if (((ITerritoryPost) u).sewv$hasTerritoryPost()) posted++;
        }
        TerritoryManager.setMode(player, false);
        return posted;
    }

    public static List<String> dump(ServerPlayer player) {
        List<String> out = new ArrayList<>();
        TerritoryData data = TerritoryData.get(player.server);
        Set<Long> claims = OpenPacCompat.selfClaimedChunks(player.serverLevel(), player.getUUID());
        out.add("mode=" + data.isOn(player.getUUID()) + " seenClaims=" + data.hasSeenClaims(player.getUUID())
                + " claims=" + claims.size() + " front=" + FrontlineMath.frontChunks(claims).size()
                + " tick=" + player.server.getTickCount());
        int n = 0;
        for (PmcUnitEntity u : TerritoryManager.ownedLoaded(player.serverLevel(), player)) {
            ITerritoryPost p = (ITerritoryPost) u;
            String post = p.sewv$hasTerritoryPost()
                    ? "post=(" + p.sewv$getTerritoryChunkX() + "," + p.sewv$getTerritoryChunkZ() + ")"
                            + (p.sewv$isTerritoryLost() ? " LOST" : "") + " dist="
                            + Math.round(Math.sqrt(TerritorySupport.distSqToCentre(p, u)))
                    : "post=-";
            out.add("#" + u.getId() + " " + u.getDisplayName().getString() + " " + TerritoryManager.status(u)
                    + " " + post + " target=" + (u.getTarget() == null ? "-" : u.getTarget().getName().getString()));
            if (++n >= 25) {
                out.add("... (capped at 25)");
                break;
            }
        }
        return out;
    }
}

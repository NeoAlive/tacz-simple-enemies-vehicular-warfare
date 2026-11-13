package com.neoalive.tacz_sewv.command.quick.scavenge;

import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;

import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.crew.VehicleAccessLive;

/**
 * Whose things a scavenger may take. OpenPAC claims are honoured by owner and party (not per
 * permission flag); a hull also needs SBW's key lock to be the issuer's own and our owner stamp to
 * authorize the issuer; a mine needs to be unowned or the issuer's side.
 */
final class ScavengeAccess {

    private ScavengeAccess() {}

    static boolean claimAllows(ServerLevel level, BlockPos pos, UUID issuer) {
        UUID owner = OpenPacCompat.claimOwnerId(level, pos);
        return owner == null || owner.equals(issuer) || OpenPacCompat.allied(level.getServer(), owner, issuer);
    }

    static boolean hullAllows(ServerLevel level, VehicleEntity hull, UUID issuer) {
        if (!claimAllows(level, hull.blockPosition(), issuer)) return false;
        if (hull.getLocked() && !issuer.toString().equals(hull.getLastDriverUUID())) return false;
        return VehicleAccessLive.stampAllows(hull, issuer);
    }

    static boolean mineAllows(ServerLevel level, Entity mine, UUID issuer) {
        if (!claimAllows(level, mine.blockPosition(), issuer)) return false;
        UUID owner = mine instanceof OwnableEntity ownable ? ownable.getOwnerUUID() : null;
        return sameSide(level, owner, issuer);
    }

    private static boolean sameSide(ServerLevel level, @Nullable UUID owner, UUID issuer) {
        return owner == null || owner.equals(issuer) || OpenPacCompat.allied(level.getServer(), owner, issuer);
    }
}

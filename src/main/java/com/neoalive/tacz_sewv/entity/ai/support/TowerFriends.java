package com.neoalive.tacz_sewv.entity.ai.support;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.AutoAimableEntity;
import com.atsuishio.superbwarfare.world.saveddata.TDMSavedData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Which units an owned SBW auto turret (CIWS, laser and wave towers) must leave alone. SBW's tower
 * AI calls anything that is an {@code Enemy} hostile before it looks at the owner, and every SEM
 * unit is a Monster, so a player's CIWS shot the player's own PMCs. Judged from the owner's side;
 * a unit SBW's TDM lists stays hostile.
 */
public final class TowerFriends {

    private TowerFriends() {}

    public static boolean spares(AutoAimableEntity tower, @Nullable Entity e) {
        if (!(e instanceof AbstractUnit unit) || tower.level().isClientSide()) return false;
        if (TDMSavedData.Companion.enabledTDM(unit)) return false;
        LivingEntity owner = tower.getOwner();
        if (owner instanceof Player player) {
            if (unit instanceof PmcUnitEntity pmc && player.getUUID().equals(pmc.getOwnerUUID())) return true;
            return VehicleTargeting.isOnPlayersSide(unit, player);
        }
        if (owner instanceof AbstractUnit ownerUnit) {
            return VehicleTargeting.isNonHostileIgnoringGameMode(ownerUnit, unit);
        }
        return false;
    }
}

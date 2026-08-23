package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.Type63Support;
import com.neoalive.tacz_sewv.item.LockItem;

/**
 * Lets RU/US infantry claim an abandoned mortar or Type-63 MLRS it walks past — the
 * {@link SeekAbandonedVehicleGoal} feature, extended to seatless emplacements that goal
 * deliberately excludes.
 */
public class SeekAbandonedMortarGoal extends Goal {

    private static final int SCAN_INTERVAL = 40;
    private static final int MAX_SCAN_INTERVAL = 200;

    private final AbstractUnit unit;
    private int scanCooldown;
    private int scanInterval = SCAN_INTERVAL;

    public SeekAbandonedMortarGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!shouldScan()) return false;
        if (this.scanCooldown-- > 0) return false;

        Entity weapon = findAbandonedSeatless();
        this.scanInterval = weapon == null
                ? Math.min(MAX_SCAN_INTERVAL, this.scanInterval * 2)
                : SCAN_INTERVAL;
        this.scanCooldown = this.scanInterval;
        if (weapon instanceof MortarEntity mortar) {
            MortarSupport.claim(this.unit, mortar);
        } else if (weapon instanceof Type63Entity type63) {
            Type63Support.claim(this.unit, type63);
        }
        return false;
    }

    private boolean shouldScan() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.AUTO_MAN_MORTAR_ENABLED.get()) return false;
        if (!(this.unit instanceof RUunitEntity || this.unit instanceof USunitEntity)) return false;
        if (this.unit.isPassenger()) return false;
        if (this.unit.getTarget() != null) return false;
        if (this.unit instanceof IVehicleBoarder boarder && boarder.tacz_sewv$isBoarding()) return false;
        return !MortarSupport.hasMortarClaim(this.unit);
    }

    @Nullable
    private Entity findAbandonedSeatless() {
        double radius = SewvConfig.AUTO_MAN_MORTAR_SCAN_RADIUS.get();
        MortarEntity mortar = nearestMortar(radius);
        Type63Entity type63 = nearestType63(radius);
        if (mortar == null) return type63;
        if (type63 == null) return mortar;
        return this.unit.distanceToSqr(mortar) <= this.unit.distanceToSqr(type63) ? mortar : type63;
    }

    @Nullable
    private MortarEntity nearestMortar(double radius) {
        List<MortarEntity> candidates = this.unit.level().getEntitiesOfClass(
                MortarEntity.class,
                this.unit.getBoundingBox().inflate(radius),
                this::isAbandonedMortar);
        return candidates.stream()
                .min(Comparator.comparingDouble(this.unit::distanceToSqr))
                .orElse(null);
    }

    @Nullable
    private Type63Entity nearestType63(double radius) {
        List<Type63Entity> candidates = this.unit.level().getEntitiesOfClass(
                Type63Entity.class,
                this.unit.getBoundingBox().inflate(radius),
                this::isAbandonedType63);
        return candidates.stream()
                .min(Comparator.comparingDouble(this.unit::distanceToSqr))
                .orElse(null);
    }

    private boolean isAbandonedMortar(MortarEntity mortar) {
        if (!mortar.isAlive() || mortar.isWreck()) return false;
        if (LockItem.isLocked(mortar)) return false;
        if (MortarSupport.isMortarClaimed(mortar, this.unit)) return false;
        return healthyEnough(mortar);
    }

    private boolean isAbandonedType63(Type63Entity launcher) {
        if (!launcher.isAlive() || launcher.isWreck()) return false;
        if (LockItem.isLocked(launcher)) return false;
        if (Type63Support.isClaimed(launcher, this.unit)) return false;
        return healthyEnough(launcher);
    }

    private boolean healthyEnough(com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity hull) {
        float max = hull.getMaxHealth();
        return !(max > 0.0F
                && hull.getHealth() < max * SewvConfig.AUTO_BOARD_MIN_HEALTH_FRACTION.get().floatValue());
    }
}

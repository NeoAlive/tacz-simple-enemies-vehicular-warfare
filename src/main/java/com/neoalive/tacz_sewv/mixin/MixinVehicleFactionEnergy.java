package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Unlimited energy for a vehicle whose driver's seat is held by an RU/US unit.
 *
 * <p>Faction crews have no way to refuel: {@code util.TankSpawner} fills the hull at
 * spawn and nothing ever tops it up again, so a long-lived RU/US vehicle eventually
 * runs flat and becomes a stationary pillbox. Players have a fuel/charge economy;
 * these crews are scenery-side opposition and shouldn't be governed by it.
 *
 * <p>Gating {@code consumeEnergy} ALONE is not enough, and this is the trap: every SBW
 * engine (VehicleEngineUtils.trackEngine and friends) tests
 * {@code getEnergy() <= energyCost || (getMaxEnergy() > 0 && getEnergy() <= 0)} and
 * bails out BEFORE it ever calls {@code consumeEnergy}. A hull that reached 0 by any
 * route would therefore never reach the drain hook and would stay dead forever. So the
 * read is gated too, which is what actually makes the energy unlimited.
 *
 * <p>{@code Integer.MAX_VALUE} is SBW's OWN sentinel for this: {@code getEnergy()}
 * returns exactly that for a vehicle with no energy storage at all, so every consumer
 * downstream already copes with it. {@code getMaxEnergy()} is deliberately left alone —
 * touching it would drag in {@code computed()} (a full {@code VehicleData.compute()},
 * too expensive for a hot read), and the HUD it feeds is unreachable anyway because
 * MixinVehicleInteractLock stops players interacting with enemy-crewed vehicles.
 *
 * <p>{@code consumeEnergy} is still cancelled so the STORED value stays pristine rather
 * than silently draining behind the gate: once the crew dies or dismounts, the hull
 * reverts to whatever charge it genuinely had, and a captured/looted vehicle behaves
 * normally instead of being mysteriously empty.
 *
 * <p>PMC units are excluded on purpose — they are player-owned, so they stay on the
 * player's energy economy.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleFactionEnergy {

    /**
     * True while the driver's seat holds an RU/US unit.
     *
     * <p>Cheap enough to run on every energy read — an empty-check, one list get and
     * two instanceofs, with no {@code computed()} call. Deliberately NOT cached per
     * tick: the value flips the instant a crew dismounts or dies, and a stale cache
     * would leave a hull running on phantom power (or a live crew stranded) for a tick.
     *
     * <p>Seat 0 == driver is the same convention DriveVehicleGoal.canUse() enforces
     * with {@code getFirstPassenger() != unit}, and the order TankSpawner mounts in.
     */
    @Unique
    private boolean tacz_sewv$hasFactionDriver() {
        Entity driver = ((VehicleEntity) (Object) this).getFirstPassenger();
        return driver instanceof RUunitEntity || driver instanceof USunitEntity;
    }

    @Inject(method = "getEnergy", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$unlimitedEnergyRead(CallbackInfoReturnable<Integer> cir) {
        if (tacz_sewv$hasFactionDriver()) cir.setReturnValue(Integer.MAX_VALUE);
    }

    @Inject(method = "consumeEnergy", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$noEnergyDrain(int amount, CallbackInfo ci) {
        if (tacz_sewv$hasFactionDriver()) ci.cancel();
    }
}

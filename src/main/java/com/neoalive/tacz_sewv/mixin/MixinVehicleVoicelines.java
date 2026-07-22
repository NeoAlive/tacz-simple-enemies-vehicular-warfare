package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.util.CrewRadio;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hull-event voicelines (the crew-event ones live in {@link MixinUnitVoicelines}). All route through
 * {@link CrewRadio}, so the driver speaks once per hull with the shared no-overlap cooldown.
 * <ul>
 *   <li><b>damaged</b> -- on {@code onHurt} once the hull is under 60% health, so every further hit
 *       re-triggers it. {@code onHurt} applies the damage itself ({@code setHealth}), so
 *       {@code getHealth()} at TAIL is final.
 *   <li><b>decoy</b> -- on the false->true edge of {@code setDecoyInputDown}, so the latched input
 *       (held down across a whole retreat) fires the line once. Catches ground and air crews alike,
 *       and a player-driven hull is skipped for free (its driver is no {@code AbstractUnit}).
 * </ul>
 */
@Mixin(VehicleEntity.class)
public abstract class MixinVehicleVoicelines {

    @Inject(method = "onHurt", at = @At("TAIL"), remap = false)
    private void tacz_sewv$damagedVoice(float amount, Entity attacker, boolean bl, CallbackInfo ci) {
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (hull.getHealth() < 0.6f * hull.getMaxHealth()) {
            CrewRadio.play(hull, CrewRadio.Line.DAMAGED);
        }
    }

    @Unique private static final String tacz_sewv$DECOY_KEY = "tacz_sewv:decoy_ep";
    @Unique private static final int tacz_sewv$DECOY_GRACE = 120; // salvos within this are one episode

    @Inject(method = "setDecoyInputDown", at = @At("HEAD"), remap = false)
    private void tacz_sewv$decoyVoice(boolean down, CallbackInfo ci) {
        if (!down) return;
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (hull.level().isClientSide) return;
        // DriveVehicleGoal cycles this latch on/off every smoke salvo, so a rising-edge check fires
        // per salvo. Debounce: announce a whole screening EPISODE once, not each volley within it.
        long now = hull.level().getGameTime();
        CompoundTag data = hull.getPersistentData();
        long episodeEnd = data.getLong(tacz_sewv$DECOY_KEY);
        data.putLong(tacz_sewv$DECOY_KEY, now + tacz_sewv$DECOY_GRACE);
        if (now >= episodeEnd) CrewRadio.play(hull, CrewRadio.Line.DECOY);
    }

    /**
     * TOW launch call-out. Injected on {@code VehicleEntity}'s own shoot rather than
     * {@code TowEntity.vehicleShoot}, because that override runs its backblast/reload tail even when
     * the super call is cancelled by MixinVehicleFireCooldown -- so hooking it would announce shots
     * that never happened. TAIL here is skipped whenever that gate cancels at HEAD, so the line
     * tracks actual launches. Reloading is silent (SBW plays its own TYPE_63_RELOAD).
     */
    @Inject(
            method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("TAIL"), remap = false)
    private void tacz_sewv$towFireVoice(LivingEntity shooter, UUID uuid, Vec3 targetPos, CallbackInfo ci) {
        if ((Object) this instanceof TowEntity tow) {
            CrewRadio.play(tow, CrewRadio.Line.TOW);
        }
    }
}

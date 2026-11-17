package com.neoalive.tacz_sewv.mixin.tripod;

import com.atsuishio.superbwarfare.entity.projectile.Agm65Entity;
import com.atsuishio.superbwarfare.entity.projectile.JavelinMissileEntity;
import com.atsuishio.superbwarfare.entity.projectile.Ru9m100MissileEntity;
import com.atsuishio.superbwarfare.entity.projectile.Ru9m336MissileEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.compat.TripodCompat;

/**
 * SBW's anti-ground homing missiles steer at their target's {@code getY()}, which for a tripod is
 * the ground under a body that floats 32 blocks up: they locked on and flew in underneath it. Each
 * tick reads the target's {@code getY()} through exactly these calls (the missile's own position is
 * read through its own class, so it never matches), and {@link TripodCompat#homingY} answers the
 * body for a tripod and the plain {@code getY()} for everything else. The AGM-65 (and the Kh-39,
 * which inherits its tick) homes on the feet; the others add half the entity height themselves.
 *
 * <p>In the tripod mixin config, so it is not even applied without the mod.
 */
@Mixin(value = {Agm65Entity.class, JavelinMissileEntity.class, Ru9m336MissileEntity.class,
        Ru9m100MissileEntity.class}, remap = false)
public abstract class MixinMissileTripodHoming {

    @Redirect(method = "tick", remap = true,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getY()D"))
    private double tacz_sewv$homeOnBody(Entity target) {
        return TripodCompat.homingY(target, !((Object) this instanceof Agm65Entity));
    }
}

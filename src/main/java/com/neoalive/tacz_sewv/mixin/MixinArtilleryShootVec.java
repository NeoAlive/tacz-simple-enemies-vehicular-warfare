package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.support.ArtillerySupport;

/**
 * Owns {@code shootVec} for an AI-crewed artillery hull. SBW's {@code ArtilleryEntity.baseTick}
 * writes {@code shootVec = controller.getViewVector(1f)} every tick, which for a mob is just where
 * its face points. Server: {@link ArtillerySupport#layVector}. Client: the synced value unchanged,
 * so the client never overwrites the server's lay with the crew's face; the artillery branch of
 * {@link MixinVehicleMissileAim} then slews the turret onto it on both sides.
 *
 * <p>Both names are vanilla ({@code baseTick}, {@code Entity.getViewVector}) and SRG in
 * production, hence {@code remap = true} on both.
 */
@Mixin(ArtilleryEntity.class)
public abstract class MixinArtilleryShootVec {

    @Redirect(method = "baseTick", remap = true,
            at = @At(value = "INVOKE", remap = true,
                    target = "Lnet/minecraft/world/entity/Entity;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 tacz_sewv$layShootVec(Entity controller, float partialTicks) {
        ArtilleryEntity hull = (ArtilleryEntity) (Object) this;
        if (!(controller instanceof AbstractUnit gunner) || !HullFacts.isArtilleryHull(hull)) {
            return controller.getViewVector(partialTicks);
        }
        if (hull.level().isClientSide()) return new Vec3(hull.getShootVec());
        return ArtillerySupport.layVector(hull, gunner);
    }
}

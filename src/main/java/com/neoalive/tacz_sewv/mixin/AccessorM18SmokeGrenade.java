package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.projectile.M18SmokeGrenadeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = M18SmokeGrenadeEntity.class, remap = false)
public interface AccessorM18SmokeGrenade {

    @Accessor("fuse")
    int tacz_sewv$getFuse();
}

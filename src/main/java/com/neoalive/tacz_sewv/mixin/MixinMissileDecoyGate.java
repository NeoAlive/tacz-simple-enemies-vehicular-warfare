package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.init.ModTags;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips SBW's decoy search on ticks with no decoy anywhere near the missile.
 *
 * <p>{@code MissileProjectile.distractedByDecoy} runs at the top of every missile's tick and
 * asks {@code SeekTool.seekLivingEntities(this, 32, 60)}, whose entity source is
 * {@code getEntities().getAll()}: every entity in the level, each put through a range check
 * that recomputes a hull's vehicle data, all to keep only {@code #superbwarfare:decoy}
 * entities (~3.4% of the server thread in a large battle). One section-backed query for a
 * decoy inside the same 32 blocks (plus a block for the eye offset, a strict superset of
 * SBW's range) answers the common case, "none", for a fraction of that. When a decoy is
 * there, SBW's own search runs unchanged: cone, smoke filter, line of sight, the 25% roll and
 * the latch are all still its own.
 *
 * <p>The {@code null} except entity keeps the query off the projectile path that
 * {@code MixinLevelObbHitIndex} and SBW's {@code LevelMixin} both act on.
 */
@Mixin(MissileProjectile.class)
public abstract class MixinMissileDecoyGate {

    private static final double DECOY_SEARCH_REACH = 33.0;

    @Inject(method = "distractedByDecoy", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$skipWithoutDecoy(CallbackInfo ci) {
        MissileProjectile self = (MissileProjectile) (Object) this;
        if (self.isDistracted()) return;
        boolean decoyNear = !self.level()
                .getEntities(
                        (Entity) null,
                        self.getBoundingBox().inflate(DECOY_SEARCH_REACH),
                        e -> e.getType().is(ModTags.EntityTypes.DECOY))
                .isEmpty();
        if (!decoyNear) ci.cancel();
    }
}

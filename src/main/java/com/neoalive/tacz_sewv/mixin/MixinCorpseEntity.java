package com.neoalive.tacz_sewv.mixin;

import de.maxhenkel.corpse.corelib.death.Death;
import de.maxhenkel.corpse.entities.CorpseEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.bridge.ISemCorpse;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.UnitCorpseAppearance;

/**
 * SEM-unit corpses only (see {@link ISemCorpse}): player corpses take every original path untouched.
 *
 * <p>CorpseMod 1.20.1 runs a full collision {@code move()} on every corpse every tick on server AND
 * client, and an {@code isEmpty()} stream scan of ~45 slots every server tick. With a battlefield's
 * worth of unit corpses lying around, that is the bulk of their cost. A resting SEM corpse now moves
 * once a second (so a corpse whose floor is broken still falls), never on the client (the tracker
 * syncs its position), and re-checks emptiness once a second.
 *
 * <p>Only loaded when Corpse is present ({@link CorpseMixinPlugin}), so the CorpseMod imports are safe.
 */
@Mixin(targets = "de.maxhenkel.corpse.entities.CorpseEntity", remap = false)
public abstract class MixinCorpseEntity implements ISemCorpse {

    @Shadow private int age;
    @Shadow protected Death death;

    @Shadow public abstract String getCorpseName();

    @Unique private boolean sewv$parsed;
    @Unique private UnitCorpseAppearance sewv$appearance;
    @Unique private ResourceLocation sewv$skin;
    @Unique private Component sewv$displayName;
    @Unique private boolean sewv$emptyKnown;
    @Unique private boolean sewv$empty;

    @Override
    public UnitCorpseAppearance sewv$appearance() {
        if (!this.sewv$parsed) {
            String name = getCorpseName();
            // Client: the synced name can arrive after construction — don't latch "not ours" on an empty one.
            if (name == null || name.isEmpty()) return null;
            this.sewv$appearance = UnitCorpseAppearance.parseEncodedName(name);
            this.sewv$parsed = true;
        }
        return this.sewv$appearance;
    }

    @Override
    public ResourceLocation sewv$cachedSkin() {
        return this.sewv$skin;
    }

    @Override
    public void sewv$setCachedSkin(ResourceLocation skin) {
        this.sewv$skin = skin;
    }

    @Override
    public void sewv$silentDiscard() {
        if (this.death != null) {
            // remove() drops death.getAllItems(); NonNullList.clear() blanks fixed-size lists in place.
            this.death.getMainInventory().clear();
            this.death.getArmorInventory().clear();
            this.death.getOffHandInventory().clear();
            this.death.getAdditionalItems().clear();
        }
        ((Entity) (Object) this).discard();
    }

    @Redirect(method = "tick", remap = true, at = @At(value = "INVOKE",
            target = "Lde/maxhenkel/corpse/entities/CorpseEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void sewv$restingMove(CorpseEntity self, MoverType type, Vec3 motion) {
        if (sewv$appearance() != null) {
            if (self.level().isClientSide) return;
            if (self.onGround() && motion.horizontalDistanceSqr() < 1.0E-6 && this.age % 20 != 0) return;
        }
        self.move(type, motion);
    }

    @Redirect(method = "tick", remap = true, at = @At(value = "INVOKE", remap = false,
            target = "Lde/maxhenkel/corpse/entities/CorpseEntity;isEmpty()Z"))
    private boolean sewv$throttledIsEmpty(CorpseEntity self) {
        if (sewv$appearance() == null) return self.isEmpty();
        if (!this.sewv$emptyKnown || this.age % 20 == 0) {
            this.sewv$empty = self.isEmpty();
            this.sewv$emptyKnown = true;
        }
        return this.sewv$empty;
    }

    @Inject(method = "tick", remap = true, at = @At("RETURN"))
    private void sewv$lifetimeCap(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide || self.isRemoved() || sewv$appearance() == null) return;
        int minutes = SewvConfig.UNIT_CORPSE_DESPAWN_MINUTES.get();
        if (minutes > 0 && this.age > minutes * 1200) {
            sewv$silentDiscard();
        }
    }

    /** "Corpse of …" without the invisible skin header. Cached: the name tag asks every frame. */
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true, remap = true)
    private void sewv$stripSkinHeader(CallbackInfoReturnable<Component> cir) {
        if (sewv$appearance() == null) return;
        if (this.sewv$displayName == null) {
            this.sewv$displayName = Component.translatable("entity.corpse.corpse_of",
                    UnitCorpseAppearance.displayName(getCorpseName()));
        }
        cir.setReturnValue(this.sewv$displayName);
    }
}

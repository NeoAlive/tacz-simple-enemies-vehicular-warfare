package com.neoalive.tacz_sewv.mixin;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.bridge.IObbHitIndex;
import com.neoalive.tacz_sewv.entity.ai.core.ObbHitIndex;

/**
 * Answers a projectile's {@code getEntities(except, box, predicate)} without SBW's world scan.
 *
 * <p>SBW's {@code LevelMixin} injects at RETURN of this method and, when {@code except} is a
 * projectile, walks every entity in the level to add OBB-only hulls (see {@link ObbHitIndex}).
 * A cancelled HEAD injection returns before the original body's return instructions, so it
 * skips every RETURN injector, SBW's included. In its place this re-enters the same method
 * with a {@code null} except, which runs the vanilla/Forge body (part entities, other mods'
 * injections) untouched, because both SBW's handler and this one bail on their first line
 * when the except entity is not a projectile. The OBB hulls SBW would have added then come
 * from the index. The result is the same list SBW produces, minus the scan.
 *
 * <p>The only behavioural difference is in multipart entities: vanilla excludes a part whose
 * PARENT is the except entity, the wrapped predicate excludes the part itself. A projectile is
 * never a multipart parent, so it cannot arise here.
 */
@Mixin(Level.class)
public abstract class MixinLevelObbHitIndex implements IObbHitIndex {

    @Unique
    private volatile Entity[] tacz_sewv$obbEntities;

    @Override
    public Entity[] tacz_sewv$obbEntities() {
        return this.tacz_sewv$obbEntities;
    }

    @Override
    public void tacz_sewv$setObbEntities(Entity[] entities) {
        this.tacz_sewv$obbEntities = entities;
    }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true)
    private void tacz_sewv$projectileQueryWithoutScan(
            Entity except,
            AABB box,
            Predicate<? super Entity> predicate,
            CallbackInfoReturnable<List<Entity>> cir) {
        if (!(except instanceof Projectile)) return;

        Level self = (Level) (Object) this;
        List<Entity> out = self.getEntities((Entity) null, box, e -> e != except && predicate.test(e));
        ObbHitIndex.appendObbHits(self, out, except, box, predicate);
        cir.setReturnValue(out);
    }
}

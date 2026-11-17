package com.neoalive.tacz_sewv.mixin;

import java.util.List;
import java.util.function.Predicate;

import com.atsuishio.superbwarfare.entity.projectile.ClaymoreEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.entity.ai.support.mines.MineFriends;

/**
 * SBW's Claymore filters its trigger candidates in a static lambda with no reference to the mine,
 * so the friendly test goes on the candidate list itself. A mine this mod did not lay is untouched:
 * {@link MineFriends#spares} answers false for it before any lookup.
 */
@Mixin(ClaymoreEntity.class)
public abstract class MixinClaymoreFriendlyFilter {

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"),
            require = 0)
    private <T extends Entity> List<T> tacz_sewv$spareFriendly(Level level, Class<T> type, AABB box,
                                                              Predicate<? super T> filter) {
        Entity self = (Entity) (Object) this;
        if (!self.getPersistentData().getBoolean(MineFriends.TAG_LAID)) {
            return level.getEntitiesOfClass(type, box, filter);
        }
        return level.getEntitiesOfClass(type, box, e -> filter.test(e) && !MineFriends.spares(self, e));
    }
}

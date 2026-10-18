package com.neoalive.tacz_sewv.bridge;

import net.minecraft.world.entity.Entity;

/**
 * Mixed onto {@code Level} (by {@code MixinLevelObbHitIndex}) to hold that level's
 * {@link com.atsuishio.superbwarfare.entity.OBBEntity OBBEntity} members, which is what
 * {@link com.neoalive.tacz_sewv.entity.ai.core.ObbHitIndex} answers projectile hit queries from.
 *
 * <p>An array snapshot replaced whole on every join/leave (copy-on-write): those are rare, a
 * few dozen hulls per level, while reads happen on every projectile query, and a reader on
 * another thread can then never see a set half way through a mutation.
 */
public interface IObbHitIndex {

    Entity[] tacz_sewv$obbEntities();

    void tacz_sewv$setObbEntities(Entity[] entities);
}

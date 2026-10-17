package com.neoalive.tacz_sewv.bridge;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.util.UnitCorpseAppearance;

/**
 * Mixed onto CorpseMod's {@code CorpseEntity} ({@code MixinCorpseEntity}) so SEWV code can ask
 * "is this an SEM unit corpse?" without naming a CorpseMod type. Every SEM-only corpse optimization
 * gates on {@link #sewv$appearance()} being non-null; a player corpse always answers null.
 */
public interface ISemCorpse {

    /** Parsed from the synced corpse name; null for a player corpse (or a client-side name not yet synced). */
    @Nullable
    UnitCorpseAppearance sewv$appearance();

    /** Client skin resolved once per corpse by {@code UnitCorpseSkin}. */
    @Nullable
    ResourceLocation sewv$cachedSkin();

    void sewv$setCachedSkin(ResourceLocation skin);

    /** Server: empties the corpse's loot so CorpseMod's {@code remove} drops nothing, then discards it. */
    void sewv$silentDiscard();
}

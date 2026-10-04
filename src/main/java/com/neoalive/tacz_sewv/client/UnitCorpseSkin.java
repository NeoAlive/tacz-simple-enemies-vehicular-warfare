package com.neoalive.tacz_sewv.client;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.bridge.ISemCorpse;
import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.util.UnitCorpseAppearance;

/**
 * Client-only bind of the CorpseEntity currently being drawn, so DummyPlayer skin lookups can
 * resolve SEM/SEWV unit textures instead of Mojang skins.
 *
 * <p>Reads the corpse through {@link ISemCorpse} (mixed on by {@code MixinCorpseEntity}), so this
 * class never mentions CorpseMod types. The skin is resolved once and cached on the corpse — this
 * runs every frame for every visible corpse.
 */
public final class UnitCorpseSkin {

    private static final ThreadLocal<ResourceLocation> BOUND = new ThreadLocal<>();

    private UnitCorpseSkin() {}

    public static void bind(@Nullable Object corpseEntity) {
        ResourceLocation skin = skinOf(corpseEntity);
        if (skin == null) {
            BOUND.remove();
        } else {
            BOUND.set(skin);
        }
    }

    /** The SEM unit skin for a corpse, or null for a player corpse. Also Coltan's corpse resolver. */
    @Nullable
    public static ResourceLocation skinOf(@Nullable Object corpseEntity) {
        if (!(corpseEntity instanceof ISemCorpse corpse)) return null;
        UnitCorpseAppearance appearance = corpse.sewv$appearance();
        if (appearance == null) return null;
        ResourceLocation skin = corpse.sewv$cachedSkin();
        if (skin == null) {
            skin = resolve(appearance.factionKey(), appearance.roleFolder(), appearance.variant());
            corpse.sewv$setCachedSkin(skin);
        }
        return skin;
    }

    public static void clear() {
        BOUND.remove();
    }

    @Nullable
    public static ResourceLocation current() {
        return BOUND.get();
    }

    @Nullable
    static ResourceLocation resolve(String factionKey, String roleFolder, int variant) {
        if (factionKey == null || factionKey.isEmpty()) return null;

        ResourceLocation fromRegistry = CrewSkinRegistry.bodySkinForCorpse(factionKey, roleFolder, variant);
        if (fromRegistry != null) return fromRegistry;

        CrewFacts.Faction faction = switch (factionKey) {
            case "ru" -> CrewFacts.Faction.RU;
            case "us" -> CrewFacts.Faction.US;
            case "pmc" -> CrewFacts.Faction.PMC;
            default -> null;
        };
        if (faction == null) return null;

        String folder = factionKey + "_unit";
        String file = switch (faction) {
            case RU -> "ru_unit_default";
            case US -> "us_unit_default";
            case PMC -> "pmc_unit_default";
        };
        if (variant > 0) {
            String indexed = switch (faction) {
                case RU -> "ru_unit_" + variant;
                case US -> "us_unit_" + variant;
                case PMC -> "pmc_unit_variant" + variant;
            };
            return new ResourceLocation("simpleenemymod", "textures/entity/" + folder + "/" + indexed + ".png");
        }
        return new ResourceLocation("simpleenemymod", "textures/entity/" + folder + "/" + file + ".png");
    }
}

package com.neoalive.tacz_sewv.client;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.util.UnitCorpseAppearance;

/**
 * Client-only bind of the CorpseEntity currently being drawn, so DummyPlayer skin lookups can
 * resolve SEM/SEWV unit textures instead of Mojang skins.
 *
 * <p>Uses reflection for {@code getCorpseName()} so this class never mentions CorpseMod types —
 * safe to classload when Corpse is absent.
 */
public final class UnitCorpseSkin {

    private static final ThreadLocal<ResourceLocation> BOUND = new ThreadLocal<>();

    private static volatile Method getCorpseName;

    private UnitCorpseSkin() {}

    public static void bind(@Nullable Object corpseEntity) {
        if (corpseEntity == null) {
            BOUND.remove();
            return;
        }
        Method method = getCorpseNameMethod();
        if (method == null) {
            BOUND.remove();
            return;
        }
        try {
            Object raw = method.invoke(corpseEntity);
            if (!(raw instanceof String name)) {
                BOUND.remove();
                return;
            }
            UnitCorpseAppearance appearance = UnitCorpseAppearance.parseEncodedName(name);
            if (appearance == null) {
                BOUND.remove();
                return;
            }
            ResourceLocation skin = resolve(
                    appearance.factionKey(), appearance.roleFolder(), appearance.variant());
            if (skin == null) {
                BOUND.remove();
            } else {
                BOUND.set(skin);
            }
        } catch (Throwable t) {
            BOUND.remove();
        }
    }

    public static void clear() {
        BOUND.remove();
    }

    @Nullable
    public static ResourceLocation current() {
        return BOUND.get();
    }

    @Nullable
    private static Method getCorpseNameMethod() {
        Method cached = getCorpseName;
        if (cached != null) return cached;
        synchronized (UnitCorpseSkin.class) {
            if (getCorpseName != null) return getCorpseName;
            try {
                Class<?> corpse = Class.forName("de.maxhenkel.corpse.entities.CorpseEntity");
                Method method = corpse.getMethod("getCorpseName");
                method.setAccessible(true);
                getCorpseName = method;
                return method;
            } catch (Throwable t) {
                return null;
            }
        }
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

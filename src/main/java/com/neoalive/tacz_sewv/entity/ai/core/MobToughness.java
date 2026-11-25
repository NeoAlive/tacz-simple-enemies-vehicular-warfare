package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Whether a mob is soft enough to leave to a machine gun. Max health alone is the wrong test:
 * Phayriosis and Spore mobs sit under 100 HP behind 10 to 25 armour and Resistance, where a rifle
 * round does a fifth of its damage. The health a rifle round has to get through is what counts.
 */
public final class MobToughness {

    /** A rifle-calibre coax round. */
    static final float REFERENCE_ROUND = 8.0F;
    /** Effective health under which a mob is an MG target. */
    static final double SOFT_EFFECTIVE_HEALTH = 100.0;

    private static final Map<EntityType<?>, Boolean> HEAVY_TYPES = new ConcurrentHashMap<>();
    private static volatile List<? extends String> heavySource;

    private MobToughness() {}

    public static boolean isSoft(LivingEntity mob) {
        if (isHeavyByNamespace(mob)) return false;
        return effectiveHealth(mob) < SOFT_EFFECTIVE_HEALTH;
    }

    static double effectiveHealth(LivingEntity mob) {
        double toughness = mob.getAttributes().hasAttribute(Attributes.ARMOR_TOUGHNESS)
                ? mob.getAttributeValue(Attributes.ARMOR_TOUGHNESS) : 0.0;
        MobEffectInstance res = mob.getEffect(MobEffects.DAMAGE_RESISTANCE);
        return effectiveHealth(mob.getMaxHealth(), mob.getAbsorptionAmount(), mob.getArmorValue(),
                toughness, res == null ? 0 : res.getAmplifier() + 1);
    }

    /** Health scaled by how much of a reference round survives armour, toughness and Resistance. */
    static double effectiveHealth(double maxHealth, double absorption, double armor, double toughness,
                                  int resistanceLevel) {
        // vanilla's armour formula; 1.21's CombatRules call needs a live entity and source
        float f = 2.0F + (float) toughness / 4.0F;
        float g = Math.min(20.0F, Math.max((float) armor * 0.2F, (float) armor - REFERENCE_ROUND / f));
        float through = REFERENCE_ROUND * (1.0F - g / 25.0F);
        through *= (float) Math.max(0.0, 1.0 - 0.2 * resistanceLevel);
        double health = maxHealth + absorption;
        if (through <= 0.01F) return Double.POSITIVE_INFINITY;
        return health * REFERENCE_ROUND / through;
    }

    /** Mobs of a configured mod always get the cannon-first doctrine. Cached per type. */
    static boolean isHeavyByNamespace(LivingEntity mob) {
        List<? extends String> source;
        try {
            source = SewvConfig.HEAVY_MOB_NAMESPACES.get();
        } catch (Throwable unbaked) {
            return false;
        }
        if (source != heavySource) {
            HEAVY_TYPES.clear();
            heavySource = source;
        }
        return HEAVY_TYPES.computeIfAbsent(mob.getType(), t -> {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(t);
            if (key == null) return false;
            for (String ns : source) {
                if (ns != null && key.getNamespace().equals(ns.trim().toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        });
    }
}

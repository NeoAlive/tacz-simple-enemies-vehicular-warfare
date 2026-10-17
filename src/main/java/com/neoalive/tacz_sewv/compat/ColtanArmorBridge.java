package com.neoalive.tacz_sewv.compat;

import java.lang.reflect.Method;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Registers SEWV faction crew armor paint on Coltan's {@code ColtanArmorSkins} resolver.
 *
 * <p>Reached only after {@link ColtanCompat#bridgeActive()} is true. Uses reflection so this class does
 * not hard-link Coltan types. Coltan's armor mixin bypasses {@code GeoArmorRendererV2}, so
 * {@code MixinGeoArmorRenderer}'s texture swaps never run for bridged pieces without this hook.
 */
public final class ColtanArmorBridge {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String SKINS_CLASS = "com.neoalive.coltan.client.compat.ColtanArmorSkins";

    private ColtanArmorBridge() {}

    @SuppressWarnings("unchecked")
    public static void register() {
        try {
            Class<?> skins = Class.forName(SKINS_CLASS);
            Method setResolver = skins.getMethod("setResolver", BiFunction.class);
            BiFunction<LivingEntity, ItemStack, ResourceLocation> resolver = ColtanArmorBridge::resolve;
            setResolver.invoke(null, resolver);
            LOGGER.info("Registered SEWV crew armor skins on ColtanArmorSkins");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Coltan present but ColtanArmorSkins resolver unavailable", e);
        }
    }

    @Nullable
    private static ResourceLocation resolve(LivingEntity entity, ItemStack stack) {
        if (entity == null || stack == null || stack.isEmpty()) {
            return null;
        }
        CrewFacts.Faction faction = CrewFacts.factionOfCrew(entity);
        if (faction == null) {
            return null;
        }
        return CrewSkinRegistry.textureFor(entity, stack, faction);
    }
}

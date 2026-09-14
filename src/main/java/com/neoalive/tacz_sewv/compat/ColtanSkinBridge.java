package com.neoalive.tacz_sewv.compat;

import java.lang.reflect.Method;
import java.util.function.BiFunction;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.neoalive.tacz_sewv.client.skin.VehicleSkinClient;
import com.neoalive.tacz_sewv.client.skin.VehicleSkinRegistry;

/**
 * Registers SEWV sticky faction paint on Coltan's {@code ColtanVehicleSkins} resolver.
 *
 * <p>Reached only after {@link ColtanCompat#bridgeActive()} is true. Uses reflection so this class does
 * not hard-link Coltan types (same soft-dep discipline as Extermination — Coltan may be absent on
 * the compile classpath of a clean CI checkout that has not copied {@code libs/coltan.jar}).
 *
 * <p>Coltan's {@code skipVanillaRender(true)} bypasses {@code GeoVehicleRenderer}, so
 * {@code MixinVehicleRenderer}'s texture swaps never run for bridged hulls without this hook.
 */
public final class ColtanSkinBridge {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String SKINS_CLASS = "com.neoalive.coltan.client.compat.ColtanVehicleSkins";

    private ColtanSkinBridge() {}

    @SuppressWarnings("unchecked")
    public static void register() {
        try {
            Class<?> skins = Class.forName(SKINS_CLASS);
            Method setResolver = skins.getMethod("setResolver", BiFunction.class);
            BiFunction<VehicleEntity, ResourceLocation, ResourceLocation> resolver =
                    ColtanSkinBridge::resolve;
            setResolver.invoke(null, resolver);
            LOGGER.info("Registered SEWV sticky vehicle skins on ColtanVehicleSkins");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Coltan present but ColtanVehicleSkins resolver unavailable", e);
        }
    }

    private static ResourceLocation resolve(VehicleEntity vehicle, ResourceLocation fallback) {
        ResourceLocation skin = VehicleSkinClient.textureFor(vehicle);
        if (skin == null) {
            return fallback;
        }
        if (vehicle.isWreck()) {
            return VehicleSkinRegistry.darkened(skin, 0.3F);
        }
        return skin;
    }
}

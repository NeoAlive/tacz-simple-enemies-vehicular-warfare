package com.neoalive.tacz_sewv.compat.fcp;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import frontline.combat.fcp.FcpWorldSettings;
import frontline.combat.fcp.balance.VehicleRearm;
import frontline.combat.fcp.entity.vehicle.VehicleInventory;
import frontline.combat.fcp.entity.vehicle.engine.EngineControl;
import frontline.combat.fcp.entity.vehicle.engine.EngineControlled;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraftforge.registries.ForgeRegistries;

/** The FCP-typed half of {@link FcpCompat}. Reached only behind {@link FcpCompat#supported()}. */
final class FcpHulls {

    private FcpHulls() {}

    static boolean hasHold(VehicleEntity hull) {
        return hull instanceof VehicleInventory inv && inv.hasVehicleInventory();
    }

    static SimpleContainer hold(VehicleEntity hull) {
        return hull instanceof VehicleInventory inv && inv.hasVehicleInventory() ? inv.getVehicleInventory() : null;
    }

    static boolean engineBlocked(VehicleEntity hull) {
        return EngineControl.blocksInput(hull);
    }

    static void startEngineNow(VehicleEntity hull) {
        if (hull instanceof EngineControlled c && EngineControl.appliesTo(hull) && !hull.isWreck()) {
            c.fcp$setEngineState(EngineControl.RUNNING);
        }
    }

    static void holdEngineStart(VehicleEntity hull) {
        EngineControl.holdStart(hull);
    }

    static void fillCombatLoad(VehicleEntity hull) {
        VehicleRearm.fillCombatLoad(hull);
    }

    static boolean isArtillery(VehicleEntity hull) {
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(hull.getType());
        return key != null && FcpWorldSettings.ARTILLERY.contains(key.toString());
    }
}

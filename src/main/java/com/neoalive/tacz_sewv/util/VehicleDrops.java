package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.MortarSupport;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.Iterator;
import java.util.List;

/**
 * Regulates SBW hull-container spills and (when tagged) SEM unit death loot via
 * {@link SewvConfig#VEHICLE_DEATH_DROPS}. {@code superbwarfare:creative_ammo_box} never drops.
 */
public final class VehicleDrops {

    public static final String GATED_TAG = "sewv:gated_drops";

    private static final String MODE_DISABLE = "disable";
    private static final String MODE_REDUCED = "reduced";

    private VehicleDrops() {}

    public static void markGated(Entity entity) {
        entity.getPersistentData().putBoolean(GATED_TAG, true);
    }

    public static boolean isGated(Entity entity) {
        return entity.getPersistentData().getBoolean(GATED_TAG);
    }

    /**
     * Marks the hull and everyone crewing it — passengers for seated weapons, plus the
     * claim-holder for a seatless mortar.
     */
    public static void markCrewAndHull(VehicleEntity hull) {
        markGated(hull);
        for (Entity passenger : hull.getPassengers()) {
            markGated(passenger);
        }
        if (hull instanceof MortarEntity mortar) {
            AbstractUnit crew = MortarSupport.crewOf(mortar, null);
            if (crew != null) markGated(crew);
        }
    }

    /**
     * Spill the hull container per config, then empty it so SBW's own {@code remove} loop
     * finds nothing.
     */
    public static void spillAndClear(VehicleEntity vehicle) {
        List<ItemStack> items = vehicle.getItems();
        String mode = SewvConfig.VEHICLE_DEATH_DROPS.get();
        if (!MODE_DISABLE.equals(mode)) {
            for (ItemStack stack : items) {
                ItemStack drop = filterStack(stack, mode);
                if (!drop.isEmpty()) {
                    vehicle.spawnAtLocation(drop, 0.5f);
                }
            }
        }
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
    }

    /**
     * Rewrite a living entity's drop list when the decedent carries {@link #GATED_TAG}.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof AbstractUnit)) return;
        if (!isGated(event.getEntity())) return;

        String mode = SewvConfig.VEHICLE_DEATH_DROPS.get();
        if (MODE_DISABLE.equals(mode)) {
            event.getDrops().clear();
            return;
        }

        Iterator<ItemEntity> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemEntity entity = it.next();
            ItemStack filtered = filterStack(entity.getItem(), mode);
            if (filtered.isEmpty()) {
                it.remove();
            } else {
                entity.setItem(filtered);
            }
        }
    }

    /**
     * Apply mode to one stack. Creative ammo box is always discarded. {@code reduced} keeps
     * {@code count / 4} (integer); a result of 0 drops nothing.
     */
    static ItemStack filterStack(ItemStack stack, String mode) {
        if (stack.isEmpty() || isCreativeAmmoBox(stack)) return ItemStack.EMPTY;
        if (MODE_REDUCED.equals(mode)) {
            int kept = stack.getCount() / 4;
            if (kept <= 0) return ItemStack.EMPTY;
            return stack.copyWithCount(kept);
        }
        // everything (and any unknown value): full stack, box already gated above
        return stack.copy();
    }

    private static boolean isCreativeAmmoBox(ItemStack stack) {
        return stack.is(ModItems.CREATIVE_AMMO_BOX.get());
    }
}

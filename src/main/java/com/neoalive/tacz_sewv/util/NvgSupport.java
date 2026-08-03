package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Data-driven NVG eligibility and equip — pool ids come from config only; nothing here names a
 * specific goggles item.
 */
public final class NvgSupport {

    private static final String HEAD_SLOT = "head";
    private static final int FIRST_ARMOR_SLOT = 2;

    private NvgSupport() {
    }

    public static boolean isPoolItem(Item item) {
        if (item == null) return false;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        if (key == null) return false;
        String id = key.toString();
        for (String entry : SewvConfig.NVG_ELIGIBLE_ITEMS.get()) {
            if (id.equals(entry)) return true;
        }
        return false;
    }

    public static boolean isPoolStack(ItemStack stack) {
        return !stack.isEmpty() && isPoolItem(stack.getItem());
    }

    /** True when the unit wears or holds any pool item (equipment, hands, or Curios). */
    public static boolean unitHasNvg(LivingEntity unit) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (isPoolStack(unit.getItemBySlot(slot))) return true;
        }
        ICuriosItemHandler curios = CuriosApi.getCuriosInventory(unit).orElse(null);
        if (curios != null && curios.isEquipped(NvgSupport::isPoolStack)) {
            return true;
        }
        return false;
    }

    /**
     * True when any passenger in a rendered (non-{@code HidePassenger}) seat has NVG. Hidden /
     * enclosed crew does not mitigate.
     */
    public static boolean vehicleHasRenderedNvg(VehicleEntity vehicle) {
        for (var passenger : vehicle.getPassengers()) {
            if (!(passenger instanceof LivingEntity living)) continue;
            if (vehicle.hidePassenger(passenger)) continue;
            if (unitHasNvg(living)) return true;
        }
        return false;
    }

    /**
     * Night, or a genuinely dark spot (caves / interiors). Uses combined sky+block brightness —
     * {@link LightLayer#BLOCK} alone is 0 outdoors in daylight and would false-trigger forever.
     */
    public static boolean isDark(Level level, BlockPos pos) {
        if (level.isNight()) return true;
        return level.getMaxLocalRawBrightness(pos) <= SewvConfig.DARK_BLOCK_LIGHT_MAX.get();
    }

    /**
     * Prefer Curios (head first among valid slots), else armor equipment slot. Never overwrites
     * an occupied slot. Returns whether something was equipped.
     */
    public static boolean tryEquip(AbstractUnit unit, ItemStack stack) {
        if (stack.isEmpty()) return false;

        if (tryEquipCurios(unit, stack)) return true;

        if (stack.getItem() instanceof ArmorItem armor) {
            EquipmentSlot slot = armor.getEquipmentSlot();
            if (!unit.getItemBySlot(slot).isEmpty()) return false;
            wearArmor(unit, slot, stack);
            return true;
        }
        return false;
    }

    private static boolean tryEquipCurios(AbstractUnit unit, ItemStack stack) {
        Map<String, ISlotType> valid = CuriosApi.getItemStackSlots(stack, unit);
        if (valid.isEmpty()) return false;

        ICuriosItemHandler curios = CuriosApi.getCuriosInventory(unit).orElse(null);
        if (curios == null) return false;

        if (valid.containsKey(HEAD_SLOT) && putInCurioSlot(curios, HEAD_SLOT, stack)) {
            return true;
        }
        for (String slotId : valid.keySet()) {
            if (HEAD_SLOT.equals(slotId)) continue;
            if (putInCurioSlot(curios, slotId, stack)) return true;
        }
        return false;
    }

    private static boolean putInCurioSlot(ICuriosItemHandler curios, String slotId, ItemStack stack) {
        ICurioStacksHandler handler = curios.getStacksHandler(slotId).orElse(null);
        if (handler == null || handler.getSlots() < 1) return false;
        if (!handler.getStacks().getStackInSlot(0).isEmpty()) return false;
        handler.getStacks().setStackInSlot(0, stack.copy());
        return true;
    }

    private static void wearArmor(AbstractUnit unit, EquipmentSlot slot, ItemStack stack) {
        IItemHandler inventory = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        int index = FIRST_ARMOR_SLOT + slot.getIndex();
        if (inventory != null && index < inventory.getSlots()) {
            inventory.insertItem(index, stack.copy(), false);
        } else {
            unit.setItemSlot(slot, stack.copy());
        }
    }

    @Nullable
    public static Item resolve(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return null;
        Item item = ForgeRegistries.ITEMS.getValue(key);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }
}

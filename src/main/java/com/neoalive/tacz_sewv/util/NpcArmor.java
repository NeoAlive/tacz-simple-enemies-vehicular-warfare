package com.neoalive.tacz_sewv.util;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.VehicleTargeting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import java.util.List;

public final class NpcArmor {

    private static final String ISSUED = "sewv:armor_issued";

    private static final int FIRST_ARMOR_SLOT = 2;

    private NpcArmor() {
    }

    public static void issue(AbstractUnit unit) {
        if (!SewvConfig.NPC_ARMOR_ENABLED.get()) return;

        CompoundTag data = unit.getPersistentData();
        if (data.getBoolean(ISSUED)) return;

        List<? extends String> loadout = loadoutFor(unit);
        // A medic/engineer takes the faction HELMET and nothing else. Their custom skin is the
        // uniform, and a chest rig drawn over it would make them read as ordinary riflemen again —
        // which is the whole thing the skins exist to prevent.
        boolean helmetOnly = VehicleTargeting.isSupportUnit(unit);
        boolean anyEquipped = false;
        for (String id : loadout) {
            Item item = resolve(id);
            if (!(item instanceof ArmorItem armor)) continue;

            EquipmentSlot slot = armor.getEquipmentSlot();
            if (helmetOnly && slot != EquipmentSlot.HEAD) continue;
            if (!unit.getItemBySlot(slot).isEmpty()) continue;

            wear(unit, slot, new ItemStack(item));
            anyEquipped = true;
        }

        // An empty loadout is a legitimate "nothing to issue" — flag it done. But a non-empty
        // loadout that equipped nothing (every id a typo, a removed addon, or every slot already
        // full) must NOT be flagged: this fires again on the next chunk load, so a config fix can
        // still retrofit the unit instead of it staying naked forever.
        if (loadout.isEmpty() || anyEquipped) {
            data.putBoolean(ISSUED, true);
        }
    }

    // Support units fall through to their faction's list like anyone else; issue() then keeps only
    // the helmet out of it, so they stay on the same config a player already tunes.
    private static List<? extends String> loadoutFor(AbstractUnit unit) {
        if (unit instanceof RUunitEntity) return SewvConfig.RU_ARMOR.get();
        if (unit instanceof USunitEntity) return SewvConfig.US_ARMOR.get();
        if (unit instanceof PmcUnitEntity) return SewvConfig.PMC_ARMOR.get();
        return List.of();
    }

    private static void wear(AbstractUnit unit, EquipmentSlot slot, ItemStack stack) {
        IItemHandler inventory = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        int index = FIRST_ARMOR_SLOT + slot.getIndex();

        if (inventory != null && index < inventory.getSlots()) {
            inventory.insertItem(index, stack, false);
        } else {
            unit.setItemSlot(slot, stack);
        }
    }

    private static Item resolve(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : ForgeRegistries.ITEMS.getValue(key);
    }
}

package com.neoalive.tacz_sewv.util;

import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * Night spawn chance to equip an NVG-eligible item. One roll per unit life ({@code sewv:nvg_rolled}),
 * same shape as {@link SupportSpawner}'s companion flag.
 */
public final class NpcNvg {

    private static final String ROLLED = "sewv:nvg_rolled";

    private NpcNvg() {
    }

    public static void issue(AbstractUnit unit) {
        CompoundTag data = unit.getPersistentData();
        if (data.getBoolean(ROLLED)) return;
        data.putBoolean(ROLLED, true);

        if (!unit.level().isNight()) return;

        double chance = SewvConfig.NVG_SPAWN_CHANCE.get();
        if (chance <= 0.0) return;
        RandomSource rnd = unit.getRandom();
        if (rnd.nextDouble() >= chance) return;

        Item item = pickItem(rnd);
        if (item == null) return;
        NvgSupport.tryEquip(unit, new ItemStack(item));
    }

    private static Item pickItem(RandomSource rnd) {
        List<Item> valid = new ArrayList<>();
        for (String id : SewvConfig.NVG_ELIGIBLE_ITEMS.get()) {
            Item item = NvgSupport.resolve(id);
            if (item != null) valid.add(item);
        }
        if (valid.isEmpty()) return null;
        return valid.get(rnd.nextInt(valid.size()));
    }
}

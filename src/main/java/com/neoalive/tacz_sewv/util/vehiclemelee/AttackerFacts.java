package com.neoalive.tacz_sewv.util.vehiclemelee;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TridentItem;

/**
 * Attacker-side inputs for {@link DamageEvaluator}: live attack attribute, health fraction, and a
 * coarse main-hand weapon tier (0–3).
 */
public record AttackerFacts(float attackDamage, float healthFrac, int weaponBonus) {

    public static AttackerFacts of(Mob mob) {
        double atk = mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float max = mob.getMaxHealth();
        float frac = max <= 0.0F ? 0.0F : Math.min(1.0F, mob.getHealth() / max);
        return new AttackerFacts((float) atk, frac, weaponBonus(mob.getMainHandItem()));
    }

    /** 0 empty/non-weapon, 1 wood/stone/gold, 2 iron (+ trident), 3 diamond/netherite. */
    private static int weaponBonus(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (item instanceof TridentItem) return 2;
        if (item instanceof TieredItem tiered) {
            Tier tier = tiered.getTier();
            if (tier == Tiers.WOOD || tier == Tiers.STONE || tier == Tiers.GOLD) return 1;
            if (tier == Tiers.IRON) return 2;
            if (tier == Tiers.DIAMOND || tier == Tiers.NETHERITE) return 3;
            // Addon tiers: treat as iron-class if it has attack damage at all.
            return item instanceof SwordItem ? 2 : 1;
        }
        return 0;
    }
}

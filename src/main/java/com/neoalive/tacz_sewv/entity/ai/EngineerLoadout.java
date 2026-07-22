package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.init.ModItems;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.List;

/**
 * An engineer's two-handed kit: a SuperbWarfare repair tool it works with, and a TACZ sidearm it
 * keeps holstered until it has something to shoot.
 *
 * <p><b>The hand swap is the whole mechanism.</b> SimpleEnemyMod's {@code RangedGunAttackGoal} fires
 * whatever TACZ gun sits in the <em>main</em> hand and no-ops on anything else, and its
 * {@code GunLayerRenderer} draws only from there too. So "holstered" is literally the off hand: the
 * pistol is inert and out of the way while the repair tool is up, and {@link #updateHolster} swaps
 * the two the moment a target appears (and back when the fight ends). {@link RepairGoal} stands
 * down on its own whenever the unit holds a target, so the two states never overlap.
 *
 * <p><b>On the gun ids.</b> {@code engineerSidearmPool} holds TACZ <em>gun ids</em>, not item ids and
 * not tags: every TACZ gun is one item ({@code tacz:modern_kinetic_gun}) carrying its id in NBT, so a
 * stack can only be produced through {@link GunItemBuilder}. An id TACZ does not know yields no
 * {@link IGun}, and the engineer is simply left without a sidearm.
 *
 * <p><b>On the ammunition.</b> There is no ammo item. RU/US units have no inventory (see
 * {@code IIssuedAmmo}), so a real magazine would have nowhere to live; TACZ's own answer for mobs is
 * <em>dummy ammo</em> written onto the gun stack, which is exactly what SEM's own weapon equippers
 * do. Setting max + current is what makes the pistol reload forever instead of firing one magazine
 * and going quiet.
 */
public final class EngineerLoadout {

    private EngineerLoadout() {}

    /** Rounds in the magazine. The dummy-ammo reserve below is what actually keeps it fed. */
    private static final int SIDEARM_MAGAZINE = 15;
    private static final int DUMMY_AMMO_RESERVE = 9999;

    /** Repair tool in hand, sidearm holstered. Called from the engineer's {@code equipRandomGun}. */
    public static void equip(AbstractUnit unit) {
        unit.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.REPAIR_TOOL.get()));
        ItemStack sidearm = buildSidearm(unit.getRandom());
        if (!sidearm.isEmpty()) {
            unit.setItemInHand(InteractionHand.OFF_HAND, sidearm);
        }
    }

    private static ItemStack buildSidearm(RandomSource random) {
        List<? extends String> pool = SewvConfig.ENGINEER_SIDEARM_POOL.get();
        if (pool.isEmpty()) return ItemStack.EMPTY;

        ResourceLocation id = ResourceLocation.tryParse(pool.get(random.nextInt(pool.size())));
        if (id == null) return ItemStack.EMPTY;

        ItemStack stack = GunItemBuilder.create()
                .setId(id)
                .setAmmoCount(SIDEARM_MAGAZINE)
                .setFireMode(FireMode.SEMI)
                .setCount(1)
                .build();

        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return ItemStack.EMPTY; // not a gun id TACZ knows — go without rather than crash
        gun.setMaxDummyAmmoAmount(stack, Integer.MAX_VALUE);
        gun.setDummyAmmoAmount(stack, DUMMY_AMMO_RESERVE);
        return stack;
    }

    /** Draw the sidearm when a target appears; put it away once the fight is over. */
    public static void updateHolster(AbstractUnit unit) {
        ItemStack main = unit.getMainHandItem();
        ItemStack off = unit.getOffhandItem();
        boolean fighting = unit.getTarget() != null;
        boolean gunInMain = IGun.getIGunOrNull(main) != null;
        boolean gunInOff = IGun.getIGunOrNull(off) != null;

        // Swap only when the gun is in the wrong hand for the current state. An engineer carrying no
        // sidearm at all fails both tests and is left holding its repair tool.
        if ((fighting && gunInOff) || (!fighting && gunInMain)) {
            unit.setItemInHand(InteractionHand.MAIN_HAND, off);
            unit.setItemInHand(InteractionHand.OFF_HAND, main);
        }
    }
}

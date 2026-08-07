package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.List;

import com.atsuishio.superbwarfare.init.ModItems;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Engineer two-handed kit: repair tool + TACZ sidearm, with an authoritative Monitor override
 * while {@link DroneControl#isLocked}. Holster is the sole continuous {@code setItemInHand} writer.
 */
public final class EngineerLoadout {

    private EngineerLoadout() {}

    public static final class HolsterGoal extends net.minecraft.world.entity.ai.goal.Goal {

        private final AbstractUnit unit;

        public HolsterGoal(AbstractUnit unit) {
            this.unit = unit;
            this.setFlags(java.util.EnumSet.noneOf(Flag.class));
        }

        @Override
        public boolean canUse() {
            return !this.unit.level().isClientSide
                    && SupportRole.of(this.unit) == SupportRole.ENGINEER;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            updateHolster(this.unit);
        }
    }

    private static final int SIDEARM_MAGAZINE = 15;
    private static final int DUMMY_AMMO_RESERVE = 9999;

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
        if (gun == null) return ItemStack.EMPTY;
        gun.setMaxDummyAmmoAmount(stack, Integer.MAX_VALUE);
        gun.setDummyAmmoAmount(stack, DUMMY_AMMO_RESERVE);
        return stack;
    }

    /**
     * Edge-triggered stash on lock false→true; Monitor while locked; restore on unlock edge;
     * otherwise repair ↔ sidearm swap.
     */
    public static void updateHolster(AbstractUnit unit) {
        boolean locked = DroneControl.isLocked(unit);
        CompoundTag data = unit.getPersistentData();
        boolean stashPresent = data.getBoolean(DroneControl.STASH_PRESENT);

        if (locked && !stashPresent) {
            // Rising edge: capture MAIN/OFF exactly once for this lock cycle.
            data.put(DroneControl.STASH_MAIN, unit.getMainHandItem().save(new CompoundTag()));
            data.put(DroneControl.STASH_OFF, unit.getOffhandItem().save(new CompoundTag()));
            data.putBoolean(DroneControl.STASH_PRESENT, true);
            stashPresent = true;
        }

        if (locked) {
            ItemStack monitor = new ItemStack(ModItems.MONITOR.get());
            if (!unit.getMainHandItem().is(ModItems.MONITOR.get())) {
                unit.setItemInHand(InteractionHand.MAIN_HAND, monitor);
            }
            // SupportRole reads either hand for the repair tool — keep it in OFF while Monitor is MAIN.
            if (!unit.getOffhandItem().is(ModItems.REPAIR_TOOL.get()) && stashPresent) {
                ItemStack stashedMain = ItemStack.of(data.getCompound(DroneControl.STASH_MAIN));
                ItemStack stashedOff = ItemStack.of(data.getCompound(DroneControl.STASH_OFF));
                ItemStack tool = stashedMain.is(ModItems.REPAIR_TOOL.get()) ? stashedMain
                        : stashedOff.is(ModItems.REPAIR_TOOL.get()) ? stashedOff : ItemStack.EMPTY;
                if (!tool.isEmpty()) {
                    unit.setItemInHand(InteractionHand.OFF_HAND, tool.copy());
                }
            }
            return;
        }

        if (stashPresent) {
            // Falling edge: restore once, then clear latch.
            ItemStack main = ItemStack.of(data.getCompound(DroneControl.STASH_MAIN));
            ItemStack off = ItemStack.of(data.getCompound(DroneControl.STASH_OFF));
            unit.setItemInHand(InteractionHand.MAIN_HAND, main);
            unit.setItemInHand(InteractionHand.OFF_HAND, off);
            data.remove(DroneControl.STASH_PRESENT);
            data.remove(DroneControl.STASH_MAIN);
            data.remove(DroneControl.STASH_OFF);
            return;
        }

        ItemStack main = unit.getMainHandItem();
        ItemStack off = unit.getOffhandItem();
        boolean fighting = unit.getTarget() != null;
        boolean gunInMain = IGun.getIGunOrNull(main) != null;
        boolean gunInOff = IGun.getIGunOrNull(off) != null;

        if ((fighting && gunInOff) || (!fighting && gunInMain)) {
            unit.setItemInHand(InteractionHand.MAIN_HAND, off);
            unit.setItemInHand(InteractionHand.OFF_HAND, main);
        }
    }
}

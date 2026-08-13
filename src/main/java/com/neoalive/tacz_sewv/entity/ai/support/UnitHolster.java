package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.EnumSet;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;

/**
 * Unit held-item presentation: engineer MAIN↔OFF switching, emplacement in-hand hide, and the
 * stack chosen for TACZ body-holster rendering ({@code HolsterLayer}).
 *
 * <p>HolsterGoal is the sole continuous {@code setItemInHand} writer for engineers. Emplacement
 * hide is render-only (TOW passenger sync / mortar manning flag). Body holster draw takes an
 * <b>explicit</b> stack from {@link #holsteredGun} — it does not call TACZ's offhand
 * {@code HumanoidOffhandRender} pipeline.
 */
public final class UnitHolster {

    /** Synched from {@code MixinAbstractUnit}; false until a mortar crew is at the tube. */
    public static final EntityDataAccessor<Boolean> MANNING_MORTAR =
            SynchedEntityData.defineId(AbstractUnit.class, EntityDataSerializers.BOOLEAN);

    private static final int SIDEARM_MAGAZINE = 15;
    private static final int DUMMY_AMMO_RESERVE = 9999;

    private UnitHolster() {}

    // --- Emplacement in-hand hide -----------------------------------------------------------

    /**
     * When held items should not draw in-hand: driver or armed seat, crewing a TOW, or standing
     * at a mortar tube. Commander / Climb seats keep the rifle visible. Approach / path failure
     * / overrun / dead tube clear the mortar flag so the rifle shows again.
     */
    public static boolean hideHeldItems(LivingEntity entity) {
        if (entity instanceof Mob mob && VehicleWeapons.controlsVehicleWeapon(mob)) return true;
        if (entity.getVehicle() instanceof TowEntity tow && tow.isAlive() && !tow.isWreck()) {
            return true;
        }
        if (!(entity instanceof AbstractUnit)) return false;
        return entity.getEntityData().get(MANNING_MORTAR);
    }

    public static void setManningMortar(AbstractUnit unit, boolean manning) {
        if (unit.level().isClientSide()) return;
        Boolean cur = unit.getEntityData().get(MANNING_MORTAR);
        if (cur == manning) return;
        unit.getEntityData().set(MANNING_MORTAR, manning);
    }

    // --- TACZ body-holster stack ------------------------------------------------------------

    /**
     * TACZ gun to draw on the body, or {@link ItemStack#EMPTY}. Never the stack already drawn
     * in MAIN as a held weapon (avoids double-draw while fighting).
     */
    public static ItemStack holsteredGun(LivingEntity entity) {
        if (entity.isDeadOrDying()) return ItemStack.EMPTY;

        ItemStack main = entity.getMainHandItem();
        ItemStack off = entity.getOffhandItem();

        if (hideHeldItems(entity)) {
            if (IGun.getIGunOrNull(main) != null) return main;
            if (IGun.getIGunOrNull(off) != null) return off;
            return ItemStack.EMPTY;
        }

        // Engineer idle: tool MAIN, sidearm OFF — body-draw the OFF gun.
        if (isWorkTool(main) && IGun.getIGunOrNull(off) != null) {
            return off;
        }
        return ItemStack.EMPTY;
    }

    // --- Engineer kit -----------------------------------------------------------------------

    public static final class HolsterGoal extends Goal {

        private final AbstractUnit unit;

        public HolsterGoal(AbstractUnit unit) {
            this.unit = unit;
            this.setFlags(EnumSet.noneOf(Flag.class));
        }

        @Override
        public boolean canUse() {
            if (this.unit.level().isClientSide) return false;
            SupportRole role = SupportRole.of(this.unit);
            return role == SupportRole.ENGINEER || role == SupportRole.COMBAT_ENGINEER;
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

    public static void equip(AbstractUnit unit) {
        unit.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.REPAIR_TOOL.get()));
        ItemStack sidearm = buildSidearm(unit.getRandom());
        if (!sidearm.isEmpty()) {
            unit.setItemInHand(InteractionHand.OFF_HAND, sidearm);
        }
    }

    /** Combat engineer kit: military shovel MAIN, TACZ sidearm OFF (same swap path as mechanic). */
    public static void equipCombatEngineer(AbstractUnit unit) {
        unit.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.MILITARY_SHOVEL.get()));
        ItemStack sidearm = buildSidearm(unit.getRandom());
        if (!sidearm.isEmpty()) {
            unit.setItemInHand(InteractionHand.OFF_HAND, sidearm);
        }
    }

    private static boolean isWorkTool(ItemStack stack) {
        return stack.is(ModItems.REPAIR_TOOL.get()) || stack.is(ModItems.MILITARY_SHOVEL.get());
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
            if (!unit.getOffhandItem().is(ModItems.REPAIR_TOOL.get())
                    && !unit.getOffhandItem().is(ModItems.MILITARY_SHOVEL.get())
                    && stashPresent) {
                ItemStack stashedMain = ItemStack.of(data.getCompound(DroneControl.STASH_MAIN));
                ItemStack stashedOff = ItemStack.of(data.getCompound(DroneControl.STASH_OFF));
                ItemStack tool = isWorkTool(stashedMain) ? stashedMain
                        : isWorkTool(stashedOff) ? stashedOff : ItemStack.EMPTY;
                if (!tool.isEmpty()) {
                    unit.setItemInHand(InteractionHand.OFF_HAND, tool.copy());
                }
            }
            return;
        }

        if (stashPresent) {
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

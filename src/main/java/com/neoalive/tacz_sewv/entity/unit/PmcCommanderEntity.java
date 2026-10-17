package com.neoalive.tacz_sewv.entity.unit;

import java.lang.reflect.Field;
import java.util.List;

import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * PMC Commander: a debug/egg-spawn-only PMC variant with its own look, a pistol-only loadout, and
 * radio access {@link com.neoalive.tacz_sewv.item.HandheldRadioItem} refuses to a plain PMC. It is
 * auto-preferred as {@code BattleGroup} commander when eligible
 * ({@link com.neoalive.tacz_sewv.entity.ai.command.CommandCoordinator#resolveCommanderEntity}) and
 * is always the leader of any {@link com.neoalive.tacz_sewv.entity.ai.command.platoon.Platoon} it
 * belongs to.
 *
 * <p>Never appears in a random faction spawn pool — {@code TankSpawner}'s crew construction is
 * hardcoded to {@code PmcUnitEntity} and does not know this class exists.
 */
public class PmcCommanderEntity extends PmcUnitEntity {

    private static final int SIDEARM_MAGAZINE = 15;
    private static final int DUMMY_AMMO_RESERVE = 9999;

    /** TDT "Platoon" category toggle — whether {@code CommanderAutoOrderGoal} may act at all. */
    private static final EntityDataAccessor<Boolean> AUTO_ORDERS_ENABLED =
            SynchedEntityData.defineId(PmcCommanderEntity.class, EntityDataSerializers.BOOLEAN);

    public PmcCommanderEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(AUTO_ORDERS_ENABLED, true);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("SewvAutoOrdersEnabled", this.entityData.get(AUTO_ORDERS_ENABLED));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SewvAutoOrdersEnabled")) {
            this.entityData.set(AUTO_ORDERS_ENABLED, tag.getBoolean("SewvAutoOrdersEnabled"));
        }
    }

    public boolean autoOrdersEnabled() {
        return this.entityData.get(AUTO_ORDERS_ENABLED);
    }

    public void setAutoOrdersEnabled(boolean enabled) {
        this.entityData.set(AUTO_ORDERS_ENABLED, enabled);
    }

    /**
     * Full override, no {@code super} call: SEM's own {@code equipRandomGun} draws from the
     * faction-wide loadout table, which is not filterable down to pistols. Writes straight into
     * the PMC's item-handler capability slot 0 — {@code UnitInventoryHandler.onContentsChanged}
     * mirrors that onto the MAINHAND equipment slot and draws the weapon itself, the same channel
     * SEM's own {@code PmcUnitWeaponEquipper} uses.
     */
    @Override
    public void equipRandomGun() {
        ItemStack sidearm = buildSidearm(this.getRandom());
        if (sidearm.isEmpty()) return;
        this.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(h -> {
            if (h instanceof IItemHandlerModifiable modifiable) {
                modifiable.setStackInSlot(0, sidearm);
            }
        });
    }

    private static ItemStack buildSidearm(RandomSource random) {
        List<? extends String> pool = SewvConfig.COMMANDER_SIDEARM_POOL.get();
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
     * Runs SEM's base combat AI plus every bridge goal a plain PMC gets ({@code MixinPmcUnitEntity}
     * tail-injects those into this exact method, and a Java subclass inherits the transform
     * automatically — that already includes {@code PlatoonCohesionGoal}, added to every PMC there),
     * then adds the Commander-only order-dispatch goal.
     */
    @Override
    public void setupRoleGoals() {
        super.setupRoleGoals();
        fixFriendlyCatchAllTarget();
        this.goalSelector.addGoal(3, new com.neoalive.tacz_sewv.entity.ai.command.platoon.CommanderAutoOrderGoal(this));
    }

    /**
     * SEM's priority-2 "Monster" catch-all target goal (installed by {@code super.setupRoleGoals()})
     * excludes same-faction PMC via {@code target.getClass() == this.getClass()} — an exact-class
     * comparison that stops working the moment {@code this} is a subclass, since
     * {@code PmcCommanderEntity.class != PmcUnitEntity.class}. A Commander's target-selector therefore
     * keeps re-acquiring nearby PMC allies through this one goal, gets vetoed on every attempt by
     * {@code MixinAbstractUnit}'s {@code setTarget} guard (the TARGET_FRIENDLY report spam), and never
     * lets go — the goal's own "found a target" bookkeeping survives the veto, so it neither retries a
     * real enemy nor stops trying the same friendly. Swapped for {@link VehicleTargeting#isNonHostile},
     * the same faction/diplomacy-aware exclusion every other proactive scan in this mod already uses.
     */
    private void fixFriendlyCatchAllTarget() {
        try {
            Field typeField = NearestAttackableTargetGoal.class.getDeclaredField("targetType");
            typeField.setAccessible(true);
            this.targetSelector.removeAllGoals(goal -> {
                try {
                    return goal instanceof NearestAttackableTargetGoal<?> g && typeField.get(g) == Monster.class;
                } catch (Throwable ignored) {
                    return false;
                }
            });
            this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, true, target -> {
                if (VehicleTargeting.isNonHostile(this, target)) return false;
                if (target instanceof AbstractUnit) return true;
                return target instanceof Enemy;
            }));
        } catch (Throwable ignored) {
            // Reflection into vanilla's protected field failed (mapping/version drift) — leave SEM's
            // own goal in place rather than risk a crash; see the catch-Throwable convention this
            // codebase already uses for cross-mod field lookups.
        }
    }
}

package com.neoalive.tacz_sewv.util;

import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.unit.RuEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.RuMedicEntity;
import com.neoalive.tacz_sewv.entity.unit.UsEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsMedicEntity;
import com.neoalive.tacz_sewv.init.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import javax.annotation.Nullable;

/** Spawns the medic/engineer support units — on command, and (by chance) alongside regular units. */
public final class SupportSpawner {

    private SupportSpawner() {}

    private static final String COMPANION_FLAG = "sewv:companion_rolled";

    public enum SupportRole { MEDIC, ENGINEER }

    /** Spawn a support unit of the given faction/role at {@code pos}, kitted and ready. */
    @Nullable
    public static AbstractUnit spawn(ServerLevel level, BlockPos pos, boolean ru, SupportRole role) {
        EntityType<?> type = typeFor(ru, role);
        if (!(type.create(level) instanceof AbstractUnit unit)) return null;
        unit.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        unit.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
        // Medics carry an unlimited issued supply of kits (RU/US units have no inventory to hold one),
        // the same channel mortar/TOW crews get their ammo through.
        if (role == SupportRole.MEDIC && unit instanceof IIssuedAmmo issued) {
            issued.sewv$setIssuedAmmo(com.atsuishio.superbwarfare.init.ModItems.MEDICAL_KIT.get());
        }
        level.addFreshEntity(unit);
        return unit;
    }

    /**
     * On a regular RU/US unit's first spawn, maybe bring a same-faction medic and/or engineer along.
     * One hook covers every spawn path (SEM events, garrisons, structures) because they all surface a
     * unit through {@code EntityJoinLevelEvent}. A persistent flag stops chunk reloads from re-rolling;
     * a proximity dedupe keeps a cluster of units from each spawning their own.
     */
    public static void maybeSpawnCompanions(AbstractUnit unit) {
        if (!(unit.level() instanceof ServerLevel level)) return;
        // Exact class: only plain RU/US units seed companions — not medics, engineers, or PMC.
        boolean ru = unit.getClass() == RUunitEntity.class;
        boolean us = unit.getClass() == USunitEntity.class;
        if (!ru && !us) return;

        CompoundTag data = unit.getPersistentData();
        if (data.getBoolean(COMPANION_FLAG)) return;
        data.putBoolean(COMPANION_FLAG, true);

        RandomSource rnd = unit.getRandom();
        rollCompanion(level, unit, ru, rnd, SewvConfig.MEDIC_SPAWN_CHANCE.get(), SupportRole.MEDIC);
        rollCompanion(level, unit, ru, rnd, SewvConfig.ENGINEER_SPAWN_CHANCE.get(), SupportRole.ENGINEER);
    }

    private static void rollCompanion(ServerLevel level, AbstractUnit near, boolean ru, RandomSource rnd,
                                      double chance, SupportRole role) {
        if (chance <= 0.0 || rnd.nextDouble() >= chance) return;
        if (companionNearby(level, near, ru, role)) return;

        BlockPos pos = TankSpawner.adjustHeight(level, near.blockPosition());
        // Defer the add to end of tick: this runs inside EntityJoinLevelEvent (fired from
        // addFreshEntity), and SEM's own join handler mutates lists it is iterating, so adding an
        // entity now risks a ConcurrentModificationException. Same guard the garrison hull uses.
        level.getServer().execute(() -> spawn(level, pos, ru, role));
    }

    private static boolean companionNearby(ServerLevel level, AbstractUnit near, boolean ru, SupportRole role) {
        double r = SewvConfig.SUPPORT_DEDUPE_RADIUS.get();
        AABB box = near.getBoundingBox().inflate(r);
        return !level.getEntitiesOfClass(typeClass(ru, role), box).isEmpty();
    }

    private static EntityType<?> typeFor(boolean ru, SupportRole role) {
        if (role == SupportRole.MEDIC) return ru ? ModEntities.RU_MEDIC.get() : ModEntities.US_MEDIC.get();
        return ru ? ModEntities.RU_ENGINEER.get() : ModEntities.US_ENGINEER.get();
    }

    private static Class<? extends AbstractUnit> typeClass(boolean ru, SupportRole role) {
        if (role == SupportRole.MEDIC) return ru ? RuMedicEntity.class : UsMedicEntity.class;
        return ru ? RuEngineerEntity.class : UsEngineerEntity.class;
    }
}

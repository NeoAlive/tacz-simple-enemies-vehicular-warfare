package com.neoalive.tacz_sewv.init;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.SandbagSeatEntity;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.entity.unit.RuCombatEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.RuEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.RuMedicEntity;
import com.neoalive.tacz_sewv.entity.unit.UsCombatEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsMedicEntity;

/**
 * The bridge's own entities — RU/US support-unit variants (medic, mechanic engineer,
 * combat engineer). They subclass SEM's faction units, so they reuse its renderers,
 * attributes and all its faction logic; only their goals and held items differ.
 */
@EventBusSubscriber(modid = TaczSewv.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, TaczSewv.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<RuMedicEntity>> RU_MEDIC =
            register("ru_medic", RuMedicEntity::new, MobCategory.MISC);
    public static final DeferredHolder<EntityType<?>, EntityType<UsMedicEntity>> US_MEDIC =
            register("us_medic", UsMedicEntity::new, MobCategory.MISC);
    public static final DeferredHolder<EntityType<?>, EntityType<RuEngineerEntity>> RU_ENGINEER =
            register("ru_engineer", RuEngineerEntity::new, MobCategory.MONSTER);
    public static final DeferredHolder<EntityType<?>, EntityType<UsEngineerEntity>> US_ENGINEER =
            register("us_engineer", UsEngineerEntity::new, MobCategory.MONSTER);
    public static final DeferredHolder<EntityType<?>, EntityType<RuCombatEngineerEntity>> RU_COMBAT_ENGINEER =
            register("ru_combat_engineer", RuCombatEngineerEntity::new, MobCategory.MONSTER);
    public static final DeferredHolder<EntityType<?>, EntityType<UsCombatEngineerEntity>> US_COMBAT_ENGINEER =
            register("us_combat_engineer", UsCombatEngineerEntity::new, MobCategory.MONSTER);
    /** Debug/egg-spawn only — never picked by TankSpawner's crew construction. */
    public static final DeferredHolder<EntityType<?>, EntityType<PmcCommanderEntity>> PMC_COMMANDER =
            register("pmc_commander", PmcCommanderEntity::new, MobCategory.MISC);

    public static final DeferredHolder<EntityType<?>, EntityType<SandbagSeatEntity>> SANDBAG_SEAT =
            ENTITY_TYPES.register("sandbag_seat", () -> EntityType.Builder
                    .<SandbagSeatEntity>of(SandbagSeatEntity::new, MobCategory.MISC)
                    .sized(0.1F, 0.1F)
                    .clientTrackingRange(64)
                    .updateInterval(10)
                    .setShouldReceiveVelocityUpdates(false)
                    .build("sandbag_seat"));

    private static <T extends AbstractUnit> DeferredHolder<EntityType<?>, EntityType<T>> register(
            String name, EntityType.EntityFactory<T> factory, MobCategory category) {
        return ENTITY_TYPES.register(name, () -> EntityType.Builder.of(factory, category)
                .sized(0.6F, 1.95F).clientTrackingRange(128).build(name));
    }

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }

    @SubscribeEvent
    public static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(RU_MEDIC.get(), AbstractUnit.createAttributes().build());
        event.put(US_MEDIC.get(), AbstractUnit.createAttributes().build());
        event.put(RU_ENGINEER.get(), AbstractUnit.createAttributes().build());
        event.put(US_ENGINEER.get(), AbstractUnit.createAttributes().build());
        event.put(RU_COMBAT_ENGINEER.get(), AbstractUnit.createAttributes().build());
        event.put(US_COMBAT_ENGINEER.get(), AbstractUnit.createAttributes().build());
        event.put(PMC_COMMANDER.get(), AbstractUnit.createAttributes().build());
    }
}

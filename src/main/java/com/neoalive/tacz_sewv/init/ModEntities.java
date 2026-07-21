package com.neoalive.tacz_sewv.init;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.unit.RuEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.RuMedicEntity;
import com.neoalive.tacz_sewv.entity.unit.UsEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsMedicEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * The bridge's own entities — its first: four RU/US support-unit variants (medic, engineer). They
 * subclass SEM's faction units, so they reuse its renderers, attributes and all its faction logic;
 * only their goals and (for the engineer) their held item differ.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, TaczSewv.MODID);

    public static final RegistryObject<EntityType<RuMedicEntity>> RU_MEDIC =
            register("ru_medic", RuMedicEntity::new, MobCategory.MISC);
    public static final RegistryObject<EntityType<UsMedicEntity>> US_MEDIC =
            register("us_medic", UsMedicEntity::new, MobCategory.MISC);
    public static final RegistryObject<EntityType<RuEngineerEntity>> RU_ENGINEER =
            register("ru_engineer", RuEngineerEntity::new, MobCategory.MONSTER);
    public static final RegistryObject<EntityType<UsEngineerEntity>> US_ENGINEER =
            register("us_engineer", UsEngineerEntity::new, MobCategory.MONSTER);

    private static <T extends AbstractUnit> RegistryObject<EntityType<T>> register(
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
    }
}

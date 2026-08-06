package net.mcreator.extermination.init;

import net.mcreator.extermination.entity.DeadMartianEntity;
import net.mcreator.extermination.entity.EmperorpodEntity;
import net.mcreator.extermination.entity.EmperorpodSpawnEntity;
import net.mcreator.extermination.entity.EventTriggerEntity;
import net.mcreator.extermination.entity.GrenadeProjectileEntity;
import net.mcreator.extermination.entity.HeatRayProjectileEntity;
import net.mcreator.extermination.entity.MartianEntity;
import net.mcreator.extermination.entity.MissleProjectileEntity;
import net.mcreator.extermination.entity.TentacleEntityEntity;
import net.mcreator.extermination.entity.TripodArmProjectileEntity;
import net.mcreator.extermination.entity.TripodBodyEntity;
import net.mcreator.extermination.entity.TripodEntity;
import net.mcreator.extermination.entity.TripodHarvesterBodyEntity;
import net.mcreator.extermination.entity.TripodHarvesterEntity;
import net.mcreator.extermination.entity.TripodHarvesterSpawnEntity;
import net.mcreator.extermination.entity.TripodSpawnEntity;
import net.mcreator.extermination.entity.UberpodBodyEntity;
import net.mcreator.extermination.entity.UberpodEntity;
import net.mcreator.extermination.entity.UberpodSpawnEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.EntityType.Builder;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@EventBusSubscriber(
   bus = Bus.MOD
)
public class ExterminationModEntities {
   public static final DeferredRegister<EntityType<?>> REGISTRY = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, "extermination");
   public static final RegistryObject<EntityType<MartianEntity>> MARTIAN = register(
      "martian",
      Builder.m_20704_(MartianEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(MartianEntity::new)
         .m_20699_(1.2F, 2.5F)
   );
   public static final RegistryObject<EntityType<TripodEntity>> TRIPOD = register(
      "tripod",
      Builder.m_20704_(TripodEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(100)
         .setUpdateInterval(3)
         .setCustomClientFactory(TripodEntity::new)
         .m_20719_()
         .m_20699_(3.5F, 24.0F)
   );
   public static final RegistryObject<EntityType<TripodHarvesterEntity>> TRIPOD_HARVESTER = register(
      "tripod_harvester",
      Builder.m_20704_(TripodHarvesterEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(100)
         .setUpdateInterval(3)
         .setCustomClientFactory(TripodHarvesterEntity::new)
         .m_20719_()
         .m_20699_(3.5F, 24.0F)
   );
   public static final RegistryObject<EntityType<UberpodEntity>> UBERPOD = register(
      "uberpod",
      Builder.m_20704_(UberpodEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(100)
         .setUpdateInterval(3)
         .setCustomClientFactory(UberpodEntity::new)
         .m_20719_()
         .m_20699_(5.0F, 30.0F)
   );
   public static final RegistryObject<EntityType<EmperorpodEntity>> EMPERORPOD = register(
      "emperorpod",
      Builder.m_20704_(EmperorpodEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(100)
         .setUpdateInterval(3)
         .setCustomClientFactory(EmperorpodEntity::new)
         .m_20719_()
         .m_20699_(5.0F, 30.0F)
   );
   public static final RegistryObject<EntityType<TripodBodyEntity>> TRIPOD_BODY = register(
      "tripod_body",
      Builder.m_20704_(TripodBodyEntity::new, MobCategory.CREATURE)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(TripodBodyEntity::new)
         .m_20719_()
         .m_20699_(6.0F, 13.0F)
   );
   public static final RegistryObject<EntityType<TripodArmProjectileEntity>> TRIPOD_ARM_PROJECTILE = register(
      "projectile_tripod_arm_projectile",
      Builder.m_20704_(TripodArmProjectileEntity::new, MobCategory.MISC)
         .setCustomClientFactory(TripodArmProjectileEntity::new)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(1)
         .m_20699_(0.5F, 0.5F)
   );
   public static final RegistryObject<EntityType<DeadMartianEntity>> DEAD_MARTIAN = register(
      "dead_martian",
      Builder.m_20704_(DeadMartianEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(DeadMartianEntity::new)
         .m_20699_(1.4F, 0.8F)
   );
   public static final RegistryObject<EntityType<TripodHarvesterBodyEntity>> TRIPOD_HARVESTER_BODY = register(
      "tripod_harvester_body",
      Builder.m_20704_(TripodHarvesterBodyEntity::new, MobCategory.CREATURE)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(TripodHarvesterBodyEntity::new)
         .m_20719_()
         .m_20699_(6.0F, 13.0F)
   );
   public static final RegistryObject<EntityType<UberpodBodyEntity>> UBERPOD_BODY = register(
      "uberpod_body",
      Builder.m_20704_(UberpodBodyEntity::new, MobCategory.CREATURE)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(UberpodBodyEntity::new)
         .m_20719_()
         .m_20699_(6.0F, 13.0F)
   );
   public static final RegistryObject<EntityType<EventTriggerEntity>> EVENT_TRIGGER = register(
      "event_trigger",
      Builder.m_20704_(EventTriggerEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(EventTriggerEntity::new)
         .m_20719_()
         .m_20699_(1.0F, 1.0F)
   );
   public static final RegistryObject<EntityType<GrenadeProjectileEntity>> GRENADE_PROJECTILE = register(
      "projectile_grenade_projectile",
      Builder.m_20704_(GrenadeProjectileEntity::new, MobCategory.MISC)
         .setCustomClientFactory(GrenadeProjectileEntity::new)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(1)
         .m_20699_(0.5F, 0.5F)
   );
   public static final RegistryObject<EntityType<TripodSpawnEntity>> TRIPOD_SPAWN = register(
      "tripod_spawn",
      Builder.m_20704_(TripodSpawnEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(TripodSpawnEntity::new)
         .m_20719_()
         .m_20699_(3.5F, 24.0F)
   );
   public static final RegistryObject<EntityType<UberpodSpawnEntity>> UBERPOD_SPAWN = register(
      "uberpod_spawn",
      Builder.m_20704_(UberpodSpawnEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(UberpodSpawnEntity::new)
         .m_20719_()
         .m_20699_(4.0F, 30.0F)
   );
   public static final RegistryObject<EntityType<TripodHarvesterSpawnEntity>> TRIPOD_HARVESTER_SPAWN = register(
      "tripod_harvester_spawn",
      Builder.m_20704_(TripodHarvesterSpawnEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(TripodHarvesterSpawnEntity::new)
         .m_20719_()
         .m_20699_(2.5F, 24.0F)
   );
   public static final RegistryObject<EntityType<MissleProjectileEntity>> MISSLE_PROJECTILE = register(
      "projectile_missle_projectile",
      Builder.m_20704_(MissleProjectileEntity::new, MobCategory.MISC)
         .setCustomClientFactory(MissleProjectileEntity::new)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(1)
         .m_20699_(0.5F, 0.5F)
   );
   public static final RegistryObject<EntityType<HeatRayProjectileEntity>> HEAT_RAY_PROJECTILE = register(
      "projectile_heat_ray_projectile",
      Builder.m_20704_(HeatRayProjectileEntity::new, MobCategory.MISC)
         .setCustomClientFactory(HeatRayProjectileEntity::new)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(1)
         .m_20699_(0.5F, 0.5F)
   );
   public static final RegistryObject<EntityType<TentacleEntityEntity>> TENTACLE_ENTITY = register(
      "tentacle_entity",
      Builder.m_20704_(TentacleEntityEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(TentacleEntityEntity::new)
         .m_20719_()
         .m_20699_(2.0F, 2.0F)
   );
   public static final RegistryObject<EntityType<EmperorpodSpawnEntity>> EMPERORPOD_SPAWN = register(
      "emperorpod_spawn",
      Builder.m_20704_(EmperorpodSpawnEntity::new, MobCategory.MONSTER)
         .setShouldReceiveVelocityUpdates(true)
         .setTrackingRange(64)
         .setUpdateInterval(3)
         .setCustomClientFactory(EmperorpodSpawnEntity::new)
         .m_20719_()
         .m_20699_(4.0F, 30.0F)
   );

   private static <T extends Entity> RegistryObject<EntityType<T>> register(String registryname, Builder<T> entityTypeBuilder) {
      return REGISTRY.register(registryname, () -> entityTypeBuilder.m_20712_(registryname));
   }

   @SubscribeEvent
   public static void init(FMLCommonSetupEvent event) {
      event.enqueueWork(() -> {
         MartianEntity.init();
         TripodEntity.init();
         TripodHarvesterEntity.init();
         UberpodEntity.init();
         EmperorpodEntity.init();
         TripodBodyEntity.init();
         DeadMartianEntity.init();
         TripodHarvesterBodyEntity.init();
         UberpodBodyEntity.init();
         EventTriggerEntity.init();
         TripodSpawnEntity.init();
         UberpodSpawnEntity.init();
         TripodHarvesterSpawnEntity.init();
         TentacleEntityEntity.init();
         EmperorpodSpawnEntity.init();
      });
   }

   @SubscribeEvent
   public static void registerAttributes(EntityAttributeCreationEvent event) {
      event.put((EntityType)MARTIAN.get(), MartianEntity.createAttributes().m_22265_());
      event.put((EntityType)TRIPOD.get(), TripodEntity.createAttributes().m_22265_());
      event.put((EntityType)TRIPOD_HARVESTER.get(), TripodHarvesterEntity.createAttributes().m_22265_());
      event.put((EntityType)UBERPOD.get(), UberpodEntity.createAttributes().m_22265_());
      event.put((EntityType)EMPERORPOD.get(), EmperorpodEntity.createAttributes().m_22265_());
      event.put((EntityType)TRIPOD_BODY.get(), TripodBodyEntity.createAttributes().m_22265_());
      event.put((EntityType)DEAD_MARTIAN.get(), DeadMartianEntity.createAttributes().m_22265_());
      event.put((EntityType)TRIPOD_HARVESTER_BODY.get(), TripodHarvesterBodyEntity.createAttributes().m_22265_());
      event.put((EntityType)UBERPOD_BODY.get(), UberpodBodyEntity.createAttributes().m_22265_());
      event.put((EntityType)EVENT_TRIGGER.get(), EventTriggerEntity.createAttributes().m_22265_());
      event.put((EntityType)TRIPOD_SPAWN.get(), TripodSpawnEntity.createAttributes().m_22265_());
      event.put((EntityType)UBERPOD_SPAWN.get(), UberpodSpawnEntity.createAttributes().m_22265_());
      event.put((EntityType)TRIPOD_HARVESTER_SPAWN.get(), TripodHarvesterSpawnEntity.createAttributes().m_22265_());
      event.put((EntityType)TENTACLE_ENTITY.get(), TentacleEntityEntity.createAttributes().m_22265_());
      event.put((EntityType)EMPERORPOD_SPAWN.get(), EmperorpodSpawnEntity.createAttributes().m_22265_());
   }
}

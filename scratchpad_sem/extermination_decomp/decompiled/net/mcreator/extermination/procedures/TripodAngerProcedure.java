package net.mcreator.extermination.procedures;

import java.util.Comparator;
import javax.annotation.Nullable;
import net.mcreator.extermination.ExterminationMod;
import net.mcreator.extermination.entity.EmperorpodEntity;
import net.mcreator.extermination.entity.MartianEntity;
import net.mcreator.extermination.entity.TripodEntity;
import net.mcreator.extermination.entity.TripodHarvesterEntity;
import net.mcreator.extermination.entity.UberpodEntity;
import net.mcreator.extermination.init.ExterminationModEntities;
import net.mcreator.extermination.procedures.TripodAngerProcedure.1;
import net.mcreator.extermination.procedures.TripodAngerProcedure.10;
import net.mcreator.extermination.procedures.TripodAngerProcedure.11;
import net.mcreator.extermination.procedures.TripodAngerProcedure.12;
import net.mcreator.extermination.procedures.TripodAngerProcedure.13;
import net.mcreator.extermination.procedures.TripodAngerProcedure.14;
import net.mcreator.extermination.procedures.TripodAngerProcedure.15;
import net.mcreator.extermination.procedures.TripodAngerProcedure.16;
import net.mcreator.extermination.procedures.TripodAngerProcedure.17;
import net.mcreator.extermination.procedures.TripodAngerProcedure.18;
import net.mcreator.extermination.procedures.TripodAngerProcedure.19;
import net.mcreator.extermination.procedures.TripodAngerProcedure.2;
import net.mcreator.extermination.procedures.TripodAngerProcedure.20;
import net.mcreator.extermination.procedures.TripodAngerProcedure.21;
import net.mcreator.extermination.procedures.TripodAngerProcedure.22;
import net.mcreator.extermination.procedures.TripodAngerProcedure.23;
import net.mcreator.extermination.procedures.TripodAngerProcedure.24;
import net.mcreator.extermination.procedures.TripodAngerProcedure.3;
import net.mcreator.extermination.procedures.TripodAngerProcedure.4;
import net.mcreator.extermination.procedures.TripodAngerProcedure.5;
import net.mcreator.extermination.procedures.TripodAngerProcedure.6;
import net.mcreator.extermination.procedures.TripodAngerProcedure.7;
import net.mcreator.extermination.procedures.TripodAngerProcedure.8;
import net.mcreator.extermination.procedures.TripodAngerProcedure.9;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.registries.ForgeRegistries;

@EventBusSubscriber
public class TripodAngerProcedure {
   @SubscribeEvent
   public static void onEntitySetsAttackTarget(LivingChangeTargetEvent event) {
      execute(
         event,
         event.getEntity().m_9236_(),
         event.getEntity().m_20185_(),
         event.getEntity().m_20186_(),
         event.getEntity().m_20189_(),
         event.getOriginalTarget(),
         event.getEntity()
      );
   }

   public static void execute(LevelAccessor world, double x, double y, double z, Entity entity, Entity sourceentity) {
      execute(null, world, x, y, z, entity, sourceentity);
   }

   private static void execute(@Nullable Event event, LevelAccessor world, double x, double y, double z, Entity entity, Entity sourceentity) {
      if (entity != null && sourceentity != null) {
         if (sourceentity.m_6084_()) {
            if (sourceentity instanceof TripodEntity) {
               if (Math.random() < 0.008) {
                  if ((sourceentity instanceof LivingEntity _livEnt ? _livEnt.m_21223_() : -1.0F) > 45.0F && world instanceof Level _level) {
                     if (!_level.m_5776_()) {
                        _level.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F
                        );
                     } else {
                        _level.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F,
                           false
                        );
                     }
                  }

                  if ((sourceentity instanceof LivingEntity _livEntx ? _livEntx.m_21223_() : -1.0F) <= 45.0F && world instanceof Level _levelx) {
                     if (!_levelx.m_5776_()) {
                        _levelx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn_broken")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F
                        );
                     } else {
                        _levelx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn_broken")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F,
                           false
                        );
                     }
                  }
               }

               if (entity instanceof Player
                  && world.m_6443_(MartianEntity.class, AABB.m_165882_(new Vec3(x, y, z), 80.0, 80.0, 80.0), e -> true).isEmpty()
                  && !world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()
                  && Math.random() < 0.002) {
                  if (world instanceof ServerLevel _levelxx) {
                     Entity entityToSpawn = ((EntityType)ExterminationModEntities.MARTIAN.get())
                        .m_262496_(_levelxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED);
                     if (entityToSpawn != null) {
                        entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                     }
                  }

                  if (world instanceof Level _levelxxx) {
                     if (!_levelxxx.m_5776_()) {
                        _levelxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("block.barrel.open")),
                           SoundSource.HOSTILE,
                           0.0F,
                           1.0F
                        );
                     } else {
                        _levelxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("block.barrel.open")),
                           SoundSource.HOSTILE,
                           0.0F,
                           1.0F,
                           false
                        );
                     }
                  }
               }

               label1152:
               if (entity instanceof Animal && world.m_6443_(Animal.class, AABB.m_165882_(new Vec3(x, y, z), 40.0, 40.0, 40.0), e -> true).isEmpty()) {
                  if (sourceentity instanceof LivingEntity _livEnt13 && _livEnt13.m_21023_(MobEffects.f_19621_)) {
                     break label1152;
                  }

                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 100, 0, false, false));
                  }

                  if (world instanceof Level _levelxxxx) {
                     if (!_levelxxxx.m_5776_()) {
                        _levelxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F
                        );
                     } else {
                        _levelxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F,
                           false
                        );
                     }
                  }

                  sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                  ExterminationMod.queueServerWork(
                     15,
                     () -> {
                        sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                        ExterminationMod.queueServerWork(
                           10,
                           () -> {
                              if (sourceentity.m_6084_()) {
                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 1().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }

                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 2().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }
                              }
                           }
                        );
                     }
                  );
               }

               label1145:
               if (entity instanceof Zombie && world.m_6443_(Zombie.class, AABB.m_165882_(new Vec3(x, y, z), 40.0, 40.0, 40.0), e -> true).isEmpty()) {
                  if (sourceentity instanceof LivingEntity _livEnt45 && _livEnt45.m_21023_(MobEffects.f_19621_)) {
                     break label1145;
                  }

                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 100, 0, false, false));
                  }

                  if (world instanceof Level _levelxxxxx) {
                     if (!_levelxxxxx.m_5776_()) {
                        _levelxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F
                        );
                     } else {
                        _levelxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F,
                           false
                        );
                     }
                  }

                  sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                  ExterminationMod.queueServerWork(
                     15,
                     () -> {
                        sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                        ExterminationMod.queueServerWork(
                           10,
                           () -> {
                              if (sourceentity.m_6084_()) {
                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 3().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }

                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 4().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }
                              }
                           }
                        );
                     }
                  );
               }

               label1138:
               if (entity instanceof Villager && world.m_6443_(Villager.class, AABB.m_165882_(new Vec3(x, y, z), 40.0, 40.0, 40.0), e -> true).isEmpty()) {
                  if (sourceentity instanceof LivingEntity _livEnt77 && _livEnt77.m_21023_(MobEffects.f_19621_)) {
                     break label1138;
                  }

                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 100, 0, false, false));
                  }

                  if (world instanceof Level _levelxxxxxx) {
                     if (!_levelxxxxxx.m_5776_()) {
                        _levelxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F,
                           false
                        );
                     }
                  }

                  sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                  ExterminationMod.queueServerWork(
                     15,
                     () -> {
                        sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                        ExterminationMod.queueServerWork(
                           10,
                           () -> {
                              if (sourceentity.m_6084_()) {
                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 5().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }

                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 6().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }
                              }
                           }
                        );
                     }
                  );
               }

               label1131:
               if (entity instanceof Player) {
                  if (sourceentity instanceof LivingEntity _livEnt108 && _livEnt108.m_21023_(MobEffects.f_19621_)) {
                     break label1131;
                  }

                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 100, 0, false, false));
                  }

                  if (world instanceof Level _levelxxxxxxx) {
                     if (!_levelxxxxxxx.m_5776_()) {
                        _levelxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F,
                           false
                        );
                     }
                  }

                  sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                  ExterminationMod.queueServerWork(
                     15,
                     () -> {
                        sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                        ExterminationMod.queueServerWork(
                           10,
                           () -> {
                              if (sourceentity.m_6084_()) {
                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 7().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }

                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 8().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }
                              }
                           }
                        );
                     }
                  );
               }

               if ((entity instanceof Player || entity instanceof Villager)
                  && world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 20.0, 20.0, 20.0), e -> true).isEmpty()
                  && Math.random() < 0.005) {
                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 60, 0, false, false));
                  }

                  if (world instanceof Level _levelxxxxxxxx) {
                     if (!_levelxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                           SoundSource.HOSTILE,
                           5.0F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                           SoundSource.HOSTILE,
                           5.0F,
                           1.0F,
                           false
                        );
                     }
                  }

                  if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                     sourceentity.m_20194_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                              CommandSource.f_80164_,
                              sourceentity.m_20182_(),
                              sourceentity.m_20155_(),
                              sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                              4,
                              sourceentity.m_7755_().getString(),
                              sourceentity.m_5446_(),
                              sourceentity.m_9236_().m_7654_(),
                              sourceentity
                           ),
                           "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                        );
                  }

                  if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                     sourceentity.m_20194_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                              CommandSource.f_80164_,
                              sourceentity.m_20182_(),
                              sourceentity.m_20155_(),
                              sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                              4,
                              sourceentity.m_7755_().getString(),
                              sourceentity.m_5446_(),
                              sourceentity.m_9236_().m_7654_(),
                              sourceentity
                           ),
                           "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                        );
                  }

                  ExterminationMod.queueServerWork(
                     10,
                     () -> {
                        if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                           sourceentity.m_20194_()
                              .m_129892_()
                              .m_230957_(
                                 new CommandSourceStack(
                                    CommandSource.f_80164_,
                                    sourceentity.m_20182_(),
                                    sourceentity.m_20155_(),
                                    sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                    4,
                                    sourceentity.m_7755_().getString(),
                                    sourceentity.m_5446_(),
                                    sourceentity.m_9236_().m_7654_(),
                                    sourceentity
                                 ),
                                 "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                              );
                        }

                        if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                           sourceentity.m_20194_()
                              .m_129892_()
                              .m_230957_(
                                 new CommandSourceStack(
                                    CommandSource.f_80164_,
                                    sourceentity.m_20182_(),
                                    sourceentity.m_20155_(),
                                    sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                    4,
                                    sourceentity.m_7755_().getString(),
                                    sourceentity.m_5446_(),
                                    sourceentity.m_9236_().m_7654_(),
                                    sourceentity
                                 ),
                                 "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                              );
                        }

                        ExterminationMod.queueServerWork(
                           10,
                           () -> {
                              if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                 sourceentity.m_20194_()
                                    .m_129892_()
                                    .m_230957_(
                                       new CommandSourceStack(
                                          CommandSource.f_80164_,
                                          sourceentity.m_20182_(),
                                          sourceentity.m_20155_(),
                                          sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                          4,
                                          sourceentity.m_7755_().getString(),
                                          sourceentity.m_5446_(),
                                          sourceentity.m_9236_().m_7654_(),
                                          sourceentity
                                       ),
                                       "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                    );
                              }

                              if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                 sourceentity.m_20194_()
                                    .m_129892_()
                                    .m_230957_(
                                       new CommandSourceStack(
                                          CommandSource.f_80164_,
                                          sourceentity.m_20182_(),
                                          sourceentity.m_20155_(),
                                          sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                          4,
                                          sourceentity.m_7755_().getString(),
                                          sourceentity.m_5446_(),
                                          sourceentity.m_9236_().m_7654_(),
                                          sourceentity
                                       ),
                                       "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                    );
                              }

                              ExterminationMod.queueServerWork(
                                 10,
                                 () -> {
                                    if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                       sourceentity.m_20194_()
                                          .m_129892_()
                                          .m_230957_(
                                             new CommandSourceStack(
                                                CommandSource.f_80164_,
                                                sourceentity.m_20182_(),
                                                sourceentity.m_20155_(),
                                                sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                4,
                                                sourceentity.m_7755_().getString(),
                                                sourceentity.m_5446_(),
                                                sourceentity.m_9236_().m_7654_(),
                                                sourceentity
                                             ),
                                             "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                          );
                                    }

                                    if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                       sourceentity.m_20194_()
                                          .m_129892_()
                                          .m_230957_(
                                             new CommandSourceStack(
                                                CommandSource.f_80164_,
                                                sourceentity.m_20182_(),
                                                sourceentity.m_20155_(),
                                                sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                4,
                                                sourceentity.m_7755_().getString(),
                                                sourceentity.m_5446_(),
                                                sourceentity.m_9236_().m_7654_(),
                                                sourceentity
                                             ),
                                             "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                          );
                                    }

                                    ExterminationMod.queueServerWork(
                                       10,
                                       () -> {
                                          if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                             sourceentity.m_20194_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                      CommandSource.f_80164_,
                                                      sourceentity.m_20182_(),
                                                      sourceentity.m_20155_(),
                                                      sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                      4,
                                                      sourceentity.m_7755_().getString(),
                                                      sourceentity.m_5446_(),
                                                      sourceentity.m_9236_().m_7654_(),
                                                      sourceentity
                                                   ),
                                                   "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                );
                                          }

                                          if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                             sourceentity.m_20194_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                      CommandSource.f_80164_,
                                                      sourceentity.m_20182_(),
                                                      sourceentity.m_20155_(),
                                                      sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                      4,
                                                      sourceentity.m_7755_().getString(),
                                                      sourceentity.m_5446_(),
                                                      sourceentity.m_9236_().m_7654_(),
                                                      sourceentity
                                                   ),
                                                   "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                );
                                          }

                                          if (world instanceof ServerLevel _levelxxxxxxxxx) {
                                             _levelxxxxxxxxx.m_7654_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         new Vec3(x, y, z),
                                                         Vec2.f_82462_,
                                                         _levelxxxxxxxxx,
                                                         4,
                                                         "",
                                                         Component.m_237113_(""),
                                                         _levelxxxxxxxxx.m_7654_(),
                                                         null
                                                      )
                                                      .m_81324_(),
                                                   "effect give @e[distance=..30] minecraft:wither 8 1"
                                                );
                                          }

                                          if (world instanceof ServerLevel _levelxxxxxxxxx) {
                                             _levelxxxxxxxxx.m_7654_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         new Vec3(x, y, z),
                                                         Vec2.f_82462_,
                                                         _levelxxxxxxxxx,
                                                         4,
                                                         "",
                                                         Component.m_237113_(""),
                                                         _levelxxxxxxxxx.m_7654_(),
                                                         null
                                                      )
                                                      .m_81324_(),
                                                   "/effect give @e[distance=..30] minecraft:nausea 8 2"
                                                );
                                          }
                                       }
                                    );
                                 }
                              );
                           }
                        );
                     }
                  );
               }
            }

            if (sourceentity instanceof UberpodEntity) {
               if (Math.random() < 0.008) {
                  if ((sourceentity instanceof LivingEntity _livEntxx ? _livEntxx.m_21223_() : -1.0F) > 60.0F && world instanceof Level _levelxxxxxxxxx) {
                     if (!_levelxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.uberpod.horn")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.uberpod.horn")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F,
                           false
                        );
                     }
                  }

                  if ((sourceentity instanceof LivingEntity _livEntxxx ? _livEntxxx.m_21223_() : -1.0F) <= 60.0F && world instanceof Level _levelxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn_broken")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn_broken")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F,
                           false
                        );
                     }
                  }
               }

               if (entity instanceof Player
                  && world.m_6443_(MartianEntity.class, AABB.m_165882_(new Vec3(x, y, z), 80.0, 80.0, 80.0), e -> true).isEmpty()
                  && !world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()
                  && Math.random() < 0.002) {
                  if (world instanceof ServerLevel _levelxxxxxxxxxxx) {
                     Entity entityToSpawn = ((EntityType)ExterminationModEntities.MARTIAN.get())
                        .m_262496_(_levelxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED);
                     if (entityToSpawn != null) {
                        entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                     }
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("block.barrel.open")),
                           SoundSource.HOSTILE,
                           0.0F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("block.barrel.open")),
                           SoundSource.HOSTILE,
                           0.0F,
                           1.0F,
                           false
                        );
                     }
                  }
               }

               if (entity instanceof Zombie && world.m_6443_(Zombie.class, AABB.m_165882_(new Vec3(x, y, z), 40.0, 40.0, 40.0), e -> true).isEmpty()) {
                  label1175: {
                     if (sourceentity instanceof LivingEntity _livEnt171 && _livEnt171.m_21023_(MobEffects.f_19621_)) {
                        break label1175;
                     }

                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 80, 0, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                              SoundSource.HOSTILE,
                              4.5F,
                              1.0F
                           );
                        } else {
                           _levelxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                              SoundSource.HOSTILE,
                              4.5F,
                              1.0F,
                              false
                           );
                        }
                     }

                     sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                     ExterminationMod.queueServerWork(
                        15,
                        () -> {
                           sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                           ExterminationMod.queueServerWork(
                              10,
                              () -> {
                                 if (sourceentity.m_6084_()) {
                                    if (world instanceof ServerLevel projectileLevel) {
                                       Projectile _entityToSpawn = new 9().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                       _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                       _entityToSpawn.m_6686_(
                                          sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                       );
                                       projectileLevel.m_7967_(_entityToSpawn);
                                    }

                                    if (world instanceof ServerLevel projectileLevel) {
                                       Projectile _entityToSpawn = new 10().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                       _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                       _entityToSpawn.m_6686_(
                                          sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                       );
                                       projectileLevel.m_7967_(_entityToSpawn);
                                    }
                                 }
                              }
                           );
                        }
                     );
                  }

                  if ((entity instanceof Player || entity instanceof Villager)
                     && world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 20.0, 20.0, 20.0), e -> true).isEmpty()
                     && Math.random() < 0.005) {
                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 60, 0, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              5.0F,
                              1.0F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              5.0F,
                              1.0F,
                              false
                           );
                        }
                     }

                     if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                        sourceentity.m_20194_()
                           .m_129892_()
                           .m_230957_(
                              new CommandSourceStack(
                                 CommandSource.f_80164_,
                                 sourceentity.m_20182_(),
                                 sourceentity.m_20155_(),
                                 sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                 4,
                                 sourceentity.m_7755_().getString(),
                                 sourceentity.m_5446_(),
                                 sourceentity.m_9236_().m_7654_(),
                                 sourceentity
                              ),
                              "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                           );
                     }

                     if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                        sourceentity.m_20194_()
                           .m_129892_()
                           .m_230957_(
                              new CommandSourceStack(
                                 CommandSource.f_80164_,
                                 sourceentity.m_20182_(),
                                 sourceentity.m_20155_(),
                                 sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                 4,
                                 sourceentity.m_7755_().getString(),
                                 sourceentity.m_5446_(),
                                 sourceentity.m_9236_().m_7654_(),
                                 sourceentity
                              ),
                              "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                           );
                     }

                     ExterminationMod.queueServerWork(
                        10,
                        () -> {
                           if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                              sourceentity.m_20194_()
                                 .m_129892_()
                                 .m_230957_(
                                    new CommandSourceStack(
                                       CommandSource.f_80164_,
                                       sourceentity.m_20182_(),
                                       sourceentity.m_20155_(),
                                       sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                       4,
                                       sourceentity.m_7755_().getString(),
                                       sourceentity.m_5446_(),
                                       sourceentity.m_9236_().m_7654_(),
                                       sourceentity
                                    ),
                                    "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                 );
                           }

                           if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                              sourceentity.m_20194_()
                                 .m_129892_()
                                 .m_230957_(
                                    new CommandSourceStack(
                                       CommandSource.f_80164_,
                                       sourceentity.m_20182_(),
                                       sourceentity.m_20155_(),
                                       sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                       4,
                                       sourceentity.m_7755_().getString(),
                                       sourceentity.m_5446_(),
                                       sourceentity.m_9236_().m_7654_(),
                                       sourceentity
                                    ),
                                    "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                 );
                           }

                           ExterminationMod.queueServerWork(
                              10,
                              () -> {
                                 if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                    sourceentity.m_20194_()
                                       .m_129892_()
                                       .m_230957_(
                                          new CommandSourceStack(
                                             CommandSource.f_80164_,
                                             sourceentity.m_20182_(),
                                             sourceentity.m_20155_(),
                                             sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                             4,
                                             sourceentity.m_7755_().getString(),
                                             sourceentity.m_5446_(),
                                             sourceentity.m_9236_().m_7654_(),
                                             sourceentity
                                          ),
                                          "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                       );
                                 }

                                 if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                    sourceentity.m_20194_()
                                       .m_129892_()
                                       .m_230957_(
                                          new CommandSourceStack(
                                             CommandSource.f_80164_,
                                             sourceentity.m_20182_(),
                                             sourceentity.m_20155_(),
                                             sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                             4,
                                             sourceentity.m_7755_().getString(),
                                             sourceentity.m_5446_(),
                                             sourceentity.m_9236_().m_7654_(),
                                             sourceentity
                                          ),
                                          "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                       );
                                 }

                                 ExterminationMod.queueServerWork(
                                    10,
                                    () -> {
                                       if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                          sourceentity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   sourceentity.m_20182_(),
                                                   sourceentity.m_20155_(),
                                                   sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                   4,
                                                   sourceentity.m_7755_().getString(),
                                                   sourceentity.m_5446_(),
                                                   sourceentity.m_9236_().m_7654_(),
                                                   sourceentity
                                                ),
                                                "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                             );
                                       }

                                       if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                          sourceentity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   sourceentity.m_20182_(),
                                                   sourceentity.m_20155_(),
                                                   sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                   4,
                                                   sourceentity.m_7755_().getString(),
                                                   sourceentity.m_5446_(),
                                                   sourceentity.m_9236_().m_7654_(),
                                                   sourceentity
                                                ),
                                                "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                             );
                                       }

                                       ExterminationMod.queueServerWork(
                                          10,
                                          () -> {
                                             if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                                sourceentity.m_20194_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         sourceentity.m_20182_(),
                                                         sourceentity.m_20155_(),
                                                         sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                         4,
                                                         sourceentity.m_7755_().getString(),
                                                         sourceentity.m_5446_(),
                                                         sourceentity.m_9236_().m_7654_(),
                                                         sourceentity
                                                      ),
                                                      "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                   );
                                             }

                                             if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                                sourceentity.m_20194_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         sourceentity.m_20182_(),
                                                         sourceentity.m_20155_(),
                                                         sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                         4,
                                                         sourceentity.m_7755_().getString(),
                                                         sourceentity.m_5446_(),
                                                         sourceentity.m_9236_().m_7654_(),
                                                         sourceentity
                                                      ),
                                                      "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                   );
                                             }

                                             if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxx) {
                                                _levelxxxxxxxxxxxxxxx.m_7654_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                            CommandSource.f_80164_,
                                                            new Vec3(x, y, z),
                                                            Vec2.f_82462_,
                                                            _levelxxxxxxxxxxxxxxx,
                                                            4,
                                                            "",
                                                            Component.m_237113_(""),
                                                            _levelxxxxxxxxxxxxxxx.m_7654_(),
                                                            null
                                                         )
                                                         .m_81324_(),
                                                      "effect give @e[distance=..30] minecraft:wither 8 1"
                                                   );
                                             }

                                             if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxx) {
                                                _levelxxxxxxxxxxxxxxx.m_7654_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                            CommandSource.f_80164_,
                                                            new Vec3(x, y, z),
                                                            Vec2.f_82462_,
                                                            _levelxxxxxxxxxxxxxxx,
                                                            4,
                                                            "",
                                                            Component.m_237113_(""),
                                                            _levelxxxxxxxxxxxxxxx.m_7654_(),
                                                            null
                                                         )
                                                         .m_81324_(),
                                                      "/effect give @e[distance=..30] minecraft:nausea 8 2"
                                                   );
                                             }
                                          }
                                       );
                                    }
                                 );
                              }
                           );
                        }
                     );
                  }
               }

               if (entity instanceof Animal && world.m_6443_(Animal.class, AABB.m_165882_(new Vec3(x, y, z), 40.0, 40.0, 40.0), e -> true).isEmpty()) {
                  label1178: {
                     if (sourceentity instanceof LivingEntity _livEnt224 && _livEnt224.m_21023_(MobEffects.f_19621_)) {
                        break label1178;
                     }

                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 80, 0, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                              SoundSource.HOSTILE,
                              4.5F,
                              1.0F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                              SoundSource.HOSTILE,
                              4.5F,
                              1.0F,
                              false
                           );
                        }
                     }

                     sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                     ExterminationMod.queueServerWork(
                        15,
                        () -> {
                           sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                           ExterminationMod.queueServerWork(
                              10,
                              () -> {
                                 if (sourceentity.m_6084_()) {
                                    if (world instanceof ServerLevel projectileLevel) {
                                       Projectile _entityToSpawn = new 11().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                       _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                       _entityToSpawn.m_6686_(
                                          sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                       );
                                       projectileLevel.m_7967_(_entityToSpawn);
                                    }

                                    if (world instanceof ServerLevel projectileLevel) {
                                       Projectile _entityToSpawn = new 12().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                       _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                       _entityToSpawn.m_6686_(
                                          sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                       );
                                       projectileLevel.m_7967_(_entityToSpawn);
                                    }
                                 }
                              }
                           );
                        }
                     );
                  }

                  if ((entity instanceof Player || entity instanceof Villager)
                     && world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 20.0, 20.0, 20.0), e -> true).isEmpty()
                     && Math.random() < 0.005) {
                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 60, 0, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              5.0F,
                              1.0F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              5.0F,
                              1.0F,
                              false
                           );
                        }
                     }

                     if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                        sourceentity.m_20194_()
                           .m_129892_()
                           .m_230957_(
                              new CommandSourceStack(
                                 CommandSource.f_80164_,
                                 sourceentity.m_20182_(),
                                 sourceentity.m_20155_(),
                                 sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                 4,
                                 sourceentity.m_7755_().getString(),
                                 sourceentity.m_5446_(),
                                 sourceentity.m_9236_().m_7654_(),
                                 sourceentity
                              ),
                              "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                           );
                     }

                     if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                        sourceentity.m_20194_()
                           .m_129892_()
                           .m_230957_(
                              new CommandSourceStack(
                                 CommandSource.f_80164_,
                                 sourceentity.m_20182_(),
                                 sourceentity.m_20155_(),
                                 sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                 4,
                                 sourceentity.m_7755_().getString(),
                                 sourceentity.m_5446_(),
                                 sourceentity.m_9236_().m_7654_(),
                                 sourceentity
                              ),
                              "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                           );
                     }

                     ExterminationMod.queueServerWork(
                        10,
                        () -> {
                           if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                              sourceentity.m_20194_()
                                 .m_129892_()
                                 .m_230957_(
                                    new CommandSourceStack(
                                       CommandSource.f_80164_,
                                       sourceentity.m_20182_(),
                                       sourceentity.m_20155_(),
                                       sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                       4,
                                       sourceentity.m_7755_().getString(),
                                       sourceentity.m_5446_(),
                                       sourceentity.m_9236_().m_7654_(),
                                       sourceentity
                                    ),
                                    "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                 );
                           }

                           if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                              sourceentity.m_20194_()
                                 .m_129892_()
                                 .m_230957_(
                                    new CommandSourceStack(
                                       CommandSource.f_80164_,
                                       sourceentity.m_20182_(),
                                       sourceentity.m_20155_(),
                                       sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                       4,
                                       sourceentity.m_7755_().getString(),
                                       sourceentity.m_5446_(),
                                       sourceentity.m_9236_().m_7654_(),
                                       sourceentity
                                    ),
                                    "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                 );
                           }

                           ExterminationMod.queueServerWork(
                              10,
                              () -> {
                                 if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                    sourceentity.m_20194_()
                                       .m_129892_()
                                       .m_230957_(
                                          new CommandSourceStack(
                                             CommandSource.f_80164_,
                                             sourceentity.m_20182_(),
                                             sourceentity.m_20155_(),
                                             sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                             4,
                                             sourceentity.m_7755_().getString(),
                                             sourceentity.m_5446_(),
                                             sourceentity.m_9236_().m_7654_(),
                                             sourceentity
                                          ),
                                          "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                       );
                                 }

                                 if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                    sourceentity.m_20194_()
                                       .m_129892_()
                                       .m_230957_(
                                          new CommandSourceStack(
                                             CommandSource.f_80164_,
                                             sourceentity.m_20182_(),
                                             sourceentity.m_20155_(),
                                             sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                             4,
                                             sourceentity.m_7755_().getString(),
                                             sourceentity.m_5446_(),
                                             sourceentity.m_9236_().m_7654_(),
                                             sourceentity
                                          ),
                                          "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                       );
                                 }

                                 ExterminationMod.queueServerWork(
                                    10,
                                    () -> {
                                       if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                          sourceentity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   sourceentity.m_20182_(),
                                                   sourceentity.m_20155_(),
                                                   sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                   4,
                                                   sourceentity.m_7755_().getString(),
                                                   sourceentity.m_5446_(),
                                                   sourceentity.m_9236_().m_7654_(),
                                                   sourceentity
                                                ),
                                                "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                             );
                                       }

                                       if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                          sourceentity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   sourceentity.m_20182_(),
                                                   sourceentity.m_20155_(),
                                                   sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                   4,
                                                   sourceentity.m_7755_().getString(),
                                                   sourceentity.m_5446_(),
                                                   sourceentity.m_9236_().m_7654_(),
                                                   sourceentity
                                                ),
                                                "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                             );
                                       }

                                       ExterminationMod.queueServerWork(
                                          10,
                                          () -> {
                                             if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                                sourceentity.m_20194_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         sourceentity.m_20182_(),
                                                         sourceentity.m_20155_(),
                                                         sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                         4,
                                                         sourceentity.m_7755_().getString(),
                                                         sourceentity.m_5446_(),
                                                         sourceentity.m_9236_().m_7654_(),
                                                         sourceentity
                                                      ),
                                                      "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                   );
                                             }

                                             if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                                sourceentity.m_20194_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         sourceentity.m_20182_(),
                                                         sourceentity.m_20155_(),
                                                         sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                         4,
                                                         sourceentity.m_7755_().getString(),
                                                         sourceentity.m_5446_(),
                                                         sourceentity.m_9236_().m_7654_(),
                                                         sourceentity
                                                      ),
                                                      "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                   );
                                             }

                                             if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxx) {
                                                _levelxxxxxxxxxxxxxxxxx.m_7654_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                            CommandSource.f_80164_,
                                                            new Vec3(x, y, z),
                                                            Vec2.f_82462_,
                                                            _levelxxxxxxxxxxxxxxxxx,
                                                            4,
                                                            "",
                                                            Component.m_237113_(""),
                                                            _levelxxxxxxxxxxxxxxxxx.m_7654_(),
                                                            null
                                                         )
                                                         .m_81324_(),
                                                      "effect give @e[distance=..30] minecraft:wither 8 1"
                                                   );
                                             }

                                             if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxx) {
                                                _levelxxxxxxxxxxxxxxxxx.m_7654_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                            CommandSource.f_80164_,
                                                            new Vec3(x, y, z),
                                                            Vec2.f_82462_,
                                                            _levelxxxxxxxxxxxxxxxxx,
                                                            4,
                                                            "",
                                                            Component.m_237113_(""),
                                                            _levelxxxxxxxxxxxxxxxxx.m_7654_(),
                                                            null
                                                         )
                                                         .m_81324_(),
                                                      "/effect give @e[distance=..30] minecraft:nausea 8 2"
                                                   );
                                             }
                                          }
                                       );
                                    }
                                 );
                              }
                           );
                        }
                     );
                  }
               }

               if (entity instanceof Villager && world.m_6443_(Villager.class, AABB.m_165882_(new Vec3(x, y, z), 40.0, 40.0, 40.0), e -> true).isEmpty()) {
                  label1181: {
                     if (sourceentity instanceof LivingEntity _livEnt277 && _livEnt277.m_21023_(MobEffects.f_19621_)) {
                        break label1181;
                     }

                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 80, 0, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                              SoundSource.HOSTILE,
                              4.5F,
                              1.0F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                              SoundSource.HOSTILE,
                              4.5F,
                              1.0F,
                              false
                           );
                        }
                     }

                     sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                     ExterminationMod.queueServerWork(
                        15,
                        () -> {
                           sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                           ExterminationMod.queueServerWork(
                              10,
                              () -> {
                                 if (sourceentity.m_6084_()) {
                                    if (world instanceof ServerLevel projectileLevel) {
                                       Projectile _entityToSpawn = new 13().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                       _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                       _entityToSpawn.m_6686_(
                                          sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                       );
                                       projectileLevel.m_7967_(_entityToSpawn);
                                    }

                                    if (world instanceof ServerLevel projectileLevel) {
                                       Projectile _entityToSpawn = new 14().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                       _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                       _entityToSpawn.m_6686_(
                                          sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                       );
                                       projectileLevel.m_7967_(_entityToSpawn);
                                    }
                                 }
                              }
                           );
                        }
                     );
                  }

                  if ((entity instanceof Player || entity instanceof Villager)
                     && world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 20.0, 20.0, 20.0), e -> true).isEmpty()
                     && Math.random() < 0.005) {
                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 60, 0, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              5.0F,
                              1.0F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              5.0F,
                              1.0F,
                              false
                           );
                        }
                     }

                     if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                        sourceentity.m_20194_()
                           .m_129892_()
                           .m_230957_(
                              new CommandSourceStack(
                                 CommandSource.f_80164_,
                                 sourceentity.m_20182_(),
                                 sourceentity.m_20155_(),
                                 sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                 4,
                                 sourceentity.m_7755_().getString(),
                                 sourceentity.m_5446_(),
                                 sourceentity.m_9236_().m_7654_(),
                                 sourceentity
                              ),
                              "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                           );
                     }

                     if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                        sourceentity.m_20194_()
                           .m_129892_()
                           .m_230957_(
                              new CommandSourceStack(
                                 CommandSource.f_80164_,
                                 sourceentity.m_20182_(),
                                 sourceentity.m_20155_(),
                                 sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                 4,
                                 sourceentity.m_7755_().getString(),
                                 sourceentity.m_5446_(),
                                 sourceentity.m_9236_().m_7654_(),
                                 sourceentity
                              ),
                              "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                           );
                     }

                     ExterminationMod.queueServerWork(
                        10,
                        () -> {
                           if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                              sourceentity.m_20194_()
                                 .m_129892_()
                                 .m_230957_(
                                    new CommandSourceStack(
                                       CommandSource.f_80164_,
                                       sourceentity.m_20182_(),
                                       sourceentity.m_20155_(),
                                       sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                       4,
                                       sourceentity.m_7755_().getString(),
                                       sourceentity.m_5446_(),
                                       sourceentity.m_9236_().m_7654_(),
                                       sourceentity
                                    ),
                                    "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                 );
                           }

                           if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                              sourceentity.m_20194_()
                                 .m_129892_()
                                 .m_230957_(
                                    new CommandSourceStack(
                                       CommandSource.f_80164_,
                                       sourceentity.m_20182_(),
                                       sourceentity.m_20155_(),
                                       sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                       4,
                                       sourceentity.m_7755_().getString(),
                                       sourceentity.m_5446_(),
                                       sourceentity.m_9236_().m_7654_(),
                                       sourceentity
                                    ),
                                    "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                 );
                           }

                           ExterminationMod.queueServerWork(
                              10,
                              () -> {
                                 if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                    sourceentity.m_20194_()
                                       .m_129892_()
                                       .m_230957_(
                                          new CommandSourceStack(
                                             CommandSource.f_80164_,
                                             sourceentity.m_20182_(),
                                             sourceentity.m_20155_(),
                                             sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                             4,
                                             sourceentity.m_7755_().getString(),
                                             sourceentity.m_5446_(),
                                             sourceentity.m_9236_().m_7654_(),
                                             sourceentity
                                          ),
                                          "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                       );
                                 }

                                 if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                    sourceentity.m_20194_()
                                       .m_129892_()
                                       .m_230957_(
                                          new CommandSourceStack(
                                             CommandSource.f_80164_,
                                             sourceentity.m_20182_(),
                                             sourceentity.m_20155_(),
                                             sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                             4,
                                             sourceentity.m_7755_().getString(),
                                             sourceentity.m_5446_(),
                                             sourceentity.m_9236_().m_7654_(),
                                             sourceentity
                                          ),
                                          "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                       );
                                 }

                                 ExterminationMod.queueServerWork(
                                    10,
                                    () -> {
                                       if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                          sourceentity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   sourceentity.m_20182_(),
                                                   sourceentity.m_20155_(),
                                                   sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                   4,
                                                   sourceentity.m_7755_().getString(),
                                                   sourceentity.m_5446_(),
                                                   sourceentity.m_9236_().m_7654_(),
                                                   sourceentity
                                                ),
                                                "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                             );
                                       }

                                       if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                          sourceentity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   sourceentity.m_20182_(),
                                                   sourceentity.m_20155_(),
                                                   sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                   4,
                                                   sourceentity.m_7755_().getString(),
                                                   sourceentity.m_5446_(),
                                                   sourceentity.m_9236_().m_7654_(),
                                                   sourceentity
                                                ),
                                                "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                             );
                                       }

                                       ExterminationMod.queueServerWork(
                                          10,
                                          () -> {
                                             if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                                sourceentity.m_20194_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         sourceentity.m_20182_(),
                                                         sourceentity.m_20155_(),
                                                         sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                         4,
                                                         sourceentity.m_7755_().getString(),
                                                         sourceentity.m_5446_(),
                                                         sourceentity.m_9236_().m_7654_(),
                                                         sourceentity
                                                      ),
                                                      "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                   );
                                             }

                                             if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                                sourceentity.m_20194_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         sourceentity.m_20182_(),
                                                         sourceentity.m_20155_(),
                                                         sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                         4,
                                                         sourceentity.m_7755_().getString(),
                                                         sourceentity.m_5446_(),
                                                         sourceentity.m_9236_().m_7654_(),
                                                         sourceentity
                                                      ),
                                                      "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                   );
                                             }

                                             if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxx) {
                                                _levelxxxxxxxxxxxxxxxxxxx.m_7654_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                            CommandSource.f_80164_,
                                                            new Vec3(x, y, z),
                                                            Vec2.f_82462_,
                                                            _levelxxxxxxxxxxxxxxxxxxx,
                                                            4,
                                                            "",
                                                            Component.m_237113_(""),
                                                            _levelxxxxxxxxxxxxxxxxxxx.m_7654_(),
                                                            null
                                                         )
                                                         .m_81324_(),
                                                      "effect give @e[distance=..30] minecraft:wither 8 1"
                                                   );
                                             }

                                             if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxx) {
                                                _levelxxxxxxxxxxxxxxxxxxx.m_7654_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                            CommandSource.f_80164_,
                                                            new Vec3(x, y, z),
                                                            Vec2.f_82462_,
                                                            _levelxxxxxxxxxxxxxxxxxxx,
                                                            4,
                                                            "",
                                                            Component.m_237113_(""),
                                                            _levelxxxxxxxxxxxxxxxxxxx.m_7654_(),
                                                            null
                                                         )
                                                         .m_81324_(),
                                                      "/effect give @e[distance=..30] minecraft:nausea 8 2"
                                                   );
                                             }
                                          }
                                       );
                                    }
                                 );
                              }
                           );
                        }
                     );
                  }
               }

               if (entity instanceof Player) {
                  label1183: {
                     if (sourceentity instanceof LivingEntity _livEnt329 && _livEnt329.m_21023_(MobEffects.f_19621_)) {
                        break label1183;
                     }

                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 80, 0, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                              SoundSource.HOSTILE,
                              4.5F,
                              1.0F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                              SoundSource.HOSTILE,
                              4.5F,
                              1.0F,
                              false
                           );
                        }
                     }

                     sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                     ExterminationMod.queueServerWork(
                        15,
                        () -> {
                           sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                           ExterminationMod.queueServerWork(
                              10,
                              () -> {
                                 if (sourceentity.m_6084_()) {
                                    if (world instanceof ServerLevel projectileLevel) {
                                       Projectile _entityToSpawn = new 15().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                       _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                       _entityToSpawn.m_6686_(
                                          sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                       );
                                       projectileLevel.m_7967_(_entityToSpawn);
                                    }

                                    if (world instanceof ServerLevel projectileLevel) {
                                       Projectile _entityToSpawn = new 16().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                       _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                       _entityToSpawn.m_6686_(
                                          sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                       );
                                       projectileLevel.m_7967_(_entityToSpawn);
                                    }
                                 }
                              }
                           );
                        }
                     );
                  }

                  if ((entity instanceof Player || entity instanceof Villager)
                     && world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 20.0, 20.0, 20.0), e -> true).isEmpty()
                     && Math.random() < 0.005) {
                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 60, 0, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              5.0F,
                              1.0F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              5.0F,
                              1.0F,
                              false
                           );
                        }
                     }

                     if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                        sourceentity.m_20194_()
                           .m_129892_()
                           .m_230957_(
                              new CommandSourceStack(
                                 CommandSource.f_80164_,
                                 sourceentity.m_20182_(),
                                 sourceentity.m_20155_(),
                                 sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                 4,
                                 sourceentity.m_7755_().getString(),
                                 sourceentity.m_5446_(),
                                 sourceentity.m_9236_().m_7654_(),
                                 sourceentity
                              ),
                              "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                           );
                     }

                     if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                        sourceentity.m_20194_()
                           .m_129892_()
                           .m_230957_(
                              new CommandSourceStack(
                                 CommandSource.f_80164_,
                                 sourceentity.m_20182_(),
                                 sourceentity.m_20155_(),
                                 sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                 4,
                                 sourceentity.m_7755_().getString(),
                                 sourceentity.m_5446_(),
                                 sourceentity.m_9236_().m_7654_(),
                                 sourceentity
                              ),
                              "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                           );
                     }

                     ExterminationMod.queueServerWork(
                        10,
                        () -> {
                           if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                              sourceentity.m_20194_()
                                 .m_129892_()
                                 .m_230957_(
                                    new CommandSourceStack(
                                       CommandSource.f_80164_,
                                       sourceentity.m_20182_(),
                                       sourceentity.m_20155_(),
                                       sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                       4,
                                       sourceentity.m_7755_().getString(),
                                       sourceentity.m_5446_(),
                                       sourceentity.m_9236_().m_7654_(),
                                       sourceentity
                                    ),
                                    "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                 );
                           }

                           if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                              sourceentity.m_20194_()
                                 .m_129892_()
                                 .m_230957_(
                                    new CommandSourceStack(
                                       CommandSource.f_80164_,
                                       sourceentity.m_20182_(),
                                       sourceentity.m_20155_(),
                                       sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                       4,
                                       sourceentity.m_7755_().getString(),
                                       sourceentity.m_5446_(),
                                       sourceentity.m_9236_().m_7654_(),
                                       sourceentity
                                    ),
                                    "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                 );
                           }

                           ExterminationMod.queueServerWork(
                              10,
                              () -> {
                                 if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                    sourceentity.m_20194_()
                                       .m_129892_()
                                       .m_230957_(
                                          new CommandSourceStack(
                                             CommandSource.f_80164_,
                                             sourceentity.m_20182_(),
                                             sourceentity.m_20155_(),
                                             sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                             4,
                                             sourceentity.m_7755_().getString(),
                                             sourceentity.m_5446_(),
                                             sourceentity.m_9236_().m_7654_(),
                                             sourceentity
                                          ),
                                          "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                       );
                                 }

                                 if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                    sourceentity.m_20194_()
                                       .m_129892_()
                                       .m_230957_(
                                          new CommandSourceStack(
                                             CommandSource.f_80164_,
                                             sourceentity.m_20182_(),
                                             sourceentity.m_20155_(),
                                             sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                             4,
                                             sourceentity.m_7755_().getString(),
                                             sourceentity.m_5446_(),
                                             sourceentity.m_9236_().m_7654_(),
                                             sourceentity
                                          ),
                                          "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                       );
                                 }

                                 ExterminationMod.queueServerWork(
                                    10,
                                    () -> {
                                       if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                          sourceentity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   sourceentity.m_20182_(),
                                                   sourceentity.m_20155_(),
                                                   sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                   4,
                                                   sourceentity.m_7755_().getString(),
                                                   sourceentity.m_5446_(),
                                                   sourceentity.m_9236_().m_7654_(),
                                                   sourceentity
                                                ),
                                                "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                             );
                                       }

                                       if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                          sourceentity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   sourceentity.m_20182_(),
                                                   sourceentity.m_20155_(),
                                                   sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                   4,
                                                   sourceentity.m_7755_().getString(),
                                                   sourceentity.m_5446_(),
                                                   sourceentity.m_9236_().m_7654_(),
                                                   sourceentity
                                                ),
                                                "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                             );
                                       }

                                       ExterminationMod.queueServerWork(
                                          10,
                                          () -> {
                                             if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                                sourceentity.m_20194_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         sourceentity.m_20182_(),
                                                         sourceentity.m_20155_(),
                                                         sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                         4,
                                                         sourceentity.m_7755_().getString(),
                                                         sourceentity.m_5446_(),
                                                         sourceentity.m_9236_().m_7654_(),
                                                         sourceentity
                                                      ),
                                                      "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                   );
                                             }

                                             if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                                sourceentity.m_20194_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         sourceentity.m_20182_(),
                                                         sourceentity.m_20155_(),
                                                         sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                         4,
                                                         sourceentity.m_7755_().getString(),
                                                         sourceentity.m_5446_(),
                                                         sourceentity.m_9236_().m_7654_(),
                                                         sourceentity
                                                      ),
                                                      "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                   );
                                             }

                                             if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxx) {
                                                _levelxxxxxxxxxxxxxxxxxxxxx.m_7654_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                            CommandSource.f_80164_,
                                                            new Vec3(x, y, z),
                                                            Vec2.f_82462_,
                                                            _levelxxxxxxxxxxxxxxxxxxxxx,
                                                            4,
                                                            "",
                                                            Component.m_237113_(""),
                                                            _levelxxxxxxxxxxxxxxxxxxxxx.m_7654_(),
                                                            null
                                                         )
                                                         .m_81324_(),
                                                      "effect give @e[distance=..30] minecraft:wither 8 1"
                                                   );
                                             }

                                             if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxx) {
                                                _levelxxxxxxxxxxxxxxxxxxxxx.m_7654_()
                                                   .m_129892_()
                                                   .m_230957_(
                                                      new CommandSourceStack(
                                                            CommandSource.f_80164_,
                                                            new Vec3(x, y, z),
                                                            Vec2.f_82462_,
                                                            _levelxxxxxxxxxxxxxxxxxxxxx,
                                                            4,
                                                            "",
                                                            Component.m_237113_(""),
                                                            _levelxxxxxxxxxxxxxxxxxxxxx.m_7654_(),
                                                            null
                                                         )
                                                         .m_81324_(),
                                                      "/effect give @e[distance=..30] minecraft:nausea 8 2"
                                                   );
                                             }
                                          }
                                       );
                                    }
                                 );
                              }
                           );
                        }
                     );
                  }
               }
            }

            if (sourceentity instanceof TripodHarvesterEntity) {
               if (Math.random() < 0.008) {
                  if ((sourceentity instanceof LivingEntity _livEntxxxx ? _livEntxxxx.m_21223_() : -1.0F) > 40.0F
                     && world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F,
                           false
                        );
                     }
                  }

                  if ((sourceentity instanceof LivingEntity _livEntxxxxx ? _livEntxxxxx.m_21223_() : -1.0F) <= 40.0F
                     && world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn_broken")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn_broken")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F,
                           false
                        );
                     }
                  }
               }

               if (entity instanceof Player
                  && world.m_6443_(MartianEntity.class, AABB.m_165882_(new Vec3(x, y, z), 80.0, 80.0, 80.0), e -> true).isEmpty()
                  && !world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()
                  && Math.random() < 0.002) {
                  if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxx) {
                     Entity entityToSpawn = ((EntityType)ExterminationModEntities.MARTIAN.get())
                        .m_262496_(_levelxxxxxxxxxxxxxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED);
                     if (entityToSpawn != null) {
                        entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                     }
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("block.barrel.open")),
                           SoundSource.HOSTILE,
                           0.0F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("block.barrel.open")),
                           SoundSource.HOSTILE,
                           0.0F,
                           1.0F,
                           false
                        );
                     }
                  }
               }

               label1062:
               if (entity instanceof Villager
                  && Math.random() < 0.05
                  && !world.m_6443_(Villager.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                  if (sourceentity instanceof LivingEntity _livEnt392 && _livEnt392.m_21023_(MobEffects.f_19597_)) {
                     break label1062;
                  }

                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19597_, 200, 100, false, false));
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                           SoundSource.HOSTILE,
                           3.0F,
                           0.7F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                           SoundSource.HOSTILE,
                           3.0F,
                           0.7F,
                           false
                        );
                     }
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                           SoundSource.HOSTILE,
                           1.5F,
                           0.7F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                           SoundSource.HOSTILE,
                           1.5F,
                           0.7F,
                           false
                        );
                     }
                  }

                  if (sourceentity instanceof TripodHarvesterEntity) {
                     ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.down");
                  }

                  ExterminationMod.queueServerWork(1, () -> {
                     if (sourceentity instanceof TripodHarvesterEntity) {
                        ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.down");
                     }

                     ExterminationMod.queueServerWork(1, () -> {
                        if (sourceentity instanceof TripodHarvesterEntity) {
                           ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.down");
                        }
                     });
                  });
                  ExterminationMod.queueServerWork(
                     48,
                     () -> {
                        if (world.m_6443_(Villager.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                           if (sourceentity instanceof TripodHarvesterEntity) {
                              ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                           }

                           ExterminationMod.queueServerWork(1, () -> {
                              if (sourceentity instanceof TripodHarvesterEntity) {
                                 ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof TripodHarvesterEntity) {
                                    ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                                 }
                              });
                           });
                           ExterminationMod.queueServerWork(40, () -> {
                              if (sourceentity instanceof LivingEntity _entityx) {
                                 _entityx.m_21195_(MobEffects.f_19597_);
                              }
                           });
                        }

                        if (!world.m_6443_(Villager.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                           if (sourceentity instanceof TripodHarvesterEntity) {
                              ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.tentacles");
                           }

                           ExterminationMod.queueServerWork(1, () -> {
                              if (sourceentity instanceof TripodHarvesterEntity) {
                                 ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.tentacles");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof TripodHarvesterEntity) {
                                    ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.tentacles");
                                 }
                              });
                           });
                           ExterminationMod.queueServerWork(
                              5,
                              () -> {
                                 if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                                    Entity entityToSpawn = ((EntityType)ExterminationModEntities.TENTACLE_ENTITY.get())
                                       .m_262496_(_levelxxxxxxxxxxxxxxxxxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED);
                                    if (entityToSpawn != null) {
                                       entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                                    }
                                 }

                                 ExterminationMod.queueServerWork(
                                    32,
                                    () -> {
                                       if (!entity.m_9236_().m_5776_() && entity.m_20194_() != null) {
                                          entity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   entity.m_20182_(),
                                                   entity.m_20155_(),
                                                   entity.m_9236_() instanceof ServerLevel ? (ServerLevel)entity.m_9236_() : null,
                                                   4,
                                                   entity.m_7755_().getString(),
                                                   entity.m_5446_(),
                                                   entity.m_9236_().m_7654_(),
                                                   entity
                                                ),
                                                "/kill @e[type=extermination:tentacle_entity,distance=..2]"
                                             );
                                       }
                                    }
                                 );
                              }
                           );
                           ExterminationMod.queueServerWork(50, () -> {
                              if (sourceentity instanceof TripodHarvesterEntity) {
                                 ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof TripodHarvesterEntity) {
                                    ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof TripodHarvesterEntity) {
                                       ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(40, () -> {
                                 if (sourceentity instanceof LivingEntity _entityx) {
                                    _entityx.m_21195_(MobEffects.f_19597_);
                                 }
                              });
                           });
                        }
                     }
                  );
               }

               label1054:
               if (entity instanceof Animal
                  && Math.random() < 0.05
                  && !world.m_6443_(Animal.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                  if (sourceentity instanceof LivingEntity _livEnt430 && _livEnt430.m_21023_(MobEffects.f_19597_)) {
                     break label1054;
                  }

                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19597_, 200, 100, false, false));
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                           SoundSource.HOSTILE,
                           3.0F,
                           0.7F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                           SoundSource.HOSTILE,
                           3.0F,
                           0.7F,
                           false
                        );
                     }
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                           SoundSource.HOSTILE,
                           1.5F,
                           0.7F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                           SoundSource.HOSTILE,
                           1.5F,
                           0.7F,
                           false
                        );
                     }
                  }

                  if (sourceentity instanceof TripodHarvesterEntity) {
                     ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.down");
                  }

                  ExterminationMod.queueServerWork(1, () -> {
                     if (sourceentity instanceof TripodHarvesterEntity) {
                        ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.down");
                     }

                     ExterminationMod.queueServerWork(1, () -> {
                        if (sourceentity instanceof TripodHarvesterEntity) {
                           ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.down");
                        }
                     });
                  });
                  ExterminationMod.queueServerWork(
                     48,
                     () -> {
                        if (world.m_6443_(Animal.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                           if (sourceentity instanceof TripodHarvesterEntity) {
                              ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                           }

                           ExterminationMod.queueServerWork(1, () -> {
                              if (sourceentity instanceof TripodHarvesterEntity) {
                                 ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof TripodHarvesterEntity) {
                                    ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                                 }
                              });
                           });
                           ExterminationMod.queueServerWork(40, () -> {
                              if (sourceentity instanceof LivingEntity _entityx) {
                                 _entityx.m_21195_(MobEffects.f_19597_);
                              }
                           });
                        }

                        if (!world.m_6443_(Animal.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                           if (sourceentity instanceof TripodHarvesterEntity) {
                              ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.tentacles");
                           }

                           ExterminationMod.queueServerWork(1, () -> {
                              if (sourceentity instanceof TripodHarvesterEntity) {
                                 ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.tentacles");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof TripodHarvesterEntity) {
                                    ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.tentacles");
                                 }
                              });
                           });
                           ExterminationMod.queueServerWork(
                              5,
                              () -> {
                                 if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                                    Entity entityToSpawn = ((EntityType)ExterminationModEntities.TENTACLE_ENTITY.get())
                                       .m_262496_(_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED);
                                    if (entityToSpawn != null) {
                                       entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                                    }
                                 }

                                 ExterminationMod.queueServerWork(
                                    32,
                                    () -> {
                                       if (!entity.m_9236_().m_5776_() && entity.m_20194_() != null) {
                                          entity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   entity.m_20182_(),
                                                   entity.m_20155_(),
                                                   entity.m_9236_() instanceof ServerLevel ? (ServerLevel)entity.m_9236_() : null,
                                                   4,
                                                   entity.m_7755_().getString(),
                                                   entity.m_5446_(),
                                                   entity.m_9236_().m_7654_(),
                                                   entity
                                                ),
                                                "/kill @e[type=extermination:tentacle_entity,distance=..2]"
                                             );
                                       }
                                    }
                                 );
                              }
                           );
                           ExterminationMod.queueServerWork(50, () -> {
                              if (sourceentity instanceof TripodHarvesterEntity) {
                                 ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof TripodHarvesterEntity) {
                                    ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof TripodHarvesterEntity) {
                                       ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(40, () -> {
                                 if (sourceentity instanceof LivingEntity _entityx) {
                                    _entityx.m_21195_(MobEffects.f_19597_);
                                 }
                              });
                           });
                        }
                     }
                  );
               }

               label1045:
               if (entity instanceof Player
                  && Math.random() < 0.05
                  && !world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                  if (sourceentity instanceof LivingEntity _livEnt468 && _livEnt468.m_21023_(MobEffects.f_19597_)) {
                     break label1045;
                  }

                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19597_, 200, 100, false, false));
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                           SoundSource.HOSTILE,
                           3.0F,
                           0.7F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                           SoundSource.HOSTILE,
                           3.0F,
                           0.7F,
                           false
                        );
                     }
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                           SoundSource.HOSTILE,
                           1.5F,
                           0.7F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                           SoundSource.HOSTILE,
                           1.5F,
                           0.7F,
                           false
                        );
                     }
                  }

                  if (sourceentity instanceof TripodHarvesterEntity) {
                     ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.down");
                  }

                  ExterminationMod.queueServerWork(1, () -> {
                     if (sourceentity instanceof TripodHarvesterEntity) {
                        ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.down");
                     }

                     ExterminationMod.queueServerWork(1, () -> {
                        if (sourceentity instanceof TripodHarvesterEntity) {
                           ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.down");
                        }
                     });
                  });
                  ExterminationMod.queueServerWork(
                     48,
                     () -> {
                        if (world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                           if (sourceentity instanceof TripodHarvesterEntity) {
                              ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                           }

                           ExterminationMod.queueServerWork(1, () -> {
                              if (sourceentity instanceof TripodHarvesterEntity) {
                                 ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof TripodHarvesterEntity) {
                                    ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                                 }
                              });
                           });
                           ExterminationMod.queueServerWork(40, () -> {
                              if (sourceentity instanceof LivingEntity _entityx) {
                                 _entityx.m_21195_(MobEffects.f_19597_);
                              }
                           });
                        }

                        if (!world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                           if (sourceentity instanceof TripodHarvesterEntity) {
                              ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.tentacles");
                           }

                           ExterminationMod.queueServerWork(1, () -> {
                              if (sourceentity instanceof TripodHarvesterEntity) {
                                 ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.tentacles");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof TripodHarvesterEntity) {
                                    ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.tentacles");
                                 }
                              });
                           });
                           ExterminationMod.queueServerWork(
                              5,
                              () -> {
                                 if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                                    Entity entityToSpawn = ((EntityType)ExterminationModEntities.TENTACLE_ENTITY.get())
                                       .m_262496_(_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED);
                                    if (entityToSpawn != null) {
                                       entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                                    }
                                 }

                                 ExterminationMod.queueServerWork(
                                    32,
                                    () -> {
                                       if (!entity.m_9236_().m_5776_() && entity.m_20194_() != null) {
                                          entity.m_20194_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                   CommandSource.f_80164_,
                                                   entity.m_20182_(),
                                                   entity.m_20155_(),
                                                   entity.m_9236_() instanceof ServerLevel ? (ServerLevel)entity.m_9236_() : null,
                                                   4,
                                                   entity.m_7755_().getString(),
                                                   entity.m_5446_(),
                                                   entity.m_9236_().m_7654_(),
                                                   entity
                                                ),
                                                "/kill @e[type=extermination:tentacle_entity,distance=..2]"
                                             );
                                       }
                                    }
                                 );
                              }
                           );
                           ExterminationMod.queueServerWork(50, () -> {
                              if (sourceentity instanceof TripodHarvesterEntity) {
                                 ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof TripodHarvesterEntity) {
                                    ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof TripodHarvesterEntity) {
                                       ((TripodHarvesterEntity)sourceentity).setAnimation("animation.tripod_harvester.up");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(40, () -> {
                                 if (sourceentity instanceof LivingEntity _entityx) {
                                    _entityx.m_21195_(MobEffects.f_19597_);
                                 }
                              });
                           });
                        }
                     }
                  );
               }
            }

            if (sourceentity instanceof EmperorpodEntity) {
               if (Math.random() < 0.008) {
                  if ((sourceentity instanceof LivingEntity _livEntxxxxxx ? _livEntxxxxxx.m_21223_() : -1.0F) > 100.0F) {
                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.emperorpod.horn")),
                              SoundSource.HOSTILE,
                              10.0F,
                              1.0F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.emperorpod.horn")),
                              SoundSource.HOSTILE,
                              10.0F,
                              1.0F,
                              false
                           );
                        }
                     }

                     if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7654_()
                           .m_129892_()
                           .m_230957_(
                              new CommandSourceStack(
                                    CommandSource.f_80164_,
                                    new Vec3(x, y, z),
                                    Vec2.f_82462_,
                                    _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx,
                                    4,
                                    "",
                                    Component.m_237113_(""),
                                    _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7654_(),
                                    null
                                 )
                                 .m_81324_(),
                              "/effect give @a[distance=..40,gamemode=!creative,gamemode=!spectator] extermination:earthquake 3 0 true"
                           );
                     }
                  }

                  if ((sourceentity instanceof LivingEntity _livEntxxxxxx ? _livEntxxxxxx.m_21223_() : -1.0F) <= 100.0F
                     && world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn_broken")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.horn_broken")),
                           SoundSource.HOSTILE,
                           10.0F,
                           1.0F,
                           false
                        );
                     }
                  }
               }

               if ((sourceentity instanceof LivingEntity _livEntxxxxxxx ? _livEntxxxxxxx.m_21223_() : -1.0F) <= 300.0F) {
                  if (entity instanceof Player
                     && world.m_6443_(MartianEntity.class, AABB.m_165882_(new Vec3(x, y, z), 80.0, 80.0, 80.0), e -> true).isEmpty()
                     && !world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()
                     && Math.random() < 0.002) {
                     if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        Entity entityToSpawn = ((EntityType)ExterminationModEntities.MARTIAN.get())
                           .m_262496_(_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED);
                        if (entityToSpawn != null) {
                           entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                        }
                     }

                     if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        Entity entityToSpawn = ((EntityType)ExterminationModEntities.MARTIAN.get())
                           .m_262496_(_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED);
                        if (entityToSpawn != null) {
                           entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                        }
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("block.barrel.open")),
                              SoundSource.HOSTILE,
                              0.0F,
                              1.0F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("block.barrel.open")),
                              SoundSource.HOSTILE,
                              0.0F,
                              1.0F,
                              false
                           );
                        }
                     }
                  }

                  Vec3 _center = new Vec3(x, y, z);

                  for (Entity entityiterator : world.m_6443_(Entity.class, new AABB(_center, _center).m_82400_(150.0), e -> true)
                     .stream()
                     .sorted(Comparator.comparingDouble(_entcnd -> _entcnd.m_20238_(_center)))
                     .toList()) {
                     if ((entityiterator instanceof TripodHarvesterEntity || entityiterator instanceof UberpodEntity || entityiterator instanceof TripodEntity)
                        && entityiterator instanceof Mob) {
                        Mob _entity = (Mob)entityiterator;
                        if (entity instanceof LivingEntity _ent) {
                           _entity.m_6710_(_ent);
                        }
                     }
                  }
               }

               if (entity instanceof Villager
                  && (!(sourceentity instanceof LivingEntity _livEnt523) || !_livEnt523.m_21023_(MobEffects.f_19621_))
                  && (!(sourceentity instanceof LivingEntity _livEnt524) || !_livEnt524.m_21023_(MobEffects.f_19597_))
                  && world.m_6443_(Villager.class, AABB.m_165882_(new Vec3(x, y, z), 40.0, 40.0, 40.0), e -> true).isEmpty()) {
                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 80, 0, false, false));
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F,
                           false
                        );
                     }
                  }

                  sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                  ExterminationMod.queueServerWork(
                     15,
                     () -> {
                        sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                        ExterminationMod.queueServerWork(
                           10,
                           () -> {
                              if (sourceentity.m_6084_()) {
                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 17().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }

                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 18().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }
                              }
                           }
                        );
                     }
                  );
               }

               if (entity instanceof Animal
                  && (!(sourceentity instanceof LivingEntity _livEnt556) || !_livEnt556.m_21023_(MobEffects.f_19621_))
                  && (!(sourceentity instanceof LivingEntity _livEnt557) || !_livEnt557.m_21023_(MobEffects.f_19597_))
                  && world.m_6443_(Animal.class, AABB.m_165882_(new Vec3(x, y, z), 40.0, 40.0, 40.0), e -> true).isEmpty()) {
                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 80, 0, false, false));
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F,
                           false
                        );
                     }
                  }

                  sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                  ExterminationMod.queueServerWork(
                     15,
                     () -> {
                        sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                        ExterminationMod.queueServerWork(
                           10,
                           () -> {
                              if (sourceentity.m_6084_()) {
                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 19().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }

                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 20().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }
                              }
                           }
                        );
                     }
                  );
               }

               if (entity instanceof Zombie
                  && (!(sourceentity instanceof LivingEntity _livEnt589) || !_livEnt589.m_21023_(MobEffects.f_19621_))
                  && (!(sourceentity instanceof LivingEntity _livEnt590) || !_livEnt590.m_21023_(MobEffects.f_19597_))
                  && world.m_6443_(Zombie.class, AABB.m_165882_(new Vec3(x, y, z), 40.0, 40.0, 40.0), e -> true).isEmpty()) {
                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 80, 0, false, false));
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F,
                           false
                        );
                     }
                  }

                  sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                  ExterminationMod.queueServerWork(
                     15,
                     () -> {
                        sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                        ExterminationMod.queueServerWork(
                           10,
                           () -> {
                              if (sourceentity.m_6084_()) {
                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 21().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }

                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 22().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }
                              }
                           }
                        );
                     }
                  );
               }

               if (entity instanceof Player
                  && (!(sourceentity instanceof LivingEntity _livEnt622) || !_livEnt622.m_21023_(MobEffects.f_19621_))
                  && (!(sourceentity instanceof LivingEntity _livEnt623) || !_livEnt623.m_21023_(MobEffects.f_19597_))
                  && world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 80, 0, false, false));
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.shoot")),
                           SoundSource.HOSTILE,
                           4.5F,
                           1.0F,
                           false
                        );
                     }
                  }

                  sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                  ExterminationMod.queueServerWork(
                     15,
                     () -> {
                        sourceentity.m_7618_(Anchor.EYES, new Vec3(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()));
                        ExterminationMod.queueServerWork(
                           10,
                           () -> {
                              if (sourceentity.m_6084_()) {
                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 23().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() - 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }

                                 if (world instanceof ServerLevel projectileLevel) {
                                    Projectile _entityToSpawn = new 24().getArrow(projectileLevel, sourceentity, 500.0F, 0, (byte)10);
                                    _entityToSpawn.m_6034_(sourceentity.m_20185_() + 3.7, sourceentity.m_20186_() + 19.8, sourceentity.m_20189_() + 4.0);
                                    _entityToSpawn.m_6686_(
                                       sourceentity.m_20154_().f_82479_, sourceentity.m_20154_().f_82480_, sourceentity.m_20154_().f_82481_, 3.5F, 0.0F
                                    );
                                    projectileLevel.m_7967_(_entityToSpawn);
                                 }
                              }
                           }
                        );
                     }
                  );
               }

               if ((sourceentity instanceof LivingEntity _livEntxxxxxxx ? _livEntxxxxxxx.m_21223_() : -1.0F) <= 300.0F
                  && (entity instanceof Player || entity instanceof Villager)
                  && world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 20.0, 20.0, 20.0), e -> true).isEmpty()
                  && Math.random() < 0.005) {
                  if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                     _entity.m_7292_(new MobEffectInstance(MobEffects.f_19621_, 60, 0, false, false));
                  }

                  if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                     if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                           null,
                           BlockPos.m_274561_(x, y, z),
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                           SoundSource.HOSTILE,
                           5.0F,
                           1.0F
                        );
                     } else {
                        _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                           x,
                           y,
                           z,
                           (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                           SoundSource.HOSTILE,
                           5.0F,
                           1.0F,
                           false
                        );
                     }
                  }

                  if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                     sourceentity.m_20194_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                              CommandSource.f_80164_,
                              sourceentity.m_20182_(),
                              sourceentity.m_20155_(),
                              sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                              4,
                              sourceentity.m_7755_().getString(),
                              sourceentity.m_5446_(),
                              sourceentity.m_9236_().m_7654_(),
                              sourceentity
                           ),
                           "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                        );
                  }

                  if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                     sourceentity.m_20194_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                              CommandSource.f_80164_,
                              sourceentity.m_20182_(),
                              sourceentity.m_20155_(),
                              sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                              4,
                              sourceentity.m_7755_().getString(),
                              sourceentity.m_5446_(),
                              sourceentity.m_9236_().m_7654_(),
                              sourceentity
                           ),
                           "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                        );
                  }

                  ExterminationMod.queueServerWork(
                     10,
                     () -> {
                        if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                           sourceentity.m_20194_()
                              .m_129892_()
                              .m_230957_(
                                 new CommandSourceStack(
                                    CommandSource.f_80164_,
                                    sourceentity.m_20182_(),
                                    sourceentity.m_20155_(),
                                    sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                    4,
                                    sourceentity.m_7755_().getString(),
                                    sourceentity.m_5446_(),
                                    sourceentity.m_9236_().m_7654_(),
                                    sourceentity
                                 ),
                                 "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                              );
                        }

                        if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                           sourceentity.m_20194_()
                              .m_129892_()
                              .m_230957_(
                                 new CommandSourceStack(
                                    CommandSource.f_80164_,
                                    sourceentity.m_20182_(),
                                    sourceentity.m_20155_(),
                                    sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                    4,
                                    sourceentity.m_7755_().getString(),
                                    sourceentity.m_5446_(),
                                    sourceentity.m_9236_().m_7654_(),
                                    sourceentity
                                 ),
                                 "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                              );
                        }

                        ExterminationMod.queueServerWork(
                           10,
                           () -> {
                              if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                 sourceentity.m_20194_()
                                    .m_129892_()
                                    .m_230957_(
                                       new CommandSourceStack(
                                          CommandSource.f_80164_,
                                          sourceentity.m_20182_(),
                                          sourceentity.m_20155_(),
                                          sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                          4,
                                          sourceentity.m_7755_().getString(),
                                          sourceentity.m_5446_(),
                                          sourceentity.m_9236_().m_7654_(),
                                          sourceentity
                                       ),
                                       "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                    );
                              }

                              if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                 sourceentity.m_20194_()
                                    .m_129892_()
                                    .m_230957_(
                                       new CommandSourceStack(
                                          CommandSource.f_80164_,
                                          sourceentity.m_20182_(),
                                          sourceentity.m_20155_(),
                                          sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                          4,
                                          sourceentity.m_7755_().getString(),
                                          sourceentity.m_5446_(),
                                          sourceentity.m_9236_().m_7654_(),
                                          sourceentity
                                       ),
                                       "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                    );
                              }

                              ExterminationMod.queueServerWork(
                                 10,
                                 () -> {
                                    if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                       sourceentity.m_20194_()
                                          .m_129892_()
                                          .m_230957_(
                                             new CommandSourceStack(
                                                CommandSource.f_80164_,
                                                sourceentity.m_20182_(),
                                                sourceentity.m_20155_(),
                                                sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                4,
                                                sourceentity.m_7755_().getString(),
                                                sourceentity.m_5446_(),
                                                sourceentity.m_9236_().m_7654_(),
                                                sourceentity
                                             ),
                                             "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                          );
                                    }

                                    if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                       sourceentity.m_20194_()
                                          .m_129892_()
                                          .m_230957_(
                                             new CommandSourceStack(
                                                CommandSource.f_80164_,
                                                sourceentity.m_20182_(),
                                                sourceentity.m_20155_(),
                                                sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                4,
                                                sourceentity.m_7755_().getString(),
                                                sourceentity.m_5446_(),
                                                sourceentity.m_9236_().m_7654_(),
                                                sourceentity
                                             ),
                                             "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                          );
                                    }

                                    ExterminationMod.queueServerWork(
                                       10,
                                       () -> {
                                          if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                             sourceentity.m_20194_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                      CommandSource.f_80164_,
                                                      sourceentity.m_20182_(),
                                                      sourceentity.m_20155_(),
                                                      sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                      4,
                                                      sourceentity.m_7755_().getString(),
                                                      sourceentity.m_5446_(),
                                                      sourceentity.m_9236_().m_7654_(),
                                                      sourceentity
                                                   ),
                                                   "particle minecraft:squid_ink ^1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                );
                                          }

                                          if (!sourceentity.m_9236_().m_5776_() && sourceentity.m_20194_() != null) {
                                             sourceentity.m_20194_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                      CommandSource.f_80164_,
                                                      sourceentity.m_20182_(),
                                                      sourceentity.m_20155_(),
                                                      sourceentity.m_9236_() instanceof ServerLevel ? (ServerLevel)sourceentity.m_9236_() : null,
                                                      4,
                                                      sourceentity.m_7755_().getString(),
                                                      sourceentity.m_5446_(),
                                                      sourceentity.m_9236_().m_7654_(),
                                                      sourceentity
                                                   ),
                                                   "particle minecraft:squid_ink ^-1.5 ^21 ^ 0.5 0.5 0.5 0.2 100 force"
                                                );
                                          }

                                          if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                                             _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7654_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         new Vec3(x, y, z),
                                                         Vec2.f_82462_,
                                                         _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx,
                                                         4,
                                                         "",
                                                         Component.m_237113_(""),
                                                         _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7654_(),
                                                         null
                                                      )
                                                      .m_81324_(),
                                                   "effect give @e[distance=..30] minecraft:wither 8 1"
                                                );
                                          }

                                          if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                                             _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7654_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                         CommandSource.f_80164_,
                                                         new Vec3(x, y, z),
                                                         Vec2.f_82462_,
                                                         _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx,
                                                         4,
                                                         "",
                                                         Component.m_237113_(""),
                                                         _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7654_(),
                                                         null
                                                      )
                                                      .m_81324_(),
                                                   "/effect give @e[distance=..30] minecraft:nausea 8 2"
                                                );
                                          }
                                       }
                                    );
                                 }
                              );
                           }
                        );
                     }
                  );
               }

               if ((sourceentity instanceof LivingEntity _livEntxxxxxxx ? _livEntxxxxxxx.m_21223_() : -1.0F) <= 250.0F) {
                  label967:
                  if (entity instanceof Zombie
                     && Math.random() < 0.05
                     && !world.m_6443_(Zombie.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                     if (sourceentity instanceof LivingEntity _livEnt679 && _livEnt679.m_21023_(MobEffects.f_19597_)) {
                        break label967;
                     }

                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19597_, 200, 100, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                              SoundSource.HOSTILE,
                              3.0F,
                              0.7F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                              SoundSource.HOSTILE,
                              3.0F,
                              0.7F,
                              false
                           );
                        }
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              1.5F,
                              0.7F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              1.5F,
                              0.7F,
                              false
                           );
                        }
                     }

                     if (sourceentity instanceof EmperorpodEntity) {
                        ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                     }

                     ExterminationMod.queueServerWork(1, () -> {
                        if (sourceentity instanceof EmperorpodEntity) {
                           ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                        }

                        ExterminationMod.queueServerWork(1, () -> {
                           if (sourceentity instanceof EmperorpodEntity) {
                              ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                           }
                        });
                     });
                     ExterminationMod.queueServerWork(
                        48,
                        () -> {
                           if (world.m_6443_(Zombie.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                              if (sourceentity instanceof EmperorpodEntity) {
                                 ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(40, () -> {
                                 if (sourceentity instanceof LivingEntity _entityx) {
                                    _entityx.m_21195_(MobEffects.f_19597_);
                                 }
                              });
                           }

                           if (!world.m_6443_(Zombie.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                              if (sourceentity instanceof EmperorpodEntity) {
                                 ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(
                                 5,
                                 () -> {
                                    if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                                       Entity entityToSpawn = ((EntityType)ExterminationModEntities.TENTACLE_ENTITY.get())
                                          .m_262496_(_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED);
                                       if (entityToSpawn != null) {
                                          entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                                       }
                                    }

                                    ExterminationMod.queueServerWork(
                                       32,
                                       () -> {
                                          if (!entity.m_9236_().m_5776_() && entity.m_20194_() != null) {
                                             entity.m_20194_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                      CommandSource.f_80164_,
                                                      entity.m_20182_(),
                                                      entity.m_20155_(),
                                                      entity.m_9236_() instanceof ServerLevel ? (ServerLevel)entity.m_9236_() : null,
                                                      4,
                                                      entity.m_7755_().getString(),
                                                      entity.m_5446_(),
                                                      entity.m_9236_().m_7654_(),
                                                      entity
                                                   ),
                                                   "/kill @e[type=extermination:tentacle_entity,distance=..2]"
                                                );
                                          }
                                       }
                                    );
                                 }
                              );
                              ExterminationMod.queueServerWork(50, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                    }

                                    ExterminationMod.queueServerWork(1, () -> {
                                       if (sourceentity instanceof EmperorpodEntity) {
                                          ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                       }
                                    });
                                 });
                                 ExterminationMod.queueServerWork(40, () -> {
                                    if (sourceentity instanceof LivingEntity _entityx) {
                                       _entityx.m_21195_(MobEffects.f_19597_);
                                    }
                                 });
                              });
                           }
                        }
                     );
                  }

                  label956:
                  if (entity instanceof Villager
                     && Math.random() < 0.05
                     && !world.m_6443_(Villager.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                     if (sourceentity instanceof LivingEntity _livEnt717 && _livEnt717.m_21023_(MobEffects.f_19597_)) {
                        break label956;
                     }

                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19597_, 200, 100, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                              SoundSource.HOSTILE,
                              3.0F,
                              0.7F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                              SoundSource.HOSTILE,
                              3.0F,
                              0.7F,
                              false
                           );
                        }
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              1.5F,
                              0.7F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              1.5F,
                              0.7F,
                              false
                           );
                        }
                     }

                     if (sourceentity instanceof EmperorpodEntity) {
                        ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                     }

                     ExterminationMod.queueServerWork(1, () -> {
                        if (sourceentity instanceof EmperorpodEntity) {
                           ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                        }

                        ExterminationMod.queueServerWork(1, () -> {
                           if (sourceentity instanceof EmperorpodEntity) {
                              ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                           }
                        });
                     });
                     ExterminationMod.queueServerWork(
                        48,
                        () -> {
                           if (world.m_6443_(Villager.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                              if (sourceentity instanceof EmperorpodEntity) {
                                 ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(40, () -> {
                                 if (sourceentity instanceof LivingEntity _entityx) {
                                    _entityx.m_21195_(MobEffects.f_19597_);
                                 }
                              });
                           }

                           if (!world.m_6443_(Villager.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                              if (sourceentity instanceof EmperorpodEntity) {
                                 ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(
                                 5,
                                 () -> {
                                    if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                                       Entity entityToSpawn = ((EntityType)ExterminationModEntities.TENTACLE_ENTITY.get())
                                          .m_262496_(
                                             _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED
                                          );
                                       if (entityToSpawn != null) {
                                          entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                                       }
                                    }

                                    ExterminationMod.queueServerWork(
                                       32,
                                       () -> {
                                          if (!entity.m_9236_().m_5776_() && entity.m_20194_() != null) {
                                             entity.m_20194_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                      CommandSource.f_80164_,
                                                      entity.m_20182_(),
                                                      entity.m_20155_(),
                                                      entity.m_9236_() instanceof ServerLevel ? (ServerLevel)entity.m_9236_() : null,
                                                      4,
                                                      entity.m_7755_().getString(),
                                                      entity.m_5446_(),
                                                      entity.m_9236_().m_7654_(),
                                                      entity
                                                   ),
                                                   "/kill @e[type=extermination:tentacle_entity,distance=..2]"
                                                );
                                          }
                                       }
                                    );
                                 }
                              );
                              ExterminationMod.queueServerWork(50, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                    }

                                    ExterminationMod.queueServerWork(1, () -> {
                                       if (sourceentity instanceof EmperorpodEntity) {
                                          ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                       }
                                    });
                                 });
                                 ExterminationMod.queueServerWork(40, () -> {
                                    if (sourceentity instanceof LivingEntity _entityx) {
                                       _entityx.m_21195_(MobEffects.f_19597_);
                                    }
                                 });
                              });
                           }
                        }
                     );
                  }

                  label945:
                  if (entity instanceof Player
                     && Math.random() < 0.05
                     && !world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                     if (sourceentity instanceof LivingEntity _livEnt755 && _livEnt755.m_21023_(MobEffects.f_19597_)) {
                        break label945;
                     }

                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19597_, 200, 100, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                              SoundSource.HOSTILE,
                              3.0F,
                              0.7F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                              SoundSource.HOSTILE,
                              3.0F,
                              0.7F,
                              false
                           );
                        }
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              1.5F,
                              0.7F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              1.5F,
                              0.7F,
                              false
                           );
                        }
                     }

                     if (sourceentity instanceof EmperorpodEntity) {
                        ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                     }

                     ExterminationMod.queueServerWork(1, () -> {
                        if (sourceentity instanceof EmperorpodEntity) {
                           ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                        }

                        ExterminationMod.queueServerWork(1, () -> {
                           if (sourceentity instanceof EmperorpodEntity) {
                              ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                           }
                        });
                     });
                     ExterminationMod.queueServerWork(
                        48,
                        () -> {
                           if (world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                              if (sourceentity instanceof EmperorpodEntity) {
                                 ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(40, () -> {
                                 if (sourceentity instanceof LivingEntity _entityx) {
                                    _entityx.m_21195_(MobEffects.f_19597_);
                                 }
                              });
                           }

                           if (!world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                              if (sourceentity instanceof EmperorpodEntity) {
                                 ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(
                                 5,
                                 () -> {
                                    if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                                       Entity entityToSpawn = ((EntityType)ExterminationModEntities.TENTACLE_ENTITY.get())
                                          .m_262496_(
                                             _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED
                                          );
                                       if (entityToSpawn != null) {
                                          entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                                       }
                                    }

                                    ExterminationMod.queueServerWork(
                                       32,
                                       () -> {
                                          if (!entity.m_9236_().m_5776_() && entity.m_20194_() != null) {
                                             entity.m_20194_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                      CommandSource.f_80164_,
                                                      entity.m_20182_(),
                                                      entity.m_20155_(),
                                                      entity.m_9236_() instanceof ServerLevel ? (ServerLevel)entity.m_9236_() : null,
                                                      4,
                                                      entity.m_7755_().getString(),
                                                      entity.m_5446_(),
                                                      entity.m_9236_().m_7654_(),
                                                      entity
                                                   ),
                                                   "/kill @e[type=extermination:tentacle_entity,distance=..2]"
                                                );
                                          }
                                       }
                                    );
                                 }
                              );
                              ExterminationMod.queueServerWork(50, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                    }

                                    ExterminationMod.queueServerWork(1, () -> {
                                       if (sourceentity instanceof EmperorpodEntity) {
                                          ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                       }
                                    });
                                 });
                                 ExterminationMod.queueServerWork(40, () -> {
                                    if (sourceentity instanceof LivingEntity _entityx) {
                                       _entityx.m_21195_(MobEffects.f_19597_);
                                    }
                                 });
                              });
                           }
                        }
                     );
                  }

                  if (entity instanceof Animal
                     && Math.random() < 0.05
                     && !world.m_6443_(Animal.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                     if (sourceentity instanceof LivingEntity _livEnt793 && _livEnt793.m_21023_(MobEffects.f_19597_)) {
                        return;
                     }

                     if (sourceentity instanceof LivingEntity _entity && !_entity.m_9236_().m_5776_()) {
                        _entity.m_7292_(new MobEffectInstance(MobEffects.f_19597_, 200, 100, false, false));
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                              SoundSource.HOSTILE,
                              3.0F,
                              0.7F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                              SoundSource.HOSTILE,
                              3.0F,
                              0.7F,
                              false
                           );
                        }
                     }

                     if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                        if (!_levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5776_()) {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_5594_(
                              null,
                              BlockPos.m_274561_(x, y, z),
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              1.5F,
                              0.7F
                           );
                        } else {
                           _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.m_7785_(
                              x,
                              y,
                              z,
                              (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.gas")),
                              SoundSource.HOSTILE,
                              1.5F,
                              0.7F,
                              false
                           );
                        }
                     }

                     if (sourceentity instanceof EmperorpodEntity) {
                        ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                     }

                     ExterminationMod.queueServerWork(1, () -> {
                        if (sourceentity instanceof EmperorpodEntity) {
                           ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                        }

                        ExterminationMod.queueServerWork(1, () -> {
                           if (sourceentity instanceof EmperorpodEntity) {
                              ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.down");
                           }
                        });
                     });
                     ExterminationMod.queueServerWork(
                        48,
                        () -> {
                           if (world.m_6443_(Animal.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                              if (sourceentity instanceof EmperorpodEntity) {
                                 ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(40, () -> {
                                 if (sourceentity instanceof LivingEntity _entityx) {
                                    _entityx.m_21195_(MobEffects.f_19597_);
                                 }
                              });
                           }

                           if (!world.m_6443_(Animal.class, AABB.m_165882_(new Vec3(x, y, z), 30.0, 30.0, 30.0), e -> true).isEmpty()) {
                              if (sourceentity instanceof EmperorpodEntity) {
                                 ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                              }

                              ExterminationMod.queueServerWork(1, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.tentacles");
                                    }
                                 });
                              });
                              ExterminationMod.queueServerWork(
                                 5,
                                 () -> {
                                    if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) {
                                       Entity entityToSpawn = ((EntityType)ExterminationModEntities.TENTACLE_ENTITY.get())
                                          .m_262496_(
                                             _levelxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, BlockPos.m_274561_(x, y, z), MobSpawnType.MOB_SUMMONED
                                          );
                                       if (entityToSpawn != null) {
                                          entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                                       }
                                    }

                                    ExterminationMod.queueServerWork(
                                       32,
                                       () -> {
                                          if (!entity.m_9236_().m_5776_() && entity.m_20194_() != null) {
                                             entity.m_20194_()
                                                .m_129892_()
                                                .m_230957_(
                                                   new CommandSourceStack(
                                                      CommandSource.f_80164_,
                                                      entity.m_20182_(),
                                                      entity.m_20155_(),
                                                      entity.m_9236_() instanceof ServerLevel ? (ServerLevel)entity.m_9236_() : null,
                                                      4,
                                                      entity.m_7755_().getString(),
                                                      entity.m_5446_(),
                                                      entity.m_9236_().m_7654_(),
                                                      entity
                                                   ),
                                                   "/kill @e[type=extermination:tentacle_entity,distance=..2]"
                                                );
                                          }
                                       }
                                    );
                                 }
                              );
                              ExterminationMod.queueServerWork(50, () -> {
                                 if (sourceentity instanceof EmperorpodEntity) {
                                    ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                 }

                                 ExterminationMod.queueServerWork(1, () -> {
                                    if (sourceentity instanceof EmperorpodEntity) {
                                       ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                    }

                                    ExterminationMod.queueServerWork(1, () -> {
                                       if (sourceentity instanceof EmperorpodEntity) {
                                          ((EmperorpodEntity)sourceentity).setAnimation("animation.emperorpod.up");
                                       }
                                    });
                                 });
                                 ExterminationMod.queueServerWork(40, () -> {
                                    if (sourceentity instanceof LivingEntity _entityx) {
                                       _entityx.m_21195_(MobEffects.f_19597_);
                                    }
                                 });
                              });
                           }
                        }
                     );
                  }
               }
            }
         }
      }
   }
}

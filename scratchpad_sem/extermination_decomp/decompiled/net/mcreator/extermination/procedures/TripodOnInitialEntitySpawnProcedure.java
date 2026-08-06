package net.mcreator.extermination.procedures;

import net.mcreator.extermination.ExterminationMod;
import net.mcreator.extermination.entity.TripodSpawnEntity;
import net.mcreator.extermination.init.ExterminationModEntities;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

public class TripodOnInitialEntitySpawnProcedure {
   public static void execute(LevelAccessor world, double x, double y, double z, Entity entity) {
      if (entity != null) {
         if (world instanceof ServerLevel _level) {
            _level.m_7654_()
               .m_129892_()
               .m_230957_(
                  new CommandSourceStack(
                        CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _level, 4, "", Component.m_237113_(""), _level.m_7654_(), null
                     )
                     .m_81324_(),
                  "particle minecraft:campfire_cosy_smoke ~ ~100 ~ 8 1 8 0.01 1000 force"
               );
         }

         ExterminationMod.queueServerWork(
            10,
            () -> {
               if (world instanceof ServerLevel _levelx) {
                  _levelx.m_7654_()
                     .m_129892_()
                     .m_230957_(
                        new CommandSourceStack(
                              CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelx, 4, "", Component.m_237113_(""), _levelx.m_7654_(), null
                           )
                           .m_81324_(),
                        "particle minecraft:campfire_cosy_smoke ~ ~100 ~ 8 1 8 0.01 3000 force"
                     );
               }

               ExterminationMod.queueServerWork(
                  10,
                  () -> {
                     if (world instanceof ServerLevel _levelxx) {
                        _levelxx.m_7654_()
                           .m_129892_()
                           .m_230957_(
                              new CommandSourceStack(
                                    CommandSource.f_80164_,
                                    new Vec3(x, y, z),
                                    Vec2.f_82462_,
                                    _levelxx,
                                    4,
                                    "",
                                    Component.m_237113_(""),
                                    _levelxx.m_7654_(),
                                    null
                                 )
                                 .m_81324_(),
                              "particle minecraft:campfire_cosy_smoke ~ ~100 ~ 8 1 8 0.01 5000 force"
                           );
                     }

                     ExterminationMod.queueServerWork(
                        20,
                        () -> {
                           if (world instanceof ServerLevel _levelxxx) {
                              _levelxxx.m_7654_()
                                 .m_129892_()
                                 .m_230957_(
                                    new CommandSourceStack(
                                          CommandSource.f_80164_,
                                          new Vec3(x, y, z),
                                          Vec2.f_82462_,
                                          _levelxxx,
                                          4,
                                          "",
                                          Component.m_237113_(""),
                                          _levelxxx.m_7654_(),
                                          null
                                       )
                                       .m_81324_(),
                                    "particle minecraft:campfire_cosy_smoke ~ ~100 ~ 8 1 8 0.01 5000 force"
                                 );
                           }

                           ExterminationMod.queueServerWork(
                              20,
                              () -> {
                                 if (world instanceof ServerLevel _levelxxxxx) {
                                    _levelxxxxx.m_7654_()
                                       .m_129892_()
                                       .m_230957_(
                                          new CommandSourceStack(
                                                CommandSource.f_80164_,
                                                new Vec3(x, y, z),
                                                Vec2.f_82462_,
                                                _levelxxxxx,
                                                4,
                                                "",
                                                Component.m_237113_(""),
                                                _levelxxxxx.m_7654_(),
                                                null
                                             )
                                             .m_81324_(),
                                          "particle minecraft:campfire_cosy_smoke ~ ~100 ~ 8 1 8 0.01 5000 force"
                                       );
                                 }

                                 if (world instanceof ServerLevel _levelxxxx) {
                                    Entity entityToSpawn = EntityType.f_20465_
                                       .m_262496_(
                                          _levelxxxx, BlockPos.m_274561_(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()), MobSpawnType.MOB_SUMMONED
                                       );
                                    if (entityToSpawn != null) {
                                       entityToSpawn.m_20334_(0.0, 0.0, 0.0);
                                    }
                                 }

                                 ExterminationMod.queueServerWork(
                                    1,
                                    () -> {
                                       if (world instanceof ServerLevel _levelxxxxxx) {
                                          _levelxxxxxx.m_7654_()
                                             .m_129892_()
                                             .m_230957_(
                                                new CommandSourceStack(
                                                      CommandSource.f_80164_,
                                                      new Vec3(x, y, z),
                                                      Vec2.f_82462_,
                                                      _levelxxxxxx,
                                                      4,
                                                      "",
                                                      Component.m_237113_(""),
                                                      _levelxxxxxx.m_7654_(),
                                                      null
                                                   )
                                                   .m_81324_(),
                                                "/stopsound @a weather minecraft:entity.lightning_bolt.thunder"
                                             );
                                       }

                                       ExterminationMod.queueServerWork(
                                          1,
                                          () -> {
                                             if (world instanceof Level _levelxxxxxxx) {
                                                if (!_levelxxxxxxx.m_5776_()) {
                                                   _levelxxxxxxx.m_5594_(
                                                      null,
                                                      BlockPos.m_274561_(x, y, z),
                                                      (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("entity.lightning_bolt.thunder")),
                                                      SoundSource.HOSTILE,
                                                      8.0F,
                                                      0.0F
                                                   );
                                                } else {
                                                   _levelxxxxxxx.m_7785_(
                                                      x,
                                                      y,
                                                      z,
                                                      (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("entity.lightning_bolt.thunder")),
                                                      SoundSource.HOSTILE,
                                                      8.0F,
                                                      0.0F,
                                                      false
                                                   );
                                                }
                                             }

                                             ExterminationMod.queueServerWork(
                                                40,
                                                () -> {
                                                   if (world instanceof ServerLevel _levelxxxxxxxx) {
                                                      Entity entityToSpawnx = EntityType.f_20465_
                                                         .m_262496_(
                                                            _levelxxxxxxxx,
                                                            BlockPos.m_274561_(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()),
                                                            MobSpawnType.MOB_SUMMONED
                                                         );
                                                      if (entityToSpawnx != null) {
                                                         entityToSpawnx.m_20334_(0.0, 0.0, 0.0);
                                                      }
                                                   }

                                                   ExterminationMod.queueServerWork(
                                                      1,
                                                      () -> {
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
                                                                  "/stopsound @a weather minecraft:entity.lightning_bolt.thunder"
                                                               );
                                                         }

                                                         ExterminationMod.queueServerWork(
                                                            1,
                                                            () -> {
                                                               if (world instanceof Level _levelxxxxxxxxxx) {
                                                                  if (!_levelxxxxxxxxxx.m_5776_()) {
                                                                     _levelxxxxxxxxxx.m_5594_(
                                                                        null,
                                                                        BlockPos.m_274561_(x, y, z),
                                                                        (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                           .getValue(new ResourceLocation("entity.lightning_bolt.thunder")),
                                                                        SoundSource.HOSTILE,
                                                                        8.0F,
                                                                        0.0F
                                                                     );
                                                                  } else {
                                                                     _levelxxxxxxxxxx.m_7785_(
                                                                        x,
                                                                        y,
                                                                        z,
                                                                        (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                           .getValue(new ResourceLocation("entity.lightning_bolt.thunder")),
                                                                        SoundSource.HOSTILE,
                                                                        8.0F,
                                                                        0.0F,
                                                                        false
                                                                     );
                                                                  }
                                                               }

                                                               ExterminationMod.queueServerWork(
                                                                  40,
                                                                  () -> {
                                                                     if (world instanceof ServerLevel _levelxxxxxxxxxxx) {
                                                                        Entity entityToSpawnxx = EntityType.f_20465_
                                                                           .m_262496_(
                                                                              _levelxxxxxxxxxxx,
                                                                              BlockPos.m_274561_(entity.m_20185_(), entity.m_20186_(), entity.m_20189_()),
                                                                              MobSpawnType.MOB_SUMMONED
                                                                           );
                                                                        if (entityToSpawnxx != null) {
                                                                           entityToSpawnxx.m_20334_(0.0, 0.0, 0.0);
                                                                        }
                                                                     }

                                                                     ExterminationMod.queueServerWork(
                                                                        1,
                                                                        () -> {
                                                                           if (world instanceof ServerLevel _levelxxxxxxxxxxxx) {
                                                                              _levelxxxxxxxxxxxx.m_7654_()
                                                                                 .m_129892_()
                                                                                 .m_230957_(
                                                                                    new CommandSourceStack(
                                                                                          CommandSource.f_80164_,
                                                                                          new Vec3(x, y, z),
                                                                                          Vec2.f_82462_,
                                                                                          _levelxxxxxxxxxxxx,
                                                                                          4,
                                                                                          "",
                                                                                          Component.m_237113_(""),
                                                                                          _levelxxxxxxxxxxxx.m_7654_(),
                                                                                          null
                                                                                       )
                                                                                       .m_81324_(),
                                                                                    "/stopsound @a weather minecraft:entity.lightning_bolt.thunder"
                                                                                 );
                                                                           }

                                                                           ExterminationMod.queueServerWork(
                                                                              1,
                                                                              () -> {
                                                                                 if (world instanceof Level _levelxxxxxxxxxxxxx) {
                                                                                    if (!_levelxxxxxxxxxxxxx.m_5776_()) {
                                                                                       _levelxxxxxxxxxxxxx.m_5594_(
                                                                                          null,
                                                                                          BlockPos.m_274561_(x, y, z),
                                                                                          (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                                             .getValue(new ResourceLocation("entity.lightning_bolt.thunder")),
                                                                                          SoundSource.HOSTILE,
                                                                                          8.0F,
                                                                                          0.0F
                                                                                       );
                                                                                    } else {
                                                                                       _levelxxxxxxxxxxxxx.m_7785_(
                                                                                          x,
                                                                                          y,
                                                                                          z,
                                                                                          (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                                             .getValue(new ResourceLocation("entity.lightning_bolt.thunder")),
                                                                                          SoundSource.HOSTILE,
                                                                                          8.0F,
                                                                                          0.0F,
                                                                                          false
                                                                                       );
                                                                                    }
                                                                                 }

                                                                                 if (Math.random() < 0.7) {
                                                                                    ExterminationMod.queueServerWork(
                                                                                       40,
                                                                                       () -> {
                                                                                          if (world instanceof ServerLevel _levelxxxxxxxxxxxxxx) {
                                                                                             Entity entityToSpawnxxx = EntityType.f_20465_
                                                                                                .m_262496_(
                                                                                                   _levelxxxxxxxxxxxxxx,
                                                                                                   BlockPos.m_274561_(
                                                                                                      entity.m_20185_(), entity.m_20186_(), entity.m_20189_()
                                                                                                   ),
                                                                                                   MobSpawnType.MOB_SUMMONED
                                                                                                );
                                                                                             if (entityToSpawnxxx != null) {
                                                                                                entityToSpawnxxx.m_20334_(0.0, 0.0, 0.0);
                                                                                             }
                                                                                          }

                                                                                          ExterminationMod.queueServerWork(
                                                                                             1,
                                                                                             () -> {
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
                                                                                                         "/stopsound @a weather minecraft:entity.lightning_bolt.thunder"
                                                                                                      );
                                                                                                }

                                                                                                ExterminationMod.queueServerWork(
                                                                                                   1,
                                                                                                   () -> {
                                                                                                      if (world instanceof Level _levelxxxxxxxxxxxxxxxx) {
                                                                                                         if (!_levelxxxxxxxxxxxxxxxx.m_5776_()) {
                                                                                                            _levelxxxxxxxxxxxxxxxx.m_5594_(
                                                                                                               null,
                                                                                                               BlockPos.m_274561_(x, y, z),
                                                                                                               (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                                                                  .getValue(
                                                                                                                     new ResourceLocation(
                                                                                                                        "entity.lightning_bolt.thunder"
                                                                                                                     )
                                                                                                                  ),
                                                                                                               SoundSource.HOSTILE,
                                                                                                               8.0F,
                                                                                                               0.0F
                                                                                                            );
                                                                                                         } else {
                                                                                                            _levelxxxxxxxxxxxxxxxx.m_7785_(
                                                                                                               x,
                                                                                                               y,
                                                                                                               z,
                                                                                                               (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                                                                  .getValue(
                                                                                                                     new ResourceLocation(
                                                                                                                        "entity.lightning_bolt.thunder"
                                                                                                                     )
                                                                                                                  ),
                                                                                                               SoundSource.HOSTILE,
                                                                                                               8.0F,
                                                                                                               0.0F,
                                                                                                               false
                                                                                                            );
                                                                                                         }
                                                                                                      }

                                                                                                      if (Math.random() < 0.7) {
                                                                                                         ExterminationMod.queueServerWork(
                                                                                                            40,
                                                                                                            () -> {
                                                                                                               if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxx
                                                                                                                  )
                                                                                                                {
                                                                                                                  Entity entityToSpawnxxxx = EntityType.f_20465_
                                                                                                                     .m_262496_(
                                                                                                                        _levelxxxxxxxxxxxxxxxxx,
                                                                                                                        BlockPos.m_274561_(
                                                                                                                           entity.m_20185_(),
                                                                                                                           entity.m_20186_(),
                                                                                                                           entity.m_20189_()
                                                                                                                        ),
                                                                                                                        MobSpawnType.MOB_SUMMONED
                                                                                                                     );
                                                                                                                  if (entityToSpawnxxxx != null) {
                                                                                                                     entityToSpawnxxxx.m_20334_(0.0, 0.0, 0.0);
                                                                                                                  }
                                                                                                               }

                                                                                                               ExterminationMod.queueServerWork(
                                                                                                                  1,
                                                                                                                  () -> {
                                                                                                                     if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxx
                                                                                                                        )
                                                                                                                      {
                                                                                                                        _levelxxxxxxxxxxxxxxxxxx.m_7654_()
                                                                                                                           .m_129892_()
                                                                                                                           .m_230957_(
                                                                                                                              new CommandSourceStack(
                                                                                                                                    CommandSource.f_80164_,
                                                                                                                                    new Vec3(x, y, z),
                                                                                                                                    Vec2.f_82462_,
                                                                                                                                    _levelxxxxxxxxxxxxxxxxxx,
                                                                                                                                    4,
                                                                                                                                    "",
                                                                                                                                    Component.m_237113_(""),
                                                                                                                                    _levelxxxxxxxxxxxxxxxxxx.m_7654_(),
                                                                                                                                    null
                                                                                                                                 )
                                                                                                                                 .m_81324_(),
                                                                                                                              "/stopsound @a weather minecraft:entity.lightning_bolt.thunder"
                                                                                                                           );
                                                                                                                     }

                                                                                                                     ExterminationMod.queueServerWork(
                                                                                                                        1,
                                                                                                                        () -> {
                                                                                                                           if (world instanceof Level _levelxxxxxxxxxxxxxxxxxxx
                                                                                                                              )
                                                                                                                            {
                                                                                                                              if (!_levelxxxxxxxxxxxxxxxxxxx.m_5776_()
                                                                                                                                 )
                                                                                                                               {
                                                                                                                                 _levelxxxxxxxxxxxxxxxxxxx.m_5594_(
                                                                                                                                    null,
                                                                                                                                    BlockPos.m_274561_(x, y, z),
                                                                                                                                    (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                                                                                       .getValue(
                                                                                                                                          new ResourceLocation(
                                                                                                                                             "entity.lightning_bolt.thunder"
                                                                                                                                          )
                                                                                                                                       ),
                                                                                                                                    SoundSource.HOSTILE,
                                                                                                                                    8.0F,
                                                                                                                                    0.0F
                                                                                                                                 );
                                                                                                                              } else {
                                                                                                                                 _levelxxxxxxxxxxxxxxxxxxx.m_7785_(
                                                                                                                                    x,
                                                                                                                                    y,
                                                                                                                                    z,
                                                                                                                                    (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                                                                                       .getValue(
                                                                                                                                          new ResourceLocation(
                                                                                                                                             "entity.lightning_bolt.thunder"
                                                                                                                                          )
                                                                                                                                       ),
                                                                                                                                    SoundSource.HOSTILE,
                                                                                                                                    8.0F,
                                                                                                                                    0.0F,
                                                                                                                                    false
                                                                                                                                 );
                                                                                                                              }
                                                                                                                           }
                                                                                                                        }
                                                                                                                     );
                                                                                                                  }
                                                                                                               );
                                                                                                            }
                                                                                                         );
                                                                                                      }
                                                                                                   }
                                                                                                );
                                                                                             }
                                                                                          );
                                                                                       }
                                                                                    );
                                                                                 }

                                                                                 ExterminationMod.queueServerWork(
                                                                                    160,
                                                                                    () -> {
                                                                                       if (entity instanceof TripodSpawnEntity) {
                                                                                          ((TripodSpawnEntity)entity)
                                                                                             .setAnimation("animation.tripod_invaders.spawn");
                                                                                       }

                                                                                       ExterminationMod.queueServerWork(1, () -> {
                                                                                          if (entity instanceof TripodSpawnEntity) {
                                                                                             ((TripodSpawnEntity)entity)
                                                                                                .setAnimation("animation.tripod_invaders.spawn");
                                                                                          }

                                                                                          ExterminationMod.queueServerWork(1, () -> {
                                                                                             if (entity instanceof TripodSpawnEntity) {
                                                                                                ((TripodSpawnEntity)entity)
                                                                                                   .setAnimation("animation.tripod_invaders.spawn");
                                                                                             }
                                                                                          });
                                                                                       });
                                                                                       if (world instanceof Level _levelxxxxxxxxxxxxxxx) {
                                                                                          if (!_levelxxxxxxxxxxxxxxx.m_5776_()) {
                                                                                             _levelxxxxxxxxxxxxxxx.m_5594_(
                                                                                                null,
                                                                                                BlockPos.m_274561_(x, y, z),
                                                                                                (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                                                   .getValue(
                                                                                                      new ResourceLocation("extermination:entity.tripod.spawn")
                                                                                                   ),
                                                                                                SoundSource.HOSTILE,
                                                                                                5.0F,
                                                                                                1.0F
                                                                                             );
                                                                                          } else {
                                                                                             _levelxxxxxxxxxxxxxxx.m_7785_(
                                                                                                x,
                                                                                                y,
                                                                                                z,
                                                                                                (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                                                   .getValue(
                                                                                                      new ResourceLocation("extermination:entity.tripod.spawn")
                                                                                                   ),
                                                                                                SoundSource.HOSTILE,
                                                                                                5.0F,
                                                                                                1.0F,
                                                                                                false
                                                                                             );
                                                                                          }
                                                                                       }

                                                                                       if (world instanceof ServerLevel _levelx) {
                                                                                          _levelx.m_7654_()
                                                                                             .m_129892_()
                                                                                             .m_230957_(
                                                                                                new CommandSourceStack(
                                                                                                      CommandSource.f_80164_,
                                                                                                      new Vec3(x, y, z),
                                                                                                      Vec2.f_82462_,
                                                                                                      _levelx,
                                                                                                      4,
                                                                                                      "",
                                                                                                      Component.m_237113_(""),
                                                                                                      _levelx.m_7654_(),
                                                                                                      null
                                                                                                   )
                                                                                                   .m_81324_(),
                                                                                                "/effect give @a[distance=..40] extermination:earthquake 12 0"
                                                                                             );
                                                                                       }

                                                                                       ExterminationMod.queueServerWork(
                                                                                          475,
                                                                                          () -> {
                                                                                             if (entity.m_6084_()
                                                                                                && world instanceof Level _levelxxxxxxxxxxxxxxxx) {
                                                                                                if (!_levelxxxxxxxxxxxxxxxx.m_5776_()) {
                                                                                                   _levelxxxxxxxxxxxxxxxx.m_5594_(
                                                                                                      null,
                                                                                                      BlockPos.m_274561_(x, y, z),
                                                                                                      (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                                                         .getValue(
                                                                                                            new ResourceLocation(
                                                                                                               "extermination:entity.tripod.horn"
                                                                                                            )
                                                                                                         ),
                                                                                                      SoundSource.HOSTILE,
                                                                                                      10.0F,
                                                                                                      1.0F
                                                                                                   );
                                                                                                } else {
                                                                                                   _levelxxxxxxxxxxxxxxxx.m_7785_(
                                                                                                      x,
                                                                                                      y,
                                                                                                      z,
                                                                                                      (SoundEvent)ForgeRegistries.SOUND_EVENTS
                                                                                                         .getValue(
                                                                                                            new ResourceLocation(
                                                                                                               "extermination:entity.tripod.horn"
                                                                                                            )
                                                                                                         ),
                                                                                                      SoundSource.HOSTILE,
                                                                                                      10.0F,
                                                                                                      1.0F,
                                                                                                      false
                                                                                                   );
                                                                                                }
                                                                                             }
                                                                                          }
                                                                                       );
                                                                                       ExterminationMod.queueServerWork(
                                                                                          600,
                                                                                          () -> {
                                                                                             if (entity.m_6084_()) {
                                                                                                if (!entity.m_9236_().m_5776_()) {
                                                                                                   entity.m_146870_();
                                                                                                }

                                                                                                if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxx) {
                                                                                                   Entity entityToSpawnxxx = ((EntityType)ExterminationModEntities.TRIPOD
                                                                                                         .get())
                                                                                                      .m_262496_(
                                                                                                         _levelxxxxxxxxxxxxxxxx,
                                                                                                         BlockPos.m_274561_(x, y, z),
                                                                                                         MobSpawnType.MOB_SUMMONED
                                                                                                      );
                                                                                                   if (entityToSpawnxxx != null) {
                                                                                                      entityToSpawnxxx.m_20334_(0.0, 0.0, 0.0);
                                                                                                   }
                                                                                                }
                                                                                             }
                                                                                          }
                                                                                       );
                                                                                       ExterminationMod.queueServerWork(
                                                                                          20,
                                                                                          () -> {
                                                                                             if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxx) {
                                                                                                _levelxxxxxxxxxxxxxxxx.m_7654_()
                                                                                                   .m_129892_()
                                                                                                   .m_230957_(
                                                                                                      new CommandSourceStack(
                                                                                                            CommandSource.f_80164_,
                                                                                                            new Vec3(x, y, z),
                                                                                                            Vec2.f_82462_,
                                                                                                            _levelxxxxxxxxxxxxxxxx,
                                                                                                            4,
                                                                                                            "",
                                                                                                            Component.m_237113_(""),
                                                                                                            _levelxxxxxxxxxxxxxxxx.m_7654_(),
                                                                                                            null
                                                                                                         )
                                                                                                         .m_81324_(),
                                                                                                      "/particle extermination:dirt_cloud ~ ~ ~ 4 2 4 0 2 force"
                                                                                                   );
                                                                                             }

                                                                                             ExterminationMod.queueServerWork(
                                                                                                8,
                                                                                                () -> {
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
                                                                                                            "/particle extermination:dirt_cloud ~ ~ ~ 4 2 4 0 2 force"
                                                                                                         );
                                                                                                   }

                                                                                                   ExterminationMod.queueServerWork(
                                                                                                      8,
                                                                                                      () -> {
                                                                                                         if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxx
                                                                                                            )
                                                                                                          {
                                                                                                            _levelxxxxxxxxxxxxxxxxxx.m_7654_()
                                                                                                               .m_129892_()
                                                                                                               .m_230957_(
                                                                                                                  new CommandSourceStack(
                                                                                                                        CommandSource.f_80164_,
                                                                                                                        new Vec3(x, y, z),
                                                                                                                        Vec2.f_82462_,
                                                                                                                        _levelxxxxxxxxxxxxxxxxxx,
                                                                                                                        4,
                                                                                                                        "",
                                                                                                                        Component.m_237113_(""),
                                                                                                                        _levelxxxxxxxxxxxxxxxxxx.m_7654_(),
                                                                                                                        null
                                                                                                                     )
                                                                                                                     .m_81324_(),
                                                                                                                  "/particle extermination:dirt_cloud ~ ~ ~ 4 2 4 0 2 force"
                                                                                                               );
                                                                                                         }

                                                                                                         ExterminationMod.queueServerWork(
                                                                                                            8,
                                                                                                            () -> {
                                                                                                               if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxx
                                                                                                                  )
                                                                                                                {
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
                                                                                                                        "/particle extermination:dirt_cloud ~ ~ ~ 4 2 4 0 2 force"
                                                                                                                     );
                                                                                                               }

                                                                                                               ExterminationMod.queueServerWork(
                                                                                                                  8,
                                                                                                                  () -> {
                                                                                                                     if (world instanceof ServerLevel _levelxxxxxxxxxxxxxxxxxxxx
                                                                                                                        )
                                                                                                                      {
                                                                                                                        _levelxxxxxxxxxxxxxxxxxxxx.m_7654_()
                                                                                                                           .m_129892_()
                                                                                                                           .m_230957_(
                                                                                                                              new CommandSourceStack(
                                                                                                                                    CommandSource.f_80164_,
                                                                                                                                    new Vec3(x, y, z),
                                                                                                                                    Vec2.f_82462_,
                                                                                                                                    _levelxxxxxxxxxxxxxxxxxxxx,
                                                                                                                                    4,
                                                                                                                                    "",
                                                                                                                                    Component.m_237113_(""),
                                                                                                                                    _levelxxxxxxxxxxxxxxxxxxxx.m_7654_(),
                                                                                                                                    null
                                                                                                                                 )
                                                                                                                                 .m_81324_(),
                                                                                                                              "/particle extermination:dirt_cloud ~ ~ ~ 4 2 4 0 2 force"
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
                                                                                       );
                                                                                       ExterminationMod.queueServerWork(
                                                                                          50,
                                                                                          () -> {
                                                                                             entity.m_20242_(true);
                                                                                             if (world.m_6106_().m_5470_().m_46207_(GameRules.f_46132_)) {
                                                                                                world.m_46961_(
                                                                                                   BlockPos.m_274561_(
                                                                                                      entity.m_20185_() + 0.0,
                                                                                                      entity.m_20186_() - 1.0,
                                                                                                      entity.m_20189_() + 0.0
                                                                                                   ),
                                                                                                   false
                                                                                                );
                                                                                                world.m_46961_(
                                                                                                   BlockPos.m_274561_(
                                                                                                      entity.m_20185_() + 0.0,
                                                                                                      entity.m_20186_() - 1.0,
                                                                                                      entity.m_20189_() + 1.0
                                                                                                   ),
                                                                                                   false
                                                                                                );
                                                                                                world.m_46961_(
                                                                                                   BlockPos.m_274561_(
                                                                                                      entity.m_20185_() + 0.0,
                                                                                                      entity.m_20186_() - 1.0,
                                                                                                      entity.m_20189_() - 2.0
                                                                                                   ),
                                                                                                   false
                                                                                                );
                                                                                                world.m_46961_(
                                                                                                   BlockPos.m_274561_(
                                                                                                      entity.m_20185_() + 2.0,
                                                                                                      entity.m_20186_() - 1.0,
                                                                                                      entity.m_20189_() + 2.0
                                                                                                   ),
                                                                                                   false
                                                                                                );
                                                                                                world.m_46961_(
                                                                                                   BlockPos.m_274561_(
                                                                                                      entity.m_20185_() - 1.0,
                                                                                                      entity.m_20186_() - 1.0,
                                                                                                      entity.m_20189_() + 3.0
                                                                                                   ),
                                                                                                   false
                                                                                                );
                                                                                                world.m_46961_(
                                                                                                   BlockPos.m_274561_(
                                                                                                      entity.m_20185_() + 1.0,
                                                                                                      entity.m_20186_() - 1.0,
                                                                                                      entity.m_20189_() + 0.0
                                                                                                   ),
                                                                                                   false
                                                                                                );
                                                                                                world.m_46961_(
                                                                                                   BlockPos.m_274561_(
                                                                                                      entity.m_20185_() - 4.0,
                                                                                                      entity.m_20186_() - 1.0,
                                                                                                      entity.m_20189_() + 0.0
                                                                                                   ),
                                                                                                   false
                                                                                                );
                                                                                                world.m_46961_(
                                                                                                   BlockPos.m_274561_(
                                                                                                      entity.m_20185_() + 4.0,
                                                                                                      entity.m_20186_() - 1.0,
                                                                                                      entity.m_20189_() - 1.0
                                                                                                   ),
                                                                                                   false
                                                                                                );
                                                                                                ExterminationMod.queueServerWork(
                                                                                                   20,
                                                                                                   () -> {
                                                                                                      if (world.m_6106_()
                                                                                                         .m_5470_()
                                                                                                         .m_46207_(GameRules.f_46132_)) {
                                                                                                         world.m_46961_(
                                                                                                            BlockPos.m_274561_(
                                                                                                               entity.m_20185_() + 1.0,
                                                                                                               entity.m_20186_() - 1.0,
                                                                                                               entity.m_20189_() + 4.0
                                                                                                            ),
                                                                                                            false
                                                                                                         );
                                                                                                         world.m_46961_(
                                                                                                            BlockPos.m_274561_(
                                                                                                               entity.m_20185_() + 5.0,
                                                                                                               entity.m_20186_() - 1.0,
                                                                                                               entity.m_20189_() + 1.0
                                                                                                            ),
                                                                                                            false
                                                                                                         );
                                                                                                         world.m_46961_(
                                                                                                            BlockPos.m_274561_(
                                                                                                               entity.m_20185_() - 2.0,
                                                                                                               entity.m_20186_() - 1.0,
                                                                                                               entity.m_20189_() + 1.0
                                                                                                            ),
                                                                                                            false
                                                                                                         );
                                                                                                         world.m_46961_(
                                                                                                            BlockPos.m_274561_(
                                                                                                               entity.m_20185_() - 3.0,
                                                                                                               entity.m_20186_() - 1.0,
                                                                                                               entity.m_20189_() + 2.0
                                                                                                            ),
                                                                                                            false
                                                                                                         );
                                                                                                         world.m_46961_(
                                                                                                            BlockPos.m_274561_(
                                                                                                               entity.m_20185_() - 2.0,
                                                                                                               entity.m_20186_() - 1.0,
                                                                                                               entity.m_20189_() - 2.0
                                                                                                            ),
                                                                                                            false
                                                                                                         );
                                                                                                         world.m_46961_(
                                                                                                            BlockPos.m_274561_(
                                                                                                               entity.m_20185_() - 1.0,
                                                                                                               entity.m_20186_() - 1.0,
                                                                                                               entity.m_20189_() - 4.0
                                                                                                            ),
                                                                                                            false
                                                                                                         );
                                                                                                         world.m_46961_(
                                                                                                            BlockPos.m_274561_(
                                                                                                               entity.m_20185_() + 2.0,
                                                                                                               entity.m_20186_() - 1.0,
                                                                                                               entity.m_20189_() - 3.0
                                                                                                            ),
                                                                                                            false
                                                                                                         );
                                                                                                         ExterminationMod.queueServerWork(
                                                                                                            20,
                                                                                                            () -> {
                                                                                                               if (world.m_6106_()
                                                                                                                  .m_5470_()
                                                                                                                  .m_46207_(GameRules.f_46132_)) {
                                                                                                                  world.m_46961_(
                                                                                                                     BlockPos.m_274561_(
                                                                                                                        entity.m_20185_() - 2.0,
                                                                                                                        entity.m_20186_() - 1.0,
                                                                                                                        entity.m_20189_() + 4.0
                                                                                                                     ),
                                                                                                                     false
                                                                                                                  );
                                                                                                                  world.m_46961_(
                                                                                                                     BlockPos.m_274561_(
                                                                                                                        entity.m_20185_() - 1.0,
                                                                                                                        entity.m_20186_() - 1.0,
                                                                                                                        entity.m_20189_() + 2.0
                                                                                                                     ),
                                                                                                                     false
                                                                                                                  );
                                                                                                                  world.m_46961_(
                                                                                                                     BlockPos.m_274561_(
                                                                                                                        entity.m_20185_() + 4.0,
                                                                                                                        entity.m_20186_() - 1.0,
                                                                                                                        entity.m_20189_() + 2.0
                                                                                                                     ),
                                                                                                                     false
                                                                                                                  );
                                                                                                                  world.m_46961_(
                                                                                                                     BlockPos.m_274561_(
                                                                                                                        entity.m_20185_() + 3.0,
                                                                                                                        entity.m_20186_() - 1.0,
                                                                                                                        entity.m_20189_() + 0.0
                                                                                                                     ),
                                                                                                                     false
                                                                                                                  );
                                                                                                                  world.m_46961_(
                                                                                                                     BlockPos.m_274561_(
                                                                                                                        entity.m_20185_() - 1.0,
                                                                                                                        entity.m_20186_() - 1.0,
                                                                                                                        entity.m_20189_() - 1.0
                                                                                                                     ),
                                                                                                                     false
                                                                                                                  );
                                                                                                                  world.m_46961_(
                                                                                                                     BlockPos.m_274561_(
                                                                                                                        entity.m_20185_() - 3.0,
                                                                                                                        entity.m_20186_() - 1.0,
                                                                                                                        entity.m_20189_() - 1.0
                                                                                                                     ),
                                                                                                                     false
                                                                                                                  );
                                                                                                                  world.m_46961_(
                                                                                                                     BlockPos.m_274561_(
                                                                                                                        entity.m_20185_() + 1.0,
                                                                                                                        entity.m_20186_() - 1.0,
                                                                                                                        entity.m_20189_() - 2.0
                                                                                                                     ),
                                                                                                                     false
                                                                                                                  );
                                                                                                                  world.m_46961_(
                                                                                                                     BlockPos.m_274561_(
                                                                                                                        entity.m_20185_() + 3.0,
                                                                                                                        entity.m_20186_() - 1.0,
                                                                                                                        entity.m_20189_() - 2.0
                                                                                                                     ),
                                                                                                                     false
                                                                                                                  );
                                                                                                                  world.m_46961_(
                                                                                                                     BlockPos.m_274561_(
                                                                                                                        entity.m_20185_() + 3.0,
                                                                                                                        entity.m_20186_() - 1.0,
                                                                                                                        entity.m_20189_() - 4.0
                                                                                                                     ),
                                                                                                                     false
                                                                                                                  );
                                                                                                                  world.m_46961_(
                                                                                                                     BlockPos.m_274561_(
                                                                                                                        entity.m_20185_() - 4.0,
                                                                                                                        entity.m_20186_() - 1.0,
                                                                                                                        entity.m_20189_() - 3.0
                                                                                                                     ),
                                                                                                                     false
                                                                                                                  );
                                                                                                               }
                                                                                                            }
                                                                                                         );
                                                                                                      }
                                                                                                   }
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
                                                               );
                                                            }
                                                         );
                                                      }
                                                   );
                                                }
                                             );
                                          }
                                       );
                                    }
                                 );
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

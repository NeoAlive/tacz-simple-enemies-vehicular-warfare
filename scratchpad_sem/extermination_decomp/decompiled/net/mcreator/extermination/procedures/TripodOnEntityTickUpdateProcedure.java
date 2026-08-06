package net.mcreator.extermination.procedures;

import net.mcreator.extermination.init.ExterminationModGameRules;
import net.mcreator.extermination.network.ExterminationModVariables.MapVariables;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

public class TripodOnEntityTickUpdateProcedure {
   public static void execute(LevelAccessor world, double x, double y, double z, Entity entity) {
      if (entity != null) {
         double distance = 0.0;
         entity.m_274367_(8.0F);
         if (entity instanceof LivingEntity _entity) {
            _entity.m_21195_(MobEffects.f_19615_);
         }

         if (entity.m_6084_()) {
            MapVariables.get(world).engine++;
            MapVariables.get(world).syncData(world);
            if (MapVariables.get(world).engine == 50.0) {
               MapVariables.get(world).engine = 0.0;
               MapVariables.get(world).syncData(world);
               if (world instanceof Level _level) {
                  if (!_level.m_5776_()) {
                     _level.m_5594_(
                        null,
                        BlockPos.m_274561_(x, y, z),
                        (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.engine.far")),
                        SoundSource.HOSTILE,
                        3.5F,
                        1.0F
                     );
                  } else {
                     _level.m_7785_(
                        x,
                        y,
                        z,
                        (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.engine.far")),
                        SoundSource.HOSTILE,
                        3.5F,
                        1.0F,
                        false
                     );
                  }
               }

               if (world instanceof Level _levelx) {
                  if (!_levelx.m_5776_()) {
                     _levelx.m_5594_(
                        null,
                        BlockPos.m_274561_(x, y, z),
                        (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.engine")),
                        SoundSource.HOSTILE,
                        1.0F,
                        1.0F
                     );
                  } else {
                     _levelx.m_7785_(
                        x,
                        y,
                        z,
                        (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.engine")),
                        SoundSource.HOSTILE,
                        1.0F,
                        1.0F,
                        false
                     );
                  }
               }
            }

            if (world.m_6106_().m_5470_().m_46207_(GameRules.f_46132_)) {
               if (world instanceof ServerLevel _levelxx) {
                  _levelxx.m_7654_()
                     .m_129892_()
                     .m_230957_(
                        new CommandSourceStack(
                              CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                           )
                           .m_81324_(),
                        "/fill ~5 ~20 ~5 ~-5 ~ ~-5 air replace #minecraft:leaves"
                     );
               }

               if (Math.random() < 0.01) {
                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #minecraft:leaves"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #minecraft:planks"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #minecraft:logs"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #minecraft:trapdoors"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #minecraft:doors"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #forge:glass"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #forge:glass_panes"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #minecraft:ice"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #forge:cobblestone"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #minecraft:dirt"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #minecraft:sand"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #minecraft:campfires"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #forge:fences"
                        );
                  }

                  if (world instanceof ServerLevel _levelxx) {
                     _levelxx.m_7654_()
                        .m_129892_()
                        .m_230957_(
                           new CommandSourceStack(
                                 CommandSource.f_80164_, new Vec3(x, y, z), Vec2.f_82462_, _levelxx, 4, "", Component.m_237113_(""), _levelxx.m_7654_(), null
                              )
                              .m_81324_(),
                           "/fill ~3 ~25 ~3 ~-3 ~ ~-3 air replace #forge:fence_gates"
                        );
                  }
               }
            }
         }

         if (world.m_6106_().m_5470_().m_46207_(ExterminationModGameRules.TRIPOD_DESPAWN)
            && world.m_6443_(ServerPlayer.class, AABB.m_165882_(new Vec3(x, y, z), 400.0, 400.0, 400.0), e -> true).isEmpty()
            && world.m_6443_(Player.class, AABB.m_165882_(new Vec3(x, y, z), 400.0, 400.0, 400.0), e -> true).isEmpty()
            && !entity.m_9236_().m_5776_()) {
            entity.m_146870_();
         }
      }
   }
}

package net.mcreator.extermination.procedures;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.registries.ForgeRegistries;

public class TripodIsHurtProcedure {
   public static void execute(LevelAccessor world, double x, double y, double z, Entity entity) {
      if (entity != null) {
         if (entity.m_6084_() && world instanceof Level _level) {
            if (!_level.m_5776_()) {
               _level.m_5594_(
                  null,
                  BlockPos.m_274561_(x, y, z),
                  (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                  SoundSource.HOSTILE,
                  3.0F,
                  1.0F
               );
            } else {
               _level.m_7785_(
                  x,
                  y,
                  z,
                  (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.hurt")),
                  SoundSource.HOSTILE,
                  3.0F,
                  1.0F,
                  false
               );
            }
         }
      }
   }
}

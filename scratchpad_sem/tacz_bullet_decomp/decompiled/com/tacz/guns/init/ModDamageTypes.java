package com.tacz.guns.init;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

public class ModDamageTypes {
   public static final ResourceKey<DamageType> BULLET = ResourceKey.m_135785_(Registries.f_268580_, new ResourceLocation("tacz", "bullet"));
   public static final ResourceKey<DamageType> BULLET_IGNORE_ARMOR = ResourceKey.m_135785_(
      Registries.f_268580_, new ResourceLocation("tacz", "bullet_ignore_armor")
   );
   public static final ResourceKey<DamageType> BULLET_VOID = ResourceKey.m_135785_(Registries.f_268580_, new ResourceLocation("tacz", "bullet_void"));
   public static final ResourceKey<DamageType> BULLET_VOID_IGNORE_ARMOR = ResourceKey.m_135785_(
      Registries.f_268580_, new ResourceLocation("tacz", "bullet_void_ignore_armor")
   );
   public static final TagKey<DamageType> BULLETS_TAG = TagKey.m_203882_(Registries.f_268580_, new ResourceLocation("tacz", "bullets"));

   public static class Sources {
      private static Reference<DamageType> getHolder(RegistryAccess access, ResourceKey<DamageType> damageTypeKey) {
         return access.m_175515_(Registries.f_268580_).m_246971_(damageTypeKey);
      }

      public static DamageSource bullet(RegistryAccess access, Entity bullet, Entity shooter, boolean ignoreArmor) {
         return new DamageSource(getHolder(access, ignoreArmor ? ModDamageTypes.BULLET_IGNORE_ARMOR : ModDamageTypes.BULLET), bullet, shooter);
      }

      public static DamageSource bulletVoid(RegistryAccess access, Entity bullet, Entity shooter, boolean ignoreArmor) {
         return new DamageSource(getHolder(access, ignoreArmor ? ModDamageTypes.BULLET_VOID_IGNORE_ARMOR : ModDamageTypes.BULLET_VOID), bullet, shooter);
      }
   }
}

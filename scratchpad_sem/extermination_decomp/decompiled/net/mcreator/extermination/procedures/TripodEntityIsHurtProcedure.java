package net.mcreator.extermination.procedures;

import net.mcreator.extermination.init.ExterminationModItems;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;

public class TripodEntityIsHurtProcedure {
   public static void execute(LevelAccessor world, double x, double y, double z, Entity entity, Entity sourceentity) {
      if (entity != null && sourceentity != null) {
         if (sourceentity instanceof Player
            && (
               (sourceentity instanceof LivingEntity _livEntx ? _livEntx.m_21206_() : ItemStack.f_41583_).m_41720_() instanceof PickaxeItem
                  || (sourceentity instanceof LivingEntity _livEnt ? _livEnt.m_21205_() : ItemStack.f_41583_).m_41720_() instanceof PickaxeItem
            )) {
            if (sourceentity instanceof ServerPlayer _player) {
               Advancement _adv = _player.f_8924_.m_129889_().m_136041_(new ResourceLocation("extermination:remnant_of_the_war"));
               AdvancementProgress _ap = _player.m_8960_().m_135996_(_adv);
               if (!_ap.m_8193_()) {
                  for (String criteria : _ap.m_8219_()) {
                     _player.m_8960_().m_135988_(_adv, criteria);
                  }
               }
            }

            if (Math.random() < 0.25) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Items.f_42416_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Items.f_42416_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.SCRAP_METAL.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            } else if (Math.random() < 0.18) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.SCRAP_WIRES.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.SCRAP_METAL.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.SCRAP_METAL.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            } else if (Math.random() < 0.15) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Blocks.f_50330_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.SCRAP_CIRCUIT.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            } else if (Math.random() < 0.12) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.ALIEN_BATTERY.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            } else if (Math.random() < 0.1) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.SCRAP_METAL.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.SCRAP_WIRES.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.SCRAP_WIRES.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.SCRAP_WIRES.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            } else if (Math.random() < 0.05) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.ALIEN_CHIP.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            } else if (Math.random() < 0.2) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack((ItemLike)ExterminationModItems.BROKEN_HEAT_WAY_ARM.get()));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            } else if (Math.random() < 0.2) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Blocks.f_50330_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Blocks.f_50058_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Blocks.f_50058_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Blocks.f_50058_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            } else if (Math.random() < 0.18) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Blocks.f_50075_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            } else if (Math.random() < 0.3) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Items.f_42451_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            } else if (Math.random() < 0.15) {
               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Blocks.f_50261_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               if (world instanceof ServerLevel _level) {
                  ItemEntity entityToSpawn = new ItemEntity(_level, x, y, z, new ItemStack(Items.f_42451_));
                  entityToSpawn.m_32010_(10);
                  _level.m_7967_(entityToSpawn);
               }

               entity.m_6469_(new DamageSource(world.m_9598_().m_175515_(Registries.f_268580_).m_246971_(DamageTypes.f_268433_)), 1.0F);
            }
         }
      }
   }
}

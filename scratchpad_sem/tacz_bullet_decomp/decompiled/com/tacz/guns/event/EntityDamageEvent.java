package com.tacz.guns.event;

import com.tacz.guns.init.ModAttributes;
import com.tacz.guns.init.ModDamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@EventBusSubscriber
public class EntityDamageEvent {
   @SubscribeEvent(
      priority = EventPriority.LOW
   )
   public static void onLivingHurt(LivingHurtEvent event) {
      if (event.getSource().m_269533_(ModDamageTypes.BULLETS_TAG)) {
         LivingEntity living = event.getEntity();
         AttributeInstance resistance = living.m_21051_((Attribute)ModAttributes.BULLET_RESISTANCE.get());
         if (resistance != null) {
            float modifiedDamage = event.getAmount() * (float)(1.0 - resistance.m_22135_());
            event.setAmount(modifiedDamage);
         }
      }
   }
}

package net.mcreator.extermination.init;

import net.mcreator.extermination.client.particle.BlastParticle;
import net.mcreator.extermination.client.particle.DirtCloudParticle;
import net.mcreator.extermination.client.particle.HeatRayBrighterParticle;
import net.mcreator.extermination.client.particle.HeatRayParticle;
import net.minecraft.core.particles.ParticleType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(
   bus = Bus.MOD,
   value = {Dist.CLIENT}
)
public class ExterminationModParticles {
   @SubscribeEvent
   public static void registerParticles(RegisterParticleProvidersEvent event) {
      event.registerSpriteSet((ParticleType)ExterminationModParticleTypes.BLAST.get(), BlastParticle::provider);
      event.registerSpriteSet((ParticleType)ExterminationModParticleTypes.DIRT_CLOUD.get(), DirtCloudParticle::provider);
      event.registerSpriteSet((ParticleType)ExterminationModParticleTypes.HEAT_RAY.get(), HeatRayParticle::provider);
      event.registerSpriteSet((ParticleType)ExterminationModParticleTypes.HEAT_RAY_BRIGHTER.get(), HeatRayBrighterParticle::provider);
   }
}

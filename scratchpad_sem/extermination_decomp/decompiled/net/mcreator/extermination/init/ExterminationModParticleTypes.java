package net.mcreator.extermination.init;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ExterminationModParticleTypes {
   public static final DeferredRegister<ParticleType<?>> REGISTRY = DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, "extermination");
   public static final RegistryObject<SimpleParticleType> BLAST = REGISTRY.register("blast", () -> new SimpleParticleType(true));
   public static final RegistryObject<SimpleParticleType> DIRT_CLOUD = REGISTRY.register("dirt_cloud", () -> new SimpleParticleType(true));
   public static final RegistryObject<SimpleParticleType> HEAT_RAY = REGISTRY.register("heat_ray", () -> new SimpleParticleType(true));
   public static final RegistryObject<SimpleParticleType> HEAT_RAY_BRIGHTER = REGISTRY.register("heat_ray_brighter", () -> new SimpleParticleType(true));
}

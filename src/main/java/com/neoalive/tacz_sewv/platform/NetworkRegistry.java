package com.neoalive.tacz_sewv.platform;

import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;

/**
 * Stand-in for Forge's {@code NetworkRegistry.newSimpleChannel}. NeoForge negotiates one version per
 * namespace and has no per-side acceptance predicates, so both must accept exactly the version
 * offered — which is the only form this mod ever used.
 */
public final class NetworkRegistry {

    private NetworkRegistry() {}

    public static SimpleChannel newSimpleChannel(ResourceLocation name, Supplier<String> version,
                                                 Predicate<String> clientAccepts, Predicate<String> serverAccepts) {
        String v = version.get();
        if (!clientAccepts.test(v) || !serverAccepts.test(v)) {
            throw new IllegalArgumentException("Channel " + name + " must accept its own version " + v);
        }
        return new SimpleChannel(name, v);
    }
}

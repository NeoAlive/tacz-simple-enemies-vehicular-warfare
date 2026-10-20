package com.neoalive.tacz_sewv.platform;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Stand-in for Forge's {@code DistExecutor} (removed in NeoForge). Keeps the double-supplier shape:
 * the outer lambda is only invoked on the matching side, so the class it names is never loaded on
 * the other one.
 */
public final class DistExecutor {

    private DistExecutor() {}

    public static void unsafeRunWhenOn(Dist dist, Supplier<Runnable> toRun) {
        if (FMLEnvironment.dist == dist) {
            toRun.get().run();
        }
    }

    public static <T> T unsafeCallWhenOn(Dist dist, Supplier<Callable<T>> toRun) {
        if (FMLEnvironment.dist != dist) return null;
        try {
            return toRun.get().call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

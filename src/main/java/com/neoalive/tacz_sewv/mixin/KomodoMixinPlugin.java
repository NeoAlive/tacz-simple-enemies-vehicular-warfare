package com.neoalive.tacz_sewv.mixin;

import java.util.List;
import java.util.Set;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Soft-compat gate for {@code tacz_sewv.komodo.mixins.json} — same discipline as
 * {@link XaeroMixinPlugin}: {@code "required": false} alone still queues the mixin and lets
 * Mixin's missing-target tolerance emit a Forge warning when Komodo is absent. Skipping
 * application here means it never enters the apply queue.
 *
 * <p>Uses {@link LoadingModList} (not {@code ModList}): mixin plugins run before the runtime mod
 * list is up. {@code MixinKmodoDormancy} targets Komodo by string name (no compile-time
 * dependency on Komodo's jar), so this plugin is the only place that needs Komodo's mod id.
 */
public final class KomodoMixinPlugin implements IMixinConfigPlugin {

    private static final String KOMODO = "komodo";

    private static final boolean LOADED =
            LoadingModList.get().getModFileById(KOMODO) != null;

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return LOADED;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

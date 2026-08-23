package com.neoalive.tacz_sewv.mixin;

import java.util.List;
import java.util.Set;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Shared body of the soft-compat mixin gates (Xaero / Komodo / PlayerRevive): each config is
 * {@code "required": false} in its own mixins JSON, but that alone still <em>queues</em> every
 * mixin and lets Mixin's missing-target tolerance emit a Forge warning when the mod is absent.
 * Skipping application here means those mixins never enter the apply queue.
 *
 * <p>Uses {@link LoadingModList} (not {@code ModList}): mixin plugins run before the runtime mod
 * list is up. Mixin instantiates each concrete plugin reflectively by FQCN, so subclasses keep
 * their exact names/packages and rely on the implicit public no-arg constructor — the only thing
 * they supply is the gated mod's id. The check runs lazily on first use because the base cannot
 * know the id until the instance exists; {@link LoadingModList} is already callable at that phase
 * (the old per-plugin static init proved it).
 */
public abstract class AbstractSoftDepMixinPlugin implements IMixinConfigPlugin {

    private Boolean loaded;

    /** The gated mod's id, exactly as it appears in mods.toml. */
    protected abstract String modId();

    private boolean loaded() {
        if (this.loaded == null) {
            this.loaded = LoadingModList.get().getModFileById(modId()) != null;
        }
        return this.loaded;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return loaded();
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

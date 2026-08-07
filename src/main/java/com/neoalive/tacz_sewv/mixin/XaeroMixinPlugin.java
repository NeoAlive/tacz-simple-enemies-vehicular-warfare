package com.neoalive.tacz_sewv.mixin;

import java.util.List;
import java.util.Set;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Soft-compat gate for {@code tacz_sewv.xaero.mixins.json}.
 *
 * <p>{@code "required": false} alone still <em>queues</em> every mixin and lets Mixin's missing-target
 * tolerance emit a Forge warning when Xaero's World Map is absent. Skipping application here means
 * those mixins never enter the apply queue — the same discipline as OpenPAC's runtime
 * {@code ModList} facade, just at mixin-load time.
 *
 * <p>Uses {@link LoadingModList} (not {@code ModList}): mixin plugins run before the runtime mod
 * list is up. Mod id is {@code xaeroworldmap} — Forge World Map only; Minimap ({@code xaerominimap})
 * does not ship {@code xaero.map.gui.GuiMap}.
 */
public final class XaeroMixinPlugin implements IMixinConfigPlugin {

    /** Same id as {@code XaeroMapCompat.MODID} / mods.toml — do not import that class (it names Xaero types). */
    private static final String XAERO_WORLD_MAP = "xaeroworldmap";

    private static final boolean LOADED =
            LoadingModList.get().getModFileById(XAERO_WORLD_MAP) != null;

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

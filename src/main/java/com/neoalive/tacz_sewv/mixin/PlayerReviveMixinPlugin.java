package com.neoalive.tacz_sewv.mixin;

import java.util.List;
import java.util.Set;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Soft-compat gate for {@code tacz_sewv.playerrevive.mixins.json} — same discipline as
 * {@link XaeroMixinPlugin}/{@link KomodoMixinPlugin}: {@code "required": false} alone still queues
 * the mixin and lets Mixin's missing-target tolerance emit a Forge warning when PlayerReviveMod is
 * absent. Skipping application here means it never enters the apply queue.
 *
 * <p>Uses {@link LoadingModList} (not {@code ModList}): mixin plugins run before the runtime mod
 * list is up. Mod id matches {@code PlayerReviveCompat.MODID} / mods.toml — do not import that
 * class here (it would force-load {@code team.creative.playerrevive.*} regardless of presence).
 */
public final class PlayerReviveMixinPlugin implements IMixinConfigPlugin {

    private static final String PLAYERREVIVE = "playerrevive";

    private static final boolean LOADED =
            LoadingModList.get().getModFileById(PLAYERREVIVE) != null;

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

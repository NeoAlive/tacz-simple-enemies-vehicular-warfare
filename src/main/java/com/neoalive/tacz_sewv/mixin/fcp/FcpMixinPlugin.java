package com.neoalive.tacz_sewv.mixin.fcp;

import java.util.List;
import java.util.Set;

import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gate for {@code tacz_sewv.fcp.mixins.json}: FCP present and 2.0.0 or newer. Older builds have
 * neither target class. Keep the floor in step with FcpCompat.MIN_VERSION (not imported here).
 */
public final class FcpMixinPlugin implements IMixinConfigPlugin {

    private static final DefaultArtifactVersion MIN = new DefaultArtifactVersion("2.0.0");

    private Boolean apply;

    private boolean apply() {
        if (this.apply == null) {
            boolean ok = false;
            try {
                ModFileInfo file = LoadingModList.get().getModFileById("fcp");
                if (file != null) {
                    ok = file.getMods().stream()
                            .filter(m -> "fcp".equals(m.getModId()))
                            .anyMatch(m -> m.getVersion().compareTo(MIN) >= 0);
                }
            } catch (Throwable ignored) {
                ok = false;
            }
            this.apply = ok;
        }
        return this.apply;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return apply();
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

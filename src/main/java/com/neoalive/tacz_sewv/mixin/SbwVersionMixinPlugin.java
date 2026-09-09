package com.neoalive.tacz_sewv.mixin;

import java.util.List;
import java.util.Set;

import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.forgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.VersionRange;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Hard gate for {@code tacz_sewv.mixins.json}: skip every mixin when Superb Warfare is absent
 * or outside the range declared in {@code mods.toml} / {@code gradle.properties}
 * ({@code sbw_version_range}).
 *
 * <p>Mixin application runs <em>before</em> Forge's dependency screen. Without this gate an
 * older SBW (e.g. {@code 0.8.9}) dies on a failed inject instead of showing the required-version
 * notice. Skipping the queue lets Forge report the unmet {@code superbwarfare} dependency
 * cleanly.
 *
 * <p>Keep {@link #SBW_RANGE} identical to {@code sbw_version_range} in {@code gradle.properties}.
 */
public final class SbwVersionMixinPlugin implements IMixinConfigPlugin {

    private static final String SBW_ID = "superbwarfare";
    /** Must match {@code sbw_version_range} / mods.toml {@code superbwarfare} versionRange. */
    private static final String SBW_RANGE = "[0.8.9.1,)";

    private Boolean compatible;

    private boolean compatible() {
        if (this.compatible == null) {
            this.compatible = check();
        }
        return this.compatible;
    }

    private static boolean check() {
        var file = LoadingModList.get().getModFileById(SBW_ID);
        if (file == null) {
            return false;
        }
        try {
            VersionRange range = VersionRange.createFromVersionSpec(SBW_RANGE);
            for (IModInfo mod : file.getMods()) {
                if (SBW_ID.equals(mod.getModId())) {
                    return range.containsVersion(mod.getVersion());
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return compatible();
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

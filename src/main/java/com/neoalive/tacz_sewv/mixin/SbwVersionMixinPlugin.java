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
 * Hard gate for {@code tacz_sewv.mixins.json}: skip every mixin when Superb Warfare or Simple
 * Enemy Mod is absent or outside the range declared in {@code mods.toml} / {@code gradle.properties}
 * ({@code sbw_version_range}, {@code sem_version_range}).
 *
 * <p>Mixin application runs <em>before</em> Forge's dependency screen. Without this gate an
 * older SBW (e.g. {@code 0.8.9}) dies on a failed inject instead of showing the required-version
 * notice. Skipping the queue lets Forge report the unmet {@code superbwarfare} dependency
 * cleanly.
 *
 * <p>SEM needs it for the same reason: plain 1.20.1-0.1.6-beta lacks the hotfix's
 * {@code isFriendlyToNpcFaction} / RU-US {@code setTarget} that MixinUnitFactionTags and
 * MixinPmcUnitEntity target.
 *
 * <p>Keep {@link #SBW_RANGE} / {@link #SEM_RANGE} identical to the properties in {@code gradle.properties}.
 */
public final class SbwVersionMixinPlugin implements IMixinConfigPlugin {

    private static final String SBW_ID = "superbwarfare";
    /** Must match {@code sbw_version_range} / mods.toml {@code superbwarfare} versionRange. */
    private static final String SBW_RANGE = "[0.8.9.1,)";
    private static final String SEM_ID = "simpleenemymod";
    /** Must match {@code sem_version_range} / mods.toml {@code simpleenemymod} versionRange. */
    private static final String SEM_RANGE = "[1.20.1-0.1.6-beta-hotfix,)";

    private Boolean compatible;

    private boolean compatible() {
        if (this.compatible == null) {
            this.compatible = check(SBW_ID, SBW_RANGE) && check(SEM_ID, SEM_RANGE);
        }
        return this.compatible;
    }

    private static boolean check(String modId, String versionRange) {
        var file = LoadingModList.get().getModFileById(modId);
        if (file == null) {
            return false;
        }
        try {
            VersionRange range = VersionRange.createFromVersionSpec(versionRange);
            for (IModInfo mod : file.getMods()) {
                if (modId.equals(mod.getModId())) {
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

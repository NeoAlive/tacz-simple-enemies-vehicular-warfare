package com.neoalive.tacz_sewv.client.radial;

import javax.annotation.Nullable;

import net.minecraft.client.resources.language.I18n;

import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;

/**
 * Title and explanation for whatever the wheel is pointing at, shown while Ctrl is held. Every leaf
 * and category has a {@code gui.tacz_sewv.wheel.desc.*} paragraph; the Select leaves are generated
 * per vehicle class and platoon, so they share one paragraph per kind.
 */
final class OrderInfo {

    static final String DESC_PREFIX = QuickCommandRegistry.LABEL_PREFIX + "desc.";

    record Text(String title, String body) {}

    private OrderInfo() {}

    /**
     * @param hot      the hovered entry, or null in the dead zone
     * @param category the submenu being shown, or null at the root
     */
    static Text of(@Nullable WedgeEntry hot, @Nullable WedgeEntry.CategoryEntry category) {
        if (hot instanceof WedgeEntry.PipelineEntry leaf) {
            return new Text(RoleChoice.label(leaf), I18n.get(DESC_PREFIX + descId(leaf.pipelineId())));
        }
        if (hot instanceof WedgeEntry.CategoryEntry cat) return ofCategory(cat);
        if (category != null) return ofCategory(category);
        return new Text(I18n.get("gui.tacz_sewv.quick_command_wheel"), I18n.get(DESC_PREFIX + "root"));
    }

    private static Text ofCategory(WedgeEntry.CategoryEntry cat) {
        String key = cat.labelKey();
        String id = key.startsWith(QuickCommandRegistry.LABEL_PREFIX)
                ? key.substring(QuickCommandRegistry.LABEL_PREFIX.length()) : key;
        return new Text(I18n.get(key), I18n.get(DESC_PREFIX + id));
    }

    static String descId(String pipelineId) {
        if (pipelineId.startsWith(QuickCommandRegistry.SELECT_KIND_PREFIX)) return "select_kind";
        if (pipelineId.startsWith(QuickCommandRegistry.SELECT_PLATOON_PREFIX)) return "select_platoon";
        return pipelineId;
    }
}

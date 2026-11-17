package com.neoalive.tacz_sewv.client.radial;

import java.util.List;
import java.util.Objects;

/**
 * One spoke of the quick-command wheel. Icons are Unicode glyphs (no texture assets).
 *
 * <p>Holds a pipeline <em>id</em> rather than a pipeline instance so {@link RadialInputState}
 * stays free of server/command types (and of Minecraft). {@link #accentRgb()} is a plain
 * 0xRRGGBB int for the same reason. {@link #labelKey()} is a language key
 * ({@code gui.tacz_sewv.wheel.*} in {@code en_us.json}); draw sites resolve it, so this type
 * never touches the language manager.
 */
public sealed interface WedgeEntry permits WedgeEntry.CategoryEntry, WedgeEntry.PipelineEntry {

    /** Language key. English text lives in {@code assets/tacz_sewv/lang/en_us.json}. */
    String labelKey();

    /** Format arguments for {@link #labelKey()}, or an empty array when the key has none. */
    default Object[] labelArgs() {
        return NO_ARGS;
    }

    String icon();

    /** Category / leaf accent as 0xRRGGBB (no alpha). */
    int accentRgb();

    /** Shared empty argument list. Callers must not mutate the array {@link #labelArgs()} returns. */
    Object[] NO_ARGS = new Object[0];

    record CategoryEntry(String labelKey, String icon, int accentRgb, List<WedgeEntry> children)
            implements WedgeEntry {
        public CategoryEntry {
            Objects.requireNonNull(labelKey, "labelKey");
            Objects.requireNonNull(icon, "icon");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
        }
    }

    record PipelineEntry(String labelKey, String icon, String pipelineId, int accentRgb, Object[] labelArgs)
            implements WedgeEntry {
        public PipelineEntry(String labelKey, String icon, String pipelineId, int accentRgb) {
            this(labelKey, icon, pipelineId, accentRgb, NO_ARGS);
        }

        public PipelineEntry {
            Objects.requireNonNull(labelKey, "labelKey");
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(pipelineId, "pipelineId");
            labelArgs = labelArgs == null || labelArgs.length == 0 ? NO_ARGS : labelArgs.clone();
        }
    }
}

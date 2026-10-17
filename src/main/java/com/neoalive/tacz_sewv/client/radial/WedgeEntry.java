package com.neoalive.tacz_sewv.client.radial;

import java.util.List;
import java.util.Objects;

/**
 * One spoke of the quick-command wheel. Icons are Unicode glyphs (no texture assets).
 *
 * <p>Holds a pipeline <em>id</em> rather than a pipeline instance so {@link RadialInputState}
 * stays free of server/command types (and of Minecraft). {@link #accentRgb()} is a plain
 * 0xRRGGBB int for the same reason.
 */
public sealed interface WedgeEntry permits WedgeEntry.CategoryEntry, WedgeEntry.PipelineEntry {

    String label();

    String icon();

    /** Category / leaf accent as 0xRRGGBB (no alpha). */
    int accentRgb();

    record CategoryEntry(String label, String icon, int accentRgb, List<WedgeEntry> children)
            implements WedgeEntry {
        public CategoryEntry {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(icon, "icon");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
        }
    }

    record PipelineEntry(String label, String icon, String pipelineId, int accentRgb)
            implements WedgeEntry {
        public PipelineEntry {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(pipelineId, "pipelineId");
        }
    }
}

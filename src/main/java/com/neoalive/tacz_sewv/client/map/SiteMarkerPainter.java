package com.neoalive.tacz_sewv.client.map;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.map.FactionColors;
import com.neoalive.tacz_sewv.map.FobMarker;

/**
 * Fixed sites — the FOB command post and cleared helipads — for either map. Both are one tinted PNG
 * with a name on hover; the helipad art is a placeholder, so swap the PNG and nothing else changes.
 */
public final class SiteMarkerPainter {

    public static final int HIT_BOX = 13;
    public static final int RENDER_BOX = 20;
    private static final int ICON = 22;
    private static final ResourceLocation FOB_TEXTURE =
            new ResourceLocation(TaczSewv.MODID, "textures/map/xaeros_icon_fob.png");
    private static final ResourceLocation HELIPAD_TEXTURE =
            new ResourceLocation(TaczSewv.MODID, "textures/map/xaeros_icon_helipad.png");

    private SiteMarkerPainter() {}

    /** Linear filtering for both site icons — see {@link VehicleMarkerPainter#prepareTextures()}. */
    public static void prepareTextures() {
        Minecraft mc = Minecraft.getInstance();
        mc.getTextureManager().getTexture(FOB_TEXTURE).setFilter(true, false);
        mc.getTextureManager().getTexture(HELIPAD_TEXTURE).setFilter(true, false);
    }

    public static void paintFob(GuiGraphics guiGraphics, FobMarker marker, boolean hovered, MarkerStyle style) {
        int color = marker.valid() ? FactionColors.configArgb(CrewFacts.Faction.PMC) : 0xFFFF5555;
        paint(guiGraphics, FOB_TEXTURE, color, "FOB", hovered, style);
    }

    public static void paintHelipad(GuiGraphics guiGraphics, boolean hovered, MarkerStyle style) {
        paint(guiGraphics, HELIPAD_TEXTURE, FactionColors.configArgb(CrewFacts.Faction.PMC), "Helipad", hovered, style);
    }

    private static void paint(GuiGraphics guiGraphics, ResourceLocation texture, int color, String label,
                              boolean hovered, MarkerStyle style) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.scale(style.scale(), style.scale(), 1.0F);

        if (hovered) {
            guiGraphics.fill(-HIT_BOX, -HIT_BOX, HIT_BOX, HIT_BOX, 0x60000000);
        }
        if (ClientConfig.MAP_SHOW_ICONS.get()) {
            guiGraphics.setColor(
                    (color >> 16 & 0xFF) / 255.0F,
                    (color >> 8 & 0xFF) / 255.0F,
                    (color & 0xFF) / 255.0F,
                    1.0F);
            guiGraphics.blit(texture, -ICON / 2, -ICON / 2, 0, 0, ICON, ICON, ICON, ICON);
            guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        if (hovered && style.labels()) {
            Font font = Minecraft.getInstance().font;
            guiGraphics.drawString(font, label, -font.width(label) / 2, -HIT_BOX - font.lineHeight, 0xFFFFFFFF, true);
        }

        pose.popPose();
    }
}

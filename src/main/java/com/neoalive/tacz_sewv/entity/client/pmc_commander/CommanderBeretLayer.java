package com.neoalive.tacz_sewv.entity.client.pmc_commander;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * The Commander's beret, tinted with its platoon's colour — the same two-layer technique vanilla
 * uses for dyed leather armor: {@code pmc_commander_overlay.png} is transparent everywhere except
 * the beret, so re-rendering the (already-posed) model through it with a colour multiply paints
 * only that region. No shader, no custom pipeline — one extra masked-texture draw pass.
 *
 * <p>Colour comes from {@link MapMarkers}, the same synced data the Xaero marker / TDT bar already
 * read — which is already owner-only server-side ({@code OwnedVehicleTracker} zeroes it on
 * anything but an {@code OWN} marker), so another player sees no tint here either, same as those
 * cues. No platoon (colour 0) draws nothing, leaving the base skin's own beret art untouched.
 *
 * <p>{@code MapMarkers} carries exactly one marker per crewed hull, keyed by its <b>driver's</b> id
 * ({@code OwnedVehicleTracker.collect}) — so a Commander riding as a gunner or other non-driver
 * seat has no marker of its own to look up, even though the hull it shares still does. Falling
 * back to that hull's driver id is what makes the beret show up while mounted at all; the colour
 * itself is already the whole vehicle's (every seat's crew is the same platoon).
 */
public class CommanderBeretLayer extends RenderLayer<PmcCommanderEntity, PmcCommanderModel<PmcCommanderEntity>> {

    private static final ResourceLocation JAR_OVERLAY_FALLBACK =
            new ResourceLocation(TaczSewv.MODID, "skins/pmc_commander_overlay.png");

    public CommanderBeretLayer(RenderLayerParent<PmcCommanderEntity, PmcCommanderModel<PmcCommanderEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, PmcCommanderEntity entity,
                       float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        VehicleMarker marker = MapMarkers.markerForDriver(entity.getId());
        if (marker == null && entity.getVehicle() instanceof VehicleEntity hull) {
            Entity driver = hull.getFirstPassenger();
            if (driver != null) marker = MapMarkers.markerForDriver(driver.getId());
        }
        int colorRgb = marker != null ? marker.platoonColorRgb() : 0;
        if (colorRgb == 0) return;

        float r = ((colorRgb >> 16) & 0xFF) / 255.0F;
        float g = ((colorRgb >> 8) & 0xFF) / 255.0F;
        float b = (colorRgb & 0xFF) / 255.0F;

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(overlayTexture()));
        this.getParentModel().renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY, r, g, b, 1.0F);
    }

    private static ResourceLocation overlayTexture() {
        ResourceLocation fromConfig = CrewSkinRegistry.overlayFor("pmc_commander");
        return fromConfig != null ? fromConfig : JAR_OVERLAY_FALLBACK;
    }
}

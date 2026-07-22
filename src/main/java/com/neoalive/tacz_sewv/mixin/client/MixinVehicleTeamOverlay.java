package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.overlay.VehicleTeamOverlay;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

/**
 * Colours SuperbWarfare's hover overlay by whoever is crewing the vehicle, so an enemy tank
 * reads as an enemy tank at a glance.
 *
 * <p>SuperbWarfare draws that overlay in one colour taken from the driver's <b>scoreboard team</b>
 * ({@code Player.getTeamColor()}), reached through a branch that requires
 * {@code getFirstPassenger() is Player}. An SEM unit is not a Player, so every AI-crewed hull in
 * the game falls through to the default white — the one case where the overlay would be most
 * useful is the one it cannot speak to.
 *
 * <p>Rather than reimplement the overlay, both colour arguments are intercepted where they are
 * passed: the name/range text through {@code GuiGraphics.drawString} and the health bar through
 * {@code RenderHelper.fill}. Two {@code @ModifyArg}s, no layout code, and SuperbWarfare keeps
 * ownership of everything about how the thing looks.
 *
 * <p>Deliberately not {@code @ModifyVariable} on the overlay's local {@code color}: it is a
 * Kotlin local with three assignment sites, and slot indices there are exactly the kind of thing
 * that shifts silently when upstream recompiles.
 *
 * <p><b>A status word (escort/patrol/formation) was deliberately NOT added here.</b> That state
 * ({@code IEscort}/{@code IVehiclePatrol}/{@code IFormationMember}) lives in transient mixin
 * fields or Forge persistent NBT, same as {@code IMortarCrew}/{@code IHelicopterPilot} — server-side
 * only, with no packet or {@code SynchedEntityData} syncing it to the client. Reading it from a
 * client-side mixin would silently read the client's own always-empty copy and never display
 * anything; {@code /sewv status} (a server-side command) is the correct home for that instead.
 * Do not add it here without first wiring real sync.
 */
@Mixin(value = VehicleTeamOverlay.class, remap = false)
public abstract class MixinVehicleTeamOverlay {

    /**
     * The vehicle under the crosshair, resolved once per client tick by the overlay's own
     * raycast in {@code onVehicleTeamOverlayClientTick}. Shadowed rather than re-traced: the
     * answer is already sitting there, and running a second raycast per frame to rediscover it
     * would be both slower and capable of disagreeing with the thing being drawn.
     *
     * <p>Deliberately a {@code @Shadow} field rather than a static {@code @Accessor} on a
     * separate interface mixin. Mixin renames an added accessor inside the target class, so a
     * <em>static</em> one invoked through the interface resolves to the interface's own body
     * instead of the generated accessor — it would compile, load, apply without complaint, and
     * then never return the entity. A shadowed field has no such gap: this mixin already targets
     * the class the field lives in.
     */
    @Shadow
    private static Entity lookingEntity;

    /**
     * The half-transparent black SuperbWarfare fills the empty part of the health bar with. It is
     * a hardcoded literal rather than the team colour, so recolouring it would paint over the
     * whole bar and destroy the very reading the bar exists to give.
     */
    @Unique
    private static final int TACZ_SEWV$BAR_BACKGROUND = 0x80000000;

    @ModifyArg(
            method = "render(Lcom/atsuishio/superbwarfare/client/overlay/RenderContext;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I",
                    remap = true),
            index = 4)
    private int tacz_sewv$colorText(int color) {
        Integer faction = tacz_sewv$factionColor();
        return faction == null ? color : faction;
    }

    @ModifyArg(
            method = "render(Lcom/atsuishio/superbwarfare/client/overlay/RenderContext;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/client/RenderHelper;fill(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/renderer/RenderType;FFFFFI)V"),
            index = 7)
    private int tacz_sewv$colorBar(int argb) {
        if (argb == TACZ_SEWV$BAR_BACKGROUND) return argb;
        Integer faction = tacz_sewv$factionColor();
        return faction == null ? argb : faction;
    }

    /**
     * The colour of the faction crewing the hovered vehicle, or null to leave SuperbWarfare's own
     * choice alone.
     *
     * <p>Answers null unless <b>every</b> passenger is a unit of one and the same faction. A hull
     * with a player aboard is the player's business and keeps its team colour; an empty one has no
     * owner to report; a mixed crew (a captured vehicle mid-fight) is genuinely ambiguous and
     * saying so by staying white beats picking a side. Note "empty" is filtered by the same test
     * that requires a faction: an empty list has no first element to match against.
     *
     * <p>This is safe on the client despite reading passengers and entity classes: vanilla syncs
     * passengers to the client, and the three SEM unit classes are common-source (the existing
     * {@code MixinClientGlowManager} already does exactly this). Nothing extra is sent — the
     * faction IS the entity class. A unit hidden by its seat's {@code hidePassenger} is still in
     * the passenger list, only unrendered, so hulls that conceal their crew still colour.
     */
    @Unique
    private static Integer tacz_sewv$factionColor() {
        if (!SewvConfig.FACTION_COLORS_ENABLED.get()) return null;

        if (!(lookingEntity instanceof VehicleEntity vehicle)) return null;

        List<Entity> passengers = vehicle.getPassengers();
        if (passengers.isEmpty()) return null;

        if (tacz_sewv$allAre(passengers, RUunitEntity.class)) {
            return SewvConfig.parseColor(SewvConfig.COLOR_RU.get(), -1);
        }
        if (tacz_sewv$allAre(passengers, USunitEntity.class)) {
            return SewvConfig.parseColor(SewvConfig.COLOR_US.get(), -1);
        }
        if (tacz_sewv$allAre(passengers, PmcUnitEntity.class)) {
            return SewvConfig.parseColor(SewvConfig.COLOR_PMC.get(), -1);
        }
        return null;
    }

    @Unique
    private static boolean tacz_sewv$allAre(List<Entity> passengers, Class<?> faction) {
        for (Entity passenger : passengers) {
            if (!faction.isInstance(passenger)) return false;
        }
        return true;
    }
}

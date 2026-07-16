package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.platform.InputConstants;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketVehicleFormation;
import com.tacz.guns.api.event.common.GunShootEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.InputEvent;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The axis-designation mode: after a mostly-mounted selection is ordered into a wedge or column,
 * the player looks in a direction and left-clicks to lay the formation along that cardinal.
 *
 * <p>It exists because a hull formation cannot use SEM's basis. SEM lays out against the
 * commander's live yaw, so the shape spins when the player looks around — survivable at 2-block
 * infantry spacing, useless at vehicle spacing. Rather than freeze whatever the player happened
 * to be facing when they clicked a menu row, the axis is designated deliberately.
 *
 * <p>Client-side and advisory only: the server re-checks ownership and derives the slot numbering
 * itself ({@link PacketVehicleFormation}). Nothing here is trusted.
 */
public final class FormationAxisSelection {

    private FormationAxisSelection() {}

    private static boolean active;
    private static boolean wedge;
    private static List<Integer> snapshot = List.of();

    public static void cancel() {
        active = false;
        snapshot = List.of();
    }

    /** Arm the mode for a selection the gate has already decided is mostly mounted. */
    public static void begin(OrderType order, List<PmcUnitEntity> units) {
        active = true;
        wedge = order == OrderType.FORM_WEDGE;
        List<Integer> ids = new ArrayList<>();
        for (PmcUnitEntity pmc : units) ids.add(pmc.getId());
        snapshot = ids;
    }

    /**
     * Strictly more than half the selection riding an SBW hull. Strict, so an even split (one
     * tank, one rifleman) reads as an infantry order and goes down SEM's untouched path — and so
     * an empty selection can never reach the division.
     *
     * <p>A MortarEntity is a VehicleEntity too, but it declares no seats: a mortar crew stands
     * beside its tube with a null vehicle and lands on the infantry side by construction, which
     * is right — it is infantry, working a tube. A TOW crew is the mirror image (its launcher has
     * seats) and counts as mounted, which is also right: it is in a hull, the hull just cannot
     * drive.
     */
    public static boolean mostlyMounted(List<PmcUnitEntity> units) {
        int mounted = 0;
        for (PmcUnitEntity pmc : units) {
            if (pmc.getVehicle() instanceof VehicleEntity) mounted++;
        }
        return mounted * 2 > units.size();
    }

    /** The selection, resolved and filtered to what we own. A stale id counts as nothing. */
    public static List<PmcUnitEntity> resolveOwned(Set<Integer> ids) {
        Minecraft mc = Minecraft.getInstance();
        List<PmcUnitEntity> units = new ArrayList<>();
        if (mc.level == null || mc.player == null) return units;
        for (int id : ids) {
            Entity entity = mc.level.getEntity(id);
            if (entity instanceof PmcUnitEntity pmc && pmc.isOwnedBy(mc.player)) units.add(pmc);
        }
        return units;
    }

    /**
     * The live readout, re-sent every tick so it tracks the player turning and never fades out
     * mid-decision.
     */
    public static void tick(Minecraft mc) {
        if (!active) return;
        Player player = mc.player;
        // Any open screen aborts. Forge fires MouseButton.Pre from MouseHandler.onPress BEFORE
        // the screen gets the click, so an armed mode left running would commit on a click in the
        // player's inventory. (SEM's own selection modes have this hole; ours does not.)
        if (player == null || mc.level == null || mc.screen != null) {
            cancel();
            return;
        }

        Direction axis = Direction.fromYRot(player.getYRot());
        player.displayClientMessage(Component.translatable(
                wedge ? "message.tacz_sewv.formation.axis.wedge"
                      : "message.tacz_sewv.formation.axis.column",
                Component.translatable(PacketVehicleFormation.axisKey(axis)))
                .withStyle(ChatFormatting.GRAY), true);
    }

    public static void onMouseInput(InputEvent.MouseButton.Pre event) {
        if (!active) return;
        // The RELEASE of the very click that picked the menu row is not a designation.
        if (event.getAction() != InputConstants.PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        if (event.getButton() == InputConstants.MOUSE_BUTTON_LEFT) {
            Direction axis = Direction.fromYRot(mc.player.getYRot());
            NetworkHandler.CHANNEL.sendToServer(new PacketVehicleFormation(
                    snapshot,
                    wedge ? PacketVehicleFormation.KIND_WEDGE : PacketVehicleFormation.KIND_COLUMN,
                    IFormationMember.axisOf(axis)));
            cancel();
            // The click designated an axis — it must not also swing whatever is in hand.
            event.setCanceled(true);
        } else if (event.getButton() == InputConstants.MOUSE_BUTTON_RIGHT) {
            cancel();
            event.setCanceled(true);
        }
    }

    /**
     * TACZ fires from its own input path rather than through InputEvent.MouseButton, so
     * cancelling the mouse event is NOT enough to keep the player's rifle quiet while they
     * designate. SEM hit exactly this and answered it the same way — its TaczInputHandler cancels
     * GunShootEvent while its own selection modes are up. This is that gate for ours.
     */
    public static void onGunShoot(GunShootEvent event) {
        if (!active || !event.getLogicalSide().isClient()) return;
        if (event.getShooter() != Minecraft.getInstance().player) return;
        event.setCanceled(true);
    }
}

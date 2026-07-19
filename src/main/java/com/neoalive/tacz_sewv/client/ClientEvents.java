package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketEscort;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.client.gui.overlay.CommanderOverlayRenderer;
import net.nekoyuni.SimpleEnemyMod.client.util.CommanderRayTrace;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// FORGE bus, client dist: opens the Tactical Data Terminal, and runs the Escort selection mode.
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public class ClientEvents {

    /** Reach for picking the vehicle to escort — SEM's own selection raytrace distance. */
    private static final double ESCORT_PICK_RANGE = 50.0;
    /** Re-show the selection prompt this often (goal-agnostic client ticks) so it doesn't fade mid-mode. */
    private static final int PROMPT_REFRESH_TICKS = 40;

    // Escort selection mode: after the player picks Escort in the TDT, the next in-world left-click
    // designates the vehicle and a right-click cancels. The units to order are captured up front
    // (SEM's menu selection, or nearby owned units), so the click only has to supply the target.
    private static boolean pendingEscort = false;
    private static List<Integer> pendingEscortUnits = List.of();
    private static int promptCooldown = 0;

    /**
     * Arm escort selection mode. Called by the TDT's Escort button, which then closes the screen.
     * Units are SEM's commander-menu selection if there is one (the flow the feature is built
     * around), otherwise the owned units near the player, so it still does something without a
     * selection. Refuses to arm with no units.
     */
    public static void armEscort() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        List<Integer> units = new ArrayList<>();
        Set<Integer> selected = CommanderOverlayRenderer.selectedUnitsSnapshot;
        if (selected != null && !selected.isEmpty()) {
            units.addAll(selected);
        } else {
            for (PmcUnitEntity pmc : mc.level.getEntitiesOfClass(PmcUnitEntity.class,
                    player.getBoundingBox().inflate(SewvConfig.BOARD_SCAN_RADIUS.get()))) {
                if (pmc.isOwnedBy(player)) units.add(pmc.getId());
            }
        }

        if (units.isEmpty()) {
            hint("message.tacz_sewv.escort.no_units");
            return;
        }
        pendingEscort = true;
        pendingEscortUnits = List.copyOf(units);
        promptCooldown = 0;
    }

    /**
     * Left click (attack) drives two things, in priority order:
     * <ol>
     *   <li>If escort selection is armed, this click designates the vehicle (or cancels on a
     *       right-click) instead of doing anything in the world.
     *   <li>Otherwise, with the terminal in hand, it opens the command menu.
     * </ol>
     *
     * <p>The event is cancelled in every case it handles, so the click never also mines the block
     * or attacks the entity the player was aiming at. Works on foot; SuperbWarfare's mouse-cancel
     * only bites inside a vehicle seat (see {@link TdtKeybind}), and an escorting commander is on
     * foot.
     */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (pendingEscort) {
            if (event.isAttack()) {
                Entity target = CommanderRayTrace.rayTraceEntity(mc.player, ESCORT_PICK_RANGE);
                if (target instanceof VehicleEntity vehicle) {
                    NetworkHandler.CHANNEL.sendToServer(new PacketEscort(pendingEscortUnits, vehicle.getId()));
                    clearEscort();
                } else {
                    hint("message.tacz_sewv.escort.no_vehicle"); // miss — stay armed so they can retry
                }
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                clearEscort();
                hint("message.tacz_sewv.escort.cancelled");
                event.setCanceled(true);
            }
            return;
        }

        if (!event.isAttack()) return;
        if (!mc.player.getMainHandItem().is(ModItems.TACTICAL_DATA_TERMINAL.get())) return;
        TdtScreen.open();
        event.setCanceled(true);
    }

    /** Keep the "left-click a vehicle / right-click to cancel" prompt on screen while armed. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !pendingEscort) return;
        // A screen came up (or the world went away) — drop the mode rather than trap the player in it.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            if (mc.player == null) clearEscort();
            return;
        }
        if (--promptCooldown <= 0) {
            promptCooldown = PROMPT_REFRESH_TICKS;
            hint("message.tacz_sewv.escort.select");
        }
    }

    private static void clearEscort() {
        pendingEscort = false;
        pendingEscortUnits = List.of();
        promptCooldown = 0;
    }

    private static void hint(String key) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
        }
    }
}

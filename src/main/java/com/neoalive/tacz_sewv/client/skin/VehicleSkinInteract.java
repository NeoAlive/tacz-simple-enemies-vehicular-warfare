package com.neoalive.tacz_sewv.client.skin;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.gun.special.RepairToolItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSetVehicleSkin;

/**
 * Sneak + right-click a vehicle with the repair tool to cycle sticky skins among stock and every
 * PNG present for that hull. Left-click stays SBW's repair ray.
 *
 * <p>Listens to both {@link PlayerInteractEvent.EntityInteract} and raw mouse (SBW guns often eat
 * the interact path).
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class VehicleSkinInteract {

    private VehicleSkinInteract() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!event.getLevel().isClientSide()) return;
        if (!(event.getTarget() instanceof VehicleEntity vehicle)) return;
        if (!canCycle(event.getEntity())) return;

        if (tryCycle(vehicle)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * Fallback when the repair tool (a gun) never reaches EntityInteract. Use-button press while
     * sneaking and looking at a hull.
     */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (!canCycle(mc.player)) return;
        if (!(mc.hitResult instanceof EntityHitResult hit)) return;
        if (hit.getType() != HitResult.Type.ENTITY) return;
        if (!(hit.getEntity() instanceof VehicleEntity vehicle)) return;

        if (tryCycle(vehicle)) {
            event.setCanceled(true);
        }
    }

    private static boolean canCycle(Player player) {
        if (!player.isShiftKeyDown()) return false;
        return isRepairTool(player.getMainHandItem()) || isRepairTool(player.getOffhandItem());
    }

    private static boolean tryCycle(VehicleEntity vehicle) {
        String path = registryPath(vehicle);
        if (path == null) return false;

        List<CrewFacts.Faction> present = VehicleSkinRegistry.factionsFor(path);
        if (present.isEmpty()) return false;

        List<CrewFacts.Faction> cycle = new ArrayList<>(present.size() + 1);
        cycle.add(null);
        cycle.addAll(present);

        CrewFacts.Faction current = VehicleSkinClient.get(vehicle.getId());
        int index = indexOf(cycle, current);
        CrewFacts.Faction next = cycle.get((index + 1) % cycle.size());
        NetworkHandler.CHANNEL.sendToServer(new PacketSetVehicleSkin(vehicle.getId(), next));
        return true;
    }

    private static boolean isRepairTool(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof RepairToolItem;
    }

    @Nullable
    private static String registryPath(VehicleEntity vehicle) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType());
        return id == null ? null : id.getPath();
    }

    private static int indexOf(List<CrewFacts.Faction> cycle, @Nullable CrewFacts.Faction current) {
        for (int i = 0; i < cycle.size(); i++) {
            CrewFacts.Faction entry = cycle.get(i);
            if (entry == null ? current == null : entry == current) {
                return i;
            }
        }
        return 0;
    }
}

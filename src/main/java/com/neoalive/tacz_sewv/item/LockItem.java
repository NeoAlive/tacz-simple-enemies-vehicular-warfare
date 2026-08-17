package com.neoalive.tacz_sewv.item;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.init.ModSounds;

/**
 * Tags a vehicle so RU/US infantry will not board it. PMC crews and players are unaffected.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public class LockItem extends Item {

    public static final String TAG = "sewv:npc_lock";

    public LockItem() {
        super(new Properties().stacksTo(16));
    }

    public static boolean isLocked(Entity entity) {
        return entity.getPersistentData().getBoolean(TAG);
    }

    /** RU/US may not enter a tagged hull. PMC and players still can. */
    public static boolean blocksNpc(Entity vehicle, Entity rider) {
        return isLocked(vehicle)
                && (rider instanceof RUunitEntity || rider instanceof USunitEntity);
    }

    /** Eligible if SBW can name an {@code EngineType} for it. */
    public static boolean eligible(Entity entity) {
        if (!(entity instanceof VehicleEntity hull)) return false;
        try {
            return hull.computed().getEngineType() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Entity target = event.getTarget();
        if (!eligible(target)) return;
        if (!(event.getItemStack().getItem() instanceof LockItem)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getLevel().isClientSide()) return;

        Player player = event.getEntity();
        if (player.isShiftKeyDown()) {
            target.getPersistentData().remove(TAG);
            hint(player, "message.tacz_sewv.lock.cleared");
        } else {
            target.getPersistentData().putBoolean(TAG, true);
            hint(player, "message.tacz_sewv.lock.applied");
        }
        event.getLevel().playSound(null, target, ModSounds.LOCK.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide() || !event.isMounting()) return;
        if (blocksNpc(event.getEntityBeingMounted(), event.getEntityMounting())) {
            event.setCanceled(true);
        }
    }

    private static void hint(Player player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.YELLOW), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("tooltip.tacz_sewv.lock").withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("tooltip.tacz_sewv.lock.unlock").withStyle(ChatFormatting.GRAY));
    }
}

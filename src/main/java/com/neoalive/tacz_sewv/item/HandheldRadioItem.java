package com.neoalive.tacz_sewv.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.items.IItemHandler;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.client.RadioScreen;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.init.ModSounds;

/**
 * Forward observer's radio: opens a compact fire-mission panel to call mortar, TOW, artillery
 * and air crews, or sneak-use to stand them down.
 */
public class HandheldRadioItem extends Item {

    public HandheldRadioItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                standDown(player);
            }
            return InteractionResultHolder.success(stack);
        }

        if (level.isClientSide()) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RadioScreen.open(null));
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide()) {
            if (player.isShiftKeyDown()) {
                return InteractionResult.SUCCESS;
            }
            if (target instanceof PmcUnitEntity) {
                player.displayClientMessage(
                        Component.translatable("message.tacz_sewv.radio.friendly").withStyle(ChatFormatting.RED),
                        true);
                return InteractionResult.FAIL;
            }
            LivingEntity designated = target;
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RadioScreen.open(designated));
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            standDown(player);
        }
        return InteractionResult.SUCCESS;
    }

    /** Whether a unit is carrying a radio, and so can call missions in on its own. */
    public static boolean isCarriedBy(PmcUnitEntity unit) {
        IItemHandler inventory = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (inventory == null) return false;

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).is(ModItems.HANDHELD_RADIO.get())) return true;
        }
        return false;
    }

    /** Calls every crew in range off their fire mission. */
    private static void standDown(Player player) {
        int released = FireMissionSupport.standDown(
                player.level(), player.getUUID(), player.position(),
                SewvConfig.MORTAR_RADIO_RANGE.get());

        if (released == 0) {
            hint(player, "message.tacz_sewv.radio.standdown.none", ChatFormatting.GRAY);
            return;
        }

        player.level().playSound(null, player, ModSounds.PMC_MORTAR.next(),
                SoundSource.NEUTRAL, 1.0F, 1.0F);

        Component msg = Component.translatable(
                released == 1
                        ? "message.tacz_sewv.radio.standdown.single"
                        : "message.tacz_sewv.radio.standdown.multiple",
                released);
        com.neoalive.tacz_sewv.network.NetworkHandler.sendOrderFeedback(
                player, msg.copy().withStyle(ChatFormatting.YELLOW));
    }

    public static boolean isDesignatable(Entity entity) {
        return entity instanceof LivingEntity
                && entity.isAlive()
                && !entity.isSpectator()
                && !(entity instanceof PmcUnitEntity);
    }

    private static void hint(Player player, String key, ChatFormatting style) {
        player.displayClientMessage(Component.translatable(key).withStyle(style), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.tacz_sewv.handheld_radio.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.tacz_sewv.handheld_radio.standdown").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.tacz_sewv.handheld_radio.unit").withStyle(ChatFormatting.GRAY));
        // Search-tree rebuild can call this before COMMON config is baked; .get() throws then.
        double range = SewvConfig.SPEC.isLoaded()
                ? SewvConfig.MORTAR_RADIO_RANGE.get()
                : SewvConfig.MORTAR_RADIO_RANGE.getDefault();
        tooltip.add(Component.translatable("tooltip.tacz_sewv.handheld_radio.range",
                (int) range).withStyle(ChatFormatting.DARK_GRAY));
    }
}

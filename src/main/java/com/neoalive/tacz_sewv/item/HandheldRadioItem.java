package com.neoalive.tacz_sewv.item;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.FireMissionSupport;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.init.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Forward observer's radio: point it at a mob to call every mortar and TOW crew you own
 * within range onto that target, or sneak-use it to call them off.
 *
 * <p>This exists because those weapons outrange the eyes behind them — a mortar shoots
 * ~770 blocks while SEM's targeting reaches {@code FOLLOW_RANGE} (96 blocks) and only ±4
 * blocks vertically. The radio hands the crew a target it could never have spotted, which
 * is the whole point of indirect fire; {@link FireMissionSupport} has the rest.
 */
public class HandheldRadioItem extends Item {

    public HandheldRadioItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) return InteractionResultHolder.success(stack);

        if (player.isShiftKeyDown()) {
            standDown(player);
            return InteractionResultHolder.success(stack);
        }

        LivingEntity target = pickTarget(player, SewvConfig.MORTAR_RADIO_RANGE.get());
        if (target == null) {
            hint(player, "message.tacz_sewv.radio.no_target", ChatFormatting.GRAY);
            return InteractionResultHolder.fail(stack);
        }
        callFireMission(player, target);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        // Within arm's reach vanilla routes the click here and never calls use(), so both
        // orders have to work from this path too.
        if (player.level().isClientSide()) return InteractionResult.SUCCESS;

        if (player.isShiftKeyDown()) {
            standDown(player);
        } else if (target instanceof PmcUnitEntity) {
            hint(player, "message.tacz_sewv.radio.friendly", ChatFormatting.RED);
        } else {
            callFireMission(player, target);
        }
        return InteractionResult.SUCCESS;
    }

    /** Puts every mortar and TOW crew in range onto {@code target}. */
    private static void callFireMission(Player player, LivingEntity target) {
        int ordered = FireMissionSupport.callFireMission(
                player.level(), player.getUUID(), player.position(),
                SewvConfig.MORTAR_RADIO_RANGE.get(), target);

        if (ordered == 0) {
            // Always shown regardless of the flag: a failure explanation is not the success
            // spam SHOW_ORDER_FEEDBACK exists to cut.
            hint(player, "message.tacz_sewv.radio.no_crews", ChatFormatting.GRAY);
            return;
        }

        player.level().playSound(null, player.blockPosition(), ModSounds.MORTAR_AFFIRMATIVE.get(),
                SoundSource.NEUTRAL, 1.0F, 1.0F);

        if (!SewvConfig.SHOW_ORDER_FEEDBACK.get()) return;
        Component msg = Component.translatable(
                ordered == 1
                        ? "message.tacz_sewv.radio.fire_mission.single"
                        : "message.tacz_sewv.radio.fire_mission.multiple",
                ordered, target.getDisplayName());
        player.displayClientMessage(msg.copy().withStyle(ChatFormatting.GREEN), true);
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
            // Always shown regardless of the flag, same reasoning as callFireMission above.
            hint(player, "message.tacz_sewv.radio.standdown.none", ChatFormatting.GRAY);
            return;
        }

        player.level().playSound(null, player.blockPosition(), ModSounds.FREE_FIRE.get(),
                SoundSource.NEUTRAL, 1.0F, 1.0F);

        if (!SewvConfig.SHOW_ORDER_FEEDBACK.get()) return;
        Component msg = Component.translatable(
                released == 1
                        ? "message.tacz_sewv.radio.standdown.single"
                        : "message.tacz_sewv.radio.standdown.multiple",
                released);
        player.displayClientMessage(msg.copy().withStyle(ChatFormatting.YELLOW), true);
    }

    /**
     * The mob the player is pointing at. Own units are skipped so a stray click can't put
     * a barrage on your own squad.
     */
    @Nullable
    private static LivingEntity pickTarget(Player player, double range) {
        Vec3 eye = player.getEyePosition();
        Vec3 reach = player.getViewVector(1.0F).scale(range);
        Vec3 end = eye.add(reach);
        AABB search = player.getBoundingBox().expandTowards(reach).inflate(1.0);

        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, end, search, HandheldRadioItem::isDesignatable, range * range);
        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }

    private static boolean isDesignatable(Entity entity) {
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
        tooltip.add(Component.translatable("tooltip.tacz_sewv.handheld_radio.range",
                (int) (double) SewvConfig.MORTAR_RADIO_RANGE.get()).withStyle(ChatFormatting.DARK_GRAY));
    }
}

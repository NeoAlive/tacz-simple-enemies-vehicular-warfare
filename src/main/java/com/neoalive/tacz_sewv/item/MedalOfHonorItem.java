package com.neoalive.tacz_sewv.item;

import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.init.ModEntities;

/**
 * Promotes an owned PMC rifleman to a {@link PmcCommanderEntity}. Inventory and armour transfer;
 * Curios are dropped at the unit's feet (Commander slot layout is not assumed to match).
 */
public class MedalOfHonorItem extends Item {

    public MedalOfHonorItem() {
        super(new Item.Properties().stacksTo(16));
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.FAIL;
        }
        if (!(target instanceof PmcUnitEntity pmc) || target instanceof PmcCommanderEntity) {
            hint(player, "message.tacz_sewv.medal.not_pmc");
            return InteractionResult.FAIL;
        }
        UUID owner = pmc.getOwnerUUID();
        if (owner != null && !owner.equals(player.getUUID()) && !player.getAbilities().instabuild) {
            hint(player, "message.tacz_sewv.medal.not_yours");
            return InteractionResult.FAIL;
        }
        if (pmc.isPassenger()) {
            hint(player, "message.tacz_sewv.medal.mounted");
            return InteractionResult.FAIL;
        }

        PmcCommanderEntity commander = ModEntities.PMC_COMMANDER.get().create(level);
        if (commander == null) {
            hint(player, "message.tacz_sewv.medal.failed");
            return InteractionResult.FAIL;
        }

        CompoundTag unitTag = new CompoundTag();
        pmc.addAdditionalSaveData(unitTag);
        float health = pmc.getHealth();
        float maxHealth = pmc.getMaxHealth();
        Component customName = pmc.getCustomName();
        CompoundTag persistent = pmc.getPersistentData().copy();
        double x = pmc.getX();
        double y = pmc.getY();
        double z = pmc.getZ();
        float yRot = pmc.getYRot();
        float xRot = pmc.getXRot();

        dropCurios(pmc);
        // Discard — do not kill — so inventory is not spilled; we already snapshotted it.
        pmc.discard();

        commander.moveTo(x, y, z, yRot, xRot);
        commander.finalizeSpawn(level, level.getCurrentDifficultyAt(commander.blockPosition()),
                MobSpawnType.CONVERSION, null, null);
        // Overwrite the sidearm finalizeSpawn just issued with the original loadout.
        commander.readAdditionalSaveData(unitTag);
        commander.getPersistentData().merge(persistent);
        commander.setOwner(owner != null ? owner : player.getUUID());
        if (maxHealth > 0f) {
            commander.setHealth(commander.getMaxHealth() * (health / maxHealth));
        } else {
            commander.setHealth(health);
        }
        if (customName != null) {
            commander.setCustomName(customName);
        }
        level.addFreshEntity(commander);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        level.playSound(null, commander.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                SoundSource.PLAYERS, 0.6f, 1.1f);
        hint(player, "message.tacz_sewv.medal.promoted");
        return InteractionResult.CONSUME;
    }

    private static void dropCurios(LivingEntity unit) {
        ICuriosItemHandler curios = CuriosApi.getCuriosInventory(unit).orElse(null);
        if (curios == null) return;
        for (ICurioStacksHandler handler : curios.getCurios().values()) {
            dropHandlerStacks(unit, handler.getStacks());
            dropHandlerStacks(unit, handler.getCosmeticStacks());
        }
    }

    private static void dropHandlerStacks(LivingEntity unit, IItemHandler stacks) {
        for (int i = 0; i < stacks.getSlots(); i++) {
            ItemStack stack = stacks.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            ItemEntity drop = new ItemEntity(unit.level(), unit.getX(), unit.getY() + 0.5, unit.getZ(),
                    stack.copy());
            drop.setDefaultPickUpDelay();
            unit.level().addFreshEntity(drop);
            if (stacks instanceof IItemHandlerModifiable mod) {
                mod.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
    }

    private static void hint(Player player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.YELLOW), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("tooltip.tacz_sewv.medal_of_honor").withStyle(ChatFormatting.GRAY));
    }
}

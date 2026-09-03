package com.neoalive.tacz_sewv.entity.ai.support;

import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.IMedicCaptured;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.NpcArmor;
import com.neoalive.tacz_sewv.notify.HudNotify;

/**
 * Handles medic capture trigger (the `LivingDeathEvent` half) and conversion-to-PMC interaction
 * (the player right-click half). The freeze goal is {@link MedicCapturedGoal}.
 *
 * <p>Unlike {@code PmcDownedSupport}, this is NOT gated on {@code PlayerReviveCompat.isLoaded()}.
 * The capture mechanic is self-contained and unrelated to the optional downed-player revive
 * feature — it resolves entirely through the player's own inventory and SEM's recruit economy.
 */
@EventBusSubscriber(modid = TaczSewv.MODID)
public class MedicCaptureSupport {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * HIGHEST, matching {@code PmcDownedSupport.onDeath}: this cancellation must be decided before
     * anything else on the event has a chance to act on an assumed-real death.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof IMedicCaptured captured)) {
            return; // Not a medic.
        }
        if (!SewvConfig.MEDIC_CAPTURE_ENABLED.get()) {
            return; // Feature disabled.
        }
        if (captured.sewv$isCaptured()) {
            debugLog("capture: {} already captured, letting this blow finish it for real", event.getEntity());
            return; // Idempotency guard: already captured, so this is the real death.
        }

        AbstractUnit medic = (AbstractUnit) event.getEntity();
        event.setCanceled(true);

        captured.sewv$setCaptured(true, medic.level().getGameTime() + SewvConfig.MEDIC_CAPTURE_DURATION_TICKS.get());
        captured.sewv$setCapturedSynced(true);
        medic.setHealth((float) Math.max(1.0, SewvConfig.MEDIC_CAPTURED_HEALTH.get()));
        medic.setTarget(null);
        // Unconditional: a capture is a rare, player-facing event, and "did it even trigger" must
        // not depend on the debug toggle.
        LOGGER.info("[sewv medic-capture] {} captured ({} ticks until escape)",
                medic.getName().getString(), SewvConfig.MEDIC_CAPTURE_DURATION_TICKS.get());
        debugLog("capture: {} captured, health={}, deadline in {} ticks", medic,
                medic.getHealth(), SewvConfig.MEDIC_CAPTURE_DURATION_TICKS.get());
        HudNotify.medicCaptured(medic, event.getSource());
    }

    /**
     * Try to convert a captured medic into a PMC if the player holds enough of the configured
     * currency item. Returns {@code null} if the medic is not captured (letting the caller fall
     * through to other interaction handling) or FAIL/CONSUME based on validation.
     */
    public static InteractionResult tryConvert(AbstractUnit medic, Player player) {
        if (medic.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!(medic instanceof IMedicCaptured captured) || !captured.sewv$isCaptured()) {
            return null; // Not captured — let other handlers take it.
        }

        if (!(medic.level() instanceof ServerLevel level)) {
            return InteractionResult.FAIL;
        }

        ItemStack held = player.getMainHandItem();
        int price = SemRecruitCost.capturePrice();
        if (!held.is(SemRecruitCost.currencyItem()) || held.getCount() < price) {
            debugLog("convert: {} refused — {} holds {}x{} (need {}x{})", medic, player.getGameProfile().getName(),
                    held.getCount(), held.getItem(), price, SemRecruitCost.currencyItem());
            player.displayClientMessage(Component.translatable("message.tacz_sewv.capture_medic.convert.need",
                    price, SemRecruitCost.currencyItem().getDescription()).withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        held.shrink(price);

        // Snapshot position/rotation only — no gear to preserve, the kit is discarded per spec.
        double x = medic.getX(), y = medic.getY(), z = medic.getZ();
        float yRot = medic.getYRot(), xRot = medic.getXRot();
        medic.discard();

        // Create a fresh, deliberately bare PMC. Two things must be actively suppressed, not just
        // left unset, because both are otherwise applied unconditionally to any spawned unit:
        // NpcArmor.suppress marks the persistent flag NpcArmor.issue checks BEFORE addFreshEntity
        // fires the EntityJoinLevelEvent that flag guards; a non-null dataTag skips AbstractUnit.
        // finalizeSpawn's own "dataTag == null -> equipRandomGun()" branch, which otherwise stashes
        // a full gun AND reserve ammo into the unit's inventory (see the rookie-gun rewrite below —
        // that random loadout is not merely replaced in the hand, it has to not be issued at all).
        PmcUnitEntity newPmc = new PmcUnitEntity(ModEntities.PMCUNIT.get(), level);
        newPmc.setRole(UnitRole.FRIENDLY_DEFAULT);
        newPmc.setOwner(player.getUUID());
        newPmc.moveTo(x, y, z, yRot, xRot);
        NpcArmor.suppress(newPmc);
        newPmc.finalizeSpawn(level, level.getCurrentDifficultyAt(newPmc.blockPosition()), MobSpawnType.CONVERSION,
                null, new CompoundTag());

        // Rookie handgun, written through the SAME ITEM_HANDLER capability SEM's own equip path
        // uses. setItemInHand alone only updates the visible equipment slot — SEM's
        // UnitInventoryHandler mirrors inventory slot 0 onto MAINHAND on writes to the handler, not
        // the other way around, so a hand-only write leaves the pre-existing inventory contents
        // (gun + reserve ammo, if finalizeSpawn had equipped any) untouched underneath. Clearing
        // every slot first is what guarantees zero backup ammo, not just an empty-looking main hand.
        ItemStack rookie = buildRookieHandgun(newPmc.getRandom());
        newPmc.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            if (!(handler instanceof IItemHandlerModifiable modifiable)) return;
            for (int slot = 0; slot < modifiable.getSlots(); slot++) {
                modifiable.setStackInSlot(slot, ItemStack.EMPTY);
            }
            if (!rookie.isEmpty()) {
                modifiable.setStackInSlot(0, rookie);
            }
        });

        level.addFreshEntity(newPmc);
        debugLog("convert: {} paid {}x{}, {} converted to PMC owned by {}", player.getGameProfile().getName(),
                price, SemRecruitCost.currencyItem(), medic, player.getGameProfile().getName());
        player.displayClientMessage(Component.translatable("message.tacz_sewv.capture_medic.convert.paid",
                price, SemRecruitCost.currencyItem().getDescription()).withStyle(ChatFormatting.GREEN), true);
        return InteractionResult.CONSUME;
    }

    /** Config-gated console trace for capture/conversion — off by default, see the class doc. */
    public static void debugLog(String format, Object... args) {
        if (SewvConfig.MEDIC_CAPTURE_DEBUG_LOGGING.get()) {
            LOGGER.info("[sewv medic-capture] " + format, args);
        }
    }

    /**
     * Build a rookie handgun from the engineer sidearm pool: a random TACZ gun id, zero magazine
     * ammo, and no dummy-ammo reserve (which is only for inventory-less RU/US units). The freshly
     * converted PMC has a real inventory and resupplies through normal TACZ channels.
     */
    private static ItemStack buildRookieHandgun(RandomSource random) {
        var pool = SewvConfig.ENGINEER_SIDEARM_POOL.get();
        if (pool.isEmpty()) {
            return ItemStack.EMPTY;
        }
        var id = ResourceLocation.tryParse(pool.get(random.nextInt(pool.size())));
        if (id == null) {
            return ItemStack.EMPTY;
        }
        return GunItemBuilder.create()
                .setId(id)
                .setAmmoCount(0) // Deliberately zero — player must supply ammo.
                .setFireMode(FireMode.SEMI)
                .setCount(1)
                .build();
        // No setMaxDummyAmmoAmount/setDummyAmmoAmount — that's for RU/US units without inventories.
    }
}

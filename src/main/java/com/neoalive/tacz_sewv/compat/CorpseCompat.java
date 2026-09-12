package com.neoalive.tacz_sewv.compat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.IMedicCaptured;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.UnitCorpseAppearance;
import com.neoalive.tacz_sewv.util.UnitCorpseLoot;

/**
 * Soft-compat facade for <b>Corpse</b> ({@code corpse}, henkelmax).
 *
 * <p>This is the <b>only</b> SEWV class allowed to touch {@code de.maxhenkel.corpse.*} /
 * relocated CoreLib death types. Those imports live exclusively in the private {@link Access}
 * nested class, classloaded only after {@link #isLoaded()} returns true.
 *
 * <p>RU/US/PMC unit deaths spawn a lootable {@code CorpseEntity} and clear ground drops. Players
 * stay on CorpseMod's own path. Compatible with or without PlayerRevive: PMC downed cancels
 * {@link LivingDeathEvent} until bleed-out, so this never runs for a still-revivable unit.
 *
 * <p>Opt into the run classpath with {@code ./gradlew runClient -PwithCorpse}.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class CorpseCompat {

    public static final String MODID = "corpse";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** PMC inventory snapshots keyed by entity network id — taken before SEM empties pockets. */
    private static final Map<Integer, UnitCorpseLoot.PmcSnapshot> PMC_SNAPSHOTS = new ConcurrentHashMap<>();

    private CorpseCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    public static void reportAvailability() {
        if (isLoaded()) {
            LOGGER.info("Corpse soft-compat available (mod id {}) — SEM unit corpses enabled when configured", MODID);
        } else {
            LOGGER.info("Corpse absent — soft-compat facade idle; SEM units keep ground drops");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!isLoaded() || !SewvConfig.UNIT_CORPSE_COMPAT.get()) return;
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof AbstractUnit unit)) return;
        if (unit.level().isClientSide) return;
        if (shouldSkipNotTrulyDead(unit)) return;
        if (!(unit instanceof PmcUnitEntity) || !SewvConfig.UNIT_CORPSE_PMC.get()) return;

        UnitCorpseLoot.PmcSnapshot snap = UnitCorpseLoot.snapshotPmc(unit);
        if (snap != null) {
            PMC_SNAPSHOTS.put(unit.getId(), snap);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!isLoaded() || !SewvConfig.UNIT_CORPSE_COMPAT.get()) return;
        if (!(event.getEntity() instanceof AbstractUnit unit)) return;
        if (unit.level().isClientSide) return;
        if (shouldSkipNotTrulyDead(unit)) return;
        if (!factionEnabled(unit)) {
            PMC_SNAPSHOTS.remove(unit.getId());
            return;
        }

        boolean spawned = false;
        try {
            spawned = Access.trySpawn(unit);
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            // Corpse jar vanished mid-session or Access classload failed — leave SEM drops alone.
            LOGGER.warn("Corpse soft-compat spawn failed (Corpse types missing): {}", e.toString());
        } catch (Throwable t) {
            LOGGER.warn("Corpse soft-compat spawn failed for {}: {}", unit, t.toString());
        } finally {
            PMC_SNAPSHOTS.remove(unit.getId());
        }
        if (spawned) {
            event.getDrops().clear();
            if (unit instanceof PmcUnitEntity) {
                UnitCorpseLoot.clearPmcHandler(unit);
            }
        }
    }

    private static boolean shouldSkipNotTrulyDead(LivingEntity unit) {
        // Medic capture cancels LivingDeathEvent; this is a belt-and-suspenders guard.
        // Do NOT gate on IPmcDowned — bleed-out death keeps isDowned=true and must still spawn.
        return unit instanceof IMedicCaptured captured && captured.sewv$isCaptured();
    }

    private static boolean factionEnabled(AbstractUnit unit) {
        if (unit instanceof PmcUnitEntity) return SewvConfig.UNIT_CORPSE_PMC.get();
        if (unit instanceof RUunitEntity) return SewvConfig.UNIT_CORPSE_RU.get();
        if (unit instanceof USunitEntity) return SewvConfig.UNIT_CORPSE_US.get();
        return false;
    }

    /**
     * CorpseMod types only. Never referenced until {@link #isLoaded()} is true, so an install
     * without Corpse never classloads this nested class.
     */
    private static final class Access {

        private Access() {}

        static boolean trySpawn(AbstractUnit unit) {
            if (!CorpseCompat.isLoaded()) return false;
            if (!(unit.level() instanceof ServerLevel level)) return false;

            UnitCorpseLoot.Fill fill;
            UUID corpseUuid;
            if (unit instanceof PmcUnitEntity pmc) {
                UnitCorpseLoot.PmcSnapshot snap = PMC_SNAPSHOTS.get(unit.getId());
                if (snap == null) snap = UnitCorpseLoot.snapshotPmc(unit);
                if (snap == null) return false;
                fill = UnitCorpseLoot.fromPmcSnapshot(snap);
                UUID owner = pmc.getOwnerUUID();
                corpseUuid = owner != null ? owner : unit.getUUID();
            } else if (unit instanceof RUunitEntity) {
                fill = UnitCorpseLoot.rollFaction(unit, "ru", level);
                corpseUuid = unit.getUUID();
            } else if (unit instanceof USunitEntity) {
                fill = UnitCorpseLoot.rollFaction(unit, "us", level);
                corpseUuid = unit.getUUID();
            } else {
                return false;
            }

            if (fill.isVisiblyEmpty()) return false;

            de.maxhenkel.corpse.corelib.death.Death death = buildDeath(unit, corpseUuid, fill);
            var host = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
            de.maxhenkel.corpse.entities.CorpseEntity corpse =
                    de.maxhenkel.corpse.entities.CorpseEntity.createFromDeath(host, death);
            corpse.setYRot(unit.getYRot());
            level.addFreshEntity(corpse);
            return true;
        }

        private static de.maxhenkel.corpse.corelib.death.Death buildDeath(
                AbstractUnit unit, UUID corpseUuid, UnitCorpseLoot.Fill fill) {
            NonNullList<ItemStack> main = toNonNull(fill.main());
            NonNullList<ItemStack> armor = toNonNull(fill.armor());
            NonNullList<ItemStack> offhand = toNonNull(fill.offhand());
            NonNullList<ItemStack> additional = NonNullList.create();
            for (ItemStack stack : fill.additional()) {
                if (stack != null && !stack.isEmpty()) additional.add(stack.copy());
            }
            NonNullList<ItemStack> equipment = toNonNull(fill.equipment());
            if (equipment.size() < EquipmentSlot.values().length) {
                NonNullList<ItemStack> resized =
                        NonNullList.withSize(EquipmentSlot.values().length, ItemStack.EMPTY);
                for (int i = 0; i < equipment.size(); i++) {
                    resized.set(i, equipment.get(i));
                }
                equipment = resized;
            }

            String display = unitName(unit);
            UnitCorpseAppearance appearance = UnitCorpseAppearance.of(unit);
            String corpseName = appearance != null ? appearance.encodeName(display) : display;

            return new de.maxhenkel.corpse.corelib.death.Death.Builder(corpseUuid, UUID.randomUUID())
                    .playerName(corpseName)
                    .mainInventory(main)
                    .armorInventory(armor)
                    .offHandInventory(offhand)
                    .additionalItems(additional)
                    .equipment(equipment)
                    .posX(unit.getX())
                    .posY(unit.getY())
                    .posZ(unit.getZ())
                    .dimension(unit.level().dimension().location().toString())
                    .timestamp(System.currentTimeMillis())
                    .model((byte) 0)
                    .build();
        }

        private static String unitName(AbstractUnit unit) {
            if (unit.hasCustomName()) {
                var custom = unit.getCustomName();
                if (custom != null) {
                    String text = custom.getString();
                    if (text != null && !text.isEmpty()) return text;
                }
            }
            String display = unit.getDisplayName().getString();
            return display == null || display.isEmpty() ? "Unit" : display;
        }

        private static NonNullList<ItemStack> toNonNull(ItemStack[] slots) {
            NonNullList<ItemStack> list = NonNullList.withSize(slots.length, ItemStack.EMPTY);
            for (int i = 0; i < slots.length; i++) {
                ItemStack stack = slots[i];
                list.set(i, stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            }
            return list;
        }
    }
}

package com.neoalive.tacz_sewv.command.quick.scavenge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.EquipmentDeploy;
import com.neoalive.tacz_sewv.entity.ai.support.GrenadeSupport;
import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MineKinds;
import com.neoalive.tacz_sewv.fob.FobResupplySupport;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;

/**
 * What one unit still wants, read live against its own inventory, so each take lowers the next
 * answer. Ammo is what its own guns fire, up to a few stacks; emplacements and utilities are capped
 * per type against what it already carries.
 */
final class ScavengeWants {

    enum Utility {
        GRENADE, MEDKIT, SHOVEL, REPAIR_TOOL, RADIO;

        int cap() {
            return switch (this) {
                case GRENADE -> SewvConfig.SCAVENGE_GRENADE_CAP.get();
                case MEDKIT -> SewvConfig.SCAVENGE_MEDKIT_CAP.get();
                case SHOVEL -> SewvConfig.SCAVENGE_SHOVEL_CAP.get();
                case REPAIR_TOOL -> SewvConfig.SCAVENGE_REPAIR_TOOL_CAP.get();
                case RADIO -> SewvConfig.SCAVENGE_RADIO_CAP.get();
            };
        }

        @Nullable
        static Utility of(ItemStack stack) {
            if (stack.isEmpty()) return null;
            if (GrenadeSupport.isGrenadeItem(stack)) return GRENADE;
            if (SupportRole.isMedicalKit(stack)) return MEDKIT;
            if (SupportRole.isShovel(stack)) return SHOVEL;
            if (SupportRole.isRepairTool(stack)) return REPAIR_TOOL;
            if (HandheldRadioItem.isRadio(stack)) return RADIO;
            return null;
        }
    }

    final PmcUnitEntity unit;
    final IItemHandlerModifiable inv;
    final PocketView pockets;
    private final FobResupplySupport.AmmoNeeds taczAmmo;
    private final List<AmmoConsumer> sbwAmmo = new ArrayList<>();
    private final int ammoStacks;
    private final List<String> towIds;
    private final List<String> mortarIds;

    private ScavengeWants(ServerLevel level, PmcUnitEntity unit, IItemHandlerModifiable inv) {
        this.unit = unit;
        this.inv = inv;
        this.pockets = new PocketView(inv);
        this.ammoStacks = SewvConfig.SCAVENGE_AMMO_STACKS.get();
        this.taczAmmo = FobResupplySupport.ammoNeeds(unit, this.ammoStacks);
        addSbwAmmo(unit.getMainHandItem());
        addSbwAmmo(unit.getOffhandItem());
        WorldVehiclePools pools = WorldVehiclePools.get(level);
        this.towIds = pools.list(TankFaction.PMC, WorldVehiclePools.Category.TOW);
        this.mortarIds = pools.list(TankFaction.PMC, WorldVehiclePools.Category.MORTAR);
    }

    @Nullable
    static ScavengeWants of(ServerLevel level, PmcUnitEntity unit) {
        unit.syncEquipmentToInventory();
        IItemHandlerModifiable inv = RoleKit.inventory(unit);
        return inv == null ? null : new ScavengeWants(level, unit, inv);
    }

    private void addSbwAmmo(ItemStack hand) {
        GunData gun = SmallArmsSupport.usableGun(hand);
        if (gun == null) return;
        try {
            AmmoConsumer consumer = gun.selectedAmmoConsumer();
            if (consumer != null) this.sbwAmmo.add(consumer);
        } catch (Throwable ignored) {
            // unreadable gun data never stops a scavenge
        }
    }

    boolean pocketsFull() {
        return this.pockets.full();
    }

    /** How many of this stack the unit takes in {@code stage}; 0 when it is not wanted there. */
    int wanted(ScavengeStage stage, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        return switch (stage) {
            case AMMO -> ammoWanted(stack);
            case EMPLACEMENT -> {
                if (isMine(stack)) yield mineRoom();
                yield isDeployable(stack) ? emplacementRoom() : 0;
            }
            case UTILITY -> {
                Utility u = Utility.of(stack);
                yield u == null ? 0 : ScavengeRules.remaining(u.cap(), count(s -> Utility.of(s) == u));
            }
            case ARMOR -> armorWanted(stack);
        };
    }

    /** Hand slots 2 to 5 mirror FEET to HEAD on a PMC's handler. */
    private static final int FIRST_ARMOR_SLOT = 2;

    private int armorWanted(ItemStack stack) {
        if (!SewvConfig.SCAVENGE_ARMOR.get()) return 0;
        EquipmentSlot slot = ScavengeItems.armorSlot(stack);
        if (slot == null || slot.getType() != EquipmentSlot.Type.ARMOR) return 0;
        // SEM keeps ammo in a PMC's FEET slot; a slot holding anything but armor is left alone.
        return ScavengeItems.beats(stack, worn(slot)) ? 1 : 0;
    }

    ItemStack worn(EquipmentSlot slot) {
        int index = FIRST_ARMOR_SLOT + slot.getIndex();
        return index < this.inv.getSlots() ? this.inv.getStackInSlot(index) : ItemStack.EMPTY;
    }

    /** Puts the piece on and returns what was worn before. */
    ItemStack wear(ItemStack piece) {
        EquipmentSlot slot = ScavengeItems.armorSlot(piece);
        if (slot == null) return piece;
        int index = FIRST_ARMOR_SLOT + slot.getIndex();
        if (index >= this.inv.getSlots()) return piece;
        ItemStack old = this.inv.getStackInSlot(index).copy();
        this.inv.setStackInSlot(index, piece);
        return old;
    }

    private int ammoWanted(ItemStack stack) {
        int tacz = this.taczAmmo.wanted(stack);
        if (tacz > 0) return tacz;
        for (AmmoConsumer consumer : this.sbwAmmo) {
            if (!consumer.isAmmoItem(stack)) continue;
            int have = countPockets(consumer::isAmmoItem);
            return Math.max(0, this.ammoStacks * stack.getMaxStackSize() - have);
        }
        return 0;
    }

    int emplacementRoom() {
        return ScavengeRules.remaining(SewvConfig.SCAVENGE_EMPLACEMENT_CAP.get(), count(this::isDeployable));
    }

    int mineRoom() {
        return ScavengeRules.remaining(SewvConfig.SCAVENGE_MINE_CAP.get(), count(ScavengeWants::isMine));
    }

    boolean isDeployable(ItemStack stack) {
        return EquipmentDeploy.resolve(stack, 0, this.towIds, this.mortarIds) != null;
    }

    static boolean isMine(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && MineKinds.all().containsKey(id);
    }

    /** Hands and pockets: a medkit already in hand counts against the cap. */
    private int count(Predicate<ItemStack> test) {
        int n = 0;
        for (int slot = 0; slot < this.inv.getSlots(); slot++) {
            ItemStack s = this.inv.getStackInSlot(slot);
            if (!s.isEmpty() && test.test(s)) n += s.getCount();
        }
        return n;
    }

    private int countPockets(Predicate<ItemStack> test) {
        int n = 0;
        for (int slot = 0; slot < this.pockets.getSlots(); slot++) {
            ItemStack s = this.pockets.getStackInSlot(slot);
            if (!s.isEmpty() && test.test(s)) n += s.getCount();
        }
        return n;
    }
}

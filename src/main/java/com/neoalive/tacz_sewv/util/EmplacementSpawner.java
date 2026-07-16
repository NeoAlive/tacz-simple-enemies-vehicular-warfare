package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.item.projectile.MortarShellItem;
import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.MortarSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Spawns a crew-served static weapon with its crew already on it.
 *
 * <p>Separate from {@link TankSpawner} because emplacements break all of its assumptions. A
 * mortar has no seats at all, so there is nothing to mount and the crew stands beside it
 * holding a claim instead; neither weapon draws from the hull's container, so TankSpawner's
 * "creative ammo box in slot 0" does nothing for them; and the two crew types cannot even be
 * armed the same way — see {@link #supply}. Every crew leaves here able to fire.
 *
 * <p>This is also the only way an RU/US crew can ever end up on one. PMC units are ordered
 * onto a tube with the board key over the network bridge; RU/US units have no order queue
 * for such an order to arrive through, so the claim is made here, at spawn.
 */
public final class EmplacementSpawner {

    /** How far from the tube the crew is dropped; ManMortarGoal walks it the rest of the way. */
    private static final int CREW_OFFSET = 2;

    /**
     * First storage slot of SEM's 18-slot unit inventory; 0-5 are the equipment mapping.
     */
    private static final int FIRST_STORAGE_SLOT = 6;

    /** Share of mortar crews issued the low-chance shell. Deliberately not configurable. */
    private static final double LOW_CHANCE_SHELL = 0.25;

    private static final Logger LOGGER = LogUtils.getLogger();

    private EmplacementSpawner() {}

    public enum Emplacement { MORTAR, TOW }

    /**
     * Spawns {@code type} at {@code pos} with one crew of {@code faction} already working it
     * and issued its ammunition. Returns the weapon, or null if it wouldn't fit.
     *
     * @param fireMission for a MORTAR, an order to shell a position while the crew has nothing
     *                    in sight, and to leave the tube when it expires; ignored by a TOW,
     *                    which is direct-fire.
     */
    @Nullable
    public static VehicleEntity spawn(ServerLevel level, BlockPos pos, Emplacement type,
                                      TankSpawner.TankFaction faction, @Nullable UUID ownerId,
                                      @Nullable FireMission fireMission) {
        EntityType<? extends VehicleEntity> entityType =
                type == Emplacement.MORTAR ? ModEntities.MORTAR.get() : ModEntities.TOW.get();
        if (!TankSpawner.hasSpace(level, pos, entityType)) return null;

        VehicleEntity weapon = entityType.create(level);
        if (weapon == null) return null;
        weapon.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(weapon);

        AbstractUnit crew = TankSpawner.createCrewUnit(level, faction, ownerId);
        BlockPos crewPos = type == Emplacement.MORTAR ? pos.offset(CREW_OFFSET, 0, 0) : pos;
        crew.setPos(crewPos.getX() + 0.5, crewPos.getY(), crewPos.getZ() + 0.5);
        crew.finalizeSpawn(level, level.getCurrentDifficultyAt(crewPos), MobSpawnType.EVENT, null, null);
        level.addFreshEntity(crew);

        if (type == Emplacement.MORTAR) {
            // Cannot fail: a mortar has no seat to refuse the crew, which is the whole reason
            // the claim exists instead of a mount.
            crewMortar(level, (MortarEntity) weapon, crew, fireMission);
        } else if (!crewTow((TowEntity) weapon, crew)) {
            // The seat refused the rider — don't leave a crewless launcher standing next to a
            // launcher-less crew.
            crew.discard();
            weapon.discard();
            return null;
        }

        supply(crew, faction, type, level.random);
        return weapon;
    }

    private static void crewMortar(ServerLevel level, MortarEntity mortar, AbstractUnit crew,
                                   @Nullable FireMission fireMission) {
        MortarSupport.claim(crew, mortar);
        ((IMortarCrew) crew).sewv$setFireMission(fireMission);
        holdChunk(level, crew);
    }

    private static boolean crewTow(TowEntity tow, AbstractUnit crew) {
        return crew.startRiding(tow);
    }

    /**
     * Arms the crew, by whichever route its unit type actually supports.
     *
     * <p>The two halves are not a style choice — the unit types genuinely differ. A PMC unit
     * is the one type SEM gives an inventory to, so it gets <b>real stacks</b> it can run out
     * of. An RU/US unit has no inventory at all, and every attempt to read one answers 0
     * rather than failing, so it gets an <b>issued</b> supply that needs no storage
     * ({@link IIssuedAmmo}).
     *
     * <p>Which ammunition is chosen is shared; only the delivery differs. Handled here at the
     * spawn site rather than by which interfaces a unit's mixin carries, because who gets
     * resupplied is doctrine and doctrine should be readable where the crew is made.
     */
    private static void supply(AbstractUnit crew, TankSpawner.TankFaction faction,
                               Emplacement type, RandomSource random) {
        Item ammo = ammoFor(type, random);
        if (faction == TankSpawner.TankFaction.PMC) {
            fillInventory(crew, ammo);
        } else if (crew instanceof IIssuedAmmo issued) {
            issued.sewv$setIssuedAmmo(ammo);
        }
    }

    /** What a crew of this weapon shoots. */
    private static Item ammoFor(Emplacement type, RandomSource random) {
        if (type == Emplacement.TOW) return ModItems.MEDIUM_ANTI_GROUND_MISSILE.get();

        // Rolled once, per crew, so a battery's tubes can differ from each other but a given
        // tube shoots one thing throughout rather than alternating at random.
        String id = random.nextDouble() < LOW_CHANCE_SHELL
                ? SewvConfig.LOW_CHANCE_MORTAR_SHELL.get()
                : SewvConfig.HIGH_CHANCE_MORTAR_SHELL.get();
        return resolveShell(id);
    }

    /**
     * The configured shell, or the plain one if that id can't be a shell.
     *
     * <p>The config validator only proves an id is well-formed — registries aren't loaded yet
     * at config time — so the real check is here. Both the {@code containsKey} guard and the
     * {@code instanceof} matter: the item registry is defaulted, so a since-removed addon's id
     * would hand back {@code minecraft:air}, and any non-shell would leave the crew holding a
     * full inventory of something the tube can never load. Falling back keeps it firing.
     */
    private static Item resolveShell(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key != null && ForgeRegistries.ITEMS.containsKey(key)) {
            Item item = ForgeRegistries.ITEMS.getValue(key);
            if (item instanceof MortarShellItem) return item;
        }
        LOGGER.warn("[tacz_sewv] '{}' is not a usable mortar shell — falling back to {}",
                id, ModItems.MORTAR_SHELL.getId());
        return ModItems.MORTAR_SHELL.get();
    }

    /**
     * Fills a PMC crew's storage with as much ammunition as it will hold.
     *
     * <p>Starts at slot 6 because SEM's 18-slot inventory maps 0-5 onto equipment — slot 0 is
     * the main hand, which it reads to draw a TACZ gun — so a shell written there would be
     * shouldered instead of fired. The remaining 12 slots come to 96 shells or 48 missiles,
     * enough that a crew is worth deploying without being unlimited.
     *
     * <p>Filling at spawn rather than leaving it to the owner is not just convenience for the
     * mortar: a TOW crew is <em>sitting in its launcher</em>, and there is no reliable way to
     * open the inventory of a unit in a seat.
     */
    private static void fillInventory(AbstractUnit crew, Item ammo) {
        IItemHandler inventory = crew.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (inventory == null) return;

        for (int slot = FIRST_STORAGE_SLOT; slot < inventory.getSlots(); slot++) {
            inventory.insertItem(slot, new ItemStack(ammo, ammo.getMaxStackSize()), false);
        }
    }

    /**
     * Bootstraps the crew's chunk ticket so a battery spawned beyond the player's simulation
     * distance actually works. See {@link ChunkTicket#bootstrap}.
     *
     * <p>Gated on the same config as the goal that adopts it: with chunk loading off the goal
     * never releases, so taking a ticket here would leak one.
     */
    private static void holdChunk(ServerLevel level, AbstractUnit crew) {
        if (!SewvConfig.MORTAR_CHUNK_LOADING.get()) return;
        ChunkTicket.bootstrap(level, crew);
    }
}

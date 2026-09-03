package com.neoalive.tacz_sewv.compat;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.minecolonies.api.colony.colonyEvents.registry.ColonyEventTypeRegistryEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.RegisterEvent;

import com.neoalive.tacz_sewv.compat.minecolonies.SewvArmoredRaidEvent;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;

/**
 * Soft-compat facade for <b>MineColonies</b>.
 *
 * <p>Like {@link OpenPacCompat}, every {@code com.minecolonies.*} type lives exclusively in the
 * private {@link Access} nested class, which is classloaded only after {@link #present()} returns
 * true — so this outer class is safe for anyone to call on an install without MineColonies. The
 * two gated mixins under {@code mixin.minecolonies} are the only other place allowed to name
 * MineColonies types, and they are never queued at all when the mod is absent
 * ({@code MineColoniesMixinPlugin}).
 *
 * <h2>Why this exists</h2>
 * {@code AbstractUnit extends net.minecraft.world.entity.monster.Monster}, so every SEM unit is an
 * {@code Enemy}, and SEM registers {@code pmcunit}/{@code ruunit} as {@code MobCategory.MONSTER}
 * but {@code usunit} as {@code MobCategory.MISC}. MineColonies keys both halves of its combat on
 * exactly those two facts and gets three wrong answers out of them:
 * <ul>
 *   <li>Its {@code EntityJoinLevelEvent} handler adds a citizen-hunting
 *       {@code NearestAttackableTargetGoal} to every {@code Mob} that is {@code Enemy} — including
 *       the player's own PMC squad. It writes {@code setTarget} directly, so none of this mod's
 *       own gates ({@code VehicleTargeting.isNonHostile}, {@code WorldTargetPriority}) see it.
 *       Fixed by datapack alone, below.</li>
 *   <li>Guard targeting reads {@code CompatibilityManager.getAllMonsters()}, which is
 *       {@code MobCategory.MONSTER} plus the {@code #minecolonies:hostile} tag. So guards shoot
 *       the player's own PMC and never react to a US unit. The tag fixes the US half (tags can
 *       only add to that set); {@code MixinGuardTargeting} fixes the PMC half.</li>
 *   <li>All MineColonies combat is {@code LivingEntity}-typed and citizen threat registration
 *       keys on {@code DamageSource.getEntity() instanceof LivingEntity}, so a vehicle hull is
 *       invisible coming and going: it cannot be targeted, and shooting a citizen with it raises
 *       no alarm at all. {@link #onCitizenHurt} closes the alarm half by blaming the crew.</li>
 * </ul>
 *
 * <h2>The two shipped tag files, since JSON cannot carry the reasoning</h2>
 * {@code data/minecolonies/tags/entity_types/mob_attack_blacklist.json} holds the units that must
 * not hunt citizens: the PMC unit and commander (the player's own), and both medics, which refuse
 * every target anyway so the goal would only churn. RU/US line units are deliberately left out —
 * a hostile faction attacking a colony is the emergent behaviour worth keeping.
 * {@code hostile.json} holds {@code usunit} alone, the one faction unit no colony could otherwise
 * attack. The medics are pointedly <em>not</em> in it: this mod's doctrine is that a medic is
 * targeted by nobody ({@code VehicleTargeting.isMedic}), and a colony shooting one would be the
 * only place in the game that broke it.
 *
 * <h2>Not handled, on purpose</h2>
 * SuperbWarfare's {@code CustomExplosion.explode()} does fire {@code ForgeEventFactory
 * .onExplosionDetonate}, so MineColonies' own colony filtering already applies to artillery. At
 * its default {@code turnoffexplosionsincolonies = damage_entities} that strips the affected
 * <em>blocks</em> inside a colony while leaving entity damage — shells kill citizens but never
 * scratch a wall. That is MineColonies' server setting to make, not this mod's to override.
 */
public final class MineColoniesCompat {

    public static final String MODID = "minecolonies";

    private static Boolean loaded;

    private MineColoniesCompat() {}

    /**
     * Whether the compat may touch MineColonies at all. Tests the mod list before the config so an
     * install without MineColonies never reads the key, and caches the mod-list answer since it
     * cannot change within a run.
     *
     * <p>Every caller outside this class must gate on this before anything that could reach
     * {@link Access}.
     */
    public static boolean present() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded(MODID);
        }
        return loaded && SewvConfig.MINECOLONIES_COMPAT_ENABLED.get();
    }

    /**
     * @param modEventBus needed for the colony event type registration, which is a
     *                    {@code RegisterEvent} on MineColonies' own {@code colonyeventtypes}
     *                    registry and therefore mod-bus, not game-bus.
     */
    public static void register(IEventBus modEventBus) {
        MinecraftForge.EVENT_BUS.register(MineColoniesCompat.class);
        Access.registerEventType(modEventBus);
    }

    /**
     * Whether this position sits in any colony's claimed chunks — the gate the procedural war
     * events consult before placing anything. False when MineColonies is absent or the exclusion
     * is switched off, so callers need no gate of their own.
     */
    public static boolean inAnyColony(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        if (!present() || !SewvConfig.MINECOLONIES_SPAWN_EXCLUSION.get()) return false;
        return Access.inAnyColony(level, pos);
    }

    /**
     * Makes a citizen shot from a vehicle raise the alarm against its <b>crew</b>.
     *
     * <p>MineColonies registers threat and calls for help only when {@code
     * DamageSource.getEntity()} is a {@code LivingEntity} — both {@code EntityCitizen}'s threat
     * write and its {@code callForHelp} bail otherwise. A SuperbWarfare hull is a plain
     * {@code Entity}, so untreated a tank kills a whole colony without a single guard reacting.
     * Blaming a crewman rather than the hull is not an approximation but the only useful answer:
     * guard combat is {@code LivingEntity}-typed end to end, so a hull could never be shot back
     * at even if it could be targeted.
     *
     * <p>Runs at {@code LOWEST} so anything that would cancel the hit (the colony's own explosion
     * filtering included) has already decided; this only observes.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCitizenHurt(LivingHurtEvent event) {
        if (!present() || !SewvConfig.MINECOLONIES_VEHICLE_RETALIATION.get()) return;
        if (event.getEntity().level().isClientSide) return;
        // A living attacker is the case MineColonies already handles, including a crewman that
        // SuperbWarfare credited directly. Reporting it again here would double-count the threat.
        if (event.getSource().getEntity() instanceof LivingEntity) return;

        LivingEntity blame = crewBehind(event.getSource().getEntity());
        if (blame == null) blame = crewBehind(event.getSource().getDirectEntity());
        if (blame == null) return;

        Access.raiseAlarm(event.getEntity(), blame, (int) event.getAmount());
    }

    /**
     * The living crewman behind a hit credited to a non-living entity, or null if no vehicle was
     * involved. Both the source and the direct entity are tried by the caller because
     * SuperbWarfare splits them differently per weapon (a shell credits its owner, an explosion
     * credits the projectile) and neither position is reliably the hull.
     */
    @Nullable
    private static LivingEntity crewBehind(@Nullable Entity candidate) {
        if (candidate == null) return null;
        VehicleEntity hull = candidate instanceof VehicleEntity v ? v
                : candidate.getVehicle() instanceof VehicleEntity riding ? riding
                : null;
        if (hull == null) return null;

        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof LivingEntity crew) return crew;
        }
        // A mortar is a VehicleEntity with no seats — its crew stands beside the tube — so the
        // passenger list is empty for exactly the weapon most likely to be shelling a colony
        // (MortarShellingEvent aims at a player's respawn point). MortarSupport.crewOf is the
        // same claim scan that answers "whose tube is this?" everywhere else.
        if (hull instanceof MortarEntity mortar) {
            return MortarSupport.crewOf(mortar, null);
        }
        return null;
    }

    /** Every {@code com.minecolonies.*} reference in the mod outside the two gated mixins. */
    private static final class Access {

        private Access() {}

        static boolean inAnyColony(Level level, BlockPos pos) {
            return com.minecolonies.api.colony.IColonyManager.getInstance()
                    .isCoordinateInAnyColony(level, pos);
        }

        /**
         * Both halves of the reaction MineColonies skipped: the threat table is what its guard AI
         * ranks targets from, {@code callForHelp} is what pulls guards off their patrol. 900 is
         * the range MineColonies itself passes when a guard citizen calls for help.
         */
        static void raiseAlarm(LivingEntity victim, LivingEntity attacker, int amount) {
            if (!(victim instanceof com.minecolonies.core.entity.citizen.EntityCitizen citizen)) return;
            citizen.getThreatTable().addThreat(attacker, amount);
            citizen.callForHelp(attacker, 900);
        }

        /**
         * Registers {@code SewvArmoredRaidEvent} in MineColonies' {@code colonyeventtypes}
         * registry, which buys exactly one thing: a raid in progress survives a save/load.
         * {@code RaidManager} never consults the registry to decide what to spawn — the mixin does
         * that — so without this the raid would simply vanish when the world reloaded.
         *
         * <p>The key is deliberately in the <b>minecolonies</b> namespace.
         * {@code EventManager.readFromNBT} stores only the path and rebuilds the id as
         * {@code new ResourceLocation(MOD_ID, path)} with its own mod id hardcoded, so an entry
         * registered under {@code tacz_sewv:} could be written but never read back. This is also
         * why the registration is a raw {@code RegisterEvent} rather than a {@code DeferredRegister}
         * — the latter builds its keys from the owning mod's id and has no way to say otherwise.
         */
        static void registerEventType(IEventBus modEventBus) {
            modEventBus.addListener((RegisterEvent event) -> event.register(
                    ResourceKey.createRegistryKey(
                            new ResourceLocation(MODID, "colonyeventtypes")),
                    SewvArmoredRaidEvent.TYPE_ID,
                    () -> new ColonyEventTypeRegistryEntry(
                            SewvArmoredRaidEvent::loadFromNBT, SewvArmoredRaidEvent.TYPE_ID, true)));
        }
    }
}

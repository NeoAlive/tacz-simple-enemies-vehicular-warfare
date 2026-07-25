package com.neoalive.tacz_sewv.entity.ai.utility;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.FireMissionSupport;
import com.neoalive.tacz_sewv.entity.ai.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.util.CrewFacts;
import com.neoalive.tacz_sewv.util.SmokeVision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What one vehicle crew knows about the battlefield. Gathers, never decides.
 *
 * <p>Refreshed on a game-time deadline (~once a second), not every tick: this is the expensive
 * half of the AI — one world scan and a handful of reads that route through SuperbWarfare's
 * {@code computed()} — while steering and firing stay tick-based and read only the fields left
 * here. Owned per crew by the drive goal, using the same attach-on-hull-identity caching
 * {@code HullFacts} and {@code StalemateBreaker} already use, so it inherits the goal's
 * driver-only ownership and needs no lifecycle of its own.
 *
 * <p>Fields are public and mutable on purpose. This is a plain struct read once per replan by
 * the scorer; accessors would be 40 lines of nothing.
 */
public final class Facts {

    /**
     * Remaining rounds, as a category rather than a percentage.
     *
     * <p>There is no denominator to make a percentage out of: SuperbWarfare exposes a per-slot
     * COUNT with no maximum, and {@code Integer.MAX_VALUE} means an infinite supply. An absolute
     * threshold is the honest reading.
     */
    public enum Ammo { OUT, LOW, OK }

    /**
     * Biomes grouped by what they mean tactically, because a crew cares about cover and sightlines,
     * not about whether it is standing in a birch forest or an old-growth pine one.
     */
    public enum Ground { OPEN, FOREST, URBAN, MOUNTAIN, SWAMP, DESERT }

    /** Weather grouped the same way: by how much it hides you and spoils your shooting. */
    public enum Sky { CLEAR, RAIN, SNOW, STORM }

    // ponytail: one absolute "running low" line for every weapon, from a cannon to a coaxial MG.
    // A per-weapon fraction would need each gun's intended load, which the vehicle data does not
    // state anywhere; revisit if a weapon shows up where 5 rounds is not a meaningful reserve.
    private static final int LOW_AMMO_ROUNDS = 5;

    /** How long a contact stays worth acting on after it is lost. */
    public static final long CONTACT_MEMORY_TICKS = 600;   // 30 s

    // ---- cached per hull (all route through computed(), too expensive for a tick) ----
    private VehicleEntity vehicle;
    private float maxHealth = 1.0F;
    private boolean hasEnergyStorage;
    private int maxEnergy;
    private boolean hasDecoy;

    private long nextRefresh = Long.MIN_VALUE;
    private long nextCommsScan = Long.MIN_VALUE;

    // ---- unit status ----
    /** 0..1, clamped: SuperbWarfare lets hull health go negative. */
    public float health = 1.0F;
    /** 0..1. Reads 1.0 for RU/US, whose hulls are given infinite energy by MixinVehicleFactionEnergy. */
    public float energy = 1.0F;
    public Ammo ammo = Ammo.OK;
    /** The weapon is loaded, cool, off reload and past our own AI fire throttle. */
    public boolean canShoot;
    /** A smoke volley is available right now. */
    public boolean smokeReady;
    /** Our own line to the target is already through smoke — so more smoke buys nothing. */
    public boolean screened;
    /** Blocks per tick. */
    public double speed;

    // ---- local battlefield ----
    @Nullable
    public LivingEntity target;
    @Nullable
    public TargetCategory targetCategory;
    /** Blocks to the current target, or {@link Double#MAX_VALUE} with none. */
    public double targetDist = Double.MAX_VALUE;
    public int allies;
    public int enemies;
    /** Friendlies nearby with no target of their own — who our target could be handed to. */
    public int idleAllies;
    /** allies+1 (us) over enemies; 1.0 is an even fight, above 1 favours us. */
    public double forceRatio = 1.0;
    public double nearestAllyDist = Double.MAX_VALUE;

    /** The range we want to fight the current target at. See {@link #preferredRange}. */
    public double preferredRange;
    /**
     * How wrong our range is, as a fraction of the preferred one: negative is too close,
     * positive is too far, zero is on the ring. 0 with no target.
     */
    public double rangeError;

    // ---- environment ----
    public Ground ground = Ground.OPEN;
    public Sky sky = Sky.CLEAR;
    /** Biggest height change over a hull-length in any direction, in blocks. */
    public int slope;
    /** 0..1 by how far above the ordinary fighting altitude we are. The doc's mountain penalty. */
    public double altitude;

    // ---- communication ----
    /** Somebody on our side can work a radio. The cheap half of the comms check. */
    public boolean hasComms;
    /**
     * Which kinds of supporting fire are actually in range and manned right now — the doc's
     * Communication state. Empty unless {@link #hasComms}; these unlock actions rather than
     * modifying anyone's utility.
     */
    public Set<FireMissionSupport.Kind> support = Set.of();
    /** Our faction, and for PMC the commanding player — who a support call would go to. */
    @Nullable
    public CrewFacts.Faction faction;
    @Nullable
    public UUID owner;

    /** 0-100 overall estimate of battlefield advantage. See {@link #computeConfidence}. */
    public double confidence = 50.0;

    public final Memory memory = new Memory();

    /**
     * Re-read the battlefield if the deadline has passed.
     *
     * @return true if this call actually gathered — the caller's cue to re-score its options.
     */
    public boolean refresh(AbstractUnit unit, VehicleEntity hull, Doctrine doctrine) {
        long now = unit.level().getGameTime();
        attach(hull);

        // Memory is fed every tick, not on the refresh cadence: a contact that appears and dies
        // between two refreshes still has to be remembered, and these are all free field reads.
        this.memory.observe(unit, now);

        if (now < this.nextRefresh) return false;
        this.nextRefresh = now + SewvConfig.UTILITY_REFRESH_INTERVAL_TICKS.get();

        readStatus(unit, hull);
        readBattlefield(unit, hull);
        readEnvironment(unit, hull);
        readComms(unit, now);
        this.confidence = computeConfidence(doctrine);
        return true;
    }

    /**
     * What supporting fire could be called on right now.
     *
     * <p>Four gates in front of one world scan, because that scan reaches far further than any
     * other in this class (mortars are a several-hundred-block weapon) and would otherwise be the
     * single most expensive thing a crew does. In the common case — no radio, or nothing to shoot
     * at — none of it runs. It is also pointless to re-run between calls the crew is not allowed
     * to make yet, so it shares the request interval rather than the refresh one.
     */
    private void readComms(AbstractUnit unit, long now) {
        if (!this.hasComms || this.target == null) {
            this.support = Set.of();
            return;
        }
        if (now < this.nextCommsScan) return;
        this.nextCommsScan = now + SewvConfig.SUPPORT_CALL_INTERVAL_TICKS.get();

        this.support = FireMissionSupport.availableSupport(
                unit.level(), this.faction, this.owner, unit.position(),
                SewvConfig.MORTAR_RADIO_RANGE.get());
    }

    private void attach(VehicleEntity hull) {
        if (this.vehicle == hull) return;
        this.vehicle = hull;
        try {
            this.maxHealth = hull.getMaxHealth();
            this.hasEnergyStorage = hull.hasEnergyStorage();
            this.maxEnergy = this.hasEnergyStorage ? hull.getMaxEnergy() : 0;
            this.hasDecoy = hull.hasDecoy();
        } catch (Throwable ignored) {
            // Unreadable hull data must never crash an AI tick; every fallback is the one whose
            // behaviour is safe — healthy, fuelled, and with no smoke to reach for.
            this.maxHealth = 1.0F;
            this.hasEnergyStorage = false;
            this.maxEnergy = 0;
            this.hasDecoy = false;
        }
    }

    private void readStatus(AbstractUnit unit, VehicleEntity hull) {
        this.health = this.maxHealth > 0.0F
                ? Mth.clamp(hull.getHealth() / this.maxHealth, 0.0F, 1.0F)
                : 1.0F;

        // Only ask when there is a tank to ask about: getEnergy()/getMaxEnergy() log a warning
        // on every call for a hull with no energy storage (a mortar, a naval gun mount).
        this.energy = this.hasEnergyStorage && this.maxEnergy > 0
                ? Mth.clamp(hull.getEnergy() / (float) this.maxEnergy, 0.0F, 1.0F)
                : 1.0F;

        int seat = hull.getSeatIndex(unit);
        this.ammo = readAmmo(hull, seat);
        this.canShoot = seat >= 0 && safeCanShoot(hull, unit);
        this.smokeReady = this.hasDecoy && safeDecoyReady(hull);
        this.speed = hull.getLastTickSpeed();
    }

    private Ammo readAmmo(VehicleEntity hull, int seat) {
        if (seat < 0) return Ammo.OK;
        try {
            // "No such weapon" and "empty magazine" both count as 0, so the null check is what
            // stops a weaponless seat reporting itself permanently out of ammo.
            if (hull.getGunData(seat) == null) return Ammo.OK;
            int count = hull.getAmmoCount(seat);
            if (count <= 0) return Ammo.OUT;
            return count <= LOW_AMMO_ROUNDS ? Ammo.LOW : Ammo.OK;
        } catch (Throwable ignored) {
            return Ammo.OK;
        }
    }

    private static boolean safeCanShoot(VehicleEntity hull, AbstractUnit unit) {
        try {
            return hull.canShoot(unit);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean safeDecoyReady(VehicleEntity hull) {
        try {
            // getDecoyReady() is the synced ready flag and the only reliable signal. Deliberately
            // NOT decoyReloadCoolDown, which is re-armed to 500 the instant the launcher comes
            // back up and so is not "ticks until ready" at all.
            return hull.getDecoyReady();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The range a crew wants to hold against this kind of target — the doc's EngageDistance.
     *
     * <p>Armor is held at arm's length: two hulls that creep into a point-blank standstill can no
     * longer bring cannon or ATGM to bear on each other. Infantry is a comfortable band inside the
     * coaxial's effective range. These are the numbers the hand-written doctrine already used, kept
     * exactly so a neutral-doctrine crew fights at the ranges it always has.
     */
    public static double preferredRange(@Nullable TargetCategory category) {
        return category == TargetCategory.VEHICLE ? 40.0 : 15.0;
    }

    /** Half-width of the band around {@link #preferredRange} that counts as "on the ring". */
    public static double rangeDeadband(@Nullable TargetCategory category) {
        return category == TargetCategory.VEHICLE ? 4.0 : 5.0;
    }

    private void readBattlefield(AbstractUnit unit, VehicleEntity hull) {
        this.target = unit.getTarget();
        this.targetCategory = this.target != null ? VehicleWeapons.classifyTarget(this.target) : null;
        this.targetDist = this.target != null ? hull.distanceTo(this.target) : Double.MAX_VALUE;

        this.preferredRange = preferredRange(this.targetCategory);
        this.rangeError = this.target != null
                ? (this.targetDist - this.preferredRange) / this.preferredRange
                : 0.0;

        this.screened = this.target != null && SmokeVision.lineBlockedBySmoke(
                unit.level(), hull, hull.position(), this.target.position(),
                SewvConfig.SMOKE_BLOCK_RADIUS.get());

        countForces(unit, hull);
    }

    /**
     * One scan for both sides of the force ratio.
     *
     * <p>Restricted to {@link AbstractUnit} — in this mod the factions ARE the battlefield, and a
     * class-filtered scan is a fraction of the cost of walking every entity in a 96-block box. The
     * crew's own target is counted separately by {@link #targetDist}, so a hostile player is never
     * missed by the thing that matters.
     */
    private void countForces(AbstractUnit unit, VehicleEntity hull) {
        double radius = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
        double height = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get();
        AABB box = new AABB(hull.position(), hull.position()).inflate(radius, height, radius);

        CrewFacts.Faction own = CrewFacts.factionOfCrew(unit);
        int allyCount = 0;
        int enemyCount = 0;
        int idleCount = 0;
        double nearestAlly = Double.MAX_VALUE;

        // RU/US have no inventory a radio could ever be in, so their comms are a doctrine setting
        // rather than a piece of equipment — the same split that makes their ammo issued rather
        // than carried. A PMC crew needs a real radio somebody is actually carrying.
        boolean organic = own != CrewFacts.Faction.PMC && SewvConfig.FACTION_ORGANIC_COMMS.get();
        boolean radio = organic
                || (unit instanceof PmcUnitEntity self && HandheldRadioItem.isCarriedBy(self));

        List<AbstractUnit> nearby = unit.level().getEntitiesOfClass(AbstractUnit.class, box);
        for (AbstractUnit other : nearby) {
            if (other == unit || !other.isAlive()) continue;
            if (own != null && CrewFacts.factionOfCrew(other) == own) {
                allyCount++;
                nearestAlly = Math.min(nearestAlly, hull.distanceTo(other));
                if (other.getTarget() == null) idleCount++;
                // Any unit on our side carrying a radio can call it in for us. Short-circuited,
                // and only ever reached for PMC — scanning an inventory per ally is not free.
                if (!radio && other instanceof PmcUnitEntity mate) {
                    radio = HandheldRadioItem.isCarriedBy(mate);
                }
            } else if (!VehicleTargeting.isNonHostile(unit, other)) {
                enemyCount++;
            }
        }
        this.hasComms = radio;

        this.allies = allyCount;
        this.enemies = enemyCount;
        this.idleAllies = idleCount;
        this.nearestAllyDist = nearestAlly;
        this.faction = own;
        this.owner = unit instanceof PmcUnitEntity pmc ? pmc.getOwnerUUID() : null;
        // +1 for ourselves on both sides: it keeps the ratio finite with no enemies about and
        // makes "alone against one" read as an even 1.0 rather than a rout.
        this.forceRatio = (allyCount + 1.0) / (enemyCount + 1.0);
    }

    /**
     * Where we are fighting, as tactical categories rather than raw world data.
     *
     * <p>Nothing here triggers a behaviour by itself — the doc is explicit that terrain and weather
     * only modify how attractive the existing options are. A forest is a good place to flank from
     * and a poor place to hold a 40-block ring; a storm makes everything less certain.
     */
    private void readEnvironment(AbstractUnit unit, VehicleEntity hull) {
        Level level = unit.level();
        BlockPos pos = hull.blockPosition();

        this.ground = readGround(level, pos);
        this.sky = readSky(level, pos);
        this.slope = readSlope(level, pos, Math.max(2, Mth.ceil(hull.getBbWidth())));
        // Y as an abstraction of mountain combat — thin air, awkward ground — rather than as a
        // literal height. Sea level is free; the penalty only builds well above it.
        this.altitude = Mth.clamp((pos.getY() - 100) / 100.0, 0.0, 1.0);
    }

    private static Ground readGround(Level level, BlockPos pos) {
        try {
            // A village outranks whatever biome it was built in: fighting between houses is an
            // urban problem wherever the trees are. Vanilla already answers this from its POI
            // data, which is what raids and zombie sieges use.
            if (level instanceof ServerLevel server && server.isVillage(pos)) return Ground.URBAN;

            Holder<Biome> biome = level.getBiome(pos);
            if (biome.is(BiomeTags.IS_MOUNTAIN)) return Ground.MOUNTAIN;
            if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) return Ground.SWAMP;
            if (biome.is(BiomeTags.IS_BADLANDS) || biome.is(Biomes.DESERT)) return Ground.DESERT;
            if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE)
                    || biome.is(BiomeTags.IS_TAIGA)) {
                return Ground.FOREST;
            }
        } catch (Throwable ignored) {
            // Unreadable world data is not worth an AI crash — open ground is the neutral answer.
        }
        return Ground.OPEN;
    }

    private static Sky readSky(Level level, BlockPos pos) {
        try {
            if (level.isThundering()) return Sky.STORM;
            if (!level.isRaining()) return Sky.CLEAR;
            // Rain and snow are the same weather; which one falls is the biome's business, and it
            // matters here because snow is the one that whites out sightlines.
            return level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.SNOW
                    ? Sky.SNOW : Sky.RAIN;
        } catch (Throwable ignored) {
            return Sky.CLEAR;
        }
    }

    /**
     * The steepest height change a hull-length away, in any of the four directions.
     *
     * <p>Read off the same heightmap the vehicle pathfinder and spawner already use, so "ground
     * level" means the same thing to all three.
     */
    private static int readSlope(Level level, BlockPos pos, int reach) {
        try {
            int here = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
            int worst = 0;
            for (Direction dir : HORIZONTAL) {
                int x = pos.getX() + dir.getStepX() * reach;
                int z = pos.getZ() + dir.getStepZ() * reach;
                int there = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                worst = Math.max(worst, Math.abs(there - here));
            }
            return worst;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static final Direction[] HORIZONTAL =
            {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /**
     * One number for "how well is this going", so no individual action has to re-derive it.
     *
     * <p>Starts at an even 50 and is pushed either way by the things a commander would actually
     * weigh. Doctrine tilts the baseline: a risk-tolerant commander reads the same battlefield
     * more favourably than a preservation-minded one, which is the whole point of having both.
     */
    private double computeConfidence(Doctrine doctrine) {
        double score = 50.0;

        score += (this.health - 0.5) * 60.0;            // ±30 across the health range
        score += (this.energy - 0.5) * 10.0;            // ±5 — only ever moves for PMC hulls
        score += switch (this.ammo) {
            case OUT -> -30.0;
            case LOW -> -10.0;
            case OK -> 0.0;
        };
        // Force ratio saturates: being outnumbered three to one is not meaningfully worse than
        // five to one, and both should read as "get out".
        score += Mth.clamp((this.forceRatio - 1.0) * 20.0, -25.0, 25.0);

        // Weather you cannot see or shoot through is a reason to be less sure of yourself, not a
        // reason to do anything in particular — the per-action effects live in the weights.
        score += switch (this.sky) {
            case STORM -> -15.0;
            case SNOW -> -8.0;
            case RAIN -> -4.0;
            case CLEAR -> 0.0;
        };
        score -= this.altitude * 10.0;

        score += doctrine.get(Doctrine.Axis.RISK_TOLERANCE) * 10.0;
        score -= doctrine.get(Doctrine.Axis.PRESERVATION) * 10.0;

        return Mth.clamp(score, 0.0, 100.0);
    }

    /** Drop everything so a crew rejoining a different hull starts clean. */
    public void clear() {
        this.vehicle = null;
        this.nextRefresh = Long.MIN_VALUE;
        this.target = null;
        this.targetCategory = null;
        this.targetDist = Double.MAX_VALUE;
        this.memory.clear();
    }

    /**
     * Ticks since an absolute game-time stamp, or {@link Long#MAX_VALUE} if it never happened.
     *
     * <p>The sentinel is tested rather than subtracted: {@code now - Long.MIN_VALUE} overflows
     * negative and reads as "happened in the future", which silently makes every never-happened
     * event look like it just did.
     */
    public static long ticksSince(long stamp, long now) {
        return stamp == Long.MIN_VALUE ? Long.MAX_VALUE : now - stamp;
    }

    /**
     * Short-term combat memory, so a crew can act on an enemy it can no longer see.
     *
     * <p>Deliberately not persisted. This is the memory of one engagement, and a crew whose chunk
     * unloaded mid-fight has nothing left worth remembering.
     */
    public static final class Memory {

        /** Who last hurt us, straight off vanilla's own record — no damage hook needed. */
        @Nullable
        public LivingEntity lastAttacker;
        /** Where a target was last actually seen. The aimpoint for a search. */
        @Nullable
        public BlockPos lastEnemyPos;
        /** Direction the last hit came FROM, normalised and horizontal. */
        @Nullable
        public Vec3 threatBearing;

        public long lastContactTick = Long.MIN_VALUE;
        public long lastDamageTick = Long.MIN_VALUE;
        public long lastSmokeTick = Long.MIN_VALUE;
        public long lastSupportTick = Long.MIN_VALUE;

        void observe(AbstractUnit unit, long now) {
            LivingEntity target = unit.getTarget();
            if (target != null && target.isAlive()) {
                this.lastEnemyPos = target.blockPosition();
                this.lastContactTick = now;
            }

            LivingEntity attacker = unit.getLastHurtByMob();
            if (attacker != null && attacker != this.lastAttacker) {
                this.lastAttacker = attacker;
                this.lastDamageTick = now;
                Vec3 from = attacker.position().subtract(unit.position());
                Vec3 flat = new Vec3(from.x, 0.0, from.z);
                this.threatBearing = flat.lengthSqr() > 1.0E-4 ? flat.normalize() : null;
            }
        }

        /** True while a contact is recent enough to still be worth searching for. */
        public boolean hasFreshContact(long now) {
            return this.lastEnemyPos != null
                    && ticksSince(this.lastContactTick, now) < CONTACT_MEMORY_TICKS;
        }

        public boolean recentlyHit(long now, long within) {
            return ticksSince(this.lastDamageTick, now) < within;
        }

        /** The request cooldown, so one contact cannot re-task every tube in the field each second. */
        public boolean recentlyCalledSupport(long now) {
            return ticksSince(this.lastSupportTick, now) < SewvConfig.SUPPORT_CALL_INTERVAL_TICKS.get();
        }

        void clear() {
            this.lastAttacker = null;
            this.lastEnemyPos = null;
            this.threatBearing = null;
            this.lastContactTick = Long.MIN_VALUE;
            this.lastDamageTick = Long.MIN_VALUE;
            this.lastSmokeTick = Long.MIN_VALUE;
            this.lastSupportTick = Long.MIN_VALUE;
        }
    }
}

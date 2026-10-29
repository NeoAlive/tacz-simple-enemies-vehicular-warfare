package com.neoalive.tacz_sewv.entity.ai.suppression;

import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.CustomExplosion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex;
import com.neoalive.tacz_sewv.entity.ai.suppression.SuppressionTable.Kind;
import com.neoalive.tacz_sewv.util.LoadedClip;

/**
 * Feeds SEM's own per-unit suppression ({@code AbstractUnit.addGenericSuppression}) from the fire SEM
 * never hears about. SEM 0.1.6 suppresses only on TaCZ block impacts and on its own rifle shots, so a
 * tank hosing a treeline, an HE shell, a mortar barrage or a player's rounds snapping past overhead
 * left its infantry completely unbothered.
 *
 * <p>This only decides <i>who</i> and <i>how much</i>. SEM keeps everything else: the per-source
 * debounce (a full-auto stream from one gunner counts once per window), the 0.35 per-add cap, decay,
 * the accuracy penalty, the cover lock, and turning an idle unit onto its shooter. Nothing is tracked
 * twice.
 *
 * <p>Units are found through {@link CombatantIndex}, one grid query per emission, never a per-bullet
 * {@code getEntitiesOfClass}. Index positions lag by up to one rebuild, so the query is padded and
 * the falloff uses each unit's live position. Mounted crews are skipped: a hull's crew is the hull's
 * problem, not SEM's infantry brain.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SuppressionBus {

    /** Slack for index positions that are up to one rebuild old. */
    private static final double INDEX_PAD = 4.0;
    /** Vertical band, either side of the emission point. */
    private static final double VERTICAL_BAND = 6.0;
    /** A player's look ray is cut here; SEM's own target scan reaches 96. */
    private static final double PLAYER_RAY_LENGTH = 96.0;
    /** Blast radius assumed for a vanilla explosion, which does not expose its own. */
    private static final double VANILLA_BLAST_RADIUS = 4.0;
    /** SEM's own floor: anything inside the radius gets at least this share. */
    private static final double FALLOFF_FLOOR = 0.05;

    private SuppressionBus() {}

    public static boolean enabled() {
        try {
            return SewvConfig.SUPPRESSION_BUS_ENABLED.get();
        } catch (IllegalStateException unbaked) {
            return false;
        }
    }

    // --- sources ---

    /**
     * An AI crew fired a vehicle weapon at its target. Called from {@code MixinVehicleFireCooldown}
     * after the shot passed every gate, throttled per hull there. Emits at the target, so a burst that
     * misses into the sky still keeps heads down. Missiles and other SPECIAL slots are left to the
     * explosion they end in.
     */
    public static void onVehicleShot(VehicleEntity hull, AbstractUnit gunner) {
        if (!enabled() || !(hull.level() instanceof ServerLevel level)) return;
        LivingEntity target = gunner.getTarget();
        if (target == null || !target.isAlive()) return;
        int role = VehicleWeapons.roleOfCurrentWeapon(hull, hull.getSeatIndex(gunner));
        Kind kind = role == VehicleWeapons.WEAPON_MG ? Kind.MG
                : role == VehicleWeapons.WEAPON_CANNON ? Kind.CANNON
                : null;
        if (kind == null) return;
        emit(level, gunner, target.position(), kind);
    }

    /**
     * An SBW bullet hit a block. SBW's counterpart of the TaCZ {@code AmmoHitBlockEvent} SEM already
     * listens to — every vehicle machine gun, AI or player. Rounds that explode are skipped here and
     * caught as an explosion instead.
     */
    public static void onSbwBulletImpact(Projectile bullet, BlockHitResult hit, float explosionRadius) {
        if (explosionRadius > 0.0f || !enabled()) return;
        if (!(bullet.level() instanceof ServerLevel level)) return;
        emit(level, bullet.getOwner(), hit.getLocation(), Kind.MG);
    }

    /**
     * A player fired a TaCZ gun. SEM suppresses only where such a round lands; this covers the ones
     * that snap past a unit and land far behind it. One clip and one index query per shot.
     */
    public static void onPlayerGunFire(ServerLevel level, Player shooter) {
        if (!enabled()) return;
        SuppressionTable.Row row = SuppressionTable.active().row(Kind.SMALL_ARMS);
        if (row == null || row.amount() <= 0.0f || row.radius() <= 0.0) return;

        Vec3 from = shooter.getEyePosition();
        Vec3 to = from.add(shooter.getLookAngle().scale(PLAYER_RAY_LENGTH));
        BlockHitResult wall = LoadedClip.clip(level,
                new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        if (wall != null && wall.getType() != HitResult.Type.MISS) to = wall.getLocation();

        double cx = (from.x + to.x) * 0.5;
        double cz = (from.z + to.z) * 0.5;
        double half = Math.max(Math.abs(to.x - from.x), Math.abs(to.z - from.z)) * 0.5 + row.radius();
        double minY = Math.min(from.y, to.y) - row.radius() - VERTICAL_BAND;
        double maxY = Math.max(from.y, to.y) + row.radius() + VERTICAL_BAND;
        for (AbstractUnit unit : candidates(level, cx, cz, half, minY, maxY)) {
            Vec3 p = unit.getEyePosition();
            double d = Math.sqrt(segmentDistanceSq(from.x, from.y, from.z, to.x, to.y, to.z, p.x, p.y, p.z));
            double f = falloff(d, row.radius());
            if (f > 0.0) apply(unit, shooter, p, (float) (row.amount() * f));
        }
    }

    /** Every detonation: SBW shells, rockets, mortars, bombs ({@code CustomExplosion} fires this too). */
    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel) || !enabled()) return;
        SuppressionTable.Row row = SuppressionTable.active().row(Kind.EXPLOSION);
        if (row == null || row.amount() <= 0.0f || row.radius() <= 0.0) return;

        double blast = event.getExplosion() instanceof CustomExplosion custom
                ? custom.getRadius() : VANILLA_BLAST_RADIUS;
        double radius = blast * row.radius();
        Vec3 at = event.getExplosion().getPosition();
        Entity source = event.getExplosion().getIndirectSourceEntity();
        if (source == null) source = event.getExplosion().getDirectSourceEntity();
        long now = event.getLevel().getGameTime();
        // The event already carries everything within twice the blast radius: no query needed.
        for (Entity e : event.getAffectedEntities()) {
            if (e instanceof VehicleEntity hull) {
                // A near miss rattles a crew even when the hit itself lands on someone else.
                if (source != null && source.getRootVehicle() == hull) continue;
                double f = falloff(hull.position().distanceTo(at), radius);
                if (f > 0.0) {
                    HullPressure.add(hull, (float) (row.amount() * f) * EasyMode.suppressionScale(hull), now);
                }
                continue;
            }
            if (!(e instanceof AbstractUnit unit) || !eligible(unit)) continue;
            double f = falloff(unit.position().distanceTo(at), radius);
            if (f > 0.0) apply(unit, source, at, (float) (row.amount() * f));
        }
    }

    // --- core ---

    /** One emission of {@code kind} at {@code pos}, using the table row for its amount and radius. */
    public static void emit(ServerLevel level, @Nullable Entity source, Vec3 pos, Kind kind) {
        SuppressionTable.Row row = SuppressionTable.active().row(kind);
        if (row == null || row.amount() <= 0.0f || row.radius() <= 0.0) return;
        double r = row.radius();
        for (AbstractUnit unit : candidates(level, pos.x, pos.z, r, pos.y - r - VERTICAL_BAND, pos.y + r + VERTICAL_BAND)) {
            double f = falloff(unit.position().distanceTo(pos), r);
            if (f > 0.0) apply(unit, source, pos, (float) (row.amount() * f));
        }
    }

    private static List<AbstractUnit> candidates(ServerLevel level, double cx, double cz, double half,
                                                 double minY, double maxY) {
        CombatantIndex.Snapshot snap = CombatantIndex.snapshot(level);
        if (snap == null) {
            return level.getEntitiesOfClass(AbstractUnit.class,
                    new AABB(cx - half, minY, cz - half, cx + half, maxY, cz + half), SuppressionBus::eligible);
        }
        List<CombatantIndex.Entry> entries = snap.query(cx, cz, half + INDEX_PAD, minY - INDEX_PAD, maxY + INDEX_PAD);
        List<AbstractUnit> out = new java.util.ArrayList<>(entries.size());
        for (CombatantIndex.Entry e : entries) {
            if (e.living instanceof AbstractUnit unit && eligible(unit)) out.add(unit);
        }
        return out;
    }

    private static boolean eligible(AbstractUnit unit) {
        return unit.isAlive() && !(unit.getVehicle() instanceof VehicleEntity);
    }

    private static void apply(AbstractUnit unit, @Nullable Entity source, Vec3 pos, float amount) {
        if (friendlyTo(unit, source)) return;
        float scaled = amount * EasyMode.suppressionScale(unit);
        if (scaled <= 0.0f) return;
        unit.addGenericSuppression(source, pos, Math.min(scaled, SuppressionTable.MAX_AMOUNT));
    }

    /**
     * SEM's own friendly-fire filter runs inside {@code addGenericSuppression}; this adds the
     * relations only this mod knows: PMC owners, diplomacy, invasion teams.
     */
    private static boolean friendlyTo(AbstractUnit unit, @Nullable Entity source) {
        LivingEntity shooter = shooterOf(source);
        if (shooter == null) return false;
        if (shooter == unit) return true;
        if (shooter instanceof Player p) return VehicleTargeting.isFriendlyPlayer(unit, p);
        return VehicleTargeting.isNonHostile(unit, shooter);
    }

    @Nullable
    private static LivingEntity shooterOf(@Nullable Entity source) {
        if (source instanceof LivingEntity living) return living;
        if (source instanceof Projectile projectile) return shooterOf(projectile.getOwner());
        if (source instanceof VehicleEntity hull && hull.getFirstPassenger() instanceof LivingEntity driver) {
            return driver;
        }
        return null;
    }

    // --- pure maths (headless, see SuppressionSelfCheck) ---

    /** Share of the full amount at {@code distance}: SEM's own shape, 0 outside the radius. */
    public static double falloff(double distance, double radius) {
        if (radius <= 0.0 || distance > radius) return 0.0;
        return Math.max(FALLOFF_FLOOR, 1.0 - distance / radius);
    }

    /** Squared distance from point P to segment AB. */
    public static double segmentDistanceSq(double ax, double ay, double az, double bx, double by, double bz,
                                           double px, double py, double pz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 <= 1.0e-9 ? 0.0 : ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double qx = ax + t * dx - px, qy = ay + t * dy - py, qz = az + t * dz - pz;
        return qx * qx + qy * qy + qz * qz;
    }
}

package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.atsuishio.superbwarfare.data.gun.Ammo;
import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ProjectileInfo;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.slf4j.Logger;

/**
 * Weapon doctrine for AI crews. Ground crews ({@link DriveVehicleGoal}) classify
 * the target and pick the slot for it — special/cannon against armor, range-split
 * MG/cannon against infantry. Flight crews ({@link DriveHelicopterGoal}) cycle a
 * random valid slot on a timer instead.
 *
 * <p>The doctrine reasons in terms of weapon <em>roles</em> (CANNON / MG / SPECIAL),
 * not physical slot indices. Each physical slot in a seat is classified at
 * selection time from its {@link GunData} — shell type, ammo class, projectile id
 * and weapon key — so a vehicle that lists its weapons in a non-standard order (a
 * modded hull with {@code ["MachineGun","Cannon"]}, say) is still driven correctly.
 * The {@code WEAPON_*} constants below are role ids that double as indices into the
 * role→slot map; they are NOT assumptions about a slot's position. A slot that
 * can't be classified falls back to the CANNON role (a usable direct-fire primary).
 *
 * <p>The switch cooldown stays in each goal (per-crew state). Gun-data reads go through
 * {@link #gunData} — a per-thread, per-vehicle, per-tick snapshot of SBW's
 * {@code getGunDataMap()} — so one selection cycle (and every other seat on the same hull
 * that tick) does not rebuild SBW's gun map on every classifier probe. That map rebuild
 * mutates a static non-thread-safe HashMap inside {@code GunData.from}; we cannot fix that,
 * but we stop amplifying it.
 */
public final class VehicleWeapons {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One {@code getGunDataMap()} per vehicle per game tick per thread. Client and server
     * each have their own ThreadLocal, so singleplayer's two tickers never share this cache
     * (they still race inside SBW's static map — that is theirs).
     */
    private static final ThreadLocal<GunMapCache> GUN_MAP = ThreadLocal.withInitial(GunMapCache::new);

    private static final class GunMapCache {
        private int vehicleId = Integer.MIN_VALUE;
        private long gameTime = Long.MIN_VALUE;
        private Map<String, GunData> map;

        Map<String, GunData> mapFor(VehicleEntity vehicle) {
            int id = vehicle.getId();
            long time = vehicle.level().getGameTime();
            if (map == null || id != vehicleId || time != gameTime) {
                vehicleId = id;
                gameTime = time;
                map = vehicle.getGunDataMap();
            }
            return map;
        }

        /** After {@code modifyGunData} the synched map moved — next read must rebuild. */
        void invalidate() {
            map = null;
        }
    }

    /** Package/test hook: drop the current thread's gun-map snapshot. */
    public static void invalidateGunMapCache() {
        GUN_MAP.get().invalidate();
    }

    // Role ids. These also index the role→slot map returned by resolveRoleSlots().
    public static final int WEAPON_CANNON = 0;
    public static final int WEAPON_MG = 1;
    public static final int WEAPON_SPECIAL = 2; // TOW / heavy anti-vehicle ordnance
    private static final int WEAPON_COUNT = 3;
    // Also selectWeaponForTarget's "nothing selected" return, so it is part of the
    // public surface, not just an internal classification miss.
    public static final int UNCLASSIFIED = -1;

    // Among roles tied at maxScore, prefer SPECIAL > CANNON > MG (old if-chain order).
    // Do not break ties by ascending role-index — CANNON=0 would steal every dead heat.
    private static final int[] TIE_PRIORITY = { WEAPON_SPECIAL, WEAPON_CANNON, WEAPON_MG };

    private static final double WEIGHT_SPECIAL_HINT = 2.0;
    private static final double WEIGHT_SHELL_TYPE = 1.0;
    private static final double WEIGHT_CANNON_FAMILY = 2.0;
    private static final double WEIGHT_MG_AMMO_CLASS = 1.5;
    private static final double WEIGHT_MG_NAME = 1.0;
    private static final double WEIGHT_MG_DEFAULT_PROJECTILE = 0.5;
    private static final double NEAR_TIE_GAP = 0.5;

    // Word-boundary "mg" — raw contains("mg") false-positives inside unrelated tokens.
    private static final Pattern MG_WORD = Pattern.compile("\\bmg\\b");

    // One classification signal: substring needle, role it votes for, confidence weight.
    private record RoleHint(int role, double weight, String needle) {}

    // SPECIAL covers guided / launched ordnance (anti-armor preference + wider fire
    // assist). Generic terms cover SW + most addon munitions; proper-nouns catch FCP
    // guns whose projectile id carries no generic keyword (malyutka / sidewinder /
    // hellfire). Matched against name | projectile | ammoId unconditionally.
    private static final RoleHint[] SPECIAL_HINTS = {
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "missile"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "rocket"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "torpedo"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "bomb"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "agm"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "kh_"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "guide"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "mortar"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "seek"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "swarm"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "launcher"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "fim"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "tow"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "malyutka"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "sidewinder"),
            hint(WEAPON_SPECIAL, WEIGHT_SPECIAL_HINT, "hellfire"),
    };

    private static RoleHint hint(int role, double weight, String needle) {
        return new RoleHint(role, weight, needle);
    }

    // FACTION_UNIT = SEM's RU/US faction infantry. (An actual PmcUnitEntity target
    // deliberately falls through to MONSTER — identical doctrine either way.)
    public enum TargetCategory { VEHICLE, MONSTER, FACTION_UNIT }

    // Ammo doctrine thresholds, read against MAX health (the hull/mob's baseline, not its
    // current damage state — doctrine picks a shell for what the thing IS, not how hurt it is).
    private static final float ARMORED_HEALTH = 400.0F; // hull at/above this is a hard target
    private static final float SOFT_HEALTH = 100.0F;    // mob below this is an MG target

    // Cannon ammo preference lists, in order, matched as a SUFFIX of the AmmoConsumer's ammo
    // item id (superbwarfare:large_shell_ap / small_shell_he / large_shell_gs — the naming is
    // consistent across every stock hull, and the per-ammo Override blocks are NOT: the
    // grapeshot entry sets no ShellType at all, and the small-shell hulls set none anywhere).
    // Later entries are fallbacks for hulls that carry no such round — the howitzers
    // (mk_42/plz_05/bl_132/mle_1934) stock AP/HE/CM/WP and have no grapeshot to load.
    private static final String[] AMMO_ANTI_ARMOR = {"_ap", "_he"};
    private static final String[] AMMO_ANTI_LIGHT = {"_he", "_ap"};
    private static final String[] AMMO_ANTI_INFANTRY = {"_gs", "_he", "_ap"};

    private VehicleWeapons() {}

    /**
     * Cached {@code vehicle.getGunData(seat, weapon)} for this thread's current game tick.
     * First touch of a hull in a tick pays one {@code getGunDataMap()}; later probes are map lookups.
     */
    @javax.annotation.Nullable
    public static GunData gunData(VehicleEntity vehicle, int seatIndex, int weaponIndex) {
        if (vehicle == null || seatIndex < 0 || weaponIndex < 0) return null;
        try {
            SeatInfo seat = vehicle.getSeat(seatIndex);
            if (seat == null) return null;
            List<String> names = seat.weapons();
            if (weaponIndex >= names.size()) return null;
            String name = names.get(weaponIndex);
            if (name == null || name.isEmpty()) return null;
            return GUN_MAP.get().mapFor(vehicle).get(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Selected weapon on {@code seatIndex}, same tick cache as {@link #gunData(VehicleEntity, int, int)}. */
    @javax.annotation.Nullable
    public static GunData gunData(VehicleEntity vehicle, int seatIndex) {
        if (vehicle == null || seatIndex < 0) return null;
        try {
            int weapon = vehicle.getSelectedWeapon(seatIndex);
            return gunData(vehicle, seatIndex, weapon);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True when {@code mob} is crewing an SBW vehicle in a role that fires a VEHICLE
     * weapon — the driver (seat 0, who works the hull's main armament) or any gunner
     * whose seat has weapons assigned. A pure passenger seat (no weapons) returns
     * false, so those units are excluded and keep their own behaviour.
     *
     * <p>Used to suppress a crew member's hand-held gun goal: without this a mounted
     * unit with a rifle in hand and a target in range fires the rifle instead of (or
     * alongside) the vehicle's guns.
     */
    public static boolean controlsVehicleWeapon(Mob mob) {
        if (!(mob.getVehicle() instanceof VehicleEntity vehicle)) return false;
        if (vehicle.getFirstPassenger() == mob) return true; // driver — always busy driving
        SeatInfo seat = vehicle.getSeat(mob);
        return seat != null && !seat.weapons().isEmpty();     // gunner; passenger → false
    }

    public static TargetCategory classifyTarget(LivingEntity target) {
        // A VehicleEntity isn't a LivingEntity, so it can never be the target itself
        // the AI targets the crew riding inside; armor makes the MG useless against them.
        if (target.getVehicle() instanceof VehicleEntity) return TargetCategory.VEHICLE;
        if (target instanceof RUunitEntity || target instanceof USunitEntity) return TargetCategory.FACTION_UNIT;
        return TargetCategory.MONSTER; // vanilla hostiles + fallback default
    }

    // Ground-crew doctrine. The seat's physical slots are first mapped to roles
    // (resolveRoleSlots), then the pick is made by role so weapon ORDER on the hull
    // doesn't matter, and finally the cannon's ammo revolver is turned to the right
    // shell for the target (selectCannonAmmo).
    //
    // The special slot (TOW / heavy AT ordnance) outranks everything and is selected
    // exactly while it is READY TO FIRE — loaded, off reload, not overheated —
    // whatever the target is; the cannon covers its 6-13 s reload gaps. That is the
    // cannon/special alternation that actually works: a fixed-timer round-robin
    // flipped the turret between the two firing solutions every few ticks (visible
    // pitch twitch) while the missile was rarely both selected and loaded at a fire
    // instant. Hulls without a special slot just stay on the cannon.
    //
    // Everything else is decided by what the target IS, on its BASELINE (max) health:
    //   mounted crew    -> cannon, AP against a hull at/above ARMORED_HEALTH, HE below
    //   mob under SOFT_HEALTH -> MG
    //   anything else   -> cannon with grapeshot
    // Range no longer enters into it — a cannon reaches as far as the crew can see.
    //
    // Every pick is bounded by the weapons the seat actually has — setWeaponIndex()
    // doesn't bounds-check, and an invalid index silently disarms the seat — and to
    // slots that are real guns (see isRealWeapon).
    //
    // Returns the ROLE the selected slot fills (WEAPON_CANNON / WEAPON_MG /
    // WEAPON_SPECIAL), or UNCLASSIFIED when nothing was selected. Callers need the role
    // and CANNOT re-derive it from getWeaponIndex(): that returns a PHYSICAL slot, and
    // the whole point of this class is that physical order carries no meaning. Handing
    // it back here is free — the role→slot map is already in hand.
    public static int selectWeaponForTarget(VehicleEntity vehicle, int seatIndex, LivingEntity target) {
        return selectWeaponForTarget(vehicle, seatIndex, target, null);
    }

    public static int selectWeaponForTarget(VehicleEntity vehicle, int seatIndex, LivingEntity target,
                                            @javax.annotation.Nullable AbstractUnit shooter) {
        if (seatIndex < 0) return UNCLASSIFIED;
        SeatInfo seat = vehicle.getSeat(seatIndex);
        int weaponCount = seat == null ? 0 : seat.weapons().size();
        if (weaponCount == 0) return UNCLASSIFIED; // weaponless seat — nothing to select

        int[] slot = resolveRoleSlots(vehicle, seatIndex, weaponCount);
        int cannon = slot[WEAPON_CANNON];
        int mg = slot[WEAPON_MG];
        int special = slot[WEAPON_SPECIAL];

        // Last resort when a role lookup misses. NOT slot 0: on a hull whose first
        // slot is a placeholder, index 0 is the one pick guaranteed to break the
        // turret. Falls back to the first real weapon, or -1 when the seat has none.
        int fallback = firstRealWeapon(vehicle, seatIndex, weaponCount);

        int chosen;
        String[] ammo = null; // null = this pick doesn't load a shell (special/MG)
        // SPECIAL only when a shot would leave the rail now. A lofted ATGM stays
        // canShoot-true forever while silent, and used to monopolise the seat so the
        // cannon never got a turn (bmp deadlock).
        if (special >= 0 && specialReady(vehicle, seatIndex, special)
                && specialLinedUp(vehicle, shooter, target)) {
            chosen = special;
        } else if (target.getVehicle() instanceof VehicleEntity hull) {
            chosen = cannon >= 0 ? cannon : fallback;
            ammo = hull.getMaxHealth() >= ARMORED_HEALTH ? AMMO_ANTI_ARMOR : AMMO_ANTI_LIGHT;
        } else if (target.getMaxHealth() < SOFT_HEALTH && mg >= 0) {
            chosen = mg;
        } else {
            // Tough infantry, or a hull with no MG to answer with — grapeshot.
            chosen = cannon >= 0 ? cannon : (mg >= 0 ? mg : fallback);
            ammo = AMMO_ANTI_INFANTRY;
        }

        // Nothing on this seat is usable (every slot is a placeholder) — leave the
        // index alone rather than parking the crew on a turret-breaking slot.
        if (chosen < 0) return UNCLASSIFIED;
        vehicle.setWeaponIndex(seatIndex, chosen);
        // Safe on whatever `chosen` ended up being: a slot with one ammo type (an MG the
        // fallback landed on) has nothing to switch to and is left alone.
        if (ammo != null) selectCannonAmmo(vehicle, seatIndex, chosen, ammo);
        // Read the role back off the SLOT WE ACTUALLY PICKED rather than off the branch
        // that picked it: the `fallback` arms can hand back the special's slot (a seat
        // with an ATGM and no cannon), and reporting that as CANNON would deny it the
        // fire assist it structurally depends on — the exact deadlock this return value
        // exists to prevent.
        return roleOf(slot, chosen);
    }

    // Which role does this physical slot fill? UNCLASSIFIED if it fills none.
    private static int roleOf(int[] roleToSlot, int slotIndex) {
        for (int r = 0; r < WEAPON_COUNT; r++) {
            if (roleToSlot[r] == slotIndex) return r;
        }
        return UNCLASSIFIED;
    }

    // Classify each physical slot in the seat and return a role→slot map (values are
    // physical weapon indices, or -1 if the seat has no weapon for that role). The
    // first physical slot matching a role wins. A slot that can't be classified is
    // held aside and, if no true cannon exists, promoted to the CANNON role so the
    // crew still has a usable direct-fire primary on exotic/modded hulls.
    //
    // Placeholder slots are skipped outright (isRealWeapon), BEFORE classification:
    // GunProp.PROJECTILE defaults to "superbwarfare:projectile", so a bodyless
    // placeholder like "Empty" reads as the plain bullet and classifies as a genuine
    // MG — it would be picked against infantry in close, not merely inherited via the
    // unclassified→CANNON promotion. Neither route can reach a placeholder now.
    private static int[] resolveRoleSlots(VehicleEntity vehicle, int seatIndex, int weaponCount) {
        int[] roleToSlot = new int[WEAPON_COUNT];
        for (int r = 0; r < WEAPON_COUNT; r++) roleToSlot[r] = -1;
        int unclassified = -1;
        for (int w = 0; w < weaponCount; w++) {
            if (!isRealWeapon(vehicle, seatIndex, w)) continue;
            int role = classifySlot(vehicle, seatIndex, w);
            if (role != UNCLASSIFIED) {
                if (roleToSlot[role] < 0) roleToSlot[role] = w;
            } else if (unclassified < 0) {
                unclassified = w;
            }
        }
        if (roleToSlot[WEAPON_CANNON] < 0 && unclassified >= 0) {
            roleToSlot[WEAPON_CANNON] = unclassified;
        }
        return roleToSlot;
    }

    /**
     * True when a physical slot is a weapon the crew can actually aim and fire —
     * i.e. it has a configured muzzle velocity.
     *
     * <p>This is a turret-safety gate, not a doctrine preference. SBW aims an
     * AI-crewed turret with {@code RangeTool.calculateFiringSolution}, whose flight
     * time starts at {@code |d0| / muzzleVelocity}; a muzzle velocity of 0 sends that
     * to Infinity and the solver returns NaN. {@code Mth.clamp} does not sanitise NaN
     * and SBW's {@code turretYRot} is a plain field that accumulates off its own
     * previous value, so ONE NaN freezes the turret permanently while the gun keeps
     * firing — and spams vanilla's "Invalid entity rotation: NaN, discarding" as the
     * NaN barrel vector is pushed onto the crew every tick. Players never hit this:
     * their aim path is pure geometry and never runs the solver.
     *
     * <p>{@code GunProp.VELOCITY} DEFAULTS TO 0, so the placeholder weapons addon
     * packs ship ({@code "Empty"}, {@code "Nothing"} — bodies like
     * {@code {"RPM":0,"Magazine":0,"Damage":0}}) resolve to 0. A player would never
     * dwell on an empty slot; the AI happily would.
     *
     * <p>Reads the CONFIGURED velocity rather than {@code getProjectileVelocity()} on
     * purpose: that accessor returns {@code deltaMovement.length() * VELOCITY} for
     * {@code AddShooterDeltaMovement} weapons, which is 0 whenever the hull is parked.
     * Gating on it would make a stationary gunship disarm itself. The dynamic zero is
     * MixinVehicleTurretNaN's job; this only removes slots that are never firable.
     *
     * <p>Defensive like the rest of this class: unreadable gun data must never crash
     * the AI tick. On error assume the slot IS real — the mixin still backstops the
     * NaN, and silently disarming a working weapon is the worse failure.
     */
    /** Package-visible for {@link HeliArmament} (same placeholder / zero-velocity gate). */
    public static boolean isRealWeapon(VehicleEntity vehicle, int seatIndex, int weaponIndex) {
        try {
            GunData gun = gunData(vehicle, seatIndex, weaponIndex);
            if (gun == null) return false; // no data at all — nothing to aim or fire
            Double velocity = gun.get(GunProp.VELOCITY);
            return velocity != null && velocity != 0.0;
        } catch (Exception e) {
            return true;
        }
    }

    // First slot in the seat that is a real weapon, or -1 when every slot is a
    // placeholder (fcp:bigbird, whose only weapon on seats 0/1 is "Empty").
    private static int firstRealWeapon(VehicleEntity vehicle, int seatIndex, int weaponCount) {
        for (int w = 0; w < weaponCount; w++) {
            if (isRealWeapon(vehicle, seatIndex, w)) return w;
        }
        return -1;
    }

    // Classify one physical weapon slot into a role from GunData. Scoring (not
    // early-return if-order) so addon hulls with competing name/projectile/ammo
    // signals resolve by weight. Defensive: unreadable gun data must never crash
    // the AI tick.
    private static int classifySlot(VehicleEntity vehicle, int seatIndex, int weaponIndex) {
        try {
            GunData gun = gunData(vehicle, seatIndex, weaponIndex);
            String name = lower(vehicle.getGunName(seatIndex, weaponIndex));
            String shell = "";
            String projectile = "";
            String ammoId = "";
            Ammo ammoClass = null;
            if (gun != null) {
                shell = lower(gun.get(GunProp.SHELL_TYPE));
                ProjectileInfo pi = gun.get(GunProp.PROJECTILE);
                if (pi != null) projectile = lower(pi.getId());
                List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
                if (consumers != null) {
                    for (AmmoConsumer c : consumers) {
                        if (c == null) continue;
                        if (ammoId.isEmpty()) {
                            String id = lower(c.getAmmo());
                            if (!id.isEmpty()) ammoId = id;
                        }
                        if (ammoClass == null && c.getPlayerAmmoType() != null) {
                            ammoClass = c.getPlayerAmmoType();
                        }
                        if (!ammoId.isEmpty() && ammoClass != null) break;
                    }
                }
            }
            int role = classifyFromSignals(name, shell, projectile, ammoId, ammoClass);
            maybeLogNearTie(vehicle, seatIndex, weaponIndex, name, shell, projectile, ammoId, ammoClass, role);
            return role;
        } catch (Exception e) {
            return UNCLASSIFIED;
        }
    }

    /**
     * Package-visible pure classifier for self-checks (no GunData / world).
     * Inputs must already be lowercased (or null — treated as empty).
     */
    public static int classifyFromSignals(String name, String shell, String projectile,
                                   String ammoId, Ammo ammoClass) {
        return pickWinner(scoreRoles(
                emptyToBlank(name), emptyToBlank(shell), emptyToBlank(projectile),
                emptyToBlank(ammoId), ammoClass));
    }

    /** Exposed for the self-check dead-heat case (construct equal SPECIAL/CANNON scores). */
    public static int pickWinnerFromScores(double cannon, double mg, double special) {
        double[] scores = new double[WEAPON_COUNT];
        scores[WEAPON_CANNON] = cannon;
        scores[WEAPON_MG] = mg;
        scores[WEAPON_SPECIAL] = special;
        return pickWinner(scores);
    }

    private static String emptyToBlank(String s) {
        return s == null ? "" : s;
    }

    private static double[] scoreRoles(String name, String shell, String projectile,
                                      String ammoId, Ammo ammoClass) {
        double[] scores = new double[WEAPON_COUNT];
        boolean specialName = false, specialProj = false, specialAmmo = false;

        for (RoleHint h : SPECIAL_HINTS) {
            boolean hit = false;
            if (contains(name, h.needle())) { specialName = true; hit = true; }
            if (contains(projectile, h.needle())) { specialProj = true; hit = true; }
            if (contains(ammoId, h.needle())) { specialAmmo = true; hit = true; }
            if (hit) scores[h.role()] += h.weight();
        }

        // CANNON shell|cannon family: +2 once, only on haystacks that did not match SPECIAL.
        if ((!specialName && cannonFamilyHit(name))
                || (!specialProj && cannonFamilyHit(projectile))
                || (!specialAmmo && cannonFamilyHit(ammoId))) {
            scores[WEAPON_CANNON] += WEIGHT_CANNON_FAMILY;
        }

        // ShellType always applied when present (HE tag, weaker than a SPECIAL word).
        if (!shell.isEmpty() && !shell.equals("default")) {
            scores[WEAPON_CANNON] += WEIGHT_SHELL_TYPE;
        }

        if (ammoClass == Ammo.RIFLE || ammoClass == Ammo.HANDGUN || ammoClass == Ammo.SHOTGUN
                || ammoClass == Ammo.SNIPER || ammoClass == Ammo.HEAVY) {
            scores[WEAPON_MG] += WEIGHT_MG_AMMO_CLASS;
        }
        if (contains(name, "machinegun") || contains(projectile, "machinegun")
                || contains(ammoId, "machinegun")
                || mgWord(name) || mgWord(projectile) || mgWord(ammoId)) {
            scores[WEAPON_MG] += WEIGHT_MG_NAME;
        }
        if (projectile.equals("superbwarfare:projectile")) {
            scores[WEAPON_MG] += WEIGHT_MG_DEFAULT_PROJECTILE;
        }
        return scores;
    }

    private static int pickWinner(double[] scores) {
        double maxScore = 0.0;
        for (double s : scores) {
            if (s > maxScore) maxScore = s;
        }
        if (maxScore <= 0.0) return UNCLASSIFIED;
        for (int role : TIE_PRIORITY) {
            if (scores[role] == maxScore) return role;
        }
        return UNCLASSIFIED;
    }

    private static void maybeLogNearTie(VehicleEntity vehicle, int seatIndex, int weaponIndex,
                                        String name, String shell, String projectile,
                                        String ammoId, Ammo ammoClass, int winner) {
        if (winner == UNCLASSIFIED || !LOGGER.isDebugEnabled()) return;
        double[] scores = scoreRoles(name, shell, projectile, ammoId, ammoClass);
        double second = 0.0;
        for (int r = 0; r < WEAPON_COUNT; r++) {
            if (r == winner) continue;
            if (scores[r] > second) second = scores[r];
        }
        if (scores[winner] - second >= NEAR_TIE_GAP) return;
        LOGGER.debug("[sewv-weapons] near-tie vehicle={} seat={} weapon={} gun={} scores=[c={}, mg={}, sp={}] picked={}",
                vehicle.getType(), seatIndex, weaponIndex, name,
                scores[WEAPON_CANNON], scores[WEAPON_MG], scores[WEAPON_SPECIAL],
                roleName(winner));
    }

    private static String roleName(int role) {
        return switch (role) {
            case WEAPON_CANNON -> "CANNON";
            case WEAPON_MG -> "MG";
            case WEAPON_SPECIAL -> "SPECIAL";
            default -> "UNCLASSIFIED";
        };
    }

    private static boolean cannonFamilyHit(String haystack) {
        return contains(haystack, "shell") || contains(haystack, "cannon");
    }

    private static boolean contains(String haystack, String needle) {
        return !haystack.isEmpty() && haystack.contains(needle);
    }

    private static boolean mgWord(String haystack) {
        return !haystack.isEmpty() && MG_WORD.matcher(haystack).find();
    }

    /**
     * Turn one weapon's ammo revolver to the first preference in {@code preferences} the
     * hull actually carries. AP/HE/GS on a cannon are not separate weapon slots — they are
     * {@code AmmoType} entries on the SAME slot, each overriding the gun's props — so this
     * goes through SBW's own switch, {@code GunData.changeAmmoConsumer}, driven exactly as
     * the player's {@code EditMessage(type = 5)} drives it: inside {@code modifyGunData}
     * (which copies, mutates and saves) with the hull's {@code ammoSupplier}.
     *
     * <p>Only ever called behind the caller's weapon-switch cooldown. {@code
     * changeAmmoConsumer} calls {@code resetStatus()}, wiping the reload timers — switching
     * every tick would mean a gun that never finishes loading. It no-ops when the index is
     * already selected, so a crew that keeps engaging the same kind of target never pays.
     *
     * <p>A preference the hull has run out of is skipped rather than selected: an empty
     * chamber the AI can't refill is worse than the wrong shell.
     */
    // TODO(phase2-ammo-scoring): see VehicleWeapons_ScoreRefactor_PLAN.md
    private static void selectCannonAmmo(VehicleEntity vehicle, int seatIndex, int weaponIndex,
                                         String[] preferences) {
        try {
            GunData gun = gunData(vehicle, seatIndex, weaponIndex);
            if (gun == null) return;
            List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
            if (consumers == null || consumers.size() < 2) return; // single-ammo weapon

            Entity supplier = vehicle.getAmmoSupplier();
            Entity source = supplier != null ? supplier : vehicle;
            for (String want : preferences) {
                for (int i = 0; i < consumers.size(); i++) {
                    AmmoConsumer c = consumers.get(i);
                    if (c == null || !lower(c.getAmmo()).endsWith(want)) continue;
                    if (i == gun.selectedAmmoType.get()) return; // already chambered
                    if (c.count(gun, source) <= 0) break;        // out of it — try next preference
                    int index = i;
                    vehicle.modifyGunData(seatIndex, weaponIndex,
                            d -> d.changeAmmoConsumer(index, source));
                    // Synched gun map changed — do not serve the pre-switch snapshot later this tick.
                    GUN_MAP.get().invalidate();
                    return;
                }
            }
        } catch (Exception e) {
            // Unreadable/exotic gun data — fire whatever is chambered rather than crash the tick.
        }
    }

    // True while the given slot could fire right now: loaded, not reloading/charging,
    // not overheated (GunData.canShoot mirrors exactly what SBW's own fire path
    // checks). The ammo probe uses the vehicle's ammo supplier like SBW does, falling
    // back to the hull itself.
    private static boolean specialReady(VehicleEntity vehicle, int seatIndex, int weaponIndex) {
        try {
            GunData special = gunData(vehicle, seatIndex, weaponIndex);
            if (special == null) return false;
            Entity supplier = vehicle.getAmmoSupplier();
            return special.canShoot(supplier != null ? supplier : vehicle);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Barrel already on the straight LOS within a tight gate. Without this, a lofted
     * ATGM solution keeps {@link #specialReady} true forever while nothing leaves the
     * rail, and the seat never falls back to cannon.
     */
    private static boolean specialLinedUp(VehicleEntity vehicle,
                                          @javax.annotation.Nullable AbstractUnit shooter,
                                          LivingEntity target) {
        if (shooter == null) return false;
        try {
            Vec3 shootDir = vehicle.getShootDirectionForHud(shooter, 1.0F);
            Vec3 toTarget = target.getBoundingBox().getCenter()
                    .subtract(vehicle.getShootPos(shooter, 1.0F));
            if (shootDir.lengthSqr() < 1.0E-6 || toTarget.lengthSqr() < 1.0E-6) return false;
            double cos = shootDir.normalize().dot(toTarget.normalize());
            double angleDeg = Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0, 1.0)));
            return angleDeg <= 8.0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /**
     * AI fire assist: fire the seat's selected weapon at {@code target} through the
     * same public path SBW's own AI trigger uses, but within {@code coneDeg} of the
     * straight muzzle-target line instead of SBW's hard-coded 4°. That 4° gate is
     * measured against the barrel's actual direction, and the turret auto-aim
     * points at the BALLISTIC solution — a velocity-3 missile lofts ~6°+ above the
     * line at standoff range, so it can never pass natively; a helicopter's
     * hull-fixed weapons rarely hold a 4° line either. Fires on the same
     * {@code tickCount % ceil(1200/rpm)} instants as SBW's loop, so a same-instant
     * duplicate from the native path is absorbed by the per-vehicle AI fire
     * cooldown in MixinVehicleFireCooldown; canShoot() carries every hold-fire
     * gate (ammo/reload plus this mod's CEASE_FIRE, cooldown, LOS and smoke).
     *
     * @return true if a shot was fired
     */
    public static boolean tryAiFireAssist(VehicleEntity vehicle, AbstractUnit unit,
                                          LivingEntity target, double coneDeg) {
        return tryAiFireAssistResult(vehicle, unit, target, coneDeg) == FireGate.FIRED;
    }

    /** Why {@link #tryAiFireAssist} did or did not shoot — for heli combat debug. */
    public enum FireGate {
        FIRED, RPM_WAIT, CANNOT_SHOOT, BAD_VECTOR, CONE, ERROR
    }

    /**
     * Same as {@link #tryAiFireAssist} but returns the gate that blocked the shot.
     * {@link FireGate#CANNOT_SHOOT} covers MixinVehicleFireCooldown (CEASE_FIRE,
     * AI cooldown, LOS/smoke) and SBW ammo/reload — dig with canShoot alone if needed.
     */
    /**
     * Minimum assist cone for SEM crews. Splash from a loose shot beats never firing;
     * floors even when an old {@code aiFireAssistConeDeg} toml still says 12°.
     */
    public static final double NPC_ASSIST_CONE_FLOOR_DEG = 35.0;

    /** Effective cone for an NPC-crewed seat: max(requested, {@link #NPC_ASSIST_CONE_FLOOR_DEG}). */
    public static double npcAssistConeDeg(double requestedDeg) {
        return Math.max(requestedDeg, NPC_ASSIST_CONE_FLOOR_DEG);
    }

    public static FireGate tryAiFireAssistResult(VehicleEntity vehicle, AbstractUnit unit,
                                                 LivingEntity target, double coneDeg) {
        return tryAiFireAssistResult(vehicle, unit, target, null, coneDeg, true);
    }

    /**
     * Fire assist with an explicit aim point and control over the NPC cone floor.
     *
     * <p><b>aimPoint</b> is where the shot has to be pointed, which is not always where the target
     * is: a lead solution puts it ahead of a mover, and gating the shot against the target's
     * current centre would then refuse exactly the shots that were correctly aimed. Null keeps the
     * original behaviour of measuring to the target's bounding-box centre.
     *
     * <p><b>npcConeFloor</b> exists to be turned OFF by fixed-wing crews. The floor buys a ground
     * turret loose shots it would otherwise never take, and splash makes that a fair trade at tank
     * ranges. It is not a fair trade for an aircraft: the miss distance of a shot fired {@code a}
     * degrees off at range {@code R} is about {@code R tan(a)}, and an aircraft engages at hundreds
     * of blocks, so a 35 degree floor guarantees misses measured in tens of blocks no matter how
     * well the autopilot flies. Aircraft pass false and gate themselves tightly instead.
     */
    public static FireGate tryAiFireAssistResult(VehicleEntity vehicle, AbstractUnit unit,
                                                 LivingEntity target,
                                                 @javax.annotation.Nullable Vec3 aimPoint,
                                                 double coneDeg, boolean npcConeFloor) {
        try {
            double effectiveCone = npcConeFloor ? npcAssistConeDeg(coneDeg) : Math.max(coneDeg, 0.1);

            int rpm = Math.max(1, vehicle.vehicleWeaponRpm(unit));
            int interval = Math.max(1, (int) Math.ceil(1200.0F / rpm));
            if (vehicle.tickCount % interval != 0) return FireGate.RPM_WAIT;
            if (!vehicle.canShoot(unit)) return FireGate.CANNOT_SHOOT;

            Vec3 shootDir = vehicle.getShootDirectionForHud(unit, 1.0F);
            Vec3 aim = aimPoint != null ? aimPoint : target.getBoundingBox().getCenter();
            Vec3 toTarget = aim.subtract(vehicle.getShootPos(unit, 1.0F));
            if (shootDir.lengthSqr() < 1.0E-6 || toTarget.lengthSqr() < 1.0E-6) {
                return FireGate.BAD_VECTOR;
            }

            double cos = shootDir.normalize().dot(toTarget.normalize());
            double angleDeg = Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0, 1.0)));
            if (angleDeg >= effectiveCone) return FireGate.CONE;

            vehicle.vehicleShoot(unit, target.getUUID(), null);
            return FireGate.FIRED;
        } catch (Exception e) {
            return FireGate.ERROR;
        }
    }

    /**
     * Formerly gunship doctrine ({@link DriveHelicopterGoal}): pick a RANDOM slot.
     * Helicopter combat v1 now uses {@link #selectWeaponForTarget}; this remains for
     * any caller that still wants a uniform real-slot pick. Bounded by the seat's
     * real weapon count — setWeaponIndex() doesn't bounds-check and an invalid index
     * silently disarms the seat.
     *
     * <p>Placeholder slots are excluded (see isRealWeapon): a uniform pick over every
     * slot would sooner or later land on one and permanently freeze the turret.
     */
    public static void selectRandomWeapon(VehicleEntity vehicle, int seatIndex, RandomSource random) {
        if (seatIndex < 0) return;
        SeatInfo seat = vehicle.getSeat(seatIndex);
        int weaponCount = seat == null ? 0 : seat.weapons().size();
        if (weaponCount <= 0) return;

        // Reservoir pick over the real slots only — one pass, no allocation, and it
        // stays uniform without needing to know the usable count up front.
        int chosen = -1;
        int seen = 0;
        for (int w = 0; w < weaponCount; w++) {
            if (!isRealWeapon(vehicle, seatIndex, w)) continue;
            seen++;
            if (random.nextInt(seen) == 0) chosen = w;
        }
        if (chosen < 0) return; // every slot is a placeholder — leave the index alone
        vehicle.setWeaponIndex(seatIndex, chosen);
    }
}

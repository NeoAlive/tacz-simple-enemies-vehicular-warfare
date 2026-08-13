package com.neoalive.tacz_sewv.compat;

import java.util.Locale;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * NPC-seated-only addon overrides keyed by registry id. No addon classes are referenced —
 * softcompat jars may be absent. Player-driven hulls never match ({@link #npcSeated}).
 */
public final class NpcVehicleOverrides {

    /** Softskin FCP / Ash wheeled hulls that read too fast under AI. */
    private static final float SOFTSKIN_SPEED = 0.60f;
    private static final float PANTSIR_SPEED = 0.55f;

    /** Raised fire-assist floor for fcp:littlebird_armed. */
    private static final double LITTLEBIRD_CONE_FLOOR_DEG = 45.0;

    // Throttle rates for mirrorThrottle, copied from the airframe's own Player branch.
    private static final float THROTTLE_INCREMENT = 0.0045f;
    private static final float THROTTLE_DECREMENT = 0.006f;
    private static final float THROTTLE_IDLE_DECAY = 0.995f;
    private static final float BRAKE_GROUND_DECAY = 0.92f;
    private static final float BRAKE_AIR_DECAY = 0.97f;

    public enum EngineHint {
        NONE,
        HELICOPTER,
        AIRCRAFT
    }

    private NpcVehicleOverrides() {}

    public static boolean npcSeated(@Nullable VehicleEntity hull) {
        return hull != null && hull.getFirstPassenger() instanceof AbstractUnit;
    }

    @Nullable
    public static String entityId(@Nullable VehicleEntity hull) {
        if (hull == null) return null;
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(hull.getType());
        return key == null ? null : key.toString().toLowerCase(Locale.ROOT);
    }

    /** Thrust scale stacked on health mobility; 1.0 = no cap. */
    public static float speedScale(@Nullable VehicleEntity hull) {
        if (!npcSeated(hull)) return 1.0f;
        String id = entityId(hull);
        if (id == null) return 1.0f;
        if (id.startsWith("fcp:")) {
            if (contains(id, "uaz") || contains(id, "gaz") || contains(id, "hmmwv")
                    || contains(id, "kozak") || contains(id, "matv")) {
                return SOFTSKIN_SPEED;
            }
        }
        if (id.equals("ashvehicle:pa_pantsir") || contains(id, "pa_pantsir")) {
            return PANTSIR_SPEED;
        }
        return 1.0f;
    }

    /**
     * Unarmed / troop-lift helicopters: transit only, no orbit/strafe combat.
     * Exact ids — armed huey/mi17 variants keep combat AI.
     */
    public static boolean isTransportHeli(@Nullable VehicleEntity hull) {
        String id = entityId(hull);
        if (id == null) return false;
        return id.equals("fcp:huey")
                || id.equals("fcp:mi17")
                || id.equals("ashvehicle:uh_60")
                || id.equals("vvp:mi_8")
                || id.equals("vvp:nh_90");
    }

    public static boolean isHeavyHeli(@Nullable VehicleEntity hull) {
        String id = entityId(hull);
        if (id == null) return false;
        return id.equals("ashvehicle:ah-64")
                || id.equals("ashvehicle:ka52")
                || contains(id, "ah-64") && id.startsWith("ashvehicle:")
                || contains(id, "ka52") && id.startsWith("ashvehicle:");
    }

    public static boolean isLittlebirdArmed(@Nullable VehicleEntity hull) {
        String id = entityId(hull);
        return id != null && id.equals("fcp:littlebird_armed");
    }

    /** Extra NPC fire-assist cone floor (degrees), or 0 if none. */
    public static double heliConeFloorDeg(@Nullable VehicleEntity hull) {
        return isLittlebirdArmed(hull) ? LITTLEBIRD_CONE_FLOOR_DEG : 0.0;
    }

    /** Lock ground weapon slot for the engagement (littlebird Cannon/Rocket thrash). */
    public static boolean heliWeaponLock(@Nullable VehicleEntity hull) {
        return isLittlebirdArmed(hull);
    }

    /**
     * Hulls whose {@code canAddPassenger} refuses anything that is not a {@link net.minecraft.world.entity.player.Player},
     * so no crew can ever mount one however long it stands next to it.
     *
     * <p>Ash's two drones answer only to the player holding the SBW Monitor that spawned them, and
     * VVP's D-30 is fired by right-clicking it rather than sitting in it. All three are otherwise
     * perfectly ordinary scavenging candidates — alive, undamaged, one free seat, nobody aboard —
     * so without this an idle rifleman walks to one, spends {@code BoardVehicleGoal}'s whole 20 s
     * timeout failing to mount, gives up, and is handed the same hull again on the next scan.
     * There is no readable signal to test instead: {@code canAddPassenger} is {@code protected}.
     */
    public static boolean refusesNpcRiders(@Nullable VehicleEntity hull) {
        String id = entityId(hull);
        if (id == null) return false;
        return id.equals("ashvehicle:reaper")
                || id.equals("ashvehicle:x-47b")
                || id.equals("vvp:d30");
    }

    public static boolean isT80bvKantemir(@Nullable VehicleEntity hull) {
        String id = entityId(hull);
        return id != null && id.equals("mcsp:t80bv_kantemir");
    }

    /**
     * Hulls that answer the throttle for a {@code Player} only, and therefore need
     * {@link #mirrorThrottle} to fly under AI at all.
     *
     * <p>Ash's F-35B declares {@code EngineType: Empty} and carries its own copy of SBW's aircraft
     * engine, and that copy narrowed SBW's {@code firstPassenger != null} input gate to
     * {@code passenger instanceof Player}. The V-22 shares the copy but is deliberately absent
     * here — see {@link #engineHint} for why giving it thrust would be worse than leaving it
     * without any.
     */
    private static boolean playerOnlyThrottle(@Nullable String id) {
        return id != null && (id.equals("ashvehicle:f-35b") || id.equals("ashvehicle:f_35b"));
    }

    /**
     * Drives {@code POWER} from the input flags the crew already set, for the one airframe whose
     * own engine will not.
     *
     * <p>The gate costs less than it looks like it should. Everything the plane goal steers with —
     * {@code mouseMoveSpeedX}/{@code Y} for yaw and pitch, the roll damping, the gear, the flaps,
     * the lift term and the thrust direction — is computed <b>outside</b> the branch and works for
     * a mob crew already. Only the block that turns {@code forward}/{@code back}/{@code down} into
     * a {@code POWER} setting is inside it, and thrust is
     * {@code viewVector.scale(0.03 * speedRate * POWER)}, so the aircraft is not degraded without
     * it, it is inert: it will not roll down the runway however correctly it is being flown.
     *
     * <p>So this reproduces that one block, against {@code VehicleEntity}'s own public synched
     * data — no addon class is named, and an install without the addon never reaches it. The rates
     * are the schema defaults rather than the hull's {@code EngineInfo}: they set how fast the
     * engine spools, while where it settles is drag against a {@code POWER} that saturates at 1
     * either way, so reading them wrong costs a second of acceleration and nothing else.
     * {@code getEngineInfo()} is also null until the hull's first {@code travel()}, which is
     * exactly the tick this would first be asked.
     */
    public static void mirrorThrottle(@Nullable VehicleEntity hull) {
        if (!npcSeated(hull) || !playerOnlyThrottle(entityId(hull))) return;
        float power = hull.getEntityData().get(VehicleEntity.POWER);
        if (hull.forwardInputDown()) {
            power = Mth.clamp(power + THROTTLE_INCREMENT, -0.1f, 1.0f);
        } else if (hull.backInputDown()) {
            power = Math.max(power - THROTTLE_DECREMENT, hull.onGround() ? -0.2f : 0.4f);
        } else {
            power *= THROTTLE_IDLE_DECAY;
        }
        // After the throttle, exactly as the hull's own block orders them: the brake is a
        // multiplier ON the new setting, not an alternative to it, which is what makes holding
        // both at once settle at a lower power instead of cancelling out.
        if (hull.downInputDown()) {
            power *= hull.onGround() ? BRAKE_GROUND_DECAY : BRAKE_AIR_DECAY;
        }
        hull.getEntityData().set(VehicleEntity.POWER, power);
    }

    /**
     * Remap datapack {@code Empty} engines for Ash VTOL airframes so spawn/goals route correctly.
     *
     * <p>Two hulls declare {@code Empty} and carry their own copy of SBW's aircraft engine, and
     * they are <b>not</b> the same case as each other:
     *
     * <ul>
     *   <li><b>F-35B</b> defaults {@code VTOL_MODE} to false, so it is an ordinary fixed-wing
     *       aircraft: thrust along the nose, pitch and yaw off the same look inputs every other
     *       jet uses. {@code DrivePlaneGoal} flies it correctly once {@link #mirrorThrottle} gets
     *       the crew past its {@code instanceof Player} throttle gate.
     *   <li><b>V-22</b> is left alone, and the reason is not the Player gate. It defaults
     *       {@code VTOL_MODE} to <em>true</em>, where thrust is
     *       {@code view.lerp(up, 1.0).normalize()} — which is the up vector outright, so throttle
     *       is pure vertical climb and no forward-flight goal can steer it. Nor is it a
     *       helicopter: SBW's helicopter engine spends {@code upInput}/{@code downInput} on the
     *       collective, while this hull spends {@code upInput} on the <em>landing gear</em>, so
     *       {@code DriveHelicopterGoal} does not climb, it cycles the gear. Reporting {@code Empty}
     *       leaves it a hull a crew can ride and shoot from without a flight goal claiming it.
     * </ul>
     */
    public static EngineHint engineHint(@Nullable String entityId) {
        if (entityId == null) return EngineHint.NONE;
        String id = entityId.toLowerCase(Locale.ROOT);
        if (id.equals("ashvehicle:f-35b") || id.equals("ashvehicle:f_35b")) {
            return EngineHint.AIRCRAFT;
        }
        return EngineHint.NONE;
    }

    public static EngineHint engineHint(@Nullable VehicleEntity hull) {
        return engineHint(entityId(hull));
    }

    /** Apply id-keyed Empty remaps; returns {@code declared} unchanged when no hint. */
    @Nullable
    public static EngineType applyEngineHint(@Nullable String entityId, @Nullable EngineType declared) {
        EngineHint hint = engineHint(entityId);
        if (hint == EngineHint.HELICOPTER) return EngineType.HELICOPTER;
        if (hint == EngineHint.AIRCRAFT) return EngineType.AIRCRAFT;
        return declared;
    }

    @Nullable
    public static EngineType applyEngineHint(@Nullable VehicleEntity hull) {
        return applyEngineHint(entityId(hull),
                hull == null ? null : safeEngine(hull));
    }

    @Nullable
    private static EngineType safeEngine(VehicleEntity hull) {
        try {
            return hull.computed().getEngineType();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean contains(String id, String clue) {
        return id.contains(clue);
    }
}

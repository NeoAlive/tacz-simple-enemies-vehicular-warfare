package com.neoalive.tacz_sewv.entity.ai.plane;

import java.util.UUID;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

/**
 * How far a plane is allowed to get from whoever it belongs to, and what it does about it.
 *
 * <p>This is the missing backstop behind "planes wander off for no reason". Nothing in the old goal
 * measured the distance to the owner at all: a FREE_FIRE plane that acquired a target flew a
 * 440-block attack run at it, turned, and flew another, and each cycle could put it further away
 * than the last with no force pulling it back. Orders pinned the flight path, but FREE_FIRE and
 * ATTACK_THAT_TARGET — the two states a player actually leaves a fighter in — pinned nothing.
 *
 * <p>Two rings with hysteresis, not one threshold: a single line would have the aircraft flipping
 * between fighting and returning every time the fight drifted across it, which reads as indecision
 * and wastes the whole approach. Past the soft ring it finishes what it is doing and comes home;
 * past the hard ring it abandons the pass outright. It only counts as home again once well inside,
 * so one good turn resolves the state instead of one lucky tick.
 */
public final class PlaneLeash {

    /** The hard ring is this multiple of the soft one. */
    public static final double HARD_MULTIPLIER = 1.5;
    /** Fraction of the soft ring the aircraft must get back inside before the leash releases. */
    public static final double RECOVER_FRACTION = 0.75;

    public enum State {
        /** Inside the leash: fight freely. */
        FREE,
        /** Past the soft ring: finish the pass, then return. Do not start a new attack. */
        RECALL,
        /** Past the hard ring: break off now and fly home. */
        RETURN
    }

    private State state = State.FREE;
    private Vec3 lastAnchor;

    public State state() {
        return this.state;
    }

    public void reset() {
        this.state = State.FREE;
        this.lastAnchor = null;
    }

    /** The point the aircraft is tethered to, or null when it is answerable to nobody. */
    @Nullable
    public Vec3 anchor() {
        return this.lastAnchor;
    }

    /**
     * Update the leash against this tick's anchor. A null anchor (owner logged out, no ally in
     * range) releases the leash rather than tethering the aircraft to a stale point: an aircraft
     * orbiting the ghost of a player who left is worse than one flying on.
     */
    public State update(@Nullable Vec3 anchor, Vec3 position, double softRadius) {
        this.lastAnchor = anchor;
        if (anchor == null) {
            this.state = State.FREE;
            return this.state;
        }
        double dx = anchor.x - position.x;
        double dz = anchor.z - position.z;
        this.state = evaluate(Math.sqrt(dx * dx + dz * dz), softRadius, this.state);
        return this.state;
    }

    /**
     * Pure ring logic with hysteresis. Kept separate from the entity lookups so the self-check can
     * pin the one property that matters: crossing out and back in must not produce a different
     * answer at the same distance depending on nothing but tick order.
     */
    public static State evaluate(double distance, double softRadius, State previous) {
        double soft = Math.max(softRadius, 1.0);
        double hard = soft * HARD_MULTIPLIER;
        if (distance >= hard) return State.RETURN;
        if (previous == State.RETURN) {
            // Already coming home: keep coming until genuinely back inside.
            return distance <= soft * RECOVER_FRACTION ? State.FREE : State.RETURN;
        }
        if (distance >= soft) return State.RECALL;
        if (previous == State.RECALL) {
            return distance <= soft * RECOVER_FRACTION ? State.FREE : State.RECALL;
        }
        return State.FREE;
    }

    /**
     * Who a crew answers to. A PMC is tethered to its owning player — the aircraft is that player's
     * asset and being where they are is the point of it. RU/US have no owner, so their tether is
     * the ally they are supporting, supplied by the caller; they are answerable to the local fight,
     * not to a person.
     */
    @Nullable
    public static Vec3 ownerAnchor(AbstractUnit unit) {
        if (!(unit instanceof PmcUnitEntity pmc)) return null;
        UUID owner = pmc.getOwnerUUID();
        if (owner == null) return null;
        Player player = unit.level().getPlayerByUUID(owner);
        return player == null ? null : player.position();
    }

    /** Convenience for the RU/US branch: the ally hull or unit being escorted. */
    @Nullable
    public static Vec3 entityAnchor(@Nullable Entity entity) {
        return entity == null ? null : entity.position();
    }
}

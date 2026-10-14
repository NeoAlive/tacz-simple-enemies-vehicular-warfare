package com.neoalive.tacz_sewv.entity.ai.core;

import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

/**
 * The only thing locomotion code may do to a hull: hold its four driving keys, and ask which way
 * it points. {@link VehicleDriver} and {@link ShipHelm} write through this instead of SBW's setters
 * so a second vehicle mod can be driven by the same code.
 *
 * <p>The key semantics are SBW's, and a non-SBW implementation translates to them, not the other
 * way round: left = bow swings left while making way ahead, steering inverts astern on a wheeled
 * hull or a boat. Yaw follows the vanilla convention (right turn grows yaw), which is what
 * {@link VehicleTargeting#signedAngleTo} assumes.
 */
public interface HullInput {

    Entity hull();

    void setInputs(boolean forward, boolean back, boolean left, boolean right);

    /** The hull's facing; only x/z are read. */
    Vector3f forwardDirection();

    default void release() {
        setInputs(false, false, false, false);
    }
}

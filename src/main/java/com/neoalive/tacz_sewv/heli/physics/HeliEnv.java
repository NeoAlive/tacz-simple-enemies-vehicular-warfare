package com.neoalive.tacz_sewv.heli.physics;

import org.joml.Vector3dc;

/**
 * Environment for one tick: gravity and density (SI), wind (world frame), the ground height
 * under the hub ({@code MOTION_BLOCKING_NO_LEAVES}, used for ground effect only; +infinity when
 * the column is unloaded), and whether the hull rested on something at tick start.
 */
public record HeliEnv(double g, double rho, Vector3dc wind, double groundY, boolean onGround) {}

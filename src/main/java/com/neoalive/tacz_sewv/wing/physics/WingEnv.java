package com.neoalive.tacz_sewv.wing.physics;

import org.joml.Vector3dc;

/**
 * Environment for one tick: gravity and density (SI), wind (world frame), the ground height under
 * the hull ({@code MOTION_BLOCKING_NO_LEAVES}; +infinity when the column is unloaded), and whether
 * the hull rested on something at tick start (the touchdown handoff reads it; the physics never
 * models ground contact).
 */
public record WingEnv(double g, double rho, Vector3dc wind, double groundY, boolean onGround) {}

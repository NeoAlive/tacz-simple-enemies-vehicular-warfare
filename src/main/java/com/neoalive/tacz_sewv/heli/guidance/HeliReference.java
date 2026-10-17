package com.neoalive.tacz_sewv.heli.guidance;

import org.joml.Vector3dc;

/**
 * One sample of a time-parameterised reference: world-frame position, velocity and acceleration
 * (SI), plus heading {@code yaw} (radians, vanilla yRot convention: nose = (-sin yaw, 0, cos yaw))
 * and its rate. There is deliberately no pitch or roll: those are whatever points the thrust at
 * the required acceleration, which is the controller's business.
 */
public record HeliReference(double t, Vector3dc p, Vector3dc v, Vector3dc a, double yaw, double yawRate) {}

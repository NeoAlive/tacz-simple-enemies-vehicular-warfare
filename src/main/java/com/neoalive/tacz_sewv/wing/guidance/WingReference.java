package com.neoalive.tacz_sewv.wing.guidance;

import org.joml.Vector3dc;

/**
 * One sample of a time-parameterised fixed-wing reference: world-frame position, velocity and
 * acceleration (SI). Unlike a helicopter reference there is no heading: a fixed wing points where
 * it flies, so attitude is entirely the controller's business.
 *
 * <p>{@link Mode#FLYPATH} tracks the path (L1 laterally, TECS on height and speed, where the speed
 * is {@code |v|}). {@link Mode#NOSE_AIM} instead points the weapon boresight along {@code aim}
 * (a world-frame unit vector, already boresight-corrected by whoever built it) while holding the
 * speed {@code |v|}; position is ignored, which is what an attack run's final seconds want.
 */
public record WingReference(double t, Vector3dc p, Vector3dc v, Vector3dc a, Mode mode, Vector3dc aim) {

    public enum Mode { FLYPATH, NOSE_AIM }

    public static WingReference flypath(double t, Vector3dc p, Vector3dc v, Vector3dc a) {
        return new WingReference(t, p, v, a, Mode.FLYPATH, null);
    }

    public static WingReference noseAim(double t, Vector3dc p, Vector3dc v, Vector3dc aim) {
        return new WingReference(t, p, v, ZERO, Mode.NOSE_AIM, aim);
    }

    private static final Vector3dc ZERO = new org.joml.Vector3d();
}

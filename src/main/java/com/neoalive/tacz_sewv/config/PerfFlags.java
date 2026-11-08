package com.neoalive.tacz_sewv.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client perf toggles for mixin hot paths. Side neutral: no client classes, and before the client
 * spec loads (or on a dedicated server) every read is the default.
 */
public final class PerfFlags {

    private PerfFlags() {}

    public static boolean gemrenderPartsPaletteLift() {
        return bool(ClientConfig.GEMRENDER_PARTS_PALETTE_LIFT);
    }

    public static boolean dogTagIconCache() {
        return bool(ClientConfig.DOG_TAG_ICON_CACHE);
    }

    public static boolean vehicleRestSkip() {
        return bool(ClientConfig.VEHICLE_REST_SKIP);
    }

    public static int vehicleFarSimDistance() {
        return integer(ClientConfig.VEHICLE_FAR_SIM_DISTANCE);
    }

    public static int vehicleTurnSoundDistance() {
        return integer(ClientConfig.VEHICLE_TURN_SOUND_DISTANCE);
    }

    public static boolean semUnitFrustumCull() {
        return bool(ClientConfig.SEM_UNIT_FRUSTUM_CULL);
    }

    public static boolean taczThirdPersonFlushSkip() {
        return bool(ClientConfig.TACZ_THIRD_PERSON_FLUSH_SKIP);
    }

    public static boolean taczFastCubeEmit() {
        return bool(ClientConfig.TACZ_FAST_CUBE_EMIT);
    }

    /** Attachment LOD distance in blocks, 0 when off. */
    public static int semAttachmentLodDistance() {
        return bool(ClientConfig.SEM_ATTACHMENT_LOD) ? integer(ClientConfig.SEM_ATTACHMENT_LOD_DISTANCE) : 0;
    }

    /** Armor LOD distance in blocks, 0 when off. */
    public static int semArmorLodDistance() {
        return bool(ClientConfig.SEM_ARMOR_LOD) ? integer(ClientConfig.SEM_ARMOR_LOD_DISTANCE) : 0;
    }

    private static boolean bool(ForgeConfigSpec.BooleanValue value) {
        if (!ClientConfig.SPEC.isLoaded()) return value.getDefault();
        return value.get();
    }

    private static int integer(ForgeConfigSpec.IntValue value) {
        if (!ClientConfig.SPEC.isLoaded()) return value.getDefault();
        return value.get();
    }
}

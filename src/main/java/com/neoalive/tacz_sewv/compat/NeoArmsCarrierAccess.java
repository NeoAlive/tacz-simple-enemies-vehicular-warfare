package com.neoalive.tacz_sewv.compat;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Reflective bridge onto Neo Arms {@code AircraftCarrierEntity} / {@code DeckStrip} / {@code DeckPark}.
 * Safe to call when Neo Arms is absent — every entry returns null/false.
 */
public final class NeoArmsCarrierAccess {

    private static final String CARRIER_CLASS = "tech.neoarms.addon.entity.AircraftCarrierEntity";
    private static final String STRIP_CLASS = "tech.neoarms.addon.entity.DeckStrip";
    private static final String DECK_PARK_CLASS = "tech.neoarms.addon.deck.DeckPark";

    public static final String TAG_CARRIER_DECK = "sewv:carrier_deck";

    private static boolean resolved;
    @Nullable private static Class<?> carrierClass;
    @Nullable private static Method mIsStaticMode;
    @Nullable private static Method mDeckStrip;
    @Nullable private static Method mPlaceOnDeck;
    @Nullable private static Method mIsNearAny;
    @Nullable private static Method mIsDeckParked;
    @Nullable private static Method mSetDeckParked;
    @Nullable private static Method mCarrierNetworkId;
    @Nullable private static Method mThreshold;
    @Nullable private static Method mHeadingDeg;
    @Nullable private static Method mLength;
    @Nullable private static Method mWidth;

    private NeoArmsCarrierAccess() {}

    /** World-space deck strip for a STATIC Neo Arms carrier, or null. */
    public record Strip(Vec3 threshold, float headingDeg, int length, int width, int carrierId) {
        public BlockPos thresholdBlock() {
            return BlockPos.containing(this.threshold.x, this.threshold.y, this.threshold.z);
        }

        public BlockPos touchdownApprox() {
            return this.thresholdBlock();
        }
    }

    public static boolean isCarrier(@Nullable Entity entity) {
        if (entity == null || !NeoArmsCompat.present()) return false;
        resolve();
        return carrierClass != null && carrierClass.isInstance(entity);
    }

    public static boolean isCarrierType(EntityType<?> type) {
        if (!NeoArmsCompat.present()) return false;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return id != null && NeoArmsCompat.CARRIER_ID.equals(id.toString());
    }

    /** Neo Arms synced deck-park flag (planes/heli on the carrier pad). */
    public static boolean isDeckParked(@Nullable Entity entity) {
        if (entity == null || !NeoArmsCompat.present()) return false;
        resolve();
        if (mIsDeckParked == null) {
            return entity.getPersistentData().getBoolean("neoarms:deck_parked");
        }
        try {
            return Boolean.TRUE.equals(mIsDeckParked.invoke(null, entity));
        } catch (Throwable t) {
            return entity.getPersistentData().getBoolean("neoarms:deck_parked");
        }
    }

    public static void setDeckParked(@Nullable Entity entity, boolean parked) {
        if (entity == null || !NeoArmsCompat.present()) return;
        resolve();
        if (mSetDeckParked != null) {
            try {
                mSetDeckParked.invoke(null, entity, parked);
                return;
            } catch (Throwable ignored) {
            }
        }
        if (parked) {
            entity.getPersistentData().putBoolean("neoarms:deck_parked", true);
        } else {
            entity.getPersistentData().remove("neoarms:deck_parked");
        }
    }

    /** True if any loaded Neo Arms carrier is within {@code range} (no entity-section scan). */
    public static boolean isNearCarrier(@Nullable Entity entity, double range) {
        if (entity == null || !NeoArmsCompat.present()) return false;
        resolve();
        if (mIsNearAny == null) return false;
        try {
            return Boolean.TRUE.equals(mIsNearAny.invoke(null, entity, range));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isStaticMode(@Nullable Entity entity) {
        if (!isCarrier(entity) || mIsStaticMode == null) return false;
        try {
            return Boolean.TRUE.equals(mIsStaticMode.invoke(entity));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when this hull is deck-parked on a carrier that is in STATIC mode
     * (taxi allowed). False for DYNAMIC / missing link — travel stays cancelled.
     */
    public static boolean isStoodOnStaticCarrier(@Nullable Entity entity) {
        if (entity == null || !isDeckParked(entity) || entity.level() == null) {
            return false;
        }
        resolve();
        int carrierId = 0;
        if (mCarrierNetworkId != null) {
            try {
                Object v = mCarrierNetworkId.invoke(null, entity);
                if (v instanceof Integer i) {
                    carrierId = i;
                }
            } catch (Throwable ignored) {
            }
        }
        if (carrierId == 0) {
            carrierId = entity.getPersistentData().getInt("neoarms:deck_carrier");
        }
        if (carrierId == 0) {
            return false;
        }
        Entity carrier = entity.level().getEntity(carrierId);
        return isStaticMode(carrier);
    }

    @Nullable
    public static Strip deckStrip(@Nullable Entity entity) {
        return deckStrip(entity, true);
    }

    /**
     * @param requireStatic when false, returns geometry even in DYNAMIC (GUI preview only).
     */
    @Nullable
    public static Strip deckStrip(@Nullable Entity entity, boolean requireStatic) {
        if (!isCarrier(entity) || mDeckStrip == null) return null;
        if (requireStatic && !isStaticMode(entity)) return null;
        try {
            Object strip = mDeckStrip.invoke(entity);
            if (strip == null) return null;
            Vec3 thr = (Vec3) mThreshold.invoke(strip);
            float heading = ((Number) mHeadingDeg.invoke(strip)).floatValue();
            int len = ((Number) mLength.invoke(strip)).intValue();
            int w = ((Number) mWidth.invoke(strip)).intValue();
            if (thr == null || len <= 0 || w <= 0) return null;
            return new Strip(thr, heading, len, w, entity.getId());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Seat an entity on the deck with its bounding-box floor sits on a Body OBB. */
    public static boolean placeOnDeck(@Nullable Entity carrier, @Nullable Entity entity,
                                      double x, double z, float yaw) {
        if (!isCarrier(carrier) || entity == null || mPlaceOnDeck == null) return false;
        try {
            return Boolean.TRUE.equals(mPlaceOnDeck.invoke(carrier, entity, x, z, yaw));
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when this hull was deployed/parked on a Neo Arms carrier deck (SEWV ops tag). */
    public static boolean isCarrierParked(@Nullable Entity entity) {
        return entity != null && entity.getPersistentData().getBoolean(TAG_CARRIER_DECK);
    }

    public static void markCarrierParked(@Nullable Entity entity, boolean parked) {
        if (entity == null) return;
        if (parked) {
            entity.getPersistentData().putBoolean(TAG_CARRIER_DECK, true);
            setDeckParked(entity, true);
        } else {
            entity.getPersistentData().remove(TAG_CARRIER_DECK);
            setDeckParked(entity, false);
        }
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!NeoArmsCompat.present()) return;
        try {
            carrierClass = Class.forName(CARRIER_CLASS);
            Class<?> stripClass = Class.forName(STRIP_CLASS);
            Class<?> deckPark = Class.forName(DECK_PARK_CLASS);
            mIsStaticMode = carrierClass.getMethod("isStaticMode");
            mDeckStrip = carrierClass.getMethod("deckStrip");
            mPlaceOnDeck = carrierClass.getMethod("placeOnDeck", Entity.class, double.class, double.class, float.class);
            mIsNearAny = carrierClass.getMethod("isNearAny", Entity.class, double.class);
            mIsDeckParked = deckPark.getMethod("isParked", Entity.class);
            mSetDeckParked = deckPark.getMethod("setParked", Entity.class, boolean.class);
            mCarrierNetworkId = deckPark.getMethod("carrierNetworkId", Entity.class);
            mThreshold = stripClass.getMethod("threshold");
            mHeadingDeg = stripClass.getMethod("headingDeg");
            mLength = stripClass.getMethod("length");
            mWidth = stripClass.getMethod("width");
        } catch (Throwable t) {
            carrierClass = null;
            mIsStaticMode = null;
            mDeckStrip = null;
            mPlaceOnDeck = null;
            mIsNearAny = null;
            mIsDeckParked = null;
            mSetDeckParked = null;
            mCarrierNetworkId = null;
            mThreshold = null;
            mHeadingDeg = null;
            mLength = null;
            mWidth = null;
        }
    }
}

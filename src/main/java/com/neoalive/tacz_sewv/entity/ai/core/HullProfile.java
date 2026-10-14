package com.neoalive.tacz_sewv.entity.ai.core;

/**
 * Static facts about a hull that is NOT an SBW {@code VehicleEntity}, in the shape the locomotion
 * code needs them. An SBW hull never has one: its facts come from {@code computed()} through
 * {@link HullFacts} and {@code GroundMobility} as they always did.
 *
 * <p>{@code width}/{@code height} are the hull's real extents, which a foreign hull may not report
 * through {@code getBbWidth()} (Limitless Vehicles sizes its box from OBBs, not the entity type).
 * {@code steeringSpeed} NaN means "no turn model", which makes {@code ManeuverPlanner} refuse and
 * the legacy back-and-fill run instead.
 */
public record HullProfile(boolean tracked, boolean wheeled, boolean ship, boolean amphibious,
                          float maxUpStep, double width, double height, double steeringSpeed) {
}

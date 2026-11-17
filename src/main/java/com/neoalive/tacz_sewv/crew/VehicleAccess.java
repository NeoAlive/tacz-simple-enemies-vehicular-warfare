package com.neoalive.tacz_sewv.crew;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;

/**
 * Who may enter or use a hull. Pure: the caller hands in the passengers, the stamp and the
 * viewer's already-resolved side relations. Diplomacy between two players is not an input.
 *
 * <p>First deny wins. A player or owned PMC passenger the viewer is not authorized for, then an
 * RU or US passenger unless that side resolved to {@link DiplomacyData.Relation#ALLY}, then a
 * stamp that does not authorize the viewer. An unset side relation is not an alliance, so an
 * RU or US crew stays locked until someone stores {@code ALLY}. An ownerless PMC passenger
 * (garrison) does not count as anyone's unit.
 */
public final class VehicleAccess {

    public static final String TAG_OWNER = "sewv:hull_owner";
    public static final String TAG_TEAM = "sewv:hull_owner_team";

    public enum Kind {
        PLAYER, PMC, RU, US
    }

    public enum Verdict {
        ALLOW, FOREIGN, HOSTILE
    }

    /** The clicker, or the owner a boarding PMC is acting for. */
    public record Viewer(@Nullable UUID id, @Nullable String team) {}

    /** One passenger that can lock the hull. {@code owner} is the player UUID for a player or a PMC. */
    public record Occupant(Kind kind, @Nullable UUID owner, @Nullable String team) {}

    /** Persisted owner. Absent when the hull has never been claimed. */
    public record Stamp(@Nullable UUID owner, @Nullable String team) {
        public boolean present() {
            return owner != null || (team != null && !team.isEmpty());
        }
    }

    public record Facts(List<Occupant> occupants, @Nullable Stamp stamp) {}

    private VehicleAccess() {}

    public static boolean denies(Viewer viewer, Facts facts, @Nullable DiplomacyData.Relation ru,
                                 @Nullable DiplomacyData.Relation us) {
        return verdict(viewer, facts, ru, us) != Verdict.ALLOW;
    }

    public static Verdict verdict(Viewer viewer, Facts facts, @Nullable DiplomacyData.Relation ru,
                                  @Nullable DiplomacyData.Relation us) {
        String stampTeam = facts.stamp == null ? null : facts.stamp.team;
        for (Occupant occupant : facts.occupants) {
            switch (occupant.kind) {
                case PLAYER -> {
                    if (!authorized(viewer, occupant.owner, stampTeam)) return Verdict.FOREIGN;
                }
                case PMC -> {
                    if (occupant.owner == null && (occupant.team == null || occupant.team.isEmpty())) break;
                    if (!authorized(viewer, occupant.owner, occupant.team)) return Verdict.FOREIGN;
                }
                case RU -> {
                    if (ru != DiplomacyData.Relation.ALLY) return Verdict.HOSTILE;
                }
                case US -> {
                    if (us != DiplomacyData.Relation.ALLY) return Verdict.HOSTILE;
                }
            }
        }
        if (facts.stamp != null && facts.stamp.present()
                && !authorized(viewer, facts.stamp.owner, facts.stamp.team)) {
            return Verdict.FOREIGN;
        }
        return Verdict.ALLOW;
    }

    /**
     * UUID match, or the owner's team tag matching the viewer's team. A live scoreboard team that
     * was never stamped on the unit or the hull does not authorize.
     */
    public static boolean authorized(Viewer viewer, @Nullable UUID owner, @Nullable String ownerTeam) {
        if (viewer.id != null && owner != null && viewer.id.equals(owner)) return true;
        return ownerTeam != null && !ownerTeam.isEmpty()
                && viewer.team != null && ownerTeam.equals(viewer.team);
    }
}

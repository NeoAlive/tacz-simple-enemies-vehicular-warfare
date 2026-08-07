package com.neoalive.tacz_sewv.invasion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Session-scoped invasion match HUD: layout computed once at start (A/B bookends + projected
 * capture points), state refreshed while the session is live.
 */
public final class InvasionHud {

    public static final byte KIND_BASE = 0;
    public static final byte KIND_POINT = 1;

    /** 0 = neutral, 1 = team A, 2 = team B. */
    public static final byte SIDE_NEUTRAL = 0;
    public static final byte SIDE_A = 1;
    public static final byte SIDE_B = 2;

    private InvasionHud() {}

    public record Slot(byte kind, BlockPos pos) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeByte(kind);
            buf.writeBlockPos(pos);
        }

        public static Slot decode(FriendlyByteBuf buf) {
            return new Slot(buf.readByte(), buf.readBlockPos());
        }
    }

    public record SlotState(byte ownerSide, byte conquerSide, float progress, boolean capturing) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeByte(ownerSide);
            buf.writeByte(conquerSide);
            buf.writeFloat(progress);
            buf.writeBoolean(capturing);
        }

        public static SlotState decode(FriendlyByteBuf buf) {
            return new SlotState(buf.readByte(), buf.readByte(), buf.readFloat(), buf.readBoolean());
        }
    }

    public record Snapshot(int colorA, int colorB, int colorNeutral,
                           String teamA, String teamB,
                           List<Slot> slots, List<SlotState> states) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeInt(colorA);
            buf.writeInt(colorB);
            buf.writeInt(colorNeutral);
            buf.writeUtf(teamA == null ? "" : teamA);
            buf.writeUtf(teamB == null ? "" : teamB);
            buf.writeVarInt(slots.size());
            for (int i = 0; i < slots.size(); i++) {
                slots.get(i).encode(buf);
                states.get(i).encode(buf);
            }
        }

        public static Snapshot decode(FriendlyByteBuf buf) {
            int colorA = buf.readInt();
            int colorB = buf.readInt();
            int colorNeutral = buf.readInt();
            String teamA = buf.readUtf();
            String teamB = buf.readUtf();
            int n = buf.readVarInt();
            List<Slot> slots = new ArrayList<>(n);
            List<SlotState> states = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                slots.add(Slot.decode(buf));
                states.add(SlotState.decode(buf));
            }
            return new Snapshot(colorA, colorB, colorNeutral, teamA, teamB, slots, states);
        }

        /** ARGB for a scoreboard / invasion team name, or null if unknown. */
        @Nullable
        public Integer colorForTeam(String team) {
            if (team == null || team.isEmpty()) return null;
            if (team.equals(teamA)) return 0xFF000000 | colorA;
            if (team.equals(teamB)) return 0xFF000000 | colorB;
            return null;
        }

        @Nullable
        public Integer colorForSide(byte side) {
            return switch (side) {
                case SIDE_A -> 0xFF000000 | colorA;
                case SIDE_B -> 0xFF000000 | colorB;
                default -> null;
            };
        }
    }

    /**
     * Cached layout for one session: ordered slots + team names for A/B colour mapping.
     */
    public record Layout(List<Slot> slots, String teamA, String teamB) {}

    /**
     * Build once at start. Bases sorted by {@link BlockPos#compareTo} → A then B;
     * points ordered by projection onto A→B.
     */
    @Nullable
    public static Layout buildLayout(List<TeamBaseBlockEntity> bases,
                                     List<CapturePointBlockEntity> points) {
        if (bases.size() != 2) return null;
        List<TeamBaseBlockEntity> ordered = new ArrayList<>(bases);
        ordered.sort(Comparator.comparing(TeamBaseBlockEntity::getBlockPos));
        TeamBaseBlockEntity baseA = ordered.get(0);
        TeamBaseBlockEntity baseB = ordered.get(1);
        BlockPos a = baseA.getBlockPos();
        BlockPos b = baseB.getBlockPos();

        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double denom = dx * dx + dz * dz;
        if (denom < 1e-6) denom = 1.0; // coincident bases — arbitrary but stable

        record Ranked(CapturePointBlockEntity point, double t) {}
        List<Ranked> ranked = new ArrayList<>(points.size());
        for (CapturePointBlockEntity point : points) {
            BlockPos p = point.getBlockPos();
            double t = ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / denom;
            ranked.add(new Ranked(point, t));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::t));

        List<Slot> slots = new ArrayList<>(2 + ranked.size());
        slots.add(new Slot(KIND_BASE, a));
        for (Ranked r : ranked) {
            slots.add(new Slot(KIND_POINT, r.point().getBlockPos()));
        }
        slots.add(new Slot(KIND_BASE, b));
        return new Layout(List.copyOf(slots), baseA.getAssignedTeam(), baseB.getAssignedTeam());
    }

    public static Snapshot snapshot(ServerLevel level, Layout layout) {
        int colorA = rgb(SewvConfig.INVASION_HUD_TEAM_A_COLOR.get(), 0x5555FF);
        int colorB = rgb(SewvConfig.INVASION_HUD_TEAM_B_COLOR.get(), 0xFF5555);
        int colorN = rgb(SewvConfig.INVASION_HUD_NEUTRAL_COLOR.get(), 0xAAAAAA);

        List<SlotState> states = new ArrayList<>(layout.slots().size());
        for (Slot slot : layout.slots()) {
            states.add(stateOf(level, slot, layout.teamA(), layout.teamB()));
        }
        return new Snapshot(colorA, colorB, colorN, layout.teamA(), layout.teamB(),
                layout.slots(), List.copyOf(states));
    }

    public static byte sideOfTeam(String team, String teamA, String teamB) {
        if (team == null || team.isEmpty()) return SIDE_NEUTRAL;
        if (team.equals(teamA)) return SIDE_A;
        if (team.equals(teamB)) return SIDE_B;
        return SIDE_NEUTRAL;
    }

    private static SlotState stateOf(ServerLevel level, Slot slot, String teamA, String teamB) {
        BlockEntity be = level.getBlockEntity(slot.pos());
        if (!(be instanceof CapturableBlockEntity zone)) {
            return new SlotState(SIDE_NEUTRAL, SIDE_NEUTRAL, 0f, false);
        }

        String holder = CaptureSupport.holdingTeam(zone);
        byte owner = sideOfTeam(holder, teamA, teamB);

        boolean capturing = !zone.isContested()
                && !zone.getAdvancingTeam().isEmpty()
                && zone.getProgress() > 0f
                && zone.getProgress() < 1f;
        if (!zone.isContested() && !zone.getAdvancingTeam().isEmpty()
                && !zone.getAdvancingTeam().equals(holder)) {
            capturing = true;
        }

        byte conquer = capturing ? sideOfTeam(zone.getAdvancingTeam(), teamA, teamB) : SIDE_NEUTRAL;
        float progress = capturing ? zone.getProgress() : 0f;
        return new SlotState(owner, conquer, progress, capturing);
    }

    private static int rgb(String hex, int fallbackRgb) {
        return ClientConfig.parseColor(hex, 0xFF000000 | fallbackRgb) & 0xFFFFFF;
    }
}

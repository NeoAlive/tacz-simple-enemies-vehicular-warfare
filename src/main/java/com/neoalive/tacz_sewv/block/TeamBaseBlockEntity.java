package com.neoalive.tacz_sewv.block;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.invasion.CapturableBlockEntity;
import com.neoalive.tacz_sewv.invasion.CaptureSupport;
import com.neoalive.tacz_sewv.invasion.InvasionLayout;
import com.neoalive.tacz_sewv.invasion.PmcOwnerKind;
import com.neoalive.tacz_sewv.invasion.force.BaseForce;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * One invasion base per vanilla scoreboard team.
 * Whether capturing it ends the match is {@link #endInvasionOnCapture} (not implied by player-owned).
 */
public class TeamBaseBlockEntity extends CapturableBlockEntity {

    private String assignedTeam = "";
    private boolean playerOwned;
    private TankFaction crewFaction = TankFaction.US;
    /** What this base fields: per-kind pools and AI counts, infantry, player deploy options. */
    private BaseForce force = new BaseForce();
    /**
     * Who owns PMC crews when {@link #crewFaction} is PMC — a single player UUID or a scoreboard
     * team name. Ignored for RU/US.
     */
    private PmcOwnerKind pmcOwnerKind = PmcOwnerKind.NONE;
    private String pmcOwnerValue = "";
    /**
     * When true, an enemy completing capture of this base ends the invasion session.
     * Legacy worlds without the NBT key inherit the old rule ({@code playerOwned}).
     */
    private boolean endInvasionOnCapture;
    /**
     * Seconds to wait after {@code /sewv invasion start} before fielding vehicles (players wait at
     * world spawn). Session uses the max across both bases. Default 0 = spawn immediately.
     */
    private int spawnDelaySeconds;
    /**
     * When true, enemies cannot begin capturing this base until their team owns every capture_point
     * in the dimension. Default true.
     */
    private boolean pointsHaveToBeConquered = true;
    /** Scoreboard teams this base's crews treat as enemies (explicit — not inferred). */
    private final List<String> enemyTeams = new ArrayList<>();

    public TeamBaseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TEAM_BASE.get(), pos, state);
    }

    public String getAssignedTeam() {
        return assignedTeam;
    }

    public void setAssignedTeam(String assignedTeam) {
        this.assignedTeam = assignedTeam == null ? "" : assignedTeam;
        setChanged();
    }

    public boolean isPlayerOwned() {
        return playerOwned;
    }

    public void setPlayerOwned(boolean playerOwned) {
        this.playerOwned = playerOwned;
        setChanged();
    }

    public BaseForce getForce() {
        return force;
    }

    public void setForce(BaseForce force) {
        this.force = force == null ? new BaseForce() : force;
        setChanged();
    }

    public TankFaction getCrewFaction() {
        return crewFaction;
    }

    public void setCrewFaction(TankFaction crewFaction) {
        this.crewFaction = crewFaction == null ? TankFaction.US : crewFaction;
        setChanged();
    }

    public PmcOwnerKind getPmcOwnerKind() {
        return pmcOwnerKind;
    }

    public String getPmcOwnerValue() {
        return pmcOwnerValue;
    }

    public void setPmcOwner(PmcOwnerKind kind, String value) {
        this.pmcOwnerKind = kind == null ? PmcOwnerKind.NONE : kind;
        this.pmcOwnerValue = value == null ? "" : value;
        if (this.pmcOwnerKind == PmcOwnerKind.NONE) {
            this.pmcOwnerValue = "";
        }
        setChanged();
    }

    public boolean isEndInvasionOnCapture() {
        return endInvasionOnCapture;
    }

    public void setEndInvasionOnCapture(boolean endInvasionOnCapture) {
        this.endInvasionOnCapture = endInvasionOnCapture;
        setChanged();
    }

    public int getSpawnDelaySeconds() {
        return spawnDelaySeconds;
    }

    public void setSpawnDelaySeconds(int spawnDelaySeconds) {
        this.spawnDelaySeconds = Math.max(0, Math.min(3600, spawnDelaySeconds));
        setChanged();
    }

    public boolean isPointsHaveToBeConquered() {
        return pointsHaveToBeConquered;
    }

    public void setPointsHaveToBeConquered(boolean pointsHaveToBeConquered) {
        this.pointsHaveToBeConquered = pointsHaveToBeConquered;
        setChanged();
    }

    public List<String> getEnemyTeams() {
        return enemyTeams;
    }

    public void setEnemyTeams(List<String> teams) {
        enemyTeams.clear();
        if (teams != null) {
            for (String t : teams) {
                if (t == null || t.isEmpty()) continue;
                if (!enemyTeams.contains(t)) enemyTeams.add(t);
            }
        }
        setChanged();
    }

    public boolean isEnemyTeam(String team) {
        return team != null && !team.isEmpty() && enemyTeams.contains(team);
    }

    /** Presence / capture tick while an {@link com.neoalive.tacz_sewv.invasion.InvasionSession} is active. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, TeamBaseBlockEntity be) {
        CaptureSupport.tick(be);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            InvasionLayout.get(serverLevel).noteTeamBase(getBlockPos());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("AssignedTeam", assignedTeam);
        tag.putBoolean("PlayerOwned", playerOwned);
        tag.putString("CrewFaction", crewFaction.name());
        tag.put("Force", force.save());
        tag.putString("PmcOwnerKind", pmcOwnerKind.name());
        tag.putString("PmcOwnerValue", pmcOwnerValue);
        tag.putBoolean("EndInvasionOnCapture", endInvasionOnCapture);
        tag.putInt("SpawnDelaySeconds", spawnDelaySeconds);
        tag.putBoolean("PointsHaveToBeConquered", pointsHaveToBeConquered);
        ListTag enemies = new ListTag();
        for (String team : enemyTeams) {
            enemies.add(StringTag.valueOf(team));
        }
        tag.put("EnemyTeams", enemies);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        assignedTeam = tag.getString("AssignedTeam");
        playerOwned = tag.getBoolean("PlayerOwned");
        try {
            crewFaction = TankFaction.valueOf(tag.getString("CrewFaction"));
        } catch (IllegalArgumentException e) {
            crewFaction = TankFaction.US;
        }
        pmcOwnerKind = PmcOwnerKind.parse(tag.getString("PmcOwnerKind"));
        pmcOwnerValue = tag.getString("PmcOwnerValue");
        if (pmcOwnerKind == PmcOwnerKind.NONE) {
            pmcOwnerValue = "";
        }
        force = tag.contains("Force", Tag.TAG_COMPOUND)
                ? BaseForce.load(tag.getCompound("Force"))
                : loadLegacyForce(tag);
        // Pre-toggle worlds: only player-owned bases ended the match.
        endInvasionOnCapture = tag.contains("EndInvasionOnCapture")
                ? tag.getBoolean("EndInvasionOnCapture")
                : playerOwned;
        spawnDelaySeconds = tag.contains("SpawnDelaySeconds")
                ? Math.max(0, Math.min(3600, tag.getInt("SpawnDelaySeconds")))
                : 0;
        // Default true — missing key means require all points (new rule).
        pointsHaveToBeConquered = !tag.contains("PointsHaveToBeConquered")
                || tag.getBoolean("PointsHaveToBeConquered");
        enemyTeams.clear();
        if (tag.contains("EnemyTeams", Tag.TAG_LIST)) {
            ListTag enemies = tag.getList("EnemyTeams", Tag.TAG_STRING);
            for (int i = 0; i < enemies.size(); i++) {
                String t = enemies.getString(i);
                if (!t.isEmpty() && !enemyTeams.contains(t)) enemyTeams.add(t);
            }
        }
    }

    /**
     * Pre-rework save: one untyped {@code VehiclePool} + {@code AiVehicleCount}. Missing count meant 1,
     * and a pure-AI base could never go below 1 — keep both so the old match loads unchanged.
     */
    private BaseForce loadLegacyForce(CompoundTag tag) {
        List<String> pool = new ArrayList<>();
        ListTag list = tag.getList("VehiclePool", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            pool.add(list.getString(i));
        }
        int count = tag.contains("AiVehicleCount") ? tag.getInt("AiVehicleCount") : 1;
        if (!playerOwned) count = Math.max(1, count);
        return BaseForce.fromLegacy(pool, count, tag.getBoolean("SpawnPlayerOwnedTanksWithNpc"));
    }
}

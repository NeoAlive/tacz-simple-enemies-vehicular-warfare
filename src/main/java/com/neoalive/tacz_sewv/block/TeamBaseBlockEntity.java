package com.neoalive.tacz_sewv.block;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
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
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * One invasion base per vanilla scoreboard team.
 * Whether capturing it ends the match is {@link #endInvasionOnCapture} (not implied by player-owned).
 */
public class TeamBaseBlockEntity extends CapturableBlockEntity {

    public static final int DEFAULT_AI_VEHICLE_COUNT = 1;
    public static final int MAX_AI_VEHICLE_COUNT = 32;

    private String assignedTeam = "";
    private boolean playerOwned;
    private boolean spawnPlayerOwnedTanksWithNpc;
    private TankFaction crewFaction = TankFaction.US;
    /** How many AI-crewed hulls this base fields (and keeps topped up) while a session is active. */
    private int aiVehicleCount = DEFAULT_AI_VEHICLE_COUNT;
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
    private final List<String> vehiclePool = new ArrayList<>();
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
        // Pure AI bases cannot disable the fleet (min 1); player bases may set 0 = no extra AI.
        if (!playerOwned && aiVehicleCount < 1) {
            aiVehicleCount = DEFAULT_AI_VEHICLE_COUNT;
        }
        setChanged();
    }

    public boolean isSpawnPlayerOwnedTanksWithNpc() {
        return spawnPlayerOwnedTanksWithNpc;
    }

    public void setSpawnPlayerOwnedTanksWithNpc(boolean spawnPlayerOwnedTanksWithNpc) {
        this.spawnPlayerOwnedTanksWithNpc = spawnPlayerOwnedTanksWithNpc;
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

    public int getAiVehicleCount() {
        return aiVehicleCount;
    }

    /** Min 0 on player-owned (0 = no additional AI); min 1 on pure AI bases. */
    public void setAiVehicleCount(int aiVehicleCount) {
        int min = playerOwned ? 0 : 1;
        this.aiVehicleCount = Math.max(min, Math.min(MAX_AI_VEHICLE_COUNT, aiVehicleCount));
        setChanged();
    }

    public int minAiVehicleCount() {
        return playerOwned ? 0 : 1;
    }

    public boolean isEndInvasionOnCapture() {
        return endInvasionOnCapture;
    }

    public void setEndInvasionOnCapture(boolean endInvasionOnCapture) {
        this.endInvasionOnCapture = endInvasionOnCapture;
        setChanged();
    }

    public List<String> getVehiclePool() {
        return vehiclePool;
    }

    public void setVehiclePool(List<String> pool) {
        vehiclePool.clear();
        if (pool != null) {
            vehiclePool.addAll(pool);
        }
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
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("AssignedTeam", assignedTeam);
        tag.putBoolean("PlayerOwned", playerOwned);
        tag.putBoolean("SpawnPlayerOwnedTanksWithNpc", spawnPlayerOwnedTanksWithNpc);
        tag.putString("CrewFaction", crewFaction.name());
        tag.putInt("AiVehicleCount", aiVehicleCount);
        tag.putString("PmcOwnerKind", pmcOwnerKind.name());
        tag.putString("PmcOwnerValue", pmcOwnerValue);
        tag.putBoolean("EndInvasionOnCapture", endInvasionOnCapture);
        ListTag pool = new ListTag();
        for (String id : vehiclePool) {
            pool.add(StringTag.valueOf(id));
        }
        tag.put("VehiclePool", pool);
        ListTag enemies = new ListTag();
        for (String team : enemyTeams) {
            enemies.add(StringTag.valueOf(team));
        }
        tag.put("EnemyTeams", enemies);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        assignedTeam = tag.getString("AssignedTeam");
        playerOwned = tag.getBoolean("PlayerOwned");
        spawnPlayerOwnedTanksWithNpc = tag.getBoolean("SpawnPlayerOwnedTanksWithNpc");
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
        aiVehicleCount = tag.contains("AiVehicleCount")
                ? tag.getInt("AiVehicleCount")
                : DEFAULT_AI_VEHICLE_COUNT;
        // Re-clamp against playerOwned (loaded above) so legacy "1" stays valid for both modes.
        int min = playerOwned ? 0 : 1;
        aiVehicleCount = Math.max(min, Math.min(MAX_AI_VEHICLE_COUNT, aiVehicleCount));
        // Pre-toggle worlds: only player-owned bases ended the match.
        endInvasionOnCapture = tag.contains("EndInvasionOnCapture")
                ? tag.getBoolean("EndInvasionOnCapture")
                : playerOwned;
        vehiclePool.clear();
        if (tag.contains("VehiclePool", Tag.TAG_LIST)) {
            ListTag pool = tag.getList("VehiclePool", Tag.TAG_STRING);
            for (int i = 0; i < pool.size(); i++) {
                vehiclePool.add(pool.getString(i));
            }
        }
        enemyTeams.clear();
        if (tag.contains("EnemyTeams", Tag.TAG_LIST)) {
            ListTag enemies = tag.getList("EnemyTeams", Tag.TAG_STRING);
            for (int i = 0; i < enemies.size(); i++) {
                String t = enemies.getString(i);
                if (!t.isEmpty() && !enemyTeams.contains(t)) enemyTeams.add(t);
            }
        }
    }
}

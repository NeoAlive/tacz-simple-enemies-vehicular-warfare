package com.neoalive.tacz_sewv.block;

import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.invasion.CapturableBlockEntity;
import com.neoalive.tacz_sewv.invasion.CaptureSupport;
import com.neoalive.tacz_sewv.invasion.InvasionLayout;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

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
     * When true, an enemy completing capture of this base ends the invasion session.
     * Legacy worlds without the NBT key inherit the old rule ({@code playerOwned}).
     */
    private boolean endInvasionOnCapture;
    private final List<String> vehiclePool = new ArrayList<>();

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
        tag.putBoolean("EndInvasionOnCapture", endInvasionOnCapture);
        ListTag pool = new ListTag();
        for (String id : vehiclePool) {
            pool.add(StringTag.valueOf(id));
        }
        tag.put("VehiclePool", pool);
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
    }
}

package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ITerritoryPost;
import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TerritorySupport;

/**
 * On-foot Territory Mode: walk to the assigned chunk's centre and keep the unit on it.
 *
 * <p>Priority 1 + MOVE like {@link SweepInfantryGoal}: it has to outrank SEM's chase and
 * follow/hold goals (priority 3). While the unit is still travelling it runs through combat: it walks to the
 * centre, fires opportunistically and never diverts or chases, even at a target inside the leash. Only once the
 * post has been REACHED does it yield to a target inside the leash (with the unit inside it too) — then SEM's own
 * chase takes over (Option B, leash-bounded pursuit). A target outside the leash never becomes a lock (the
 * {@code setTarget} veto) and, if one somehow is held, {@code FollowLeash} suppresses the chase.
 *
 * <p>Hysteresis: arrived within 3 blocks; once arrived, only drift past 6 blocks pulls it back. The
 * {@code holding} flag is transient on purpose — after a reload a unit sitting in the 3-6 band simply
 * walks the last few blocks in.
 *
 * <p>{@code canUse} starts with the cached post check, so every PMC without a post pays one field read.
 */
public class TerritoryPostGoal extends Goal {

    private static final int REPATH_TICKS = 16;
    private static final double SPEED = 1.0;

    private final PmcUnitEntity unit;
    private final ITerritoryPost post;
    private boolean holding;
    private long nextRepath;

    public TerritoryPostGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.post = (ITerritoryPost) unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!post.sewv$hasTerritoryPost()) return false;
        return step();
    }

    @Override
    public boolean canContinueToUse() {
        if (!post.sewv$hasTerritoryPost()) return false;
        return step();
    }

    /**
     * Arrival is recorded FIRST, because it is what unlocks yielding to a fight: a unit still walking (however
     * close its target) keeps walking. {@code holding} is only meaningful once the post has been reached, so it is
     * cleared until then — a re-post inside the old 3-6 block band must still walk in and reach the centre.
     */
    private boolean step() {
        TerritorySupport.noteArrival(unit, post);
        if (!post.sewv$hasReachedTerritoryPost()) holding = false;
        if (!eligibleNow()) return false;
        holding = TerritorySupport.nextHolding(holding, TerritorySupport.distSqToCentre(post, unit));
        return !holding;
    }

    /** Not lost, not riding, not downed, and not (arrived and) handing over to an in-leash fight. */
    private boolean eligibleNow() {
        if (unit.level().isClientSide()) return false;
        if (post.sewv$isTerritoryLost() || unit.isPassenger() || PmcDownedSupport.isDowned(unit)) return false;
        return !TerritorySupport.yieldsToContact(unit, post);
    }

    @Override
    public void start() {
        nextRepath = 0;
    }

    @Override
    public void stop() {
        unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        long now = unit.level().getGameTime();
        if (now < nextRepath) return;
        Level level = unit.level();
        int x = (int) Math.floor(TerritorySupport.centreX(post));
        int z = (int) Math.floor(TerritorySupport.centreZ(post));
        // An unloaded centre has no height to path to; wait for it rather than force-loading a chunk.
        if (!level.hasChunkAt(x, z)) return;
        nextRepath = now + REPATH_TICKS;
        ((AbstractUnit) unit).releaseMovementLock();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        unit.getNavigation().moveTo(x + 0.5, y, z + 0.5, SPEED);
    }
}

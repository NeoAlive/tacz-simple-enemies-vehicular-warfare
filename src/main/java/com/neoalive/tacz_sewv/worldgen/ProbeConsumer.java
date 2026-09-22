package com.neoalive.tacz_sewv.worldgen;

import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.SpawnProbeBlockEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.worldgen.StructureLocator.Ownership;

/**
 * Consumes the spawn probes of freshly generated structures: the owning faction's probes become
 * real spawns and every probe is deleted; the losing faction's chests lose their loot table.
 *
 * <p>The chunk event only queues the chunk. Block entities are not final at that point and entities
 * cannot safely be added from it, so the work runs from the tick, a couple of chunks at a time.
 * Only probes flagged {@code live} are ever touched: a new chunk flags every probe in it (a
 * structure was just generated there), and the flag is saved, so a probe still pending at shutdown
 * is consumed on the next load. Probes an op placed by hand never get flagged and stay inert.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class ProbeConsumer {

    private record Job(ResourceKey<Level> dimension, long chunk, boolean isNew) {}

    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * A fixed "N chunks per tick" cap made the queue FIFO-ordered against ordinary chunk loading:
     * on join (or any fast approach) the render-distance flood of plain terrain chunks queues ahead
     * of a structure's own chunks, and at a handful per tick that backlog took tens of seconds to
     * drain — read by players as "the probe doesn't fire until I'm standing on it", when it was
     * really "the probe fired ages ago, in queue order, and I only just arrived at where it was".
     * A chunk with no probe and no faction loot chest costs one cheap block-entity scan, so the
     * queue can drain far faster; a time budget bounds the rare chunk that IS a structure (which
     * pays for {@link StructureLocator#ownershipAt}) without needing a second, arbitrary count.
     */
    private static final long BUDGET_NANOS = 2_000_000L; // 2ms of a 50ms tick
    private static final Queue<Job> QUEUE = new ConcurrentLinkedQueue<>();

    private ProbeConsumer() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || ProbeCatalog.isEmpty() || !enabled()) return;
        QUEUE.add(new Job(level.dimension(), event.getChunk().getPos().toLong(), event.isNewChunk()));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || QUEUE.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        Job job;
        while ((job = QUEUE.poll()) != null) {
            try {
                process(server, job);
            } catch (Throwable t) {
                LOGGER.warn("[tacz_sewv] structure probe pass failed for chunk {}", new ChunkPos(job.chunk()), t);
            }
            if (System.nanoTime() >= deadline) break;
        }
    }

    private static boolean enabled() {
        try {
            return SewvConfig.NATIVE_STRUCTURES_ENABLED.get();
        } catch (IllegalStateException unbaked) {
            return false;
        }
    }

    private static void process(MinecraftServer server, Job job) {
        ServerLevel level = server.getLevel(job.dimension());
        if (level == null || !enabled()) return;
        ChunkPos pos = new ChunkPos(job.chunk());
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) return;
        // A copy: consuming a probe removes its block entity from the map being walked.
        for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
            if (be instanceof SpawnProbeBlockEntity probe) {
                if (job.isNew()) probe.markLive();
                if (probe.isLive()) consume(level, probe);
            } else if (job.isNew() && be instanceof RandomizableContainerBlockEntity container) {
                tidyChest(level, container);
            }
        }
    }

    /**
     * The block goes first and the spawn second, so a failure part-way through can lose a spawn but
     * can never repeat one when the chunk loads again.
     */
    private static void consume(ServerLevel level, SpawnProbeBlockEntity probe) {
        BlockPos pos = probe.getBlockPos();
        TankFaction faction = probe.getFactionType();
        Ownership ownership = StructureLocator.ownershipAt(level, pos);
        // An unidentified structure keeps every probe as authored; PMC belongs to no owner.
        boolean spawns = faction == TankFaction.PMC || !ownership.identified()
                || ownership.owner() == null || ownership.owner() == faction;
        ProbeSpawner.Payload payload = spawns ? ProbeSpawner.Payload.of(probe) : null;
        level.removeBlock(pos, false);
        if (payload != null) ProbeSpawner.spawn(level, pos, faction, payload);
    }

    /** A chest whose loot table belongs to the faction that lost the structure is left empty. */
    private static void tidyChest(ServerLevel level, RandomizableContainerBlockEntity container) {
        TankFaction lootFaction = lootFaction(container.saveWithoutMetadata().getString("LootTable"));
        if (lootFaction == null) return;
        Ownership ownership = StructureLocator.ownershipAt(level, container.getBlockPos());
        if (!ownership.identified() || ownership.owner() == null || ownership.owner() == lootFaction) return;
        container.setLootTable(null, 0L);
        container.setChanged();
    }

    @Nullable
    static TankFaction lootFaction(String table) {
        ResourceLocation id = table.isEmpty() ? null : ResourceLocation.tryParse(table);
        if (id == null || !TaczSewv.MODID.equals(id.getNamespace())) return null;
        String path = id.getPath();
        if (path.startsWith("chests/ru_")) return TankFaction.RU;
        if (path.startsWith("chests/us_")) return TankFaction.US;
        return null;
    }
}

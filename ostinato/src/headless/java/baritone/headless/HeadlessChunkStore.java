/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 1 / Phase A optimized).
 *
 * Per-bot, per-dimension chunk store. Sections are decoded with vanilla's own
 * PalettedContainer#read(FriendlyByteBuf) - the same codec LevelChunkSection#read uses - so
 * palette formats and block-state ids come from the 26.3 classes rather than a hand-rolled decoder.
 *
 * Memory & hot-path optimizations (Phase A):
 * - Primitive Long2ObjectOpenHashMap<Chunk> guarded by a ReentrantReadWriteLock (no Long boxing).
 * - Plain PalettedContainer<BlockState>[] array per chunk instead of ArrayList.
 * - Shared immutable EMPTY_AIR_SECTION sentinel for all-air sections (copy-on-write on non-air edit).
 * - ThreadLocal scratch block-state and biome containers during decoding (zero per-section waste for air).
 * - Configurable maxChunkRadius (clamped >= 2) enforced on chunk retention.
 */
package baritone.headless;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class HeadlessChunkStore {

    public static final String CHUNK_RADIUS_PROPERTY = "ostinato.headless.chunkRadius";
    public static final int DEFAULT_MAX_CHUNK_RADIUS = 6;
    public static final int MIN_CHUNK_RADIUS = 2;

    private static final Strategy<BlockState> BLOCK_STATES = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState VOID_AIR = Blocks.VOID_AIR.defaultBlockState();

    /** Shared immutable sentinel for all-air sections. Never mutated directly; copy-on-write on non-air set. */
    static final PalettedContainer<BlockState> EMPTY_AIR_SECTION = new PalettedContainer<>(AIR, BLOCK_STATES);

    private static final ThreadLocal<PalettedContainer<BlockState>> SCRATCH_STATES =
            ThreadLocal.withInitial(() -> new PalettedContainer<>(AIR, BLOCK_STATES));

    /** Stands in for the BIOME registry: ids are kept verbatim, values are never inspected. */
    private static final IdMap<Integer> BIOME_IDS = new IdMap<Integer>() {
        @Override
        public int getId(Integer value) {
            return value == null ? 0 : value;
        }

        @Override
        public Integer byId(int id) {
            return id;
        }

        @Override
        public int size() {
            return 1 << 16;
        }

        @Override
        public Iterator<Integer> iterator() {
            return Collections.singletonList(0).iterator();
        }
    };

    private static final Strategy<Integer> BIOMES = Strategy.createForBiomes(BIOME_IDS);

    private static final ThreadLocal<PalettedContainer<Integer>> SCRATCH_BIOMES =
            ThreadLocal.withInitial(() -> new PalettedContainer<>(0, BIOMES));

    private final Long2ObjectOpenHashMap<Chunk> chunks = new Long2ObjectOpenHashMap<>(256);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile int minSection = -4; // overworld minY -64 >> 4
    private volatile int expectedSections = 24; // 384 / 16
    private volatile int maxChunkRadius = configuredMaxChunkRadius();
    private volatile int layoutVersion;
    private volatile long loadedChunks;
    private volatile long decodedSections;
    private volatile long blockUpdates;
    private volatile long forgottenChunks;

    public HeadlessChunkStore() {
    }

    private static int configuredMaxChunkRadius() {
        String raw = System.getProperty(CHUNK_RADIUS_PROPERTY);
        int r = DEFAULT_MAX_CHUNK_RADIUS;
        if (raw != null && !raw.isBlank()) {
            try {
                r = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.max(MIN_CHUNK_RADIUS, r);
    }

    public int maxChunkRadius() {
        return maxChunkRadius;
    }

    public void setMaxChunkRadius(int maxChunkRadius) {
        this.maxChunkRadius = Math.max(MIN_CHUNK_RADIUS, maxChunkRadius);
    }

    public int effectiveRetentionRadius(int serverRadius) {
        if (serverRadius < 0) {
            return serverRadius;
        }
        return Math.max(MIN_CHUNK_RADIUS, Math.min(serverRadius, maxChunkRadius));
    }

    int layoutVersion() {
        return layoutVersion;
    }

    /** Applied from the dimension type carried by ClientboundLoginPacket. */
    public void setDimension(int minY, int height) {
        this.minSection = minY >> 4;
        this.expectedSections = (height + 15) >> 4;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xffffffffL);
    }

    /**
     * Read one section from the wire with vanilla palette codecs.
     * Layout (LevelChunkSection#write): short nonEmptyBlockCount, short fluidCount,
     * block-state palette, biome palette.
     */
    public static PalettedContainer<BlockState> readSection(FriendlyByteBuf buf) {
        buf.readShort();
        buf.readShort();
        PalettedContainer<BlockState> scratch = SCRATCH_STATES.get();
        scratch.read(buf);
        // Consume the biome palette so the next section starts at the right byte.
        SCRATCH_BIOMES.get().read(buf);
        if (scratch.bitsPerEntry() == 0 && AIR.equals(scratch.get(0, 0, 0))) {
            return EMPTY_AIR_SECTION;
        }
        return scratch.copy();
    }

    /** Test/synthetic counterpart of {@link #readSection(FriendlyByteBuf)}. */
    public static void writeSection(FriendlyByteBuf buf, PalettedContainer<BlockState> states) {
        buf.writeShort(0);
        buf.writeShort(0);
        states.write(buf);
        new PalettedContainer<>(0, BIOMES).write(buf);
    }

    /**
     * Decode one full chunk. ClientboundLevelChunkPacketData#getReadBuffer is exactly the
     * concatenation of the chunk's sections as written by LevelChunkSection#write (verified from
     * calculateChunkSize / extractChunkData bytecode), so reading sections until the buffer is
     * exhausted needs no section-count field and works for every dimension height.
     */
    @SuppressWarnings("unchecked")
    public void loadChunk(ClientboundLevelChunkWithLightPacket packet) {
        ClientboundLevelChunkPacketData data = packet.chunkData();
        FriendlyByteBuf buf = data.getReadBuffer();
        List<PalettedContainer<BlockState>> decoded = new ArrayList<>(expectedSections);
        try {
            while (buf.isReadable()) {
                decoded.add(readSection(buf));
                if (buf.readableBytes() > 0 && buf.readableBytes() < 2) {
                    throw new IllegalStateException("truncated section header");
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("chunk " + packet.x() + "," + packet.z()
                    + " section decode failed after " + decoded.size() + " sections, "
                    + buf.readableBytes() + " bytes left", e);
        }
        if (!decoded.isEmpty() && decoded.size() != expectedSections) {
            // The server decides the section count; trust what it actually sent.
            this.expectedSections = decoded.size();
        }
        PalettedContainer<BlockState>[] sections = decoded.toArray(new PalettedContainer[0]);
        lock.writeLock().lock();
        try {
            chunks.put(key(packet.x(), packet.z()), new Chunk(sections));
            layoutVersion++;
            loadedChunks++;
            decodedSections += sections.length;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void applySectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
        packet.runUpdates((pos, state) -> setBlockState(pos, state));
        blockUpdates++;
    }

    /** @return false if the chunk or section is not loaded (the update will be applied on load). */
    public boolean setBlockState(BlockPos pos, BlockState state) {
        lock.writeLock().lock();
        try {
            Chunk chunk = chunks.get(key(pos.getX() >> 4, pos.getZ() >> 4));
            if (chunk == null) {
                return false;
            }
            int sy = (pos.getY() >> 4) - minSection;
            if (sy < 0 || sy >= chunk.sections.length) {
                return false;
            }
            PalettedContainer<BlockState> section = chunk.sections[sy];
            if (section == EMPTY_AIR_SECTION) {
                if (AIR.equals(state)) {
                    return true;
                }
                section = new PalettedContainer<>(AIR, BLOCK_STATES);
                chunk.sections[sy] = section;
                layoutVersion++;
            }
            section.getAndSet(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** AIR is used for known-empty space; VOID_AIR marks an unknown/unloaded cell. */
    public BlockState getBlockState(int x, int y, int z) {
        lock.readLock().lock();
        try {
            Chunk chunk = chunks.get(key(x >> 4, z >> 4));
            if (chunk == null) {
                return VOID_AIR;
            }
            int sy = (y >> 4) - minSection;
            if (sy < 0 || sy >= chunk.sections.length) {
                return VOID_AIR;
            }
            return chunk.sections[sy].get(x & 15, y & 15, z & 15);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Direct chunk lookup for single-entry caching in {@link HeadlessBlockStateInterface}. */
    Chunk getChunkDirect(int cx, int cz) {
        lock.readLock().lock();
        try {
            return chunks.get(key(cx, cz));
        } finally {
            lock.readLock().unlock();
        }
    }

    public void forgetChunk(int cx, int cz) {
        lock.writeLock().lock();
        try {
            if (chunks.remove(key(cx, cz)) != null) {
                layoutVersion++;
                forgottenChunks++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Drop columns outside the current cache window, clamped by {@link #maxChunkRadius}. */
    public int retainAround(int centerX, int centerZ, int radius) {
        if (radius < 0) {
            return 0;
        }
        int effective = effectiveRetentionRadius(radius);
        int dropped = 0;
        lock.writeLock().lock();
        try {
            ObjectIterator<Long2ObjectMap.Entry<Chunk>> it = chunks.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                Long2ObjectMap.Entry<Chunk> entry = it.next();
                long k = entry.getLongKey();
                int cx = (int) (k >> 32);
                int cz = (int) k;
                if (Math.abs(cx - centerX) > effective || Math.abs(cz - centerZ) > effective) {
                    it.remove();
                    forgottenChunks++;
                    dropped++;
                }
            }
            if (dropped > 0) {
                layoutVersion++;
            }
        } finally {
            lock.writeLock().unlock();
        }
        return dropped;
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            chunks.clear();
            layoutVersion++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Install an all-air chunk column (synthetic worlds, unit tests, offline simulation). */
    @SuppressWarnings("unchecked")
    public void createEmptyChunk(int cx, int cz) {
        PalettedContainer<BlockState>[] sections = new PalettedContainer[expectedSections];
        Arrays.fill(sections, EMPTY_AIR_SECTION);
        lock.writeLock().lock();
        try {
            chunks.put(key(cx, cz), new Chunk(sections));
            layoutVersion++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** True when the store holds decoded sections for this chunk column. */
    public boolean isChunkLoaded(int cx, int cz) {
        lock.readLock().lock();
        try {
            return chunks.containsKey(key(cx, cz));
        } finally {
            lock.readLock().unlock();
        }
    }

    public int chunkCount() {
        lock.readLock().lock();
        try {
            return chunks.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long loadedChunks() {
        return loadedChunks;
    }

    public long decodedSections() {
        return decodedSections;
    }

    public long blockUpdates() {
        return blockUpdates;
    }

    public long forgottenChunks() {
        return forgottenChunks;
    }

    public int expectedSections() {
        return expectedSections;
    }

    public int minSection() {
        return minSection;
    }

    static final class Chunk {
        final PalettedContainer<BlockState>[] sections;

        Chunk(PalettedContainer<BlockState>[] sections) {
            this.sections = sections;
        }

        BlockState get(int lx, int y, int lz, int minSection) {
            int sy = (y >> 4) - minSection;
            if (sy < 0 || sy >= sections.length) {
                return VOID_AIR;
            }
            return sections[sy].get(lx, y & 15, lz);
        }
    }
}

/*
 * Ostinato: Phase 1 chunk-decode test. New file, replaces no Baritone code.
 *
 * Builds a known chunk with vanilla's PalettedContainer codecs, pushes it through
 * ClientboundLevelChunkPacketData.STREAM_CODEC, and checks that HeadlessChunkStore decodes the same
 * block states back. No hand-rolled wire format, no client world, no datapack registry.
 */
package baritone.headless;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class HeadlessChunkDecodeTest {

    private static RegistryAccess.Frozen access;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static ClientboundLevelChunkPacketData chunkData(byte[] sectionBytes) throws Exception {
        Constructor<ClientboundLevelChunkPacketData> ctor = ClientboundLevelChunkPacketData.class
                .getDeclaredConstructor(Map.class, byte[].class, List.class);
        ctor.setAccessible(true);
        return ctor.newInstance(Map.of(), sectionBytes, List.of());
    }

    private static byte[] oneSectionWith(BlockState state) {
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        states.set(3, 7, 11, state);
        FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
        HeadlessChunkStore.writeSection(raw, states);
        byte[] bytes = new byte[raw.readableBytes()];
        raw.readBytes(bytes);
        return bytes;
    }

    @Test
    public void decodesAKnownChunkThroughTheVanillaCodec() throws Exception {
        byte[] bytes = oneSectionWith(Blocks.STONE.defaultBlockState());
        ClientboundLevelChunkPacketData data = chunkData(bytes);

        // Genuine wire round trip with the 26.3 codec, using a RegistryFriendlyByteBuf.
        RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        ClientboundLevelChunkPacketData.STREAM_CODEC.encode(wire, data);
        ClientboundLevelChunkPacketData decoded = ClientboundLevelChunkPacketData.STREAM_CODEC.decode(wire);
        assertEquals("codec left bytes unread", 0, wire.readableBytes());
        FriendlyByteBuf read = decoded.getReadBuffer();
        byte[] copy = new byte[bytes.length];
        read.readBytes(copy);
        assertArrayEquals("chunk section bytes", bytes, copy);

        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 16); // one section, minY=0
        // Light data is deliberately null: a headless bot does not render and never reads it.
        store.loadChunk(new ClientboundLevelChunkWithLightPacket(2, -3, decoded, null));

        assertEquals(1, store.chunkCount());
        assertEquals(1, store.decodedSections());
        assertEquals(Blocks.STONE.defaultBlockState(), store.getBlockState(2 * 16 + 3, 7, -3 * 16 + 11));
        assertEquals(Blocks.AIR.defaultBlockState(), store.getBlockState(2 * 16 + 4, 7, -3 * 16 + 11));
        assertEquals("outside a loaded chunk is VOID_AIR, not AIR",
                Blocks.VOID_AIR.defaultBlockState(), store.getBlockState(500, 7, 500));
    }

    @Test
    public void blockUpdatesAndForgetWorkOnDecodedSections() throws Exception {
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 16);
        store.loadChunk(new ClientboundLevelChunkWithLightPacket(0, 0,
                chunkData(oneSectionWith(Blocks.STONE.defaultBlockState())), null));

        ClientboundBlockUpdatePacket update = new ClientboundBlockUpdatePacket(
                new BlockPos(3, 7, 11), Blocks.IRON_BLOCK.defaultBlockState());
        assertTrue(store.setBlockState(update.getPos(), update.getBlockState()));
        assertEquals(Blocks.IRON_BLOCK.defaultBlockState(), store.getBlockState(3, 7, 11));

        assertFalse("a block outside loaded chunks cannot be applied",
                store.setBlockState(new BlockPos(500, 7, 500), Blocks.STONE.defaultBlockState()));

        store.forgetChunk(0, 0);
        assertEquals(0, store.chunkCount());
        assertEquals(1, store.forgottenChunks());
        assertEquals(Blocks.VOID_AIR.defaultBlockState(), store.getBlockState(3, 7, 11));
    }

    @Test
    public void aCorruptSectionBufferFailsLoudlyRatherThanSilentlyMisDecoding() throws Exception {
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 16);
        byte[] truncated = new byte[]{0, 0}; // not a complete section
        try {
            store.loadChunk(new ClientboundLevelChunkWithLightPacket(0, 0, chunkData(truncated), null));
            fail("a truncated section buffer must not be accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("section decode failed"));
        }
    }

    @Test
    public void sharesEmptyAirSectionAndCopiesOnWriteWithCappedRetention() throws Exception {
        HeadlessChunkStore storeA = new HeadlessChunkStore();
        HeadlessChunkStore storeB = new HeadlessChunkStore();
        storeA.setDimension(0, 32); // 2 sections
        storeB.setDimension(0, 32);

        PalettedContainer<BlockState> airSection = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), net.minecraft.world.level.chunk.Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        PalettedContainer<BlockState> stoneSection = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), net.minecraft.world.level.chunk.Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        stoneSection.set(1, 2, 3, Blocks.STONE.defaultBlockState());

        FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
        HeadlessChunkStore.writeSection(raw, airSection);
        HeadlessChunkStore.writeSection(raw, stoneSection);
        byte[] bytes = new byte[raw.readableBytes()];
        raw.readBytes(bytes);

        storeA.loadChunk(new ClientboundLevelChunkWithLightPacket(0, 0, chunkData(bytes), null));
        storeB.loadChunk(new ClientboundLevelChunkWithLightPacket(0, 0, chunkData(bytes), null));

        // Copy-on-write on storeA's air section (y=0..15) must not affect storeB or EMPTY_AIR_SECTION.
        assertTrue(storeA.setBlockState(new BlockPos(2, 4, 6), Blocks.COBBLESTONE.defaultBlockState()));
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), storeA.getBlockState(2, 4, 6));
        assertEquals(Blocks.AIR.defaultBlockState(), storeB.getBlockState(2, 4, 6));
        assertEquals(Blocks.AIR.defaultBlockState(), HeadlessChunkStore.EMPTY_AIR_SECTION.get(2, 4, 6));

        // HeadlessBlockStateInterface single-entry cache matches store state including post-COW update.
        HeadlessBlockStateInterface bsi = new HeadlessBlockStateInterface(storeA);
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), bsi.get0(2, 4, 6));
        assertEquals(Blocks.STONE.defaultBlockState(), bsi.get0(1, 18, 3));
        assertTrue(storeA.setBlockState(new BlockPos(5, 5, 5), Blocks.OAK_LOG.defaultBlockState()));
        assertEquals(Blocks.OAK_LOG.defaultBlockState(), bsi.get0(5, 5, 5));

        // Radius clamping: maxChunkRadius=3 caps server radius 12 down to 3, and never below 2.
        storeA.setMaxChunkRadius(3);
        storeA.createEmptyChunk(3, 0);
        storeA.createEmptyChunk(4, 0);
        int dropped = storeA.retainAround(0, 0, 12);
        assertEquals(1, dropped);
        assertTrue(storeA.isChunkLoaded(3, 0));
        assertFalse(storeA.isChunkLoaded(4, 0));
    }
}

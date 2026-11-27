package baritone.headless;

import baritone.api.context.BotContext;
import baritone.api.context.BotContextScope;
import baritone.api.legit.SeenOnly;
import baritone.api.utils.Rotation;
import baritone.altoclef.AltoClefSettings;
import baritone.api.Settings;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

public class HeadlessVanillaComplianceTest {

    @Test
    public void rotationDeltasAreQuantizedToMouseGcdAndCappedPerTick() {
        double[] sensitivities = {0.25, 0.5, 1.0};
        for (double sens : sensitivities) {
            float gcd = VanillaRotationMath.gcdStep(sens);
            assertTrue(gcd > 0f);

            Rotation step = VanillaRotationMath.stepTowards(
                    0f, 0f, 120f, -95f,
                    VanillaRotationMath.MAX_YAW_PER_TICK,
                    VanillaRotationMath.MAX_PITCH_PER_TICK,
                    sens);
            assertTrue("Yaw delta must be capped <= 32 deg/tick", Math.abs(step.getYaw()) <= 32.0f + 1e-4f);
            assertTrue("Pitch delta must be capped <= 32 deg/tick", Math.abs(step.getPitch()) <= 32.0f + 1e-4f);
            assertTrue("Yaw delta must sit on GCD grid",
                    VanillaRotationMath.isQuantizedDelta(step.getYaw(), sens, 1e-4f));
            assertTrue("Pitch delta must sit on GCD grid",
                    VanillaRotationMath.isQuantizedDelta(step.getPitch(), sens, 1e-4f));
        }

        // Climbable cap: 8 deg/tick
        Rotation climbStep = VanillaRotationMath.stepTowards(
                10f, 0f, 90f, 0f,
                VanillaRotationMath.MAX_CLIMBABLE_YAW_PER_TICK,
                VanillaRotationMath.MAX_PITCH_PER_TICK,
                0.5);
        assertTrue("Climbable yaw delta must stay <= 8 deg/tick",
                Math.abs(climbStep.getYaw() - 10f) <= 8.0f + 1e-4f);

        // Multi-tick convergence reaches within 1 mouse step of target
        HeadlessChunkStore store = new HeadlessChunkStore();
        HeadlessLevel level = HeadlessLevel.create(store, HeadlessRuntime.sharedRegistries());
        HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(UUIDUtil.createOfflinePlayerUUID("RotBot"), "RotBot"));
        player.setYRot(0f);
        player.setXRot(0f);
        HeadlessLookBehavior look = new HeadlessLookBehavior(player);
        for (int i = 0; i < 8; i++) {
            look.updateTarget(new Rotation(115f, -40f), false);
        }
        float gcd = VanillaRotationMath.gcdStep(0.5);
        assertTrue(Math.abs(115f - player.getYRot()) <= gcd + 1e-4f);
        assertTrue(Math.abs(-40f - player.getXRot()) <= gcd + 1e-4f);
    }

    @Test
    public void legitMineSeenViewRejectsBuriedBlocksAndAdmitsExposedBlocks() {
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.createEmptyChunk(0, 0);

        // Exposed stone block at (4, 65, 4) with adjacent air at (3, 65, 4)
        BlockPos exposed = new BlockPos(4, 65, 4);
        store.setBlockState(exposed, Blocks.DIAMOND_ORE.defaultBlockState());

        // Buried ore completely encased in stone (2x2 wall on all 6 faces)
        BlockPos buried = new BlockPos(8, 65, 8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    store.setBlockState(buried.offset(dx, dy, dz),
                            (dx == 0 && dy == 0 && dz == 0)
                                    ? Blocks.DIAMOND_ORE.defaultBlockState()
                                    : Blocks.STONE.defaultBlockState());
                }
            }
        }

        HeadlessSeenView view = new HeadlessSeenView(store);
        assertTrue("Exposed diamond ore should be knowable",
                SeenOnly.knowable(view, 1.5, 65.62, 4.5, exposed.getX(), exposed.getY(), exposed.getZ()));
        assertFalse("Buried diamond ore must be rejected by legit LOS view",
                SeenOnly.knowable(view, 1.5, 65.62, 4.5, buried.getX(), buried.getY(), buried.getZ()));
        assertEquals("Unloaded chunk must be UNKNOWN",
                SeenOnly.Cell.UNKNOWN, view.at(1000, 65, 1000));
    }

    @Test
    public void packetOrderPlacesClientTickEndStrictlyLastPerTick() {
        HeadlessRuntime.ensureBootstrapped();
        RecordingTransport transport = new RecordingTransport();
        UUID botUuid = UUIDUtil.createOfflinePlayerUUID("OrderBot");
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "OrderBot",
                botUuid, HeadlessRuntime.sharedRegistries());
        try {
            bot.start();
            bot.onPacket(new ClientboundLoginFinishedPacket(new GameProfile(botUuid, "OrderBot"), botUuid));
            bot.onPacket(ClientboundFinishConfigurationPacket.INSTANCE);
            bot.chunks().setDimension(0, 128);
            bot.chunks().createEmptyChunk(0, 0);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    bot.chunks().setBlockState(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
                }
            }
            bot.onPacket(new ClientboundPlayerPositionPacket(1,
                    new PositionMoveRotation(new Vec3(0.5, 64.0, 0.5), Vec3.ZERO, 0f, 0f), Set.of()));
            bot.onPacket(new ClientboundChunkBatchFinishedPacket(1));
            transport.sent.clear();

            int ticksWithPackets = 0;
            for (int i = 0; i < 25; i++) {
                int start = transport.sent.size();
                bot.tick();
                List<Packet<?>> slice = transport.sent.subList(start, transport.sent.size());
                if (!slice.isEmpty()) {
                    ticksWithPackets++;
                    assertTrue("Tick must end with ServerboundClientTickEndPacket, was "
                                    + slice.get(slice.size() - 1).getClass().getSimpleName(),
                            slice.get(slice.size() - 1) instanceof ServerboundClientTickEndPacket);
                    long movePackets = slice.stream().filter(p -> p instanceof ServerboundMovePlayerPacket).count();
                    assertTrue("At most one MovePlayerPacket per client tick, got " + movePackets, movePackets <= 1);
                }
            }
            assertTrue("Expected at least one tick to emit packets", ticksWithPackets > 0);
        } finally {
            bot.closeHost();
        }
    }

    @Test
    public void schedulerRunsMultipleBotsAtTwentyTpsWithIsolatedContexts() throws Exception {
        HeadlessRuntime.ensureBootstrapped();
        HeadlessTickScheduler scheduler = new HeadlessTickScheduler(2);
        BotContext ctxA = new BotContext("SchedA", Path.of("tmp", "schedA"), true, new Settings(), new AltoClefSettings());
        BotContext ctxB = new BotContext("SchedB", Path.of("tmp", "schedB"), true, new Settings(), new AltoClefSettings());
        HeadlessBot botA = new HeadlessBot(new RecordingTransport(), "127.0.0.1", 25565, "SchedA",
                UUIDUtil.createOfflinePlayerUUID("SchedA"), HeadlessRuntime.sharedRegistries(), ctxA);
        HeadlessBot botB = new HeadlessBot(new RecordingTransport(), "127.0.0.1", 25565, "SchedB",
                UUIDUtil.createOfflinePlayerUUID("SchedB"), HeadlessRuntime.sharedRegistries(), ctxB);
        scheduler.register(botA);
        scheduler.register(botB);
        scheduler.start();
        try {
            Thread.sleep(220);
        } finally {
            scheduler.stop();
        }
        assertTrue("Expected at least 3 ticks in 220ms, got " + scheduler.tickCount(), scheduler.tickCount() >= 3);
        assertEquals(0L, scheduler.errorCount());
        assertNotSame(botA.botContext(), botB.botContext());
    }

    private static final class RecordingTransport implements HeadlessTransport {
        final List<Packet<?>> sent = new CopyOnWriteArrayList<>();

        @Override
        public void initiate(String host, int port, net.minecraft.network.protocol.login.ClientLoginPacketListener listener) {
        }

        @Override
        public void setupInbound(net.minecraft.network.ProtocolInfo<?> protocol, net.minecraft.network.PacketListener listener) {
        }

        @Override
        public void setupOutbound(net.minecraft.network.ProtocolInfo<?> protocol) {
        }

        @Override
        public void setupCompression(int threshold, boolean validateDecompressed) {
        }

        @Override
        public void send(Packet<?> packet) {
            sent.add(packet);
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }
}

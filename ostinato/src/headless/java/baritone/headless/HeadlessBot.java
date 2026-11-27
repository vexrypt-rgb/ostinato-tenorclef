/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 1). New file, replaces no Baritone code.
 *
 * Login -> configuration -> play state machine, using vanilla's own packet classes and codecs.
 * No Minecraft client object is constructed anywhere; the only game side used is Bootstrap,
 * the built-in registries, the network codecs and the chunk palette code.
 */
package baritone.headless;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundAcceptCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.context.BotContext;
import baritone.api.context.BotContextScope;
import baritone.api.headless.IHeadlessAgent;
import baritone.api.headless.IHeadlessHost;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One bot connection. All clientbound packets funnel through {@link #onPacket(Object)}; all
 * serverbound packets go through {@link HeadlessTransport}, which makes the whole sequence
 * observable in a JUnit fake-server test.
 */
public final class HeadlessBot implements HeadlessPacketListeners.Handler, IHeadlessHost {

    public enum State { NEW, LOGIN, CONFIGURATION, PLAY, DISCONNECTED }

    /** A survival bot: no creative slot packets, no command-block packets. */
    private static final GameProtocols.Context SURVIVAL_CONTEXT = new GameProtocols.Context() {
        @Override
        public boolean hasInfiniteMaterials() {
            return false;
        }

        @Override
        public boolean canUseCommandBlocks() {
            return false;
        }
    };

    private static final float DESIRED_CHUNKS_PER_TICK = 64.0f;
    /** Vanilla sends a position packet at least once every 20 client ticks. */
    private static final int POSITION_REMINDER_TICKS = 20;

    private final HeadlessTransport transport;
    private final String host;
    private final int port;
    private final String username;
    private final UUID uuid;
    private final String accessToken;
    private final BotContext botContext;
    private volatile IHeadlessAgent agent;
    private volatile boolean agentAttached;
    private volatile Consumer<String> chatReceiveListener;
    private volatile Consumer<net.minecraft.network.protocol.Packet<?>> outboundObserver;
    private volatile boolean stopRequested;
    private volatile float lastHealth = 20.0f;
    private volatile int lastFood = 20;
    private volatile float lastSaturation = 5.0f;
    // Ostinato: the PLAY codec needs datapack registries that Bootstrap does not create. The
    // built-in access is a placeholder until configuration has delivered the real registry data.
    private volatile RegistryAccess.Frozen registryAccess;
    private final HeadlessRegistries registries = new HeadlessRegistries();
    private final HeadlessChunkStore chunks;
    // Ostinato: inventory packets can arrive before the pathing player exists. They queue here.
    private final HeadlessInventory inventory = new HeadlessInventory();
    // Ostinato: nearby entities, for combat. Packets can arrive before the pathing host exists.
    private final HeadlessEntityTracker entities = new HeadlessEntityTracker();
    // Ostinato: once spawned, this driver ticks PlayerSim and sends a movement packet every tick,
    // including while idle — a real client does the same, and a frozen mid-air pose is a flying flag.
    private volatile HeadlessMovement movement;
    // Ostinato: the pathing host, built once the bot has spawned. Null until then.
    private volatile HeadlessLiveDriver live;
    private final Object hostLock = new Object();
    private final ConcurrentLinkedQueue<String> pathingLines = new ConcurrentLinkedQueue<>();
    // Ostinato: our own attribute snapshots, applied on the tick thread (ClientPacketListener.handleUpdateAttributes).
    private final ConcurrentLinkedQueue<ClientboundUpdateAttributesPacket> pendingAttributes = new ConcurrentLinkedQueue<>();
    private volatile Consumer<String> chatCommands;
    // Ostinato: Grim Post flags a block-place (or slot change) that arrives while
    // sentFlying is still set and then sees the answering pong before the tick's
    // movement packet. The netty thread used to write that pong the moment the
    // ping arrived, which can fall in the gap after UseItemOn and before Pos.
    // Queue those pongs for the end of the client tick. A ping that arrives
    // between ticks is still answered immediately, which is what the login tests
    // expect and what a vanilla client does between ticks.
    private final Object pongLock = new Object();
    private final ConcurrentLinkedQueue<Integer> deferredPongs = new ConcurrentLinkedQueue<>();
    private boolean deferPongs;

    private final ClientLoginPacketListener loginListener;
    private final ClientConfigurationPacketListener configurationListener;
    private final ClientGamePacketListener gameListener;

    private volatile State state = State.NEW;
    private volatile String disconnectReason;
    private volatile int compressionThreshold = -1;
    private volatile int playerId = -1;
    private volatile String dimension = "";
    private volatile int chunkCenterX;
    private volatile int chunkCenterZ;
    private volatile int chunkRadius = -1;
    private volatile boolean loadedSent;
    private volatile boolean spawnKnown;
    private final AtomicInteger movementSuppressedUntilTick = new AtomicInteger(-1);
    private volatile int spawnTeleports;
    private volatile int positionReminder;
    private volatile boolean onGround = true;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yRot;
    private volatile float xRot;
    private volatile Vec3 lastVelocity = Vec3.ZERO;
    private volatile int lastBlockChangeSequence = -1;

    private final AtomicLong packetsIn = new AtomicLong();
    private final AtomicLong packetsOut = new AtomicLong();
    private final AtomicLong keepAlives = new AtomicLong();
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong corrections = new AtomicLong();
    private final AtomicLong registryDataPackets = new AtomicLong();
    private final AtomicLong chunkBatchReplies = new AtomicLong();
    private final AtomicLong tagRegistriesApplied = new AtomicLong();
    private final AtomicLong unhandled = new AtomicLong();
    private final Map<String, AtomicLong> unhandledTypes = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();

    public HeadlessBot(HeadlessTransport transport, String host, int port, String username,
                       UUID uuid, RegistryAccess.Frozen registryAccess) {
        this(transport, host, port, username, uuid, registryAccess,
                new BotContext(username, Path.of("headless-bots", username), true,
                        baritone.api.BaritoneAPI.getSettings(), new AltoClefSettings()));
    }

    public HeadlessBot(HeadlessTransport transport, String host, int port, String username,
                       UUID uuid, RegistryAccess.Frozen registryAccess, BotContext botContext) {
        this(transport, host, port, username, uuid, registryAccess, botContext, "");
    }

    public HeadlessBot(HeadlessTransport transport, String host, int port, String username,
                       UUID uuid, RegistryAccess.Frozen registryAccess, BotContext botContext,
                       String accessToken) {
        this.transport = transport;
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.host = host;
        this.port = port;
        this.username = username;
        this.uuid = uuid;
        this.botContext = botContext;
        this.botContext.setHeadlessHost(this);
        this.registryAccess = registryAccess;
        this.chunks = new HeadlessChunkStore();
        this.loginListener = HeadlessPacketListeners.create(
                ClientLoginPacketListener.class, ConnectionProtocol.LOGIN, this);
        this.configurationListener = HeadlessPacketListeners.create(
                ClientConfigurationPacketListener.class, ConnectionProtocol.CONFIGURATION, this);
        this.gameListener = HeadlessPacketListeners.create(
                ClientGamePacketListener.class, ConnectionProtocol.PLAY, this);
        inventory.attach(this);
    }

    public BotContext botContext() {
        return botContext;
    }

    public void attachAgent(IHeadlessAgent agent) {
        this.agent = agent;
        this.agentAttached = false;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    // ------------------------------------------------------------------ startup

    /** Vanilla's ConnectScreen$1 does exactly this: initiate, then send Hello. */
    public void start() {
        state = State.LOGIN;
        event("start login as " + username + " to " + host + ":" + port);
        transport.initiate(host, port, loginListener);
        send(new ServerboundHelloPacket(username, uuid));
    }

    /** Package-private so the movement driver sends through the same observable path. */
    void send(net.minecraft.network.protocol.Packet<?> packet) {
        packetsOut.incrementAndGet();
        Consumer<net.minecraft.network.protocol.Packet<?>> observer = outboundObserver;
        if (observer != null) {
            observer.accept(packet);
        }
        if (baritone.utils.PacketLog.ON) {
            baritone.utils.PacketLog.outbound(packet);
        }
        // Ostinato: one wire order, the order of send() calls, as vanilla gets by sending from one thread. The tick
        // thread's Connection.send only queues a task on the netty loop; a packet the netty thread wrote inline (a
        // pong, an AcceptTeleport) overtook tick packets still queued: Grim TransactionOrder (pong B before A) and
        // Post (UseItemOn, then a pong, then the Pos that was meant to precede it). In play, queue netty-side sends
        // behind them too. Login/configuration stay inline: their protocol switches follow the send synchronously.
        io.netty.util.concurrent.EventExecutor loop = io.netty.util.internal.ThreadExecutorMap.currentExecutor();
        if (loop != null && state == State.PLAY
                && !(packet instanceof net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket)) {
            loop.execute(() -> transport.send(packet));
            return;
        }
        transport.send(packet);
    }

    // ------------------------------------------------------------------ inbound

    @Override
    public void onPacket(Object packet) {
        if (packet == null) {
            return;
        }
        packetsIn.incrementAndGet();
        if (baritone.utils.PacketLog.ON) {
            baritone.utils.PacketLog.inbound((net.minecraft.network.protocol.Packet<?>) packet);
        }
        try {
            BotContextScope.runWith(botContext, () -> dispatch(packet));
        } catch (RuntimeException e) {
            event("ERROR handling " + packet.getClass().getName() + ": " + e);
            e.printStackTrace(System.err);
        }
    }

    @Override
    public void onDisconnect(String reason) {
        state = State.DISCONNECTED;
        disconnectReason = reason;
        event("disconnected: " + reason);
    }

    private void dispatch(Object p) {
        // Ostinato: 26.3 wraps entity spawn/move (and other play packets) in a bundle.
        // Without unwrapping, combat never sees AddEntity and cannot find its target.
        if (p instanceof ClientboundBundlePacket bundle) {
            for (var sub : bundle.subPackets()) {
                dispatch(sub);
            }
            return;
        }
        // ---- shared by LOGIN / CONFIGURATION / PLAY --------------------------
        if (p instanceof ClientboundKeepAlivePacket) {
            send(new ServerboundKeepAlivePacket(((ClientboundKeepAlivePacket) p).getId()));
            keepAlives.incrementAndGet();
            return;
        }
        if (p instanceof ClientboundPingPacket) {
            answerPing(((ClientboundPingPacket) p).getId());
            return;
        }
        if (p instanceof ClientboundUpdateTagsPacket) {
            applyTags((ClientboundUpdateTagsPacket) p);
            return;
        }
        if (p instanceof ClientboundDisconnectPacket) {
            onDisconnect("play/config disconnect: " + ((ClientboundDisconnectPacket) p).reason().getString());
            return;
        }
        if (p instanceof ClientboundResourcePackPushPacket) {
            // A headless bot never downloads a pack. Decline so the server does not wait forever.
            send(new ServerboundResourcePackPacket(((ClientboundResourcePackPushPacket) p).id(),
                    ServerboundResourcePackPacket.Action.DECLINED));
            return;
        }
        if (p instanceof ClientboundCookieRequestPacket) {
            event("cookie request ignored: " + ((ClientboundCookieRequestPacket) p).key());
            return;
        }
        if (p instanceof ClientboundStoreCookiePacket || p instanceof ClientboundTransferPacket) {
            // Transfers are deliberately ignored: the bot only ever connects to its own local server.
            event("ignored " + p.getClass().getSimpleName());
            return;
        }
        if (p instanceof ClientboundSystemChatPacket) {
            String text = ((ClientboundSystemChatPacket) p).content().getString();
            event("chat: " + text);
            Consumer<String> chatListener = chatReceiveListener;
            if (chatListener != null) {
                chatListener.accept(text);
            }
            IHeadlessAgent activeAgent = agent;
            if (activeAgent != null) {
                activeAgent.onChat("", text);
            }
            // Ostinato: owner whispers and server-console system chat. Public player chat is a different packet.
            String command = HeadlessCommands.commandFromChat(text);
            if (command != null) {
                event("chat command: " + command);
                Consumer<String> sink = chatCommands;
                if (sink != null) {
                    sink.accept(command);
                }
                if (activeAgent != null) {
                    activeAgent.onCommand(command);
                }
            }
            return;
        }

        // ---- LOGIN -----------------------------------------------------------
        if (p instanceof ClientboundLoginCompressionPacket) {
            int threshold = ((ClientboundLoginCompressionPacket) p).getCompressionThreshold();
            compressionThreshold = threshold;
            transport.setupCompression(threshold, true);
            event("compression threshold " + threshold);
            return;
        }
        if (p instanceof ClientboundLoginFinishedPacket) {
            event("login finished: " + ((ClientboundLoginFinishedPacket) p).gameProfile());
            enterConfiguration(false);
            return;
        }
        if (p instanceof ClientboundHelloPacket hello) {
            handleHelloPacket(hello);
            return;
        }
        if (p instanceof ClientboundLoginDisconnectPacket) {
            onDisconnect("login disconnect: " + ((ClientboundLoginDisconnectPacket) p).reason().getString());
            return;
        }

        // ---- CONFIGURATION ---------------------------------------------------
        if (p instanceof ClientboundRegistryDataPacket) {
            ClientboundRegistryDataPacket data = (ClientboundRegistryDataPacket) p;
            registries.appendContents(data);
            registryDataPackets.incrementAndGet();
            event("registry data: " + data.registry().identifier() + " (" + data.entries().size() + " entries)");
            return;
        }
        if (p instanceof ClientboundSelectKnownPacks) {
            // Empty selection forces the server to send the full registry data.
            send(new ServerboundSelectKnownPacks(List.of()));
            return;
        }
        if (p instanceof ClientboundUpdateEnabledFeaturesPacket) {
            event("enabled features: " + ((ClientboundUpdateEnabledFeaturesPacket) p).features().size());
            return;
        }
        if (p instanceof ClientboundCodeOfConductPacket) {
            send(ServerboundAcceptCodeOfConductPacket.INSTANCE);
            return;
        }
        if (p instanceof ClientboundFinishConfigurationPacket) {
            enterPlay();
            return;
        }

        // ---- PLAY ------------------------------------------------------------
        if (p instanceof ClientboundLoginPacket) {
            ClientboundLoginPacket login = (ClientboundLoginPacket) p;
            playerId = login.playerId();
            dimension = login.commonPlayerSpawnInfo().dimension().identifier().toString();
            try {
                chunks.setDimension(login.commonPlayerSpawnInfo().dimensionType().value().minY(),
                        login.commonPlayerSpawnInfo().dimensionType().value().height());
            } catch (RuntimeException e) {
                event("dimension type not resolvable, keeping -64/384 default: " + e);
            }
            spawnKnown = false;
            loadedSent = false;
            chunkRadius = chunks.effectiveRetentionRadius(login.chunkRadius());
            event("play login id=" + playerId + " dim=" + dimension
                    + " view=" + login.chunkRadius() + " retainedRadius=" + chunkRadius
                    + " sim=" + login.simulationDistance()
                    + " secureChat=" + login.enforcesSecureChat());
            return;
        }
        if (p instanceof ClientboundPlayerPositionPacket) {
            handlePlayerPosition((ClientboundPlayerPositionPacket) p);
            return;
        }
        if (p instanceof ClientboundLevelChunkWithLightPacket) {
            chunks.loadChunk((ClientboundLevelChunkWithLightPacket) p);
            return;
        }
        if (p instanceof ClientboundSectionBlocksUpdatePacket) {
            ((ClientboundSectionBlocksUpdatePacket) p).runUpdates((pos, state) -> {
                if (!absorbPredictedUpdate(pos, state)) {
                    chunks.setBlockState(pos, state);
                }
            });
            notePlaceWorld();
            return;
        }
        if (p instanceof ClientboundBlockUpdatePacket) {
            ClientboundBlockUpdatePacket u = (ClientboundBlockUpdatePacket) p;
            if (!absorbPredictedUpdate(u.getPos(), u.getBlockState())) {
                chunks.setBlockState(u.getPos(), u.getBlockState());
            }
            notePlaceWorld();
            return;
        }
        if (p instanceof ClientboundForgetLevelChunkPacket) {
            var pos = ((ClientboundForgetLevelChunkPacket) p).pos();
            chunks.forgetChunk(pos.x(), pos.z());
            return;
        }
        if (p instanceof ClientboundChunkBatchStartPacket) {
            return;
        }
        if (p instanceof ClientboundChunkBatchFinishedPacket) {
            send(new ServerboundChunkBatchReceivedPacket(DESIRED_CHUNKS_PER_TICK));
            chunkBatchReplies.incrementAndGet();
            if (!loadedSent) {
                send(new ServerboundPlayerLoadedPacket());
                send(new ServerboundPlayerInputPacket(Input.EMPTY));
                loadedSent = true;
                event("player loaded (" + ((ClientboundChunkBatchFinishedPacket) p).batchSize()
                        + " chunks in first batch)");
            }
            return;
        }
        if (p instanceof ClientboundSetChunkCacheCenterPacket) {
            chunkCenterX = ((ClientboundSetChunkCacheCenterPacket) p).getX();
            chunkCenterZ = ((ClientboundSetChunkCacheCenterPacket) p).getZ();
            int dropped = chunks.retainAround(chunkCenterX, chunkCenterZ, chunkRadius);
            if (dropped > 0) {
                event("chunk center " + chunkCenterX + "," + chunkCenterZ
                        + " radius=" + chunkRadius + " dropped=" + dropped
                        + " remain=" + chunks.chunkCount());
            }
            return;
        }
        if (p instanceof ClientboundSetChunkCacheRadiusPacket) {
            chunkRadius = chunks.effectiveRetentionRadius(((ClientboundSetChunkCacheRadiusPacket) p).getRadius());
            int dropped = chunks.retainAround(chunkCenterX, chunkCenterZ, chunkRadius);
            event("chunk radius " + chunkRadius
                    + (dropped > 0 ? " dropped=" + dropped + " remain=" + chunks.chunkCount() : ""));
            return;
        }
        if (p instanceof ClientboundStartConfigurationPacket) {
            pathingLines.add("cancel");
            enterConfiguration(true);
            return;
        }
        if (p instanceof ClientboundRespawnPacket) {
            ClientboundRespawnPacket respawn = (ClientboundRespawnPacket) p;
            chunks.clear();
            dimension = respawn.commonPlayerSpawnInfo().dimension().identifier().toString();
            spawnKnown = false;
            loadedSent = false;
            pathingLines.add("cancel");
            event("respawn into " + dimension);
            return;
        }
        if (p instanceof ClientboundSetEntityMotionPacket) {
            ClientboundSetEntityMotionPacket motion = (ClientboundSetEntityMotionPacket) p;
            if (motion.id() == playerId) {
                lastVelocity = motion.movement();
                HeadlessMovement driver = movement;
                if (driver != null) {
                    driver.applyVelocity(lastVelocity);
                }
                if (lastVelocity.lengthSqr() > 1.0E-12) {
                    event("SetEntityMotion " + lastVelocity);
                }
            } else {
                entities.handle(p, playerId);
            }
            return;
        }
        if (p instanceof ClientboundEntityPositionSyncPacket) {
            ClientboundEntityPositionSyncPacket sync = (ClientboundEntityPositionSyncPacket) p;
            if (sync.id() == playerId) {
                Vec3 end = sync.position().endPosition();
                double d = (end.x - x) * (end.x - x) + (end.y - y) * (end.y - y)
                        + (end.z - z) * (end.z - z);
                x = end.x;
                y = end.y;
                z = end.z;
                yRot = Mth.wrapDegrees(sync.yRot());
                xRot = Mth.wrapDegrees(sync.xRot());
                onGround = sync.onGround();
                HeadlessMovement driver = movement;
                if (driver != null) {
                    driver.rotation(yRot, xRot);
                    driver.resync(x, y, z, onGround);
                }
                if (d > 1.0E-12) {
                    corrections.incrementAndGet();
                    event("correction entity sync -> " + end + " (delta^2=" + d + ")");
                }
            } else {
                entities.handle(p, playerId);
            }
            return;
        }
        if (p instanceof ClientboundExplodePacket) {
            ClientboundExplodePacket boom = (ClientboundExplodePacket) p;
            if (boom.playerKnockback().isPresent()) {
                Vec3 v = boom.playerKnockback().get();
                HeadlessMovement driver = movement;
                if (driver != null) {
                    driver.applyVelocity(v);
                }
                event("explosion knockback " + v);
            }
            return;
        }
        if (p instanceof ClientboundBlockChangedAckPacket) {
            lastBlockChangeSequence = ((ClientboundBlockChangedAckPacket) p).sequence();
            notePlaceAck();
            return;
        }
        if (p instanceof ClientboundPlayerCombatKillPacket kill) {
            if (kill.playerId() == playerId) {
                // Ostinato: TenorClef respawns a dead client bot; the death screen's button sends PERFORM_RESPAWN.
                // Without it the protocol bot stayed dead and answered every later command with nothing.
                pathingLines.add("cancel");
                send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                event("died: " + kill.message().getString() + "; respawn requested");
            }
            return;
        }
        if (p instanceof ClientboundUpdateAttributesPacket attrs) {
            if (attrs.getEntityId() == playerId) {
                pendingAttributes.add(attrs);
            }
            return;
        }
        if (p instanceof ClientboundSetHealthPacket health) {
            lastHealth = health.getHealth();
            lastFood = health.getFood();
            lastSaturation = health.getSaturation();
            HeadlessPlayer body = inventory.player();
            if (body != null) {
                body.setHealth(lastHealth);
                body.getFoodData().setFoodLevel(lastFood);
                body.getFoodData().setSaturation(lastSaturation);
            }
            return;
        }
        if (entities.handle(p, playerId)) {
            if (p instanceof ClientboundTakeItemEntityPacket take && take.getPlayerId() == playerId) {
                // Ostinato: TakeItemEntity is the authoritative vanilla pickup signal.
                event("TakeItemEntity itemId=" + take.getItemId()
                        + " playerId=" + take.getPlayerId()
                        + " amount=" + take.getAmount());
            }
            return;
        }
        if (inventory.handle(p)) {
            return;
        }

        unhandled.incrementAndGet();
        String type = p.getClass().getSimpleName();
        synchronized (unhandledTypes) {
            unhandledTypes.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ protocol switches

    /** Login finished, or play -> configuration re-entry. */
    private void enterConfiguration(boolean fromPlay) {
        state = State.CONFIGURATION;
        // Outbound is still LOGIN (or PLAY on re-entry), which is exactly what the ack packet needs.
        transport.setupInbound(ConfigurationProtocols.CLIENTBOUND, configurationListener);
        if (fromPlay) {
            send(ServerboundConfigurationAcknowledgedPacket.INSTANCE);
        } else {
            send(ServerboundLoginAcknowledgedPacket.INSTANCE);
        }
        transport.setupOutbound(ConfigurationProtocols.SERVERBOUND);
        send(new ServerboundCustomPayloadPacket(new BrandPayload("fabric"))); // Ostinato parity: the real client bot runs on Fabric
        // Ostinato: createDefault() uses viewDistance=2 (a dummy server-side player).
        // A real client advertises its render distance; 12 is vanilla's options default.
        // Paper/Moonrise will otherwise forget columns down to a 2-chunk window as we
        // walk, and a bot with no chunks rubberbands and flags Simulation.
        send(new ServerboundClientInformationPacket(vanillaClientInformation(Math.max(4, Math.min(12, chunks.maxChunkRadius())))));
        event("entered configuration" + (fromPlay ? " (re-entry)" : ""));
    }

    private void enterPlay() {
        // Configuration has delivered the dynamic registries; rebuild them common-side before the
        // first ClientboundLoginPacket, whose Holder<DimensionType> needs the dimension_type registry.
        if (registries.registryDataPackets() > 0) {
            try {
                RegistryAccess.Frozen built = registries.build();
                registryAccess = built;
                inventory.invalidateHasher();
                event("rebuilt " + built.listRegistryKeys().count() + " registries from configuration");
            } catch (RuntimeException e) {
                event("registry reconstruction FAILED: " + e + " - PLAY decode may fail");
                e.printStackTrace(System.err);
            }
        }
        var decorator = RegistryFriendlyByteBuf.decorator(registryAccess);
        var inbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(decorator);
        var outbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(decorator, SURVIVAL_CONTEXT);
        // Inbound first, then the terminal config packet while outbound is still CONFIGURATION.
        transport.setupInbound(inbound, gameListener);
        send(ServerboundFinishConfigurationPacket.INSTANCE);
        transport.setupOutbound(outbound);
        // Ostinato: PLAY only after the outbound switch: send() queues netty-side play packets, and the terminal
        // config packet must be written inline before setupOutbound swaps the encoder.
        state = State.PLAY;
        event("entered play");
    }

    private void handlePlayerPosition(ClientboundPlayerPositionPacket p) {
        // Vanilla's own relative/absolute resolution, including DELTA_* and ROTATE_DELTA.
        PositionMoveRotation current = new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot);
        PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(current, p.change(), p.relatives());
        Vec3 pos = absolute.position();
        float yaw = Mth.wrapDegrees(absolute.yRot());
        float pitch = Mth.wrapDegrees(absolute.xRot());
        // Accept first, then publish spawnKnown, so a racing tick cannot emit a
        // MovePlayer packet the server would see as a move from 0,0,0 to spawn
        // ("moved too quickly") before the teleport is confirmed.
        send(new ServerboundAcceptTeleportationPacket(p.id(), pos.x, pos.y, pos.z, yaw, pitch));
        x = pos.x;
        y = pos.y;
        z = pos.z;
        yRot = yaw;
        xRot = pitch;
        spawnKnown = true;
        spawnTeleports++;
        // Do not send our own position in this tick; wait for the accepted pose.
        movementSuppressedUntilTick.set((int) ticks.get());
        HeadlessMovement driver = movement;
        if (driver != null) {
            driver.rotation(yRot, xRot);
            driver.resync(x, y, z, onGround);
        }
        if (spawnTeleports > 1) {
            corrections.incrementAndGet();
            event("correction teleport #" + (spawnTeleports - 1) + " id=" + p.id() + " -> " + pos);
        } else {
            event("spawn teleport id=" + p.id() + " -> " + pos);
        }
    }

    /** Vanilla client info with bounded view distance so Paper/Moonrise only streams chunks we will retain. */
    private static ClientInformation vanillaClientInformation(int viewDistance) {
        return new ClientInformation(
                "en_us",
                viewDistance,
                ChatVisiblity.FULL,
                true,
                127,
                HumanoidArm.RIGHT,
                false,
                true,
                ParticleStatus.ALL);
    }

    /** Common-side replacement for the client-only RegistryDataCollector tag path. */
    private void applyTags(ClientboundUpdateTagsPacket packet) {
        registries.appendTags(packet);
        tagRegistriesApplied.addAndGet(packet.tags().size());
        event("tags collected for " + packet.tags().size() + " registries");
    }

    // ------------------------------------------------------------------ tick

    /**
     * One 20 TPS client tick. Vanilla sends StatusOnly on most ticks and a position packet at
     * least every 20 ticks; doing that (rather than a position packet every tick) also guarantees
     * the server never sees two position packets inside one of its own ticks.
     */
    public void tick() {
        // Hold deferral across the whole tick, including the early returns, so a
        // ping cannot be written between UseItemOn and the movement packet.
        synchronized (pongLock) {
            deferPongs = true;
        }
        try {
            BotContextScope.runWith(botContext, this::tickOnce);
        } finally {
            flushDeferredPongs();
        }
    }

    private void answerPing(int id) {
        // The send stays inside the lock. Releasing the lock before the write
        // lets the tick thread start UseItemOn while this pong is still unsent,
        // which is the same Post window.
        synchronized (pongLock) {
            if (deferPongs) {
                deferredPongs.add(id);
                return;
            }
            send(new ServerboundPongPacket(id));
        }
    }

    private void flushDeferredPongs() {
        synchronized (pongLock) {
            deferPongs = false;
            Integer id;
            while ((id = deferredPongs.poll()) != null) {
                send(new ServerboundPongPacket(id));
            }
        }
    }

    private void tickOnce() {
        long n = ticks.incrementAndGet();
        if (baritone.utils.PacketLog.ON) {
            baritone.utils.PacketLog.tick((int) n);
        }
        transport.tick();
        maybeMarkPlayerLoaded();
        if (state != State.PLAY || !spawnKnown || !loadedSent) {
            return;
        }
        IHeadlessAgent activeAgent = agent;
        if (activeAgent != null) {
            if (live == null) {
                ensureHost();
            }
            if (movement == null) {
                ensureMovement();
            }
            HeadlessLiveDriver curLive = live;
            HeadlessMovement curMove = movement;
            if (curLive != null && curMove != null) {
                curLive.sync(curMove);
                curLive.look().beginTick(curMove.lastSentYaw(), curMove.lastSentPitch());
            } else if (curLive != null) {
                curLive.look().beginTick(yRot, xRot);
            }
            try {
                activeAgent.onTick();
            } catch (RuntimeException e) {
                event("ERROR agent tick: " + e);
                e.printStackTrace(System.err);
            }
        } else if (live != null) {
            HeadlessLiveDriver curLive = live;
            HeadlessMovement curMove = movement;
            if (curMove != null) {
                curLive.look().beginTick(curMove.lastSentYaw(), curMove.lastSentPitch());
            } else {
                curLive.look().beginTick(yRot, xRot);
            }
        }
        // Ostinato: pathing commands run here, before the single movement packet, so A* is only queued.
        drainPathing();
        // Ostinato: a real client ticks physics and sends a movement packet every tick, even standing
        // still. After a pillar or jump lands in pathing=idle, skipping PlayerSim left the last
        // mid-air pose frozen at onGround=false, which Grim/NCP treat as flying.
        if (movement == null) {
            ensureMovement();
        }
        HeadlessMovement driver = movement;
        if (driver != null) {
            try {
                if (n <= movementSuppressedUntilTick.get()) {
                    // Ostinato: AcceptTeleportation just went out. Vanilla does not
                    // send a MovePlayer in the same tick; doing so is "moved too quickly".
                    send(ServerboundClientTickEndPacket.INSTANCE);
                } else {
                    driver.tick(this);
                }
            } catch (RuntimeException e) {
                event("ERROR movement tick: " + e);
                e.printStackTrace(System.err);
            }
            if (n % 200 == 0) {
                event("tick " + n + " pos=" + x + "," + y + "," + z
                        + " chunks=" + chunks.chunkCount() + " corrections=" + corrections.get());
            }
            return;
        }
        positionReminder++;
        // Ostinato: the movement driver is the normal path. If we get here, still
        // do not repeat look on the 20-tick reminder — vanilla sends Pos, not PosRot.
        if (positionReminder >= POSITION_REMINDER_TICKS) {
            send(new ServerboundMovePlayerPacket.Pos(x, y, z, onGround, false));
            positionReminder = 0;
        }
        // Must be the last packet of the client tick: the server clears its per-tick movement
        // flags in ServerGamePacketListenerImpl.handleClientTickEnd.
        send(ServerboundClientTickEndPacket.INSTANCE);
        if (n % 200 == 0) {
            event("tick " + n + " pos=" + x + "," + y + "," + z
                    + " chunks=" + chunks.chunkCount() + " corrections=" + corrections.get());
        }
    }

    // ------------------------------------------------------------------ observation

    public HeadlessChunkStore chunks() {
        return chunks;
    }

    /** Attach the PlayerSim driver. Called after spawn so the sim can be seeded from the real position. */
    public void setMovement(HeadlessMovement driver) {
        this.movement = driver;
        if (live != null) {
            driver.setDriver(live);
        }
        if (spawnKnown) {
            // Ostinato: the course step overwrites this yaw before the packet; a live driver reads it first.
            driver.rotation(yRot, xRot);
            driver.start(x, y, z, onGround);
        }
        event("movement driver attached (" + driver.status().trim().replace('\n', ' ') + ")");
    }

    public HeadlessMovement movement() {
        return movement;
    }

    void setReportedPosition(double nx, double ny, double nz, boolean ground, float yaw, float pitch) {
        this.x = nx;
        this.y = ny;
        this.z = nz;
        this.onGround = ground;
        // Ostinato: the idle reminder repeats the look the last movement packet actually sent.
        this.yRot = yaw;
        this.xRot = pitch;
    }

    public int playerId() {
        return playerId;
    }

    public boolean onGround() {
        return onGround;
    }

    public float yRot() {
        return yRot;
    }

    public float xRot() {
        return xRot;
    }

    public boolean spawnKnown() {
        return spawnKnown;
    }

    // Ostinato: vanilla 26.3 (protocol 777) wraps each send with ChunkBatchStart
    // (play id 12) and ChunkBatchFinished (play id 11). Paper 26.3-252 is the same
    // protocol, but Moonrise's RegionizedPlayerChunkLoader calls
    // PlayerChunkSender.sendChunk() per column and never emits those batch
    // packets. Waiting for ChunkBatchFinished would leave loadedSent false, so
    // the pathing host never attaches and PlayerSim never starts. Send
    // PlayerLoaded after the first decoded chunk, the same way the vanilla batch
    // path does after the first finished packet.
    private void maybeMarkPlayerLoaded() {
        if (loadedSent || state != State.PLAY || !spawnKnown) {
            return;
        }
        // Do not wait for a 3x3: Moonrise may have only the spawn column so far,
        // and the vanilla batch path also fires PlayerLoaded after the first
        // finished packet rather than a full neighborhood.
        if (chunks.chunkCount() < 1) {
            return;
        }
        send(new ServerboundPlayerLoadedPacket());
        send(new ServerboundPlayerInputPacket(Input.EMPTY));
        loadedSent = true;
        event("player loaded (" + chunks.chunkCount() + " chunks, no ChunkBatchFinished)");
    }

    public boolean playerLoaded() {
        return loadedSent;
    }

    public boolean hostAttached() {
        return live != null;
    }

    /** Play session with a confirmed spawn. Chunks may still be arriving. */
    public boolean hostReady() {
        return state == State.PLAY && spawnKnown;
    }

    public int lastBlockChangeSequence() {
        return lastBlockChangeSequence;
    }

    /** The survival inventory mirror. Packets received before the host exists are queued on it. */
    public HeadlessInventory inventory() {
        return inventory;
    }

    RegistryAccess.Frozen registryAccess() {
        return registryAccess;
    }

    /**
     * Ostinato: vanilla's client body gets its attributes only from the server (handleUpdateAttributes: base value,
     * drop every modifier, add the sent ones as transient). The bot ignored the packet, so its attack speed stayed the
     * bare-hand 4.0 with a sword held: it swung every 5 ticks at a 0.4 server-side charge, did a third of the damage,
     * and died to the 3-zombie gate.
     */
    void applyAttributeUpdates(HeadlessPlayer body) {
        ClientboundUpdateAttributesPacket attrs;
        while ((attrs = pendingAttributes.poll()) != null) {
            for (ClientboundUpdateAttributesPacket.AttributeSnapshot snap : attrs.getValues()) {
                net.minecraft.world.entity.ai.attributes.AttributeInstance inst = body.getAttributes().getInstance(snap.attribute());
                if (inst == null) {
                    continue;
                }
                inst.setBaseValue(snap.base());
                inst.removeModifiers();
                for (net.minecraft.world.entity.ai.attributes.AttributeModifier mod : snap.modifiers()) {
                    inst.addTransientModifier(mod);
                }
            }
        }
    }

    HeadlessLiveDriver liveDriver() {
        return live;
    }

    private void notePlaceWorld() {
        HeadlessLiveDriver current = live;
        if (current != null) {
            current.noteWorldChange();
        }
    }

    private void notePlaceAck() {
        HeadlessLiveDriver current = live;
        if (current != null) {
            current.noteAck();
        }
    }

    /** Ostinato: a BlockUpdate for an open place prediction updates the server snapshot only. */
    private boolean absorbPredictedUpdate(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        HeadlessLiveDriver current = live;
        return current != null && current.absorbServerBlockUpdate(pos, state);
    }

    /** Where stdin, commands.txt, and accepted whispers deliver operator lines that are not pathing. */
    public void setChatCommandListener(Consumer<String> listener) {
        this.chatCommands = listener;
    }

    /** Queue a goto / cancel / mine line for the tick thread. Never plans on the caller. */
    public void enqueuePathing(String line) {
        pathingLines.add(line);
    }

    public String pathingStatus() {
        HeadlessLiveDriver current = live;
        return current == null ? "not attached" : current.status();
    }

    /**
     * Ostinato: build the pathing body from the live connection. Idempotent. Movement is attached
     * on the next tick so idle physics keep running.
     */
    public void ensureHost() {
        BotContextScope.runWith(botContext, () -> {
            if (live != null) {
                if (agent != null && !agentAttached) {
                    attachAgentInternal();
                }
                return;
            }
            synchronized (hostLock) {
                if (live != null) {
                    if (agent != null && !agentAttached) {
                        attachAgentInternal();
                    }
                    return;
                }
                if (state != State.PLAY || !spawnKnown) {
                    throw new IllegalStateException("pathing host requires a spawned play session");
                }
                HeadlessLevel level = HeadlessLevel.create(chunks, registryAccess);
                level.bindEntityTracker(entities);
                HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(uuid, username));
                player.setId(playerId);
                player.setPos(x, y, z);
                player.setYRot(yRot);
                player.setXRot(xRot);
                player.setOnGround(onGround);
                player.setHealth(lastHealth);
                player.getFoodData().setFoodLevel(lastFood);
                player.getFoodData().setSaturation(lastSaturation);
                inventory.bind(player);
                HeadlessLiveDriver created = new HeadlessLiveDriver(this, player, level);
                botContext.setBaritone(created.baritone());
                if (movement != null) {
                    movement.setDriver(created);
                }
                live = created;
                event("pathing host attached at " + x + "," + y + "," + z);
                if (agent != null && !agentAttached) {
                    attachAgentInternal();
                }
            }
        });
    }

    private void attachAgentInternal() {
        IHeadlessAgent active = agent;
        if (active == null || agentAttached) {
            return;
        }
        agentAttached = true;
        active.onAttach(botContext, this);
    }

    public void closeHost() {
        BotContextScope.runWith(botContext, () -> {
            IHeadlessAgent active = agent;
            if (active != null && agentAttached) {
                try {
                    active.onShutdown();
                } catch (RuntimeException e) {
                    event("ERROR agent shutdown: " + e);
                }
                agentAttached = false;
            }
            HeadlessLiveDriver current = live;
            if (current != null) {
                current.close();
                live = null;
            }
            botContext.setBaritone(null);
            baritone.utils.pathing.SearchCache.releaseThreadLocal();
            baritone.pathing.calc.PiCheap.releaseThreadLocal();
            baritone.pathing.kinematic.BsiWorld.releaseThreadLocal();
        });
    }

    /**
     * Ostinato: A* on the test thread (and PlayerSim on the tick thread) leave
     * ThreadLocal memos. A later bot in the same JVM must not reuse them.
     */
    public static void resetSharedStateForTests() {
        baritone.utils.pathing.SearchCache.releaseThreadLocal();
        baritone.pathing.calc.PiCheap.releaseThreadLocal();
        baritone.pathing.kinematic.BsiWorld.releaseThreadLocal();
        baritone.pathing.RouteReuse.clear();
        baritone.pathing.movement.Movement.breathing = false;
        baritone.api.utils.RayTraceUtils.fluidHandling = net.minecraft.world.level.ClipContext.Fluid.NONE;
        Thread.interrupted();
    }

    private void drainPathing() {
        String line;
        while ((line = pathingLines.poll()) != null) {
            try {
                HeadlessCommands.Parsed parsed = HeadlessCommands.parse(line);
                if (parsed == null) {
                    continue;
                }
                if (parsed.error() != null) {
                    System.err.println("[HeadlessBot] " + parsed.error());
                    event("pathing command rejected: " + parsed.error());
                    continue;
                }
                applyPathing(parsed);
            } catch (RuntimeException e) {
                System.err.println("[HeadlessBot] pathing command failed: " + e.getMessage());
                event("pathing command failed: " + e.getMessage());
            }
        }
    }

    private void applyPathing(HeadlessCommands.Parsed parsed) {
        switch (parsed.kind()) {
            case CANCEL -> {
                if (live != null) {
                    live.cancel();
                    event("pathing cancelled");
                }
            }
            case GOTO -> {
                ensureHost();
                ensureMovement();
                live.sync(movement);
                live.gotoBlock(parsed.x(), parsed.y(), parsed.z());
                event("goto " + parsed.x() + " " + parsed.y() + " " + parsed.z());
                System.out.println("[HeadlessPathing] goto " + parsed.x() + " " + parsed.y() + " " + parsed.z());
            }
            case MINE -> {
                ensureHost();
                ensureMovement();
                live.sync(movement);
                live.mine(parsed.text());
                event("mine " + parsed.text());
                System.out.println("[HeadlessPathing] mine " + parsed.text() + " " + live.status());
            }
            case PLACE -> {
                ensureHost();
                ensureMovement();
                live.sync(movement);
                live.place(parsed.x(), parsed.y(), parsed.z());
                event("place " + parsed.x() + " " + parsed.y() + " " + parsed.z());
                System.out.println("[HeadlessPlace] place " + parsed.x() + " " + parsed.y() + " " + parsed.z());
            }
            case KILL -> {
                ensureHost();
                ensureMovement();
                live.sync(movement);
                live.kill(parsed.text());
                event("kill " + (parsed.text().isEmpty() ? "(monsters)" : parsed.text()));
                System.out.println("[HeadlessCombat] kill " + (parsed.text().isEmpty() ? "(monsters)" : parsed.text()));
            }
            case CRAFT, SMELT, CHEST, DROP, PICKUP, EQUIP -> {
                ensureHost();
                ensureMovement();
                live.sync(movement);
                live.container(parsed);
                event(parsed.kind().name().toLowerCase() + " " + parsed.text());
            }
            default -> System.err.println("[HeadlessBot] not a pathing command: " + parsed.kind());
        }
    }

    private void ensureMovement() {
        if (movement == null) {
            setMovement(new HeadlessMovement(new HeadlessBlockStateInterface(chunks), List.of()));
        } else if (live != null) {
            movement.setDriver(live);
            if (!movement.started() && spawnKnown) {
                movement.rotation(yRot, xRot);
                movement.start(x, y, z, onGround);
            }
        }
    }

    public HeadlessEntityTracker entities() {
        return entities;
    }

    // ------------------------------------------------------------------ IHeadlessHost

    @Override
    public String botId() {
        return botContext.botId();
    }

    @Override
    public Player player() {
        HeadlessLiveDriver current = live;
        return current == null ? null : current.player();
    }

    @Override
    public Level level() {
        HeadlessLiveDriver current = live;
        return current == null ? null : current.player().level();
    }

    @Override
    public IBaritone baritone() {
        HeadlessLiveDriver current = live;
        return current == null ? null : current.baritone();
    }

    @Override
    public IPlayerContext playerContext() {
        HeadlessLiveDriver current = live;
        return current == null ? null : current.context();
    }

    @Override
    public IPlayerController playerController() {
        HeadlessLiveDriver current = live;
        return current == null ? null : current.controller();
    }

    @Override
    public Iterable<Entity> worldEntities() {
        return entities.commonEntities();
    }

    @Override
    public boolean isChunkLoaded(int cx, int cz) {
        return chunks.isChunkLoaded(cx, cz);
    }

    @Override
    public long tickCount() {
        return ticks.get();
    }

    @Override
    public void sendChat(String message) {
        if (message == null || message.isEmpty() || state != State.PLAY) {
            return;
        }
        if (message.startsWith("/")) {
            String cmd = message.substring(1);
            if (!cmd.isEmpty()) {
                send(new ServerboundChatCommandPacket(cmd));
            }
            return;
        }
        send(new ServerboundChatPacket(message, Instant.now(), 0L, Optional.empty(),
                new LastSeenMessages.Update(0, new BitSet(20), LastSeenMessages.Update.IGNORE_CHECKSUM)));
    }

    @Override
    public void setChatReceiveListener(Consumer<String> listener) {
        this.chatReceiveListener = listener;
    }

    @Override
    public double mouseSensitivity() {
        return 0.5;
    }

    @Override
    public void setInputState(boolean forward, boolean back, boolean left, boolean right,
                              boolean jump, boolean sneak, boolean sprint) {
        HeadlessLiveDriver current = live;
        if (current == null) {
            return;
        }
        var io = current.baritone().getInputOverrideHandler();
        io.setInputForceState(baritone.api.utils.input.Input.MOVE_FORWARD, forward);
        io.setInputForceState(baritone.api.utils.input.Input.MOVE_BACK, back);
        io.setInputForceState(baritone.api.utils.input.Input.MOVE_LEFT, left);
        io.setInputForceState(baritone.api.utils.input.Input.MOVE_RIGHT, right);
        io.setInputForceState(baritone.api.utils.input.Input.JUMP, jump);
        io.setInputForceState(baritone.api.utils.input.Input.SNEAK, sneak);
        io.setInputForceState(baritone.api.utils.input.Input.SPRINT, sprint);
    }

    @Override
    public void setInputOverride(baritone.api.utils.input.Input key, boolean pressed) {
        HeadlessLiveDriver current = live;
        if (current != null) {
            current.baritone().getInputOverrideHandler().setInputForceState(key, pressed);
        }
    }

    @Override
    public boolean isInputPressed(baritone.api.utils.input.Input key) {
        HeadlessLiveDriver current = live;
        return current != null && current.baritone().getInputOverrideHandler().isInputForcedDown(key);
    }

    @Override
    public void clickContainerSlot(int containerId, int slot, int button, String clickType) {
        HeadlessLiveDriver current = live;
        if (current == null) {
            return;
        }
        ContainerInput type = ContainerInput.PICKUP;
        if (clickType != null && !clickType.isBlank()) {
            String norm = clickType.trim().toUpperCase(Locale.ROOT);
            if ("SHIFT_CLICK".equals(norm) || "QUICK_MOVE".equals(norm)) {
                type = ContainerInput.QUICK_MOVE;
            } else {
                try {
                    type = ContainerInput.valueOf(norm);
                } catch (IllegalArgumentException ignored) {
                    type = ContainerInput.PICKUP;
                }
            }
        }
        current.controller().windowClick(containerId, slot, button, type, current.player());
    }

    @Override
    public void clickContainerButton(int containerId, int buttonId) {
        HeadlessLiveDriver current = live;
        if (current != null) {
            current.controller().clickButton(containerId, buttonId);
        }
    }

    @Override
    public void closeContainer() {
        inventory.closeContainer();
    }

    @Override
    public void respawn() {
        send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    @Override
    public Path configDir() {
        return botContext.configDir();
    }

    @Override
    public void requestStop() {
        this.stopRequested = true;
    }

    @Override
    public HitResult rayTrace(double reach, boolean includeFluids) {
        HeadlessLiveDriver current = live;
        if (current == null) {
            return null;
        }
        ClipContext.Fluid prev = RayTraceUtils.currentFluidHandling();
        try {
            RayTraceUtils.setFluidHandling(includeFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE);
            HeadlessPlayer body = current.player();
            return RayTraceUtils.rayTraceTowards(body, new Rotation(body.getYRot(), body.getXRot()), reach);
        } finally {
            RayTraceUtils.setFluidHandling(prev);
        }
    }

    public State state() {
        return state;
    }

    public String disconnectReason() {
        return disconnectReason;
    }

    public boolean isConnected() {
        return state != State.DISCONNECTED && transport.isConnected();
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public long corrections() {
        return corrections.get();
    }

    public long ticks() {
        return ticks.get();
    }

    public long packetsIn() {
        return packetsIn.get();
    }

    public long packetsOut() {
        return packetsOut.get();
    }

    public String username() {
        return username;
    }

    public UUID uuid() {
        return uuid;
    }

    public float health() {
        HeadlessPlayer body = inventory.player();
        return body != null ? body.getHealth() : lastHealth;
    }

    public int food() {
        HeadlessPlayer body = inventory.player();
        return body != null ? body.getFoodData().getFoodLevel() : lastFood;
    }

    public float saturation() {
        HeadlessPlayer body = inventory.player();
        return body != null ? body.getFoodData().getSaturationLevel() : lastSaturation;
    }

    public IHeadlessAgent agent() {
        return agent;
    }

    public String currentTask() {
        IHeadlessAgent active = agent;
        if (active != null && agentAttached) {
            try {
                String task = active.currentTask();
                if (task != null && !task.isBlank()) {
                    return task;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return pathingStatus();
    }

    public void setOutboundObserver(Consumer<net.minecraft.network.protocol.Packet<?>> observer) {
        this.outboundObserver = observer;
    }

    public void dispatchAgentCommand(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        String trimmed = command.trim();
        IHeadlessAgent active = agent;
        if (active != null && (trimmed.startsWith("@") || "stop".equalsIgnoreCase(trimmed))) {
            String forwarded = "stop".equalsIgnoreCase(trimmed) ? "@stop" : trimmed;
            if ("@stop".equalsIgnoreCase(forwarded) || "cancel".equalsIgnoreCase(trimmed)) {
                enqueuePathing("cancel");
            }
            active.onCommand(forwarded);
            return;
        }
        enqueuePathing(trimmed);
    }

    private void handleHelloPacket(ClientboundHelloPacket hello) {
        try {
            javax.crypto.SecretKey secretKey = net.minecraft.util.Crypt.generateSecretKey();
            java.security.PublicKey publicKey = hello.getPublicKey();
            String serverHash = new java.math.BigInteger(
                    net.minecraft.util.Crypt.digestData(hello.getServerId(), publicKey, secretKey)).toString(16);
            javax.crypto.Cipher decryptCipher = net.minecraft.util.Crypt.getCipher(
                    javax.crypto.Cipher.DECRYPT_MODE, secretKey);
            javax.crypto.Cipher encryptCipher = net.minecraft.util.Crypt.getCipher(
                    javax.crypto.Cipher.ENCRYPT_MODE, secretKey);
            net.minecraft.network.protocol.login.ServerboundKeyPacket keyPacket =
                    new net.minecraft.network.protocol.login.ServerboundKeyPacket(
                            secretKey, publicKey, hello.getChallenge());
            if (!hello.shouldAuthenticate()) {
                transport.sendWithEncryption(keyPacket, decryptCipher, encryptCipher);
                return;
            }
            if (accessToken.isBlank()) {
                String msg = "server requested online-mode authentication but no accessToken was provided for " + username;
                transport.disconnect(msg);
                onDisconnect(msg);
                return;
            }
            Thread authThread = new Thread(() -> authenticateAndEnableEncryption(
                    serverHash, keyPacket, decryptCipher, encryptCipher), "headless-auth-" + username);
            authThread.setDaemon(true);
            authThread.start();
        } catch (net.minecraft.util.CryptException | RuntimeException e) {
            String msg = "encryption setup failed: " + e.getMessage();
            transport.disconnect(msg);
            onDisconnect(msg);
        }
    }

    private void authenticateAndEnableEncryption(
            String serverHash,
            net.minecraft.network.protocol.login.ServerboundKeyPacket keyPacket,
            javax.crypto.Cipher decryptCipher,
            javax.crypto.Cipher encryptCipher) {
        try {
            com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
            payload.addProperty("accessToken", accessToken);
            payload.addProperty("selectedProfile", uuid.toString().replace("-", ""));
            payload.addProperty("serverId", serverHash);
            String url = System.getProperty(
                    "ostinato.sessionServerUrl",
                    "https://sessionserver.mojang.com/session/minecraft/join");
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            java.net.http.HttpResponse<String> resp = client.send(
                    req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                transport.sendWithEncryption(keyPacket, decryptCipher, encryptCipher);
                return;
            }
            String msg = "sessionserver join failed (HTTP " + resp.statusCode() + ")";
            transport.disconnect(msg);
            onDisconnect(msg);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String msg = "sessionserver join error: " + e.getMessage();
            transport.disconnect(msg);
            onDisconnect(msg);
        }
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(state)
                .append(" user=").append(username)
                .append(" connected=").append(isConnected())
                .append(" disconnectReason=").append(disconnectReason)
                .append("\nplayerId=").append(playerId)
                .append(" dimension=").append(dimension)
                .append(" pos=").append(x).append(',').append(y).append(',').append(z)
                .append(" rot=").append(yRot).append(',').append(xRot)
                .append(" onGround=").append(onGround)
                .append("\ncompression=").append(compressionThreshold)
                .append(" chunkCenter=").append(chunkCenterX).append(',').append(chunkCenterZ)
                .append(" chunkRadius=").append(chunkRadius)
                .append("\nticks=").append(ticks.get())
                .append(" packetsIn=").append(packetsIn.get())
                .append(" packetsOut=").append(packetsOut.get())
                .append(" keepAlives=").append(keepAlives.get())
                .append(" corrections=").append(corrections.get())
                .append(" spawnTeleports=").append(spawnTeleports)
                .append("\nregistryDataPackets=").append(registryDataPackets.get())
                .append(" tagRegistriesApplied=").append(tagRegistriesApplied.get())
                .append(" chunkBatchReplies=").append(chunkBatchReplies.get())
                .append("\nchunks=").append(chunks.chunkCount())
                .append(" loadedChunks=").append(chunks.loadedChunks())
                .append(" decodedSections=").append(chunks.decodedSections())
                .append(" blockUpdates=").append(chunks.blockUpdates())
                .append(" forgotten=").append(chunks.forgottenChunks())
                .append(" minSection=").append(chunks.minSection())
                .append(" expectedSections=").append(chunks.expectedSections())
                .append("\nunhandled=").append(unhandled.get()).append(' ');
        synchronized (unhandledTypes) {
            sb.append(new LinkedHashMap<>(unhandledTypes));
        }
        sb.append("\nevents:\n");
        synchronized (events) {
            int from = Math.max(0, events.size() - 25);
            for (int i = from; i < events.size(); i++) {
                sb.append("  ").append(events.get(i)).append('\n');
            }
        }
        sb.append("pathing: ").append(pathingStatus()).append('\n');
        HeadlessMovement driver = movement;
        if (driver != null) {
            sb.append("movement:\n").append(driver.status());
            List<String> lines = driver.log();
            int from = Math.max(0, lines.size() - 60);
            for (int i = from; i < lines.size(); i++) {
                sb.append("  ").append(lines.get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    void event(String text) {
        synchronized (events) {
            events.add(text);
        }
        System.out.println("[HeadlessBot " + username + "] " + text);
    }
}

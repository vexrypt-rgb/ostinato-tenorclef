/*
 * Ostinato: isolated per-bot context for single-JVM multi-instance execution.
 */
package baritone.api.context;

import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.headless.IHeadlessHost;
import baritone.api.policy.IAgentPolicy;
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.level.ClipContext;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Isolated per-instance state for a single bot running inside a shared JVM.
 * Bound onto the bot's tick thread and worker tasks via {@link BotContextScope}.
 */
public final class BotContext {

    private final String botId;
    private final Path configDir;
    private final boolean headless;
    private final Settings settings;
    private final IAgentPolicy agentPolicy;
    private final Map<Path, Object> worldCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Object> attachments = new ConcurrentHashMap<>();

    private volatile IBaritone baritone;
    private volatile IHeadlessHost headlessHost;
    private int overallTickCount;
    private volatile ClipContext.Fluid rayTraceFluidHandling = ClipContext.Fluid.NONE;
    private volatile float lastLookTurnDegrees;

    public BotContext(String botId, Path configDir, boolean headless, Settings settings, IAgentPolicy agentPolicy) {
        this.botId = Objects.requireNonNull(botId, "botId");
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.headless = headless;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.agentPolicy = agentPolicy;
    }

    public String botId() {
        return botId;
    }

    public Path configDir() {
        return configDir;
    }

    public boolean headless() {
        return headless;
    }

    public Settings settings() {
        return settings;
    }

    public IAgentPolicy agentPolicy() {
        return agentPolicy;
    }

    public IBaritone baritone() {
        return baritone;
    }

    public void setBaritone(IBaritone baritone) {
        this.baritone = baritone;
    }

    public IPlayerContext playerContext() {
        IBaritone b = this.baritone;
        return b == null ? null : b.getPlayerContext();
    }

    public IHeadlessHost headlessHost() {
        return headlessHost;
    }

    public void setHeadlessHost(IHeadlessHost headlessHost) {
        this.headlessHost = headlessHost;
    }

    public synchronized int nextOverallTickCount() {
        return overallTickCount++;
    }

    public ClipContext.Fluid rayTraceFluidHandling() {
        return rayTraceFluidHandling;
    }

    public void setRayTraceFluidHandling(ClipContext.Fluid fluidHandling) {
        this.rayTraceFluidHandling = fluidHandling == null ? ClipContext.Fluid.NONE : fluidHandling;
    }

    public float lastLookTurnDegrees() {
        return lastLookTurnDegrees;
    }

    public void setLastLookTurnDegrees(float degrees) {
        this.lastLookTurnDegrees = degrees;
    }

    public Map<Path, Object> worldCache() {
        return worldCache;
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttachment(Class<T> type) {
        return (T) attachments.get(type);
    }

    public <T> void putAttachment(Class<T> type, T value) {
        attachments.put(type, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T computeAttachmentIfAbsent(Class<T> type, Function<BotContext, T> factory) {
        return (T) attachments.computeIfAbsent(type, k -> factory.apply(this));
    }
}

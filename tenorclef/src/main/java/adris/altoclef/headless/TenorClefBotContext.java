package adris.altoclef.headless;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.util.Pair;
//#if MC >= 260000
//$$ import baritone.api.context.BotContext;
//$$ import baritone.api.context.BotContextScope;
//$$ import baritone.api.headless.IHeadlessHost;
//#endif

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-bot isolated mutable state for TenorClef inside a single JVM.
 * Stored as an attachment inside Ostinato's {@code BotContext} on 26.3.
 */
@SuppressWarnings("rawtypes")
public final class TenorClefBotContext {

    //#if MC >= 260000
    //$$ private final BotContext botContext;
    //$$ private volatile IHeadlessHost headlessHost;
    //#endif
    private volatile AltoClef mod;
    private volatile boolean loadStarted;
    private volatile Object swarmRuntimeState;

    // Isolated Fleet member set per bot
    public final Object fleetLock = new Object();
    public final Set<String> fleetMembers = new java.util.LinkedHashSet<>();

    // Isolated EventBus state per bot
    public final HashMap<Class, List<Subscription>> eventTopics = new HashMap<>();
    public final List<Pair<Class, Subscription>> eventToAdd = new ArrayList<>();
    public boolean eventLock;

    // Isolated ConfigHelper state per bot
    public final Map<String, Runnable> loadedConfigs = new HashMap<>();

    // Isolated BlockScanner protected cell set per bot
    public final Set<Long> protectedCells = new HashSet<>();

    // Isolated chain/task static-equivalent state per bot
    public boolean foodChainHasFood;
    public boolean mobDefenseShielding;
    public String butlerLastUser = "";
    public String whisperLastMessage;
    public final Map<String, Object> taskState = new ConcurrentHashMap<>();

    //#if MC >= 260000
    //$$ public TenorClefBotContext(BotContext botContext) {
    //$$     this.botContext = botContext;
    //$$     this.headlessHost = botContext.headlessHost();
    //$$ }
    //#else
    public TenorClefBotContext() {
    }
    //#endif

    public static TenorClefBotContext currentOrNull() {
        //#if MC >= 260000
        //$$ BotContext ctx = BotContextScope.currentOrNull();
        //$$ if (ctx == null) {
        //$$     return null;
        //$$ }
        //$$ return ctx.computeAttachmentIfAbsent(TenorClefBotContext.class, TenorClefBotContext::new);
        //#else
        return null;
        //#endif
    }

    //#if MC >= 260000
    //$$ public BotContext botContext() {
    //$$     return botContext;
    //$$ }
    //#endif

    public Path configDir() {
        //#if MC >= 260000
        //$$ return botContext.configDir();
        //#else
        return Path.of(".");
        //#endif
    }

    public AltoClef mod() {
        return mod;
    }

    public void setMod(AltoClef mod) {
        this.mod = mod;
    }

    //#if MC >= 260000
    //$$ public IHeadlessHost headlessHost() {
    //$$     IHeadlessHost h = headlessHost;
    //$$     return h != null ? h : botContext.headlessHost();
    //$$ }
    //$$
    //$$ public void setHeadlessHost(IHeadlessHost headlessHost) {
    //$$     this.headlessHost = headlessHost;
    //$$     botContext.setHeadlessHost(headlessHost);
    //$$ }
    //#endif

    public boolean isLoadStarted() {
        return loadStarted;
    }

    public void setLoadStarted(boolean loadStarted) {
        this.loadStarted = loadStarted;
    }

    public Object getSwarmRuntimeState() {
        return swarmRuntimeState;
    }

    public void setSwarmRuntimeState(Object swarmRuntimeState) {
        this.swarmRuntimeState = swarmRuntimeState;
    }

    @SuppressWarnings("unchecked")
    public <T> T getTaskState(String key, T defaultValue) {
        Object val = taskState.get(key);
        return val != null ? (T) val : defaultValue;
    }

    public void setTaskState(String key, Object value) {
        if (value == null) {
            taskState.remove(key);
        } else {
            taskState.put(key, value);
        }
    }
}

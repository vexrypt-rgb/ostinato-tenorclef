package adris.altoclef.swarm;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.headless.TenorClefBotContext;
import adris.altoclef.tasks.speedrun.testrun2.fleet.FleetProtocol;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;

import java.util.Set;

/**
 * Per-bot swarm runtime: owns this client's/bot's leader and/or worker role.
 * Isolated per {@link TenorClefBotContext} in single-JVM headless mode, falling back
 * cleanly to a single shared instance in standard single-client graphical mode.
 */
public final class SwarmRuntime {
    private static final Set<String> TO_LEADER = Set.of("reg", "bye", "hb", "accept", "reject", "prog", "result", "obs", "cov");

    private static final class State {
        SwarmLink link = SwarmLink.create();
        SwarmCoordinator leader;
        SwarmWorker worker;
        String leaderName;
        boolean loopback;
        long loggedSeq;
        boolean protocolHookAttempted;
        boolean fleetHandlerRegistered;
    }

    private static final State DEFAULT_STATE = new State();

    private SwarmRuntime() {}

    private static State state() {
        TenorClefBotContext tc = TenorClefBotContext.currentOrNull();
        if (tc == null) {
            return DEFAULT_STATE;
        }
        Object raw = tc.getSwarmRuntimeState();
        if (raw instanceof State s) {
            return s;
        }
        synchronized (tc) {
            raw = tc.getSwarmRuntimeState();
            if (raw instanceof State s) {
                return s;
            }
            State created = new State();
            tc.setSwarmRuntimeState(created);
            return created;
        }
    }

    /** Replace the link (tests). Roles held over the old link end with it. */
    public static void useLink(SwarmLink l) {
        State s = state();
        synchronized (s) {
            s.link = l;
            s.leader = null;
            s.worker = null;
            s.leaderName = null;
            s.loopback = false;
            s.fleetHandlerRegistered = false;
        }
    }

    public static SwarmLink link() { return state().link; }
    public static SwarmCoordinator leader() { return state().leader; }
    public static SwarmWorker worker() { return state().worker; }

    /** @return why we cannot swarm right now, or null if the link is up */
    public static String problem() { return state().link.problem(); }

    public static SwarmCoordinator lead(AltoClef mod) {
        State s = state();
        synchronized (s) {
            String why = s.link.problem();
            if (why != null) throw new IllegalStateException(why);
            if (!s.link.leads()) throw new IllegalStateException("you are not the lead of any roster group (lead= in swarm.txt)");
            s.link.onReceive((from, group, body) -> onWireFor(s, from, group, body));
            if (!s.fleetHandlerRegistered) {
                s.fleetHandlerRegistered = FleetProtocol.registerSwarmHandler();
            }
            // Leading again must not start over: the helpers are registered with the coordinator that is running.
            if (s.leader != null && !s.loopback) return s.leader;
            s.loopback = false;
            SwarmCoordinator.Config cfg = new SwarmCoordinator.Config();
            if (BotClientBridge.isHeadless()) {
                cfg.heartbeatTimeoutMs = 15_000;
                cfg.offerTimeoutMs = 10_000;
            } else {
                // The chat link is rate-limited (0.5 lines/s, signed lines are bigger), so give replies room.
                cfg.heartbeatTimeoutMs = 40_000;
                cfg.offerTimeoutMs = 30_000;
            }
            SwarmLink activeLink = s.link;
            s.leader = new SwarmCoordinator((agent, m) -> activeLink.send(agent, m), cfg);
            return s.leader;
        }
    }

    public static SwarmWorker join(AltoClef mod, Set<Capability> caps) {
        State s = state();
        synchronized (s) {
            String why = s.link.problem();
            if (why != null) throw new IllegalStateException(why);
            String lead = s.link.leader();
            if (lead == null) throw new IllegalStateException("your roster group has no lead=");
            s.link.onReceive((from, group, body) -> onWireFor(s, from, group, body));
            if (!s.fleetHandlerRegistered) {
                s.fleetHandlerRegistered = FleetProtocol.registerSwarmHandler();
            }
            if (s.worker != null) s.worker.leave();
            s.leaderName = lead;
            long hbMs = BotClientBridge.isHeadless() ? 2_000L : 12_000L;
            SwarmLink activeLink = s.link;
            s.worker = new SwarmWorker(activeLink.self(), caps, new AltoClefExecutor(mod), m -> activeLink.send(lead, m), hbMs);
            s.worker.register(System.currentTimeMillis());
            return s.worker;
        }
    }

    /**
     * Single-client loopback for live verification: a leader and one worker in this process, wired
     * directly, with the worker running the real AltoClefExecutor. Every ledger event goes to the log.
     */
    public static SwarmCoordinator local(AltoClef mod) {
        State s = state();
        synchronized (s) {
            final SwarmWorker[] w = new SwarmWorker[1];
            s.leader = new SwarmCoordinator((agent, m) -> {
                if (w[0] != null) w[0].onMessage(SwarmMessage.decode(m.encode()), System.currentTimeMillis());
            });
            SwarmCoordinator l = s.leader;
            w[0] = new SwarmWorker("local", java.util.EnumSet.allOf(Capability.class), new AltoClefExecutor(mod),
                    m -> l.onMessage("local", SwarmMessage.decode(m.encode()), System.currentTimeMillis()), 2_000);
            s.worker = w[0];
            s.leaderName = "local";
            s.loopback = true;
            s.loggedSeq = 0;
            s.worker.register(System.currentTimeMillis());
            return s.leader;
        }
    }

    public static void leave() {
        State s = state();
        synchronized (s) {
            if (s.worker != null) s.worker.leave();
            s.worker = null;
            s.leaderName = null;
        }
    }

    /** Inbound objective message routed to the current bot's runtime state. */
    public static void onWire(String from, String group, String body) {
        onWireFor(state(), from, group, body);
    }

    private static void onWireFor(State s, String from, String group, String body) {
        synchronized (s) {
            SwarmMessage m = SwarmMessage.decode(body);
            if (m == null) return;
            long now = System.currentTimeMillis();
            if (Boolean.getBoolean("tenorclef.swarm.log")) {
                Debug.logMessage("SWARMRX " + m.op + " from " + from + " " + body);
            }
            if (TO_LEADER.contains(m.op)) {
                if (s.leader != null) s.leader.onMessage(from, m, now);
                else Debug.logMessage("SWARM " + m.op + " from " + from + " ignored: not leading");
            } else if (s.worker != null && from.equals(s.link.leadOf(group)) && from.equals(s.leaderName)) {
                s.worker.onMessage(m, now);
            } else {
                Debug.logMessage("SWARM " + m.op + " from " + from + " ignored: not my leader");
            }
        }
    }

    /**
     * Publish safety latches and the {@code @} hook. Reflection only: a stock Baritone jar has none of
     * these methods, and this class must still compile against that jar. No effect while the protocol is off.
     */
    private static void noteProtocolSafety(State s) {
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary == null || primary.getSwarm() == null) {
                return;
            }
            Object swarm = primary.getSwarm();
            if (!s.protocolHookAttempted) {
                s.protocolHookAttempted = true;
                try {
                    Class<?> hookType = Class.forName("baritone.api.swarm.protocol.ExternalCommandHook");
                    Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                            hookType.getClassLoader(),
                            new Class<?>[]{hookType},
                            new SwarmProtocolHook());
                    swarm.getClass().getMethod("setExternalCommandHook", hookType).invoke(swarm, proxy);
                } catch (Throwable ignoredHook) {
                    // This Baritone build has no Phase 1 hook.
                }
            }
            AltoClef mod = AltoClef.getInstance();
            if (mod == null) {
                return;
            }
            invokeBool(swarm, "noteOwnerEmergency", mod.isPaused());
            boolean eating = mod.getFoodChain() != null
                    && (mod.getFoodChain().isTryingToEat() || mod.getFoodChain().needsToEat());
            adris.altoclef.chains.MobDefenseChain defense = mod.getMobDefenseChain();
            boolean defending = defense != null && (defense.isShielding() || defense.isDoingAcrobatics()
                    || defense.isPuttingOutFire() || defense.getPriority() > 0);
            invokeBool(swarm, "noteLocalSurvival", eating || defending);
            boolean danger = false;
            if (mod.getPlayer() != null) {
                danger = mod.getPlayer().isOnFire()
                        || mod.getPlayer().isInLava()
                        || mod.getPlayer().getAir() < 40
                        || mod.getPlayer().getHealth() <= 6.0f;
            }
            invokeBool(swarm, "noteImmediateSafety", danger);
        } catch (Throwable ignored) {
            // Baritone is not ready, or this build has no protocol methods. The link still ticks.
        }
    }

    private static void invokeBool(Object swarm, String name, boolean value) throws Exception {
        swarm.getClass().getMethod(name, boolean.class).invoke(swarm, Boolean.valueOf(value));
    }

    public static void tick() {
        State s = state();
        synchronized (s) {
            long now = System.currentTimeMillis();
            try {
                noteProtocolSafety(s);
                if (!s.fleetHandlerRegistered && s.link.problem() == null) {
                    s.fleetHandlerRegistered = FleetProtocol.registerSwarmHandler();
                }
                if (s.leader != null) s.leader.tick(now);
                if (s.worker != null) s.worker.tick(now);
                if (s.leader != null && ("local".equals(s.leaderName) || Boolean.getBoolean("tenorclef.swarm.log"))) {
                    java.util.List<SwarmLedger.Event> all = s.leader.ledger().all();
                    for (SwarmLedger.Event e : all) {
                        if (e.seq() <= s.loggedSeq) continue;
                        s.loggedSeq = e.seq();
                        Debug.logMessage("SWARMEVT " + e);
                    }
                }
            } catch (Throwable t) {
                Debug.logWarning("SWARM tick: " + t);
            }
        }
    }
}

package adris.altoclef.swarm;

//#if MC >= 260000
//$$ import adris.altoclef.headless.TenorClefBotContext;
//$$ import adris.altoclef.tasks.speedrun.testrun2.fleet.Fleet;
//$$ import baritone.api.IBaritone;
//$$ import baritone.api.Settings;
//$$ import baritone.api.context.BotContext;
//$$ import baritone.api.context.BotContextScope;
//$$ import baritone.api.policy.IAgentPolicy;
//$$ import baritone.api.swarm.ISwarm;
//$$ import baritone.api.swarm.ISwarmGroup;
//$$ import baritone.api.swarm.SwarmPriority;
//$$ import org.junit.jupiter.api.AfterEach;
//$$ import org.junit.jupiter.api.BeforeAll;
//$$ import org.junit.jupiter.api.Test;
//$$ import org.junit.jupiter.api.io.TempDir;
//$$
//$$ import java.lang.reflect.Proxy;
//$$ import java.nio.file.Path;
//$$ import java.util.EnumSet;
//$$ import java.util.HashSet;
//$$ import java.util.List;
//$$ import java.util.Map;
//$$ import java.util.Set;
//$$ import java.util.concurrent.ConcurrentHashMap;
//$$ import java.util.concurrent.ConcurrentLinkedQueue;
//$$ import java.util.concurrent.atomic.AtomicInteger;
//$$
//$$ import static org.junit.jupiter.api.Assertions.*;
//$$
//$$ /**
//$$  * Verifies per-{@link BotContext} / {@link TenorClefBotContext} isolation of {@link SwarmRuntime},
//$$  * {@link SwarmCoordinator}, {@link SwarmWorker}, and {@link Fleet} across multiple single-JVM bots.
//$$  */
//$$ public class SwarmContextIsolationTest {
//$$
//$$     private record WirePacket(String from, String group, String type, String body) {}
//$$
//$$     private static final class InMemorySwarmRouter {
//$$         final String group = "local";
//$$         final String lead = "LeadBot";
//$$         final List<String> members = List.of("LeadBot", "Worker1", "Worker2");
//$$         final Map<String, ConcurrentLinkedQueue<WirePacket>> inboxes = new ConcurrentHashMap<>();
//$$         final Map<String, Map<String, ISwarm.SwarmHandler>> handlers = new ConcurrentHashMap<>();
//$$
//$$         ISwarm createLink(String self) {
//$$             inboxes.computeIfAbsent(self, k -> new ConcurrentLinkedQueue<>());
//$$             handlers.computeIfAbsent(self, k -> new ConcurrentHashMap<>());
//$$             ISwarmGroup grp = new ISwarmGroup() {
//$$                 @Override public String id() { return group; }
//$$                 @Override public String lead() { return lead; }
//$$                 @Override public List<String> members() { return members; }
//$$                 @Override public boolean has(String member) { return members.contains(member); }
//$$             };
//$$             return new ISwarm() {
//$$                 @Override public boolean isOnline() { return true; }
//$$                 @Override public String state() { return "online"; }
//$$                 @Override public String self() { return self; }
//$$                 @Override public List<ISwarmGroup> myGroups() { return List.of(grp); }
//$$                 @Override public ISwarmGroup group(String id) { return group.equals(id) ? grp : null; }
//$$
//$$                 @Override
//$$                 public boolean send(String g, String to, String type, String body, SwarmPriority priority) {
//$$                     ConcurrentLinkedQueue<WirePacket> q = inboxes.get(to);
//$$                     if (q == null) return false;
//$$                     q.add(new WirePacket(self, g, type, body));
//$$                     return true;
//$$                 }
//$$
//$$                 @Override
//$$                 public boolean registerHandler(String type, SwarmHandler handler) {
//$$                     handlers.get(self).put(type, handler);
//$$                     return true;
//$$                 }
//$$             };
//$$         }
//$$
//$$         int drain(String self) {
//$$             ConcurrentLinkedQueue<WirePacket> q = inboxes.get(self);
//$$             if (q == null) return 0;
//$$             int count = 0;
//$$             WirePacket pkt;
//$$             while ((pkt = q.poll()) != null) {
//$$                 ISwarm.SwarmHandler h = handlers.get(self).get(pkt.type());
//$$                 if (h != null) {
//$$                     h.onMessage(pkt.from(), pkt.group(), pkt.body());
//$$                     count++;
//$$                 }
//$$             }
//$$             return count;
//$$         }
//$$     }
//$$
//$$     private static final class ScriptedExecutor implements AssignmentExecutor {
//$$         Objective active;
//$$         int pollCount;
//$$         int startedCount;
//$$
//$$         @Override
//$$         public void start(Objective objective) {
//$$             this.active = objective;
//$$             this.pollCount = 0;
//$$             this.startedCount++;
//$$         }
//$$
//$$         @Override
//$$         public Status poll() {
//$$             if (active == null) return Status.idle();
//$$             pollCount++;
//$$             if (pollCount < 2) {
//$$                 return Status.running(1);
//$$             }
//$$             return Status.succeeded(Map.of("have", String.valueOf(active.count)));
//$$         }
//$$
//$$         @Override
//$$         public void cancel() {
//$$             active = null;
//$$         }
//$$     }
//$$
//$$     @BeforeAll
//$$     static void bootstrap() {
//$$         net.minecraft.SharedConstants.tryDetectVersion();
//$$         net.minecraft.server.Bootstrap.bootStrap();
//$$     }
//$$
//$$     @AfterEach
//$$     void cleanupDefault() {
//$$         SwarmRuntime.leave();
//$$         SwarmRuntime.useLink(SwarmLink.create());
//$$     }
//$$
//$$     @Test
//$$     void swarmRuntimeLeaderWorkersAndFleetAreIsolatedPerBotContext(@TempDir Path tempDir) throws Exception {
//$$         InMemorySwarmRouter router = new InMemorySwarmRouter();
//$$         BotContext leadCtx = createContext("LeadBot", tempDir.resolve("lead"), router.createLink("LeadBot"));
//$$         BotContext w1Ctx = createContext("Worker1", tempDir.resolve("w1"), router.createLink("Worker1"));
//$$         BotContext w2Ctx = createContext("Worker2", tempDir.resolve("w2"), router.createLink("Worker2"));
//$$
//$$         // Verify Fleet membership isolation across TenorClefBotContexts
//$$         BotContextScope.runWith(w1Ctx, () -> Fleet.add("PeerAlpha"));
//$$         BotContextScope.runWith(w2Ctx, () -> Fleet.add("PeerBeta"));
//$$         assertTrue(BotContextScope.callWith(w1Ctx, () -> Fleet.known("PeerAlpha")));
//$$         assertFalse(BotContextScope.callWith(w1Ctx, () -> Fleet.known("PeerBeta")));
//$$         assertTrue(BotContextScope.callWith(w2Ctx, () -> Fleet.known("PeerBeta")));
//$$         assertFalse(BotContextScope.callWith(w2Ctx, () -> Fleet.known("PeerAlpha")));
//$$
//$$         // Initialize isolated SwarmRuntime links per bot
//$$         BotContextScope.runWith(leadCtx, () -> {
//$$             SwarmRuntime.useLink(SwarmLink.create());
//$$             SwarmRuntime.lead(null);
//$$         });
//$$         BotContextScope.runWith(w1Ctx, () -> SwarmRuntime.useLink(SwarmLink.create()));
//$$         BotContextScope.runWith(w2Ctx, () -> SwarmRuntime.useLink(SwarmLink.create()));
//$$
//$$         // Leader state must NOT leak to worker contexts
//$$         assertNotNull(BotContextScope.callWith(leadCtx, SwarmRuntime::leader));
//$$         assertNull(BotContextScope.callWith(w1Ctx, SwarmRuntime::leader));
//$$         assertNull(BotContextScope.callWith(w2Ctx, SwarmRuntime::leader));
//$$
//$$         ScriptedExecutor exec1 = new ScriptedExecutor();
//$$         ScriptedExecutor exec2 = new ScriptedExecutor();
//$$         long[] now = { System.currentTimeMillis() };
//$$
//$$         SwarmWorker worker1 = BotContextScope.callWith(w1Ctx, () -> {
//$$             SwarmLink link = SwarmRuntime.link();
//$$             SwarmWorker w = new SwarmWorker("Worker1", EnumSet.allOf(Capability.class), exec1,
//$$                     m -> link.send(link.leader(), m), 1_000L);
//$$             link.onReceive((from, g, body) -> {
//$$                 SwarmMessage m = SwarmMessage.decode(body);
//$$                 if (m != null) w.onMessage(m, now[0]);
//$$             });
//$$             w.register(now[0]);
//$$             return w;
//$$         });
//$$
//$$         SwarmWorker worker2 = BotContextScope.callWith(w2Ctx, () -> {
//$$             SwarmLink link = SwarmRuntime.link();
//$$             SwarmWorker w = new SwarmWorker("Worker2", EnumSet.allOf(Capability.class), exec2,
//$$                     m -> link.send(link.leader(), m), 1_000L);
//$$             link.onReceive((from, g, body) -> {
//$$                 SwarmMessage m = SwarmMessage.decode(body);
//$$                 if (m != null) w.onMessage(m, now[0]);
//$$             });
//$$             w.register(now[0]);
//$$             return w;
//$$         });
//$$
//$$         // Pump registration + submit two concurrent objectives on LeadBot
//$$         BotContextScope.runWith(leadCtx, () -> {
//$$             router.drain("LeadBot");
//$$             SwarmCoordinator coord = SwarmRuntime.leader();
//$$             coord.submit(coord.create(Objective.acquire("oak_log", 2), 5, EnumSet.noneOf(Capability.class)), now[0]);
//$$             coord.submit(coord.create(Objective.acquire("dirt", 2), 5, EnumSet.noneOf(Capability.class)), now[0]);
//$$         });
//$$
//$$         // Pump worker & coordinator ticks strictly inside each bot's own BotContextScope
//$$         for (int step = 0; step < 8; step++) {
//$$             now[0] += 1_000L;
//$$             BotContextScope.runWith(w1Ctx, () -> {
//$$                 router.drain("Worker1");
//$$                 worker1.tick(now[0]);
//$$             });
//$$             BotContextScope.runWith(w2Ctx, () -> {
//$$                 router.drain("Worker2");
//$$                 worker2.tick(now[0]);
//$$             });
//$$             BotContextScope.runWith(leadCtx, () -> {
//$$                 router.drain("LeadBot");
//$$                 SwarmRuntime.tick();
//$$             });
//$$         }
//$$
//$$         List<Assignment> outcomes = BotContextScope.callWith(leadCtx, () -> SwarmRuntime.leader().assignments());
//$$         assertEquals(2, outcomes.size());
//$$         Set<String> assignees = new HashSet<>();
//$$         for (Assignment a : outcomes) {
//$$             assertEquals(AssignmentState.SUCCEEDED, a.state(), a::toString);
//$$             assignees.add(a.agentId());
//$$         }
//$$         assertEquals(Set.of("Worker1", "Worker2"), assignees);
//$$         assertEquals(1, exec1.startedCount);
//$$         assertEquals(1, exec2.startedCount);
//$$
//$$         BotContextScope.runWith(leadCtx, () -> {
//$$             SwarmRuntime.leave();
//$$             SwarmRuntime.useLink(SwarmLink.create());
//$$         });
//$$         BotContextScope.runWith(w1Ctx, SwarmRuntime::leave);
//$$         BotContextScope.runWith(w2Ctx, SwarmRuntime::leave);
//$$         assertNull(BotContextScope.callWith(leadCtx, SwarmRuntime::leader));
//$$         assertNull(BotContextScope.callWith(w1Ctx, SwarmRuntime::worker));
//$$         assertNull(BotContextScope.callWith(w2Ctx, SwarmRuntime::worker));
//$$     }
//$$
//$$     private static BotContext createContext(String name, Path cfgDir, ISwarm swarm) {
//$$         BotContext ctx = new BotContext(name, cfgDir, true, new Settings(), (IAgentPolicy) null);
//$$         IBaritone proxy = (IBaritone) Proxy.newProxyInstance(
//$$                 IBaritone.class.getClassLoader(),
//$$                 new Class<?>[] { IBaritone.class },
//$$                 (obj, method, args) -> {
//$$                     if ("getSwarm".equals(method.getName())) {
//$$                         return swarm;
//$$                     }
//$$                     return null;
//$$                 });
//$$         ctx.setBaritone(proxy);
//$$         return ctx;
//$$     }
//$$ }
//#else
public class SwarmContextIsolationTest {
}
//#endif

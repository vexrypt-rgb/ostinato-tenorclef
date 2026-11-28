package baritone.swarm.transport;

import baritone.swarm.roster.SwarmRoster;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ChatAdaptationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void cleanup() {
        SharedChatBucket.clearRegistryForTests();
    }

    @Test
    public void whisperDetectorOrdersBrigadierTreeByKnownPreference() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("pm")
                .then(RequiredArgumentBuilder.argument("target", StringArgumentType.word())
                        .then(RequiredArgumentBuilder.argument("message", StringArgumentType.greedyString()))));
        dispatcher.register(LiteralArgumentBuilder.literal("tell")
                .then(RequiredArgumentBuilder.argument("target", StringArgumentType.word())
                        .then(RequiredArgumentBuilder.argument("message", StringArgumentType.greedyString()))));
        dispatcher.register(LiteralArgumentBuilder.literal("help")); // only 0 args, not whisper-shaped

        List<String> twoArg = WhisperDetector.inspectDispatcher(dispatcher);
        assertTrue(twoArg.contains("pm"));
        assertTrue(twoArg.contains("tell"));
        assertFalse(twoArg.contains("help"));

        WhisperDetector detector = WhisperDetector.create(twoArg, "", "");
        assertEquals(WhisperDetector.State.PROBING, detector.state());
        assertEquals("/tell {to} {msg}", detector.currentTemplate());

        // Rejecting tell falls through to pm, then fails instead of leaking to public chat
        assertEquals("/pm {to} {msg}", detector.onCandidateRejected(1000L));
        assertNull(detector.onCandidateRejected(2000L));
        assertEquals(WhisperDetector.State.FAILED, detector.state());
        assertNull(detector.currentTemplate());
    }

    @Test
    public void whisperDetectorResolvesOnSignedAckAndRespectsManualOverride() {
        WhisperDetector manual = WhisperDetector.create(List.of("msg", "tell"), "/dm {to} {msg}", "/w {to} {msg}");
        assertEquals(WhisperDetector.State.RESOLVED, manual.state());
        assertEquals("/dm {to} {msg}", manual.currentTemplate());

        WhisperDetector probing = WhisperDetector.create(List.of("w", "pm"), "", "");
        assertEquals("/w {to} {msg}", probing.currentTemplate());
        assertTrue(probing.onSignedAckReceived(WhisperDetector.matchesWhisperEcho("Azalea whispers to you: S2S.abc")));
        assertEquals(WhisperDetector.State.RESOLVED, probing.state());
        assertTrue(probing.isEchoVerified());
    }

    @Test
    public void serverChatStorePersistsLearnedAndOverridesAlwaysWin() throws IOException {
        Path file = tmp.newFolder().toPath().resolve("swarm-servers.properties");
        ServerChatStore store = new ServerChatStore(file);
        store.putLearned("Play.Example.com:25565",
                new ServerChatStore.ServerProfile("/tell {to} {msg}", 180, 0.25, 12345L));

        ServerChatStore reloaded = new ServerChatStore(file);
        ServerChatStore.ServerProfile learned = reloaded.learnedFor("play.example.com");
        assertEquals("/tell {to} {msg}", learned.whisperTemplate());
        assertEquals(180, learned.maxLineChars());
        assertEquals(0.25, learned.ratePerSec(), 1e-4);

        reloaded.loadOverridesSpec("play.example.com=/w {to} {msg}|220|0.4");
        ServerChatStore.ServerProfile eff = reloaded.effectiveFor("play.example.com:25565");
        assertEquals("/w {to} {msg}", eff.whisperTemplate());
        assertEquals(220, eff.maxLineChars());
        assertEquals(0.4, eff.ratePerSec(), 1e-4);
    }

    @Test
    public void chatBudgetStartsConservativeBacksOffAndRecovers() throws IOException {
        Path file = tmp.newFolder().toPath().resolve("swarm-servers.properties");
        ServerChatStore store = new ServerChatStore(file);
        WhisperDetector detector = WhisperDetector.create(List.of("msg", "tell"), "", "");
        SharedChatBucket shared = SharedChatBucket.forServer("mc.test.local", 0.5, 5, 1000L);
        ChatBudget budget = new ChatBudget("mc.test.local", store, detector, shared, 0.9, 256, false, 1000L, null);

        // Conservative defaults on a brand-new server
        assertEquals(0.5, budget.effectiveRate(), 1e-4);
        assertEquals(200, budget.effectiveMaxChars());

        // Cooldown halves rate
        assertEquals(ChatBudget.Signal.COOLDOWN_OR_SPAM, budget.onServerText("Please slow down before chatting!", 2000L));
        assertEquals(0.25, budget.effectiveRate(), 1e-4);
        assertEquals(0.25, shared.ratePerSec(), 1e-4);

        // Too long lowers line cap
        assertEquals(ChatBudget.Signal.TOO_LONG, budget.onServerText("Message exceeds maximum length", 3000L));
        assertTrue(budget.effectiveMaxChars() < 200);
        assertTrue(budget.effectiveMaxChars() >= ChatBudget.MIN_LINE_CHARS);

        // Unknown command advances whisper candidate
        assertEquals(ChatBudget.Signal.WHISPER_REJECTED, budget.onServerText("Unknown or incomplete command", 4000L));
        assertEquals("/tell {to} {msg}", budget.effectiveTemplate());

        // Sealed SIGIL line is never misclassified as an error signal
        assertEquals(ChatBudget.Signal.NONE, budget.onServerText("Azalea whispers to you: S2S.slowdown.cooldown", 5000L));

        // Clean recovery after 120 s
        double backedOffRate = budget.effectiveRate();
        int backedOffChars = budget.effectiveMaxChars();
        assertTrue(budget.tick(4000L + ChatBudget.RECOVERY_INTERVAL_MS));
        assertTrue(budget.effectiveRate() > backedOffRate);
        assertTrue(budget.effectiveMaxChars() > backedOffChars);
    }

    @Test
    public void sharedBucketAndWhisperDownGuardWorkOnTransport() throws Exception {
        SharedChatBucket shared = new SharedChatBucket("shared.local", 0.5, 1, 1000L);
        SwarmRateLimiter a = new SwarmRateLimiter(0.9, 5, false, 16, 1000L);
        SwarmRateLimiter b = new SwarmRateLimiter(0.9, 5, false, 16, 1000L);
        a.setSharedBucket(shared);
        b.setSharedBucket(shared);

        a.offer(SwarmPriority.NORMAL, "/msg Bob S2S.aaa");
        b.offer(SwarmPriority.NORMAL, "/msg Bob S2S.bbb");

        // Shared burst=1 lets only one bot through at t=1000
        assertNotNull(a.poll(1000L));
        assertNull(b.poll(1000L));
        assertEquals(1, shared.throttled());
        // After 2 seconds (rate 0.5/s -> 1 token), second bot goes through
        assertNotNull(b.poll(3000L));

        // Whisper link down refuses whisper send instead of falling back to public chat
        SwarmRoster roster = SwarmRoster.parse("group g1 circle=c1 members=Alice,Bob lead=Alice\n");
        ChatSwarmTransport transport = new ChatSwarmTransport("Alice", SwarmChannel.WHISPER, "", roster, a, 200);
        transport.setEffectiveTemplate(null);
        assertTrue(transport.isWhisperLinkDown());
        String validSealed = "S2S.abcd.0123456789012345678901234567890123456789";
        assertThrows(IOException.class, () -> transport.sendTo("g1", "Bob", validSealed, SwarmPriority.NORMAL));
    }
}

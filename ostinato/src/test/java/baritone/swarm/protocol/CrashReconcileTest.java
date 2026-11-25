/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.swarm.protocol;

import baritone.agent.AgentPolicy;
import baritone.api.command.CommandPermission;
import baritone.api.swarm.protocol.CapabilityDescriptor;
import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A relaunch is a new {@link CommandProtocol} on the same ledger file. These fail when the file is ignored:
 * the second protocol would call {@code start} again.
 */
public class CrashReconcileTest {

    @Test
    public void doneEffectIsNotRepeated() throws Exception {
        for (String command : List.of("place 10 64 10 stone", "mine 1 emerald_ore", "pickup diamond", "drop diamond")) {
            Path dir = Files.createTempDirectory("s6-done");
            try {
                Path file = dir.resolve("swarm-ledger.log");
                Held first = run(file, command, EffectCheck.Verdict.NOT_DONE);
                assertEquals(command, 1, first.exec.starts);
                assertTrue(command, first.exec.startSawRecord);
                assertEquals(command, 1, first.check.captures);
                first.bot.closeLedger();

                Held second = open(file, EffectCheck.Verdict.DONE);
                second.bot.receive("mem", "Alice", CommandProtocol.TYPE_CMD, first.body);
                assertEquals(command, 0, second.exec.starts);
                assertTrue(command, second.check.evaluates >= 1);
                assertEquals(command, "snap-" + commandName(command), second.check.lastNote);
                assertTrue(command, joined(second.log).contains("not repeating"));
                second.bot.closeLedger();
            } finally {
                delete(dir);
            }
        }
    }

    @Test
    public void notDoneEffectRunsOnce() throws Exception {
        Path dir = Files.createTempDirectory("s6-retry");
        try {
            Path file = dir.resolve("swarm-ledger.log");
            Held first = run(file, "mine 1 emerald_ore", EffectCheck.Verdict.NOT_DONE);
            first.bot.closeLedger();
            Held second = open(file, EffectCheck.Verdict.NOT_DONE);
            second.bot.receive("mem", "Alice", CommandProtocol.TYPE_CMD, first.body);
            assertEquals(1, second.exec.starts);
            assertTrue(second.exec.startSawRecord);
            assertTrue(joined(second.log).contains("not done yet"));
            assertEquals("snap-mine", second.check.lastNote);
            second.bot.closeLedger();
        } finally {
            delete(dir);
        }
    }

    @Test
    public void unknownEffectDoesNotStartAndIsCheckedAgain() throws Exception {
        Path dir = Files.createTempDirectory("s6-unknown");
        try {
            Path file = dir.resolve("swarm-ledger.log");
            Held first = run(file, "place 10 64 10 stone", EffectCheck.Verdict.NOT_DONE);
            first.bot.closeLedger();
            Held second = open(file, EffectCheck.Verdict.UNKNOWN);
            second.bot.receive("mem", "Alice", CommandProtocol.TYPE_CMD, first.body);
            second.bot.receive("mem", "Alice", CommandProtocol.TYPE_CMD, first.body);
            assertEquals(0, second.exec.starts);
            assertEquals(2, second.check.evaluates);
            assertTrue(joined(second.log).contains("effect unknown"));
            second.bot.closeLedger();
        } finally {
            delete(dir);
        }
    }

    @Test
    public void tickSeesADoneEffectBeforeTheRetryArrives() throws Exception {
        Path dir = Files.createTempDirectory("s6-tick");
        try {
            Path file = dir.resolve("swarm-ledger.log");
            Held first = run(file, "mine 1 emerald_ore", EffectCheck.Verdict.NOT_DONE);
            first.bot.closeLedger();
            Held second = open(file, EffectCheck.Verdict.DONE);
            second.bot.pollExecutions();
            assertEquals(0, second.exec.starts);
            assertTrue(joined(second.log).contains("already done"));
            second.bot.receive("mem", "Alice", CommandProtocol.TYPE_CMD, first.body);
            assertEquals(0, second.exec.starts);
            assertTrue(joined(second.log).contains("duplicate"));
            second.bot.closeLedger();
        } finally {
            delete(dir);
        }
    }

    @Test
    public void recordedEndIsNotRunAgain() throws Exception {
        Path dir = Files.createTempDirectory("s6-end");
        try {
            Path file = dir.resolve("swarm-ledger.log");
            Held first = run(file, "mine 1 emerald_ore", EffectCheck.Verdict.NOT_DONE);
            first.exec.poll = ExecutionState.SUCCEEDED;
            first.bot.pollExecutions();
            assertTrue(Files.readString(file).contains("|END|"));
            first.bot.closeLedger();
            Held second = open(file, EffectCheck.Verdict.NOT_DONE);
            second.bot.receive("mem", "Alice", CommandProtocol.TYPE_CMD, first.body);
            assertEquals(0, second.exec.starts);
            assertEquals(0, second.check.evaluates);
            assertTrue(joined(second.log).contains("duplicate"));
            second.bot.closeLedger();
        } finally {
            delete(dir);
        }
    }

    @Test
    public void gotoWritesNoStartLine() throws Exception {
        Path dir = Files.createTempDirectory("s6-goto");
        try {
            Path file = dir.resolve("swarm-ledger.log");
            Held held = run(file, "goto 1 2 3", EffectCheck.Verdict.DONE);
            assertEquals(1, held.exec.starts);
            assertEquals(0, held.check.captures);
            assertFalse(Files.readString(file).contains("|START|"));
            held.bot.closeLedger();
        } finally {
            delete(dir);
        }
    }

    @Test
    public void issuerOutcomeAndRetrySurviveRestart() throws Exception {
        Path dir = Files.createTempDirectory("s7-issuer");
        try {
            Path file = dir.resolve("swarm-ledger.log");
            AtomicLong clock = new AtomicLong(Phase1Support.T0);
            List<String> log = new ArrayList<>();
            boolean[] sentFirst = {false};
            Exec exec = new Exec();
            CommandProtocol alice = node("Alice", exec, log, clock, (to, type, body) -> {
                try {
                    sentFirst[0] = Files.readString(file).contains("|SENT|");
                } catch (IOException e) {
                    sentFirst[0] = false;
                }
                return true;
            });
            alice.setLedgerFile(file);
            String line = alice.submit("Bob", "mine 1 emerald_ore");
            assertTrue(line, line.startsWith("swarm: sent "));
            assertTrue(sentFirst[0]);
            UUID id = UUID.fromString(line.split(" ")[2]);
            String body = alice.lookupSent(id).body;
            alice.receive("mem", "Bob", CommandProtocol.TYPE_ACK,
                    "1|ack|" + id + "|accepted||succeeded (verified)|confirmed");
            alice.fenced(id);
            alice.closeLedger();

            List<String> againLog = new ArrayList<>();
            List<String> resent = new ArrayList<>();
            CommandProtocol relaunch = node("Alice", new Exec(), againLog, clock, (to, type, bodyOut) -> {
                resent.add(bodyOut);
                return true;
            });
            relaunch.setLedgerFile(file);
            String outcome = relaunch.outcomeLine(id.toString());
            assertFalse(outcome, outcome.contains("no command"));
            assertTrue(outcome, outcome.contains("succeeded (verified)"));
            assertTrue(outcome, outcome.contains(ObservationState.CONFIRMED.label()));
            assertNotNull(relaunch.lookupSent(id));
            assertTrue(relaunch.lookupSent(id).fenced);
            relaunch.pump();
            assertTrue(resent.isEmpty()); // acked, so the same id is not sent again
            relaunch.closeLedger();

            // An unsettled send is retried with the same body.
            Path open = dir.resolve("open.log");
            CommandProtocol fresh = node("Alice", new Exec(), log, clock, (to, type, b) -> true);
            fresh.setLedgerFile(open);
            String sent = fresh.submit("Bob", "echo hi");
            UUID openId = UUID.fromString(sent.split(" ")[2]);
            String openBody = fresh.lookupSent(openId).body;
            fresh.closeLedger();
            List<String> retry = new ArrayList<>();
            CommandProtocol resumed = node("Alice", new Exec(), log, clock, (to, type, b) -> {
                if (CommandProtocol.TYPE_CMD.equals(type)) {
                    retry.add(b);
                }
                return true;
            });
            resumed.setLedgerFile(open);
            assertTrue(resumed.outcomeLine(openId.toString()).contains("awaiting ack"));
            resumed.pump();
            assertEquals(List.of(openBody), retry);
            resumed.closeLedger();
        } finally {
            delete(dir);
        }
    }

    @Test
    public void compactDropsOldSettledRowsAndKeepsTheOpenOnes() throws Exception {
        Path dir = Files.createTempDirectory("s7-compact");
        try {
            Path file = dir.resolve("swarm-ledger.log");
            SwarmLedger ledger = SwarmLedger.open(file);
            ledger.setCompactAt(4);
            ledger.setRetainMs(1_000L);
            UUID old = UUID.randomUUID();
            UUID live = UUID.randomUUID();
            UUID crash = UUID.randomUUID();
            ledger.sent(0L, old, "Bob", 50L, false, "old-body");
            ledger.state(0L, old, true, true, false, false, 1, ExecutionState.SUCCEEDED, ObservationState.CONFIRMED, "");
            ledger.sent(0L, live, "Bob", 50_000L, false, "live-body");
            ledger.start(0L, crash, "mine", "hash", "1 emerald_ore", "snap-mine");
            UUID recent = UUID.randomUUID();
            ledger.sent(10_000L, recent, "Bob", 20_000L, false, "recent-body");
            ledger.close();

            SwarmLedger again = SwarmLedger.open(file);
            SwarmLedger.Replay replay = again.replay();
            assertFalse(replay.sent.containsKey(old));
            assertEquals("live-body", replay.sent.get(live).body);
            assertFalse(replay.sent.get(live).acked);
            assertTrue(replay.open.containsKey(crash));
            assertEquals("snap-mine", replay.open.get(crash).note);
            assertTrue(replay.sent.containsKey(recent));
            again.close();
        } finally {
            delete(dir);
        }
    }

    private static Held run(Path file, String command, EffectCheck.Verdict verdict) {
        AtomicLong clock = new AtomicLong(Phase1Support.T0);
        List<String> log = new ArrayList<>();
        Exec exec = new Exec();
        exec.file = file;
        Script check = new Script();
        check.verdict = verdict;
        CommandProtocol alice = node("Alice", new Exec(), log, clock, (to, type, body) -> true);
        CommandProtocol bob = node("Bob", exec, log, clock, (to, type, body) -> true);
        bob.setEffectCheck(check);
        bob.setLedgerFile(file);
        alice.learnPeer("Bob", "Bobkey", CapabilityDescriptor.gameplay());
        String submitted = alice.submit("Bob", command);
        assertTrue(submitted, submitted.startsWith("swarm: sent "));
        String body = alice.lookupSent(UUID.fromString(submitted.split(" ")[2])).body;
        bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        return new Held(bob, exec, check, log, body);
    }

    private static Held open(Path file, EffectCheck.Verdict verdict) {
        AtomicLong clock = new AtomicLong(Phase1Support.T0);
        List<String> log = new ArrayList<>();
        Exec exec = new Exec();
        exec.file = file;
        Script check = new Script();
        check.verdict = verdict;
        CommandProtocol bob = node("Bob", exec, log, clock, (to, type, body) -> true);
        bob.setEffectCheck(check);
        bob.setLedgerFile(file);
        return new Held(bob, exec, check, log, null);
    }

    private static CommandProtocol node(String name, Exec exec, List<String> log, AtomicLong clock, CommandProtocol.Wire wire) {
        CommandProtocol protocol = new CommandProtocol(
                new CommandProtocol.BotIdentity(name, name + "key"),
                Set.of("Alice", "Bob"),
                wire,
                exec,
                new CommandProtocol.Policy("Alice", true, false, AgentPolicy.DEFAULT_TOOLS),
                clock::get,
                log::add,
                1_000L);
        protocol.learnPeer("Alice", "Alicekey", CapabilityDescriptor.gameplay());
        protocol.learnPeer("Bob", "Bobkey", CapabilityDescriptor.gameplay());
        return protocol;
    }

    private static String commandName(String command) {
        return command.split(" ")[0];
    }

    private static String joined(List<String> log) {
        return String.join("\n", log);
    }

    private static void delete(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // the assertion already ran; a leftover temp dir is not the result
                }
            });
        }
    }

    private static final class Held {
        final CommandProtocol bot;
        final Exec exec;
        final Script check;
        final List<String> log;
        final String body;

        Held(CommandProtocol bot, Exec exec, Script check, List<String> log, String body) {
            this.bot = bot;
            this.exec = exec;
            this.check = check;
            this.log = log;
            this.body = body;
        }
    }

    private static final class Script implements EffectCheck {
        Verdict verdict = Verdict.NOT_DONE;
        int captures;
        int evaluates;
        String lastNote = "";

        @Override
        public String capture(UUID id, String name, String args) {
            captures++;
            return "snap-" + name;
        }

        @Override
        public Verdict evaluate(UUID id, String name, String args, String note) {
            evaluates++;
            lastNote = note == null ? "" : note;
            return verdict;
        }
    }

    private static final class Exec implements CommandProtocol.Executor {
        int starts;
        ExecutionState poll;
        Path file;
        boolean startSawRecord;

        @Override
        public CommandPermission permissionOf(String name) {
            CommandCatalog.Entry entry = describe(name);
            return entry == null ? null : entry.permission;
        }

        @Override
        public CommandCatalog.Entry describe(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            return CommandCatalog.infer(name.toLowerCase(java.util.Locale.ROOT), CommandCatalog.defaultPermission(name));
        }

        @Override
        public boolean start(UUID id, String name, String args) {
            starts++;
            if (file != null) {
                try {
                    startSawRecord = Files.readString(file).contains("|START|");
                } catch (IOException e) {
                    startSawRecord = false;
                }
            }
            return true;
        }

        @Override
        public boolean confirmStopped(UUID id) {
            return true;
        }

        @Override
        public ExecutionState poll(UUID id) {
            return poll;
        }
    }
}

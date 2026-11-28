package adris.altoclef.compose;

import adris.altoclef.compose.DemoStationBindings.StationPos;
import adris.altoclef.tasks.replenish.ContainerSlotSnapshot;
import adris.altoclef.tasks.replenish.ContainerSlotSnapshot.SlotEntry;
import adris.altoclef.tasks.replenish.ContainerTransferPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for teach-by-demonstration recording, ambiguity handling, editable summary,
 * persistence, station rebinding, command parsing, and verified transfer replay across
 * 10 rearranged layouts + failure cases.
 */
class TeachByDemoTest {

    private static ContainerSlotSnapshot snapshot(int containerSlots,
                                                  Map<Integer, SlotEntry> containerBySlot,
                                                  Map<Integer, SlotEntry> playerBySlot,
                                                  String cursorItem,
                                                  int cursorCount) {
        List<SlotEntry> entries = new ArrayList<>(containerSlots + 36);
        for (int i = 0; i < containerSlots; i++) {
            SlotEntry e = containerBySlot.get(i);
            entries.add(e != null ? e : new SlotEntry(i, true, "", 0, 64));
        }
        for (int i = 0; i < 36; i++) {
            int win = containerSlots + i;
            SlotEntry e = playerBySlot.get(i);
            entries.add(e != null ? new SlotEntry(win, false, e.itemId(), e.count(), e.maxStackSize())
                    : new SlotEntry(win, false, "", 0, 64));
        }
        return new ContainerSlotSnapshot(containerSlots, entries, cursorItem, cursorCount, 64);
    }

    private static SlotEntry item(int slot, boolean container, String id, int count) {
        return new SlotEntry(slot, container, id, count, 64);
    }

    private static ContainerSlotSnapshot simulateDemoTransfer(DemoTransferState state, ContainerSlotSnapshot start) {
        ContainerSlotSnapshot snap = start;
        for (int i = 0; i < 64; i++) {
            DemoTransferState.Decision d = state.evaluate(snap);
            if (d.status() == DemoTransferState.Status.SUCCEEDED || d.status() == DemoTransferState.Status.FAILED) {
                return snap;
            }
            assertNotNull(d.nextClick(), "In-progress transfer must provide a click");
            state.recordClickDispatched(snap);
            snap = snap.simulate(d.nextClick());
        }
        fail("Demo transfer did not terminate within 64 clicks");
        return snap;
    }

    @Test
    void recordsTwoBreadWithdrawAndDepositAcrossSyncDelayAndMultiClickSplit(@TempDir Path tmp) throws Exception {
        DemoRecorder recorder = new DemoRecorder();
        DemoStationBindings bindings = new DemoStationBindings(tmp.resolve("bindings.properties"));
        recorder.start(List.of(), bindings);

        // --- Site A Step 1: Open pantry chest (initial sync arrives on tick 2), split bread stack to take 2 ---
        recorder.onBlockInteract("OVERWORLD", 10, 64, 20, "chest", 1_000L);
        recorder.onScreenOpened(1, null, "", 1_050L);

        // Tick 1: empty container before server contents packet arrives
        ContainerSlotSnapshot syncEmpty = snapshot(27, Map.of(), Map.of(0, item(0, false, "cobblestone", 12)), "", 0);
        recorder.onContainerTick(1, syncEmpty, 1_100L);

        // Tick 2: server contents packet populates 10 bread + 5 apples (player unchanged -> refreshes baseline)
        Map<Integer, SlotEntry> pantryStart = Map.of(
                4, item(4, true, "bread", 10),
                12, item(12, true, "apple", 5));
        Map<Integer, SlotEntry> playerStart = Map.of(0, item(0, false, "cobblestone", 12));
        ContainerSlotSnapshot pantrySynced = snapshot(27, pantryStart, playerStart, "", 0);
        recorder.onContainerTick(1, pantrySynced, 1_150L);

        // Player right-clicks bread stack (10 -> 5 in chest, 5 on cursor), drops 2 in player slot, returns 3 to chest
        recorder.onSlotClickObserved();
        ContainerSlotSnapshot midSplit = snapshot(27,
                Map.of(4, item(4, true, "bread", 5), 12, item(12, true, "apple", 5)),
                playerStart, "bread", 5);
        recorder.onContainerTick(1, midSplit, 1_250L);

        ContainerSlotSnapshot pantryDone = snapshot(27,
                Map.of(4, item(4, true, "bread", 8), 12, item(12, true, "apple", 5)),
                Map.of(0, item(0, false, "cobblestone", 12), 1, item(1, false, "bread", 2)),
                "", 0);
        recorder.onContainerTick(1, pantryDone, 1_350L);
        assertTrue(recorder.onScreenClosed(1_400L).isPresent());

        // --- No-op inspection of an unrelated barrel (internal shuffle only -> ignored) ---
        recorder.onBlockInteract("OVERWORLD", 12, 64, 20, "barrel", 1_500L);
        recorder.onScreenOpened(2, null, "", 1_520L);
        recorder.onContainerTick(2, snapshot(27, Map.of(0, item(0, true, "carrot", 7)),
                Map.of(0, item(0, false, "cobblestone", 12), 1, item(1, false, "bread", 2)), "", 0), 1_550L);
        recorder.onContainerTick(2, snapshot(27, Map.of(9, item(9, true, "carrot", 7)),
                Map.of(0, item(0, false, "cobblestone", 12), 1, item(1, false, "bread", 2)), "", 0), 1_600L);
        assertTrue(recorder.onScreenClosed(1_650L).isEmpty(), "Internal shuffle must not record a step");

        // --- Site A Step 2: Open serving barrel and deposit 2 bread ---
        recorder.onBlockInteract("OVERWORLD", 16, 64, 20, "barrel", 2_000L);
        recorder.onScreenOpened(3, null, "", 2_050L);
        ContainerSlotSnapshot barrelStart = snapshot(27,
                Map.of(2, item(2, true, "bread", 3)),
                Map.of(0, item(0, false, "cobblestone", 12), 1, item(1, false, "bread", 2)),
                "", 0);
        recorder.onContainerTick(3, barrelStart, 2_100L);
        ContainerSlotSnapshot barrelEnd = snapshot(27,
                Map.of(2, item(2, true, "bread", 5)),
                Map.of(0, item(0, false, "cobblestone", 12)),
                "", 0);
        recorder.onContainerTick(3, barrelEnd, 2_200L);
        assertTrue(recorder.onScreenClosed(2_250L).isPresent());

        DemoRoutine draft = recorder.stopRecording();
        assertEquals(2, draft.steps().size());
        assertFalse(draft.hasUnresolvedSteps());
        assertEquals(DemoStep.Action.WITHDRAW, draft.steps().get(0).action());
        assertEquals("bread", draft.steps().get(0).itemId());
        assertEquals(2, draft.steps().get(0).count());
        assertEquals(DemoStep.Action.DEPOSIT, draft.steps().get(1).action());
        assertEquals("bread", draft.steps().get(1).itemId());
        assertEquals(2, draft.steps().get(1).count());

        // Rename both stations to human roles ("pantry" and "serving barrel") in the editable summary.
        recorder.renameStation(1, "pantry");
        recorder.renameStation(2, "serving barrel");
        DemoRoutine named = recorder.currentDraft().withName("inn_bread");
        String summary = named.formatSummary();
        assertTrue(summary.contains("WITHDRAW 2x bread from \"pantry\""), summary);
        assertTrue(summary.contains("DEPOSIT 2x bread to \"serving barrel\""), summary);

        // Save to disk and verify zero raw slot indices or coordinates are stored in the routine file.
        DemoRoutineStore store = new DemoRoutineStore(tmp.resolve("demos"));
        store.save(named);
        String savedText = Files.readString(tmp.resolve("demos").resolve("inn_bread.demo.properties"));
        assertFalse(savedText.contains("10,64,20"), "Routine must not hardcode Site A coordinates");
        assertFalse(savedText.contains("slot"), "Routine must not store raw slot indices");

        DemoRoutine reloaded = store.load("inn_bread");
        assertEquals(named, reloaded);

        // Bind stations to Site B and confirm case/space/underscore lookup equivalence.
        bindings.bind("pantry", "OVERWORLD", 100, 70, -40);
        bindings.bind("serving barrel", "OVERWORLD", 108, 70, -35);
        assertEquals(new StationPos("OVERWORLD", 100, 70, -40), bindings.resolve("pantry").orElseThrow().pos());
        assertEquals(new StationPos("OVERWORLD", 108, 70, -35), bindings.resolve("serving_barrel").orElseThrow().pos());
        assertEquals(new StationPos("OVERWORLD", 108, 70, -35), bindings.resolve("Serving Barrel").orElseThrow().pos());
    }

    @Test
    void ambiguousDemonstrationStepsStayUnresolvedUntilExplicitlyEditedAndConfirmed(@TempDir Path tmp) throws Exception {
        DemoRecorder recorder = new DemoRecorder();
        DemoStationBindings bindings = DemoStationBindings.inMemory();
        bindings.bind("pantry", "OVERWORLD", 0, 64, 0);
        recorder.start(List.of(), bindings);

        // Ambiguity 1: Multiple items transferred in one container visit (2 bread + 1 apple)
        recorder.onBlockInteract("OVERWORLD", 0, 64, 0, "chest", 1_000L);
        recorder.onScreenOpened(1, null, "", 1_010L);
        recorder.onContainerTick(1, snapshot(27,
                Map.of(0, item(0, true, "bread", 5), 1, item(1, true, "apple", 3)),
                Map.of(), "", 0), 1_050L);
        recorder.onContainerTick(1, snapshot(27,
                Map.of(0, item(0, true, "bread", 3), 1, item(1, true, "apple", 2)),
                Map.of(0, item(0, false, "bread", 2), 1, item(1, false, "apple", 1)),
                "", 0), 1_100L);
        DemoStep s1 = recorder.onScreenClosed(1_150L).orElseThrow();
        assertFalse(s1.resolved());
        assertEquals(DemoStep.Action.UNRESOLVED, s1.action());
        assertTrue(s1.ambiguityReason().contains("Multiple items"));

        // Ambiguity 2: Conservation mismatch (4 bread left container, only 2 reached player inventory)
        recorder.onBlockInteract("OVERWORLD", 0, 64, 0, "chest", 2_000L);
        recorder.onScreenOpened(2, null, "", 2_010L);
        recorder.onContainerTick(2, snapshot(27, Map.of(0, item(0, true, "bread", 6)), Map.of(), "", 0), 2_050L);
        recorder.onContainerTick(2, snapshot(27, Map.of(0, item(0, true, "bread", 2)), Map.of(0, item(0, false, "bread", 2)), "", 0), 2_100L);
        DemoStep s2 = recorder.onScreenClosed(2_150L).orElseThrow();
        assertFalse(s2.resolved());
        assertTrue(s2.ambiguityReason().contains("Conservation mismatch"));

        // Ambiguity 3: Closed while cursor still holds bread
        recorder.onBlockInteract("OVERWORLD", 0, 64, 0, "chest", 3_000L);
        recorder.onScreenOpened(3, null, "", 3_010L);
        recorder.onContainerTick(3, snapshot(27, Map.of(0, item(0, true, "bread", 4)), Map.of(), "", 0), 3_050L);
        recorder.onContainerTick(3, snapshot(27, Map.of(0, item(0, true, "bread", 2)), Map.of(), "bread", 2), 3_100L);
        DemoStep s3 = recorder.onScreenClosed(3_150L).orElseThrow();
        assertFalse(s3.resolved());
        assertTrue(s3.ambiguityReason().contains("cursor"));

        // Ambiguity 4: No block interaction observed before screen opened
        recorder.onScreenOpened(4, null, "", 15_000L); // beyond INTERACT_WINDOW_MS
        recorder.onContainerTick(4, snapshot(27, Map.of(0, item(0, true, "bread", 4)), Map.of(), "", 0), 15_050L);
        recorder.onContainerTick(4, snapshot(27, Map.of(0, item(0, true, "bread", 2)), Map.of(0, item(0, false, "bread", 2)), "", 0), 15_100L);
        DemoStep s4 = recorder.onScreenClosed(15_150L).orElseThrow();
        assertFalse(s4.resolved());
        assertTrue(s4.ambiguityReason().contains("block position"));

        DemoRoutine draft = recorder.stopRecording();
        assertTrue(draft.hasUnresolvedSteps());
        assertEquals(List.of(1, 2, 3, 4), draft.unresolvedIndices());
        assertTrue(draft.formatSummary().contains("[UNRESOLVED]"));

        // Renaming station alone MUST NOT resolve an ambiguous step.
        recorder.renameStation(1, "pantry");
        assertFalse(recorder.currentDraft().steps().get(0).resolved());

        // Attempting to save while unresolved MUST be refused.
        DemoRoutineStore store = new DemoRoutineStore(tmp.resolve("demos"));
        assertThrows(IllegalStateException.class, () -> store.save(recorder.currentDraft().withName("bad")));

        // Remove steps 2..4 and explicitly edit+confirm step 1 -> now resolves and saves cleanly.
        recorder.removeStep(4);
        recorder.removeStep(3);
        recorder.removeStep(2);
        DemoStep confirmed = recorder.editStep(1, DemoStep.Action.WITHDRAW, "bread", 2, "pantry", true);
        assertTrue(confirmed.resolved());
        assertEquals(DemoStep.Action.WITHDRAW, confirmed.action());
        assertEquals("bread", confirmed.itemId());
        assertEquals(2, confirmed.count());
        assertFalse(recorder.currentDraft().hasUnresolvedSteps());

        store.save(recorder.currentDraft().withName("fixed"));
        assertTrue(store.exists("fixed"));
    }

    @Test
    void replaysSavedTwoBreadRoutineAcrossTenRearrangedLayoutsWithZeroUnrelatedTransfers() {
        DemoStep withdraw = DemoStep.withdraw("bread", 2, "pantry", "chest");
        DemoStep deposit = DemoStep.deposit("bread", 2, "serving barrel", "barrel");

        // 10 diverse rearranged slot/stack/decoy/container-size layouts
        int[] pantrySizes = {27, 27, 54, 27, 54, 27, 27, 54, 27, 27};
        int[][] pantryBreadSlots = {
                {0}, {26}, {53}, {7, 19}, {13}, {4, 15, 22}, {25}, {31}, {1, 2}, {18}
        };
        int[][] pantryBreadCounts = {
                {2}, {64}, {5}, {1, 1}, {3}, {1, 1, 1}, {17}, {64}, {1, 8}, {9}
        };
        int[] barrelInitialBread = {0, 4, 62, 0, 12, 1, 0, 33, 60, 5};
        int[] playerInitialBread = {0, 0, 3, 0, 1, 0, 62, 0, 0, 4};

        int completed = 0;
        for (int layout = 0; layout < 10; layout++) {
            Map<Integer, SlotEntry> pantryMap = new LinkedHashMap<>();
            int initialPantryBread = 0;
            for (int k = 0; k < pantryBreadSlots[layout].length; k++) {
                int slot = pantryBreadSlots[layout][k];
                int cnt = pantryBreadCounts[layout][k];
                pantryMap.put(slot, item(slot, true, "bread", cnt));
                initialPantryBread += cnt;
            }
            // Place decoy items in pantry slots that do not collide with bread
            int decoySlot1 = (pantryBreadSlots[layout][0] + 3) % pantrySizes[layout];
            int decoySlot2 = (pantryBreadSlots[layout][0] + 6) % pantrySizes[layout];
            if (!pantryMap.containsKey(decoySlot1)) pantryMap.put(decoySlot1, item(decoySlot1, true, "apple", 7));
            if (!pantryMap.containsKey(decoySlot2)) pantryMap.put(decoySlot2, item(decoySlot2, true, "cobblestone", 23));

            Map<Integer, SlotEntry> playerMap = new LinkedHashMap<>();
            if (playerInitialBread[layout] > 0) {
                playerMap.put(layout % 9, item(layout % 9, false, "bread", playerInitialBread[layout]));
            }
            int playerDecoy = (layout + 4) % 9;
            playerMap.put(playerDecoy, item(playerDecoy, false, "iron_ingot", 5));

            // --- Step 1: Withdraw 2 bread from pantry ---
            ContainerSlotSnapshot pantrySnapStart = snapshot(pantrySizes[layout], pantryMap, playerMap, "", 0);
            int pantryApplesBefore = pantrySnapStart.countInContainer("apple");
            int pantryCobbleBefore = pantrySnapStart.countInContainer("cobblestone");
            DemoTransferState step1State = new DemoTransferState(1, withdraw, 64);
            ContainerSlotSnapshot pantrySnapEnd = simulateDemoTransfer(step1State, pantrySnapStart);

            assertEquals(DemoTransferState.Status.SUCCEEDED, step1State.status(), "Layout " + layout + " withdraw failed: " + step1State.message());
            assertTrue(pantrySnapEnd.cursorEmpty());
            assertEquals(initialPantryBread - 2, pantrySnapEnd.countInContainer("bread"));
            assertEquals(playerInitialBread[layout] + 2, pantrySnapEnd.countInPlayer("bread"));
            assertEquals(pantryApplesBefore, pantrySnapEnd.countInContainer("apple"));
            assertEquals(pantryCobbleBefore, pantrySnapEnd.countInContainer("cobblestone"));
            assertEquals(5, pantrySnapEnd.countInPlayer("iron_ingot"));

            // Carry the exact updated player inventory over to the serving barrel!
            Map<Integer, SlotEntry> carriedPlayer = new LinkedHashMap<>();
            for (SlotEntry se : pantrySnapEnd.slots()) {
                if (!se.container() && !se.isEmpty()) {
                    carriedPlayer.put(se.windowSlot() - pantrySizes[layout],
                            item(se.windowSlot() - pantrySizes[layout], false, se.itemId(), se.count()));
                }
            }

            // --- Step 2: Deposit 2 bread into serving barrel ---
            Map<Integer, SlotEntry> barrelMap = new LinkedHashMap<>();
            if (barrelInitialBread[layout] > 0) {
                int bSlot = (layout * 5 + 2) % 27;
                barrelMap.put(bSlot, item(bSlot, true, "bread", barrelInitialBread[layout]));
            }
            int barrelDecoySlot = (layout * 5 + 11) % 27;
            if (!barrelMap.containsKey(barrelDecoySlot)) {
                barrelMap.put(barrelDecoySlot, item(barrelDecoySlot, true, "oak_planks", 16));
            }
            ContainerSlotSnapshot barrelSnapStart = snapshot(27, barrelMap, carriedPlayer, "", 0);
            int barrelPlanksBefore = barrelSnapStart.countInContainer("oak_planks");
            DemoTransferState step2State = new DemoTransferState(2, deposit, 64);
            ContainerSlotSnapshot barrelSnapEnd = simulateDemoTransfer(step2State, barrelSnapStart);

            assertEquals(DemoTransferState.Status.SUCCEEDED, step2State.status(), "Layout " + layout + " deposit failed: " + step2State.message());
            assertTrue(barrelSnapEnd.cursorEmpty());
            assertEquals(barrelInitialBread[layout] + 2, barrelSnapEnd.countInContainer("bread"));
            assertEquals(playerInitialBread[layout], barrelSnapEnd.countInPlayer("bread"));
            assertEquals(barrelPlanksBefore, barrelSnapEnd.countInContainer("oak_planks"));
            assertEquals(5, barrelSnapEnd.countInPlayer("iron_ingot"));
            completed++;
        }
        assertEquals(10, completed);
    }

    @Test
    void missingItemFailsCleanlyAndIdentifiesUnsatisfiedStep() {
        DemoStep withdraw = DemoStep.withdraw("bread", 2, "pantry", "chest");
        ContainerSlotSnapshot pantryShort = snapshot(27,
                Map.of(5, item(5, true, "bread", 1), 6, item(6, true, "apple", 9)),
                Map.of(), "", 0);
        DemoTransferState state = new DemoTransferState(1, withdraw, 64);
        DemoTransferState.Decision d = state.evaluate(pantryShort);

        assertEquals(DemoTransferState.Status.FAILED, d.status());
        assertEquals(DemoTransferState.FailureKind.MISSING_ITEM, d.failureKind());
        assertTrue(d.message().contains("Step 1"), d.message());
        assertTrue(d.message().contains("pantry"), d.message());
        assertTrue(d.message().contains("bread"), d.message());
        assertNull(d.nextClick());

        // Step 2 missing item in player inventory
        DemoStep deposit = DemoStep.deposit("bread", 2, "serving barrel", "barrel");
        ContainerSlotSnapshot playerShort = snapshot(27,
                Map.of(0, item(0, true, "oak_planks", 4)),
                Map.of(0, item(0, false, "bread", 1)), "", 0);
        DemoTransferState state2 = new DemoTransferState(2, deposit, 64);
        DemoTransferState.Decision d2 = state2.evaluate(playerShort);
        assertEquals(DemoTransferState.Status.FAILED, d2.status());
        assertEquals(DemoTransferState.FailureKind.MISSING_ITEM, d2.failureKind());
        assertTrue(d2.message().contains("Step 2"), d2.message());
        assertTrue(d2.message().contains("serving barrel"), d2.message());
    }

    @Test
    void detectsStalledClicksAndUnrelatedMutationsWithoutFalseSuccess() {
        DemoStep withdraw = DemoStep.withdraw("bread", 2, "pantry", "chest");
        ContainerSlotSnapshot start = snapshot(27,
                Map.of(0, item(0, true, "bread", 5), 1, item(1, true, "apple", 4)),
                Map.of(), "", 0);
        DemoTransferState stallState = new DemoTransferState(1, withdraw, 64, 3, 20);
        DemoTransferState.Decision first = stallState.evaluate(start);
        assertEquals(DemoTransferState.Status.IN_PROGRESS, first.status());
        stallState.recordClickDispatched(start);
        // Server never applies click -> stalls cleanly
        for (int i = 0; i < 3; i++) {
            first = stallState.evaluate(start);
        }
        assertEquals(DemoTransferState.Status.FAILED, first.status());
        assertEquals(DemoTransferState.FailureKind.STALLED, first.failureKind());

        // Unrelated item mutation -> aborts cleanly
        DemoTransferState driftState = new DemoTransferState(1, withdraw, 64);
        assertEquals(DemoTransferState.Status.IN_PROGRESS, driftState.evaluate(start).status());
        ContainerSlotSnapshot drifted = snapshot(27,
                Map.of(0, item(0, true, "bread", 5), 1, item(1, true, "apple", 3)),
                Map.of(1, item(1, false, "apple", 1)), "", 0);
        DemoTransferState.Decision driftDec = driftState.evaluate(drifted);
        assertEquals(DemoTransferState.Status.FAILED, driftDec.status());
        assertEquals(DemoTransferState.FailureKind.UNRELATED_ITEM_CHANGED, driftDec.failureKind());
    }

    @Test
    void commandParserHandlesQuotedAndMultiWordStationNames() {
        DemoCommandParser.ParsedCommand bindQuoted = DemoCommandParser.parse("bind \"serving barrel\" 12 140 -8");
        assertEquals(DemoCommandParser.Action.BIND, bindQuoted.action());
        assertEquals("serving barrel", bindQuoted.stationName());
        assertTrue(bindQuoted.hasCoords());
        assertEquals(12, bindQuoted.x());
        assertEquals(140, bindQuoted.y());
        assertEquals(-8, bindQuoted.z());

        DemoCommandParser.ParsedCommand bindUnquoted = DemoCommandParser.parse("bind serving barrel 12 140 -8");
        assertEquals("serving barrel", bindUnquoted.stationName());
        assertTrue(bindUnquoted.hasCoords());

        DemoCommandParser.ParsedCommand editStation = DemoCommandParser.parse("edit 2 station \"serving barrel\"");
        assertEquals(DemoCommandParser.Action.EDIT_FIELD, editStation.action());
        assertEquals(2, editStation.stepIndex());
        assertEquals("station", editStation.field());
        assertEquals("serving barrel", editStation.value());

        DemoCommandParser.ParsedCommand confirmFull = DemoCommandParser.parse("confirm 1 withdraw 2 bread pantry");
        assertEquals(DemoCommandParser.Action.CONFIRM, confirmFull.action());
        assertTrue(confirmFull.hasFullStepSpec());
        assertEquals(DemoStep.Action.WITHDRAW, confirmFull.stepAction());
        assertEquals(2, confirmFull.count());
        assertEquals("bread", confirmFull.itemId());
        assertEquals("pantry", confirmFull.stationName());

        DemoCommandParser.ParsedCommand startHints = DemoCommandParser.parse("start pantry \"serving barrel\"");
        assertEquals(DemoCommandParser.Action.START, startHints.action());
        assertEquals(List.of("pantry", "serving barrel"), startHints.hints());

        assertThrows(IllegalArgumentException.class, () -> DemoCommandParser.parse("save"));
        assertThrows(IllegalArgumentException.class, () -> DemoCommandParser.parse("bind \"unclosed"));
    }
}

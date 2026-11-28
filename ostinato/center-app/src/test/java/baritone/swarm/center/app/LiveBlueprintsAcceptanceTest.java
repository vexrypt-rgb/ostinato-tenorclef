package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.rts.BlockStateSpec;
import baritone.swarm.rts.Blueprint;
import baritone.swarm.rts.BlueprintRepairSession;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Ostinato: Acceptance and edge-case tests for state-preserving live blueprints:
 * <ul>
 *   <li>Pass case: 24-cell landing (logs, slabs, straight stairs) round-trips import -> save -> reload ->
 *       live verify (8 wrong-state detected &amp; highlighted) -> scoped repair -> settle -> independent verify.</li>
 *   <li>Edge case 1: Legacy name-only file loads and matches by block type regardless of orientation.</li>
 *   <li>Edge case 2: Cell with unsupported explicit property remains visibly unresolved and never marked complete.</li>
 *   <li>Edge case 3: Mid-repair live edit removes a pending target -> immediately invalidated, never restored.</li>
 * </ul>
 */
public class LiveBlueprintsAcceptanceTest {

    private static final int OX = 10, OY = -60, OZ = 20;

    /** Builds the 24-cell landing fixture: 8 logs, 8 slabs, 8 straight stairs across a 6x1x4 platform. */
    static List<OstSchematic.Cell> landingFixture24() {
        List<OstSchematic.Cell> cells = new ArrayList<>();
        // Row z=0 (6 logs): corner posts y-axis, beam x-axis
        cells.add(new OstSchematic.Cell(0, 0, 0, "oak_log[axis=y]"));
        cells.add(new OstSchematic.Cell(1, 0, 0, "oak_log[axis=x]"));
        cells.add(new OstSchematic.Cell(2, 0, 0, "oak_log[axis=x]"));
        cells.add(new OstSchematic.Cell(3, 0, 0, "oak_log[axis=x]"));
        cells.add(new OstSchematic.Cell(4, 0, 0, "oak_log[axis=x]"));
        cells.add(new OstSchematic.Cell(5, 0, 0, "oak_log[axis=y]"));
        // Row z=1 (2 z-axis side logs + 4 slabs)
        cells.add(new OstSchematic.Cell(0, 0, 1, "oak_log[axis=z]"));
        cells.add(new OstSchematic.Cell(1, 0, 1, "oak_slab[type=bottom]"));
        cells.add(new OstSchematic.Cell(2, 0, 1, "oak_slab[type=top]"));
        cells.add(new OstSchematic.Cell(3, 0, 1, "oak_slab[type=bottom]"));
        cells.add(new OstSchematic.Cell(4, 0, 1, "oak_slab[type=top]"));
        cells.add(new OstSchematic.Cell(5, 0, 1, "oak_log[axis=z]"));
        // Row z=2 (4 slabs + 2 straight stairs)
        cells.add(new OstSchematic.Cell(0, 0, 2, "oak_slab[type=top]"));
        cells.add(new OstSchematic.Cell(1, 0, 2, "oak_slab[type=bottom]"));
        cells.add(new OstSchematic.Cell(2, 0, 2, "oak_slab[type=top]"));
        cells.add(new OstSchematic.Cell(3, 0, 2, "oak_slab[type=bottom]"));
        cells.add(new OstSchematic.Cell(4, 0, 2, "oak_stairs[facing=east,half=bottom]"));
        cells.add(new OstSchematic.Cell(5, 0, 2, "oak_stairs[facing=east,half=top]"));
        // Row z=3 (6 straight stairs along the landing front)
        cells.add(new OstSchematic.Cell(0, 0, 3, "oak_stairs[facing=north,half=bottom]"));
        cells.add(new OstSchematic.Cell(1, 0, 3, "oak_stairs[facing=north,half=top]"));
        cells.add(new OstSchematic.Cell(2, 0, 3, "oak_stairs[facing=south,half=bottom]"));
        cells.add(new OstSchematic.Cell(3, 0, 3, "oak_stairs[facing=south,half=top]"));
        cells.add(new OstSchematic.Cell(4, 0, 3, "oak_stairs[facing=west,half=bottom]"));
        cells.add(new OstSchematic.Cell(5, 0, 3, "oak_stairs[facing=west,half=top]"));
        assertEquals(24, cells.size());
        return cells;
    }

    private static final class FakeWorld {
        final Map<Long, BlueprintRepairSession.CellObservation> map = new HashMap<>();

        void set(int x, int y, int z, String spec) {
            BlockStateSpec.Parsed p = BlockStateSpec.parse(spec);
            map.put(Blueprint.cellKey(x, y, z), BlueprintRepairSession.CellObservation.of(p.block(), p.properties()));
        }

        void setAir(int x, int y, int z) {
            map.put(Blueprint.cellKey(x, y, z), BlueprintRepairSession.CellObservation.air());
        }

        BlueprintRepairSession.CellObservation read(int x, int y, int z) {
            return map.getOrDefault(Blueprint.cellKey(x, y, z), BlueprintRepairSession.CellObservation.air());
        }
    }

    private static FakeWorld preplaceLandingWith8Misoriented(Blueprint bp) {
        FakeWorld w = new FakeWorld();
        for (Blueprint.Cell c : bp.cells()) {
            w.set(c.x(), c.y(), c.z(), c.spec());
        }
        // Flip 8 cells to the RIGHT material but WRONG state/orientation (3 logs, 2 slabs, 3 straight stairs):
        w.set(OX + 0, OY, OZ + 0, "oak_log[axis=x]");                     // want axis=y
        w.set(OX + 2, OY, OZ + 0, "oak_log[axis=y]");                     // want axis=x
        w.set(OX + 0, OY, OZ + 1, "oak_log[axis=x]");                     // want axis=z
        w.set(OX + 1, OY, OZ + 1, "oak_slab[type=top]");                  // want type=bottom
        w.set(OX + 2, OY, OZ + 2, "oak_slab[type=bottom]");               // want type=top
        w.set(OX + 4, OY, OZ + 2, "oak_stairs[facing=west,half=bottom]"); // want facing=east,half=bottom
        w.set(OX + 1, OY, OZ + 3, "oak_stairs[facing=north,half=bottom]");// want facing=north,half=top
        w.set(OX + 5, OY, OZ + 3, "oak_stairs[facing=east,half=bottom]"); // want facing=west,half=top
        return w;
    }

    @Test
    public void passCase24CellLandingImportSaveReloadVerifyHighlightRepairAndReverify() throws Exception {
        Path dir = Files.createTempDirectory("landing-pass");
        List<OstSchematic.Cell> fixture = landingFixture24();
        Path schemPath = dir.resolve("landing.schem");
        Files.write(schemPath, SchemImport.writeSchem(6, 1, 4, fixture));

        // 1. Import -> Save -> Reload through the normal EditorView entry point
        UiHarness ui = UiHarness.threeBots();
        ui.shell.setPrefsDir(dir);
        ui.shell.show(CenterLayout.EDITOR);
        ui.refresh();
        EditorView ed = ui.shell.editor();

        ui.type("ed:name", "landing");
        ui.type("ed:origin", OX + "," + OY + "," + OZ);
        ui.type("ed:path", schemPath.toString());
        ui.click("ed:import");
        assertEquals(24, ed.blueprint().size());
        assertTrue(ed.unsupportedDiscrepancies().isEmpty());

        ui.click("ed:save");
        Path savedOst = dir.resolve("blueprints").resolve("landing.ost");
        assertTrue(Files.isRegularFile(savedOst));

        ui.click("ed:clear");
        assertEquals(0, ed.blueprint().size());
        ui.type("ed:path", "");
        ui.click("ed:load");
        Blueprint reloaded = ed.blueprint();
        assertEquals(24, reloaded.size());
        assertEquals("oak_log[axis=x]", reloaded.at(OX + 2, OY, OZ).spec());
        assertEquals("oak_slab[type=top]", reloaded.at(OX + 2, OY, OZ + 2).spec());
        assertEquals("oak_stairs[facing=west,half=top]", reloaded.at(OX + 5, OY, OZ + 3).spec());

        // 2. Live verify against world with 8 wrong-state cells
        FakeWorld world = preplaceLandingWith8Misoriented(reloaded);
        ui.click("ed:verify");
        assertTrue(lines(ui).stream().anyMatch(l -> l.contains("blueprint verify landing")));

        BlueprintRepairSession.Comparison beforeCmp = BlueprintRepairSession.compare(reloaded, world::read);
        assertEquals(16, beforeCmp.matched().size());
        assertEquals(8, beforeCmp.discrepancies().size());
        assertEquals(8, beforeCmp.wrongState().size());
        assertFalse(beforeCmp.allMatched());

        // Write bot status with discrepancies and verify editor highlights all 8 wrong-state cells
        Path statusFile = dir.resolve("blueprints").resolve("landing.status");
        Files.writeString(statusFile, Blueprint.writeStatus(
                reloaded.version(), beforeCmp.matched(), reloaded.size(),
                BlueprintRepairSession.summaryNote(beforeCmp, false, null), beforeCmp.discrepancies()));
        ed.readStatus();
        assertEquals(16, ed.placed().size());
        assertEquals(8, ed.wrongStateDiscrepancies().size());
        assertEquals(8, ed.canvas().discrepancies().size());
        assertTrue(ui.text("ed:diff:summary").contains("8 wrong-state"));
        assertTrue(ui.text("ed:diff:text").contains("WRONG_STATE"));
        assertFalse(ui.text("ed:status").contains("done"));

        // 3. Scoped repair action via editor button
        ui.click("ed:repair");
        String repairCmd = lines(ui).get(lines(ui).size() - 1);
        assertTrue("expected scoped repair command, got: " + repairCmd,
                repairCmd.contains("blueprint repair landing cells="));
        String cellArg = repairCmd.substring(repairCmd.indexOf("cells=") + 6).trim();
        assertEquals(8, cellArg.split(";").length);

        Set<Long> requested = new LinkedHashSet<>();
        for (Blueprint.Discrepancy d : beforeCmp.wrongState()) {
            requested.add(Blueprint.cellKey(d.x(), d.y(), d.z()));
        }
        BlueprintRepairSession session = new BlueprintRepairSession(
                reloaded, requested, false, world::read, null, 3, 3);
        assertTrue(session.isActive());
        assertEquals(8, session.pendingKeys().size());

        // Tick 1 starts builder strictly scoped to the 8 misoriented cells
        BlueprintRepairSession.Action startAct = session.tick(world::read, false, false);
        assertEquals(BlueprintRepairSession.ActionKind.START_BUILDER, startAct.kind());
        assertEquals(requested, startAct.targetCells());

        // Simulate ordinary placement repairing all 8 cells
        for (Blueprint.Discrepancy d : beforeCmp.wrongState()) {
            Blueprint.Cell want = reloaded.at(d.x(), d.y(), d.z());
            world.set(d.x(), d.y(), d.z(), want.spec());
        }

        // First matching tick enters SETTLING (not immediately confirmed!)
        session.tick(world::read, true, false);
        assertTrue("must wait for neighbor updates to settle before confirming", session.isActive());
        assertEquals(0, session.confirmedKeys().size());

        // Simulate a neighbor update flipping one cell back during settling -> retries cleanly!
        world.set(OX + 5, OY, OZ + 3, "oak_stairs[facing=east,half=bottom]");
        BlueprintRepairSession.Action retryAct = session.tick(world::read, false, false);
        assertEquals(BlueprintRepairSession.ActionKind.START_BUILDER, retryAct.kind());
        assertEquals(Set.of(Blueprint.cellKey(OX + 5, OY, OZ + 3)), retryAct.targetCells());
        assertTrue(session.isActive());

        // Fix the flipped stair and tick through full settle window
        world.set(OX + 5, OY, OZ + 3, "oak_stairs[facing=west,half=top]");
        for (int i = 0; i < 6 && session.isActive(); i++) {
            session.tick(world::read, false, false);
        }
        assertEquals(BlueprintRepairSession.SessionState.SUCCEEDED, session.state());
        assertEquals(8, session.confirmedKeys().size());

        // 4. Independent verification: all 24 cells match expected block + state
        BlueprintRepairSession.Comparison afterCmp = BlueprintRepairSession.compare(reloaded, world::read);
        assertTrue(afterCmp.allMatched());
        assertEquals(24, afterCmp.matched().size());
        assertTrue(afterCmp.discrepancies().isEmpty());

        Files.writeString(statusFile, Blueprint.writeStatus(
                reloaded.version(), afterCmp.matched(), reloaded.size(), "done", afterCmp.discrepancies()));
        ed.readStatus();
        assertEquals(24, ed.placed().size());
        assertTrue(ed.discrepancies().isEmpty());
        assertTrue(ui.text("ed:status").contains("24/24 placed"));
    }

    @Test
    public void edgeCase1LegacyNameOnlyFileLoadsAndMatchesByBlockType() {
        String legacyOst = "ost1\n3 1 1\n"
                + "# blueprint legacy origin 10 -60 20 version 4\n"
                + "0 0 0 oak_log\n"
                + "1 0 0 oak_slab\n"
                + "2 0 0 oak_stairs\n";
        Blueprint bp = Blueprint.read("legacy", legacyOst);
        assertEquals(3, bp.size());
        assertFalse(bp.at(10, -60, 20).hasExplicitProperties());
        assertFalse(bp.at(11, -60, 20).hasExplicitProperties());
        assertFalse(bp.at(12, -60, 20).hasExplicitProperties());
        // Serialization keeps legacy name-only lines without brackets
        assertFalse(bp.write().contains("["));

        // World holds non-default orientations of each block -> still matches by block type!
        FakeWorld world = new FakeWorld();
        world.set(10, -60, 20, "oak_log[axis=x]");
        world.set(11, -60, 20, "oak_slab[type=top]");
        world.set(12, -60, 20, "oak_stairs[facing=west,half=top,shape=inner_left]");

        BlueprintRepairSession.Comparison cmp = BlueprintRepairSession.compare(bp, world::read);
        assertTrue(cmp.allMatched());
        assertEquals(3, cmp.matched().size());
        assertTrue(cmp.discrepancies().isEmpty());
    }

    @Test
    public void edgeCase2UnsupportedExplicitPropertyRemainsVisiblyUnresolvedNeverComplete() throws Exception {
        Path dir = Files.createTempDirectory("landing-unsup");
        UiHarness ui = UiHarness.threeBots();
        ui.shell.setPrefsDir(dir);
        ui.shell.show(CenterLayout.EDITOR);
        ui.refresh();
        EditorView ed = ui.shell.editor();

        // Mix supported and unsupported explicit properties
        List<OstSchematic.Cell> cells = List.of(
                new OstSchematic.Cell(0, 0, 0, "oak_log[axis=x]"),
                new OstSchematic.Cell(1, 0, 0, "oak_stairs[facing=east,half=bottom,waterlogged=true]"),
                new OstSchematic.Cell(2, 0, 0, "oak_stairs[facing=east,half=bottom,shape=inner_left]"),
                new OstSchematic.Cell(3, 0, 0, "oak_slab[type=double]"),
                new OstSchematic.Cell(4, 0, 0, "furnace[facing=north,lit=true]"));
        Path schem = dir.resolve("unsup.schem");
        Files.write(schem, SchemImport.writeSchem(5, 1, 1, cells));

        ui.type("ed:name", "unsup");
        ui.type("ed:origin", "0,-60,0");
        ui.type("ed:path", schem.toString());
        ui.click("ed:import");
        assertEquals(5, ed.blueprint().size());

        // Editor immediately surfaces all 4 unsupported cells before any bot status arrives
        assertEquals(4, ed.unsupportedDiscrepancies().size());
        assertTrue(ui.text("ed:diff:summary").contains("4 unsupported"));
        assertTrue(ui.text("ed:diff:text").contains("UNSUPPORTED"));
        assertTrue(ui.text("ed:status").contains("4 unsupported"));

        // Even if the world has identical blocks or a bogus status claims all 5 placed & "done",
        // neither compare() nor EditorView can ever mark unsupported cells complete!
        FakeWorld world = new FakeWorld();
        world.set(0, -60, 0, "oak_log[axis=x]");
        world.set(1, -60, 0, "oak_stairs[facing=east,half=bottom,waterlogged=true]");
        world.set(2, -60, 0, "oak_stairs[facing=east,half=bottom,shape=inner_left]");
        world.set(3, -60, 0, "oak_slab[type=double]");
        world.set(4, -60, 0, "furnace[facing=north,lit=true]");

        BlueprintRepairSession.Comparison cmp = BlueprintRepairSession.compare(ed.blueprint(), world::read);
        assertEquals(1, cmp.matched().size());
        assertEquals(4, cmp.unsupported().size());
        assertFalse(cmp.allMatched());
        assertNotEquals("done", BlueprintRepairSession.summaryNote(cmp, false, null));

        ed.setLive(true);
        ed.writeNow();
        Path statusFile = dir.resolve("blueprints").resolve("unsup.status");
        Files.writeString(statusFile, Blueprint.writeStatus(
                ed.blueprint().version(),
                List.of(new int[]{0, -60, 0}, new int[]{1, -60, 0}, new int[]{2, -60, 0},
                        new int[]{3, -60, 0}, new int[]{4, -60, 0}),
                5, "done"));
        ed.readStatus();
        assertEquals(1, ed.placed().size());
        assertEquals(4, ed.unsupportedDiscrepancies().size());
        assertFalse("editor must never claim done with unsupported cells: " + ui.text("ed:status"),
                ui.text("ed:status").contains("done"));
    }

    @Test
    public void edgeCase3MidRepairLiveEditRemovesPendingTargetAndBotDoesNotRestoreIt() {
        Blueprint bp = SchemImport.toBlueprint("landing", OstSchematic.of(landingFixture24()), new int[]{OX, OY, OZ});
        FakeWorld world = preplaceLandingWith8Misoriented(bp);

        BlueprintRepairSession.Comparison cmp = BlueprintRepairSession.compare(bp, world::read);
        assertEquals(8, cmp.wrongState().size());
        Set<Long> requested = new LinkedHashSet<>();
        for (Blueprint.Discrepancy d : cmp.wrongState()) {
            requested.add(Blueprint.cellKey(d.x(), d.y(), d.z()));
        }

        BlueprintRepairSession session = new BlueprintRepairSession(
                bp, requested, false, world::read, null, 2, 3);
        BlueprintRepairSession.Action initial = session.tick(world::read, false, false);
        assertEquals(BlueprintRepairSession.ActionKind.START_BUILDER, initial.kind());
        assertEquals(8, initial.targetCells().size());

        // Mid-repair: bot breaks pending target (OX+2, OY, OZ+2) to air, and user erases it in live edit!
        int removedX = OX + 2, removedY = OY, removedZ = OZ + 2;
        long removedKey = Blueprint.cellKey(removedX, removedY, removedZ);
        world.setAir(removedX, removedY, removedZ);
        assertTrue(bp.erase(removedX, removedY, removedZ));
        assertEquals(23, bp.size());

        // Immediate invalidation on reconcile!
        assertTrue(session.reconcile(bp));
        assertFalse("removed cell must be dropped immediately", session.pendingKeys().contains(removedKey));
        assertEquals(7, session.pendingKeys().size());

        // Next tick immediately restarts builder with ONLY the 7 remaining targets (never restoring removedKey)
        BlueprintRepairSession.Action narrowed = session.tick(world::read, true, false);
        assertEquals(BlueprintRepairSession.ActionKind.START_BUILDER, narrowed.kind());
        assertEquals(7, narrowed.targetCells().size());
        assertFalse(narrowed.targetCells().contains(removedKey));

        // Bot repairs only the 7 remaining targets and leaves removed cell air
        for (long key : narrowed.targetCells()) {
            for (Blueprint.Cell c : bp.cells()) {
                if (Blueprint.cellKey(c.x(), c.y(), c.z()) == key) {
                    world.set(c.x(), c.y(), c.z(), c.spec());
                }
            }
        }
        for (int i = 0; i < 5 && session.isActive(); i++) {
            session.tick(world::read, false, false);
        }
        assertEquals(BlueprintRepairSession.SessionState.SUCCEEDED, session.state());
        assertTrue("removed cell must stay air", world.read(removedX, removedY, removedZ).isAir());

        BlueprintRepairSession.Comparison finalCmp = BlueprintRepairSession.compare(bp, world::read);
        assertTrue(finalCmp.allMatched());
        assertEquals(23, finalCmp.matched().size());
    }

    @Test
    public void boundedRetriesAndCancellationStopCleanly() {
        Blueprint bp = new Blueprint("retrytest");
        bp.paint(0, 64, 0, "oak_log[axis=x]");
        FakeWorld world = new FakeWorld();
        world.set(0, 64, 0, "oak_log[axis=y]");

        BlueprintRepairSession session = new BlueprintRepairSession(
                bp, Set.of(Blueprint.cellKey(0, 64, 0)), false, world::read, null, 2, 2);
        // Attempt 1
        assertEquals(BlueprintRepairSession.ActionKind.START_BUILDER,
                session.tick(world::read, false, false).kind());
        // Builder exits without fixing -> attempt 2
        assertEquals(BlueprintRepairSession.ActionKind.START_BUILDER,
                session.tick(world::read, false, false).kind());
        // Builder exits again -> bounded retries exhausted -> FAILED
        session.tick(world::read, false, false);
        assertEquals(BlueprintRepairSession.SessionState.FAILED, session.state());
        assertEquals(1, session.failedDiscrepancies().size());
        assertEquals(Blueprint.DiscrepancyKind.REPAIR_FAILED, session.failedDiscrepancies().get(0).kind());

        // Cancellation stops immediately
        BlueprintRepairSession cancelSession = new BlueprintRepairSession(
                bp, Set.of(Blueprint.cellKey(0, 64, 0)), false, world::read, null, 2, 3);
        cancelSession.tick(world::read, false, false);
        cancelSession.cancel();
        assertEquals(BlueprintRepairSession.SessionState.CANCELLED, cancelSession.state());
        assertEquals(BlueprintRepairSession.ActionKind.STOP_BUILDER,
                cancelSession.tick(world::read, true, false).kind());
    }

    private static List<String> lines(UiHarness ui) {
        List<String> out = new ArrayList<>();
        for (CenterRequest r : ui.sent()) {
            out.add(ui.port.lineFor(r));
        }
        return out;
    }
}

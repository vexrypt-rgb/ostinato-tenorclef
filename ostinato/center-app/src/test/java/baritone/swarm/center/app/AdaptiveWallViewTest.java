package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JTable;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: end-to-end headless UI tests for the adaptive {@link CenterShell} / {@link WallView} redesign across
 * {@link DensityPolicy.Tier#CARDS}, {@link DensityPolicy.Tier#ROSTER}, and {@link DensityPolicy.Tier#CLUSTER},
 * viewport scaling, filter/batch controls, FocusPanel quick commands, and all 4 WCAG {@link CenterTheme} palettes.
 */
public class AdaptiveWallViewTest {

    @Test
    public void transitionsAcrossCardsRosterAndClusterByViewportAndFleetSize() {
        // 8 bots: CARDS on 1280x860, switches to ROSTER on compact 680x460 viewport
        UiHarness ui = UiHarness.many(8, CenterTheme.DEFAULT_DARK);
        ui.refresh();
        assertEquals(DensityPolicy.Tier.CARDS, ui.shell.activeWallTier());
        assertTrue(ui.has("tile:Copper"));
        assertEquals("View: Cards (auto)", ui.text("header.tier"));

        resizeAndLayout(ui, 680, 460);
        assertEquals(DensityPolicy.Tier.ROSTER, ui.shell.activeWallTier());
        assertFalse("tiles dropped in Roster for O(1) overhead", ui.has("tile:Copper"));
        assertEquals("View: Roster (auto)", ui.text("header.tier"));

        // 24 bots on 1280x860 -> ROSTER; 60 bots on 1280x860 -> CLUSTER
        UiHarness mid = UiHarness.many(24, CenterTheme.DEFAULT_DARK).refresh();
        assertEquals(DensityPolicy.Tier.ROSTER, mid.shell.activeWallTier());
        assertFalse(mid.has("tile:Copper"));
        JTable table = (JTable) mid.find("wall.roster.table");
        assertNotNull(table);
        assertEquals(24, table.getRowCount());

        UiHarness big = UiHarness.many(60, CenterTheme.DEFAULT_DARK).refresh();
        assertEquals(DensityPolicy.Tier.CLUSTER, big.shell.activeWallTier());
        assertFalse(big.has("tile:Copper"));
        assertNotNull(big.find("cluster.heatmap"));
        assertNotNull(big.find("cluster.exceptions.cancelAll"));

        // Manual header overrides work regardless of fleet size
        big.click("header.density.roster");
        assertEquals(DensityPolicy.Tier.ROSTER, big.shell.activeWallTier());
        assertEquals("View: Roster (manual)", big.text("header.tier"));
        big.click("header.density.cards");
        assertEquals(DensityPolicy.Tier.CARDS, big.shell.activeWallTier());
        assertTrue(big.has("tile:Copper"));
        big.click("header.density.auto");
        assertEquals(DensityPolicy.Tier.CLUSTER, big.shell.activeWallTier());
    }

    @Test
    public void filterBarBatchDispatchAndTwoClickDespawnWorkCleanly() {
        AtomicLong now = new AtomicLong(1_000_000);
        FakeSwarm swarm = FakeSwarm.many(6, now::get);
        swarm.bot("Copper").lease("v:o:10,64,10", 1, "active", 30_000);
        swarm.coordinator(new CoordinatorFeed("Copper", now.get(), 1, "active", "world",
                List.of(new CoordinatorFeed.Lease("l1", "v:o:10,64,10", "vein", 'o',
                        new int[]{10, 64, 10, 12, 65, 12}, "Copper", 1, "granted", "mine", "running", "confirmed", "")),
                List.of(), 0));
        UiHarness ui = new UiHarness(now, swarm, CenterTheme.DEFAULT_DARK, CenterLayout.WALL).refresh();

        assertEquals("Coord active · 1 lease", ui.text("header.coordinator"));
        assertEquals("Coord active · 1 lease", ui.text("filter.coord"));
        assertTrue(ui.text("tile:Copper:lease").contains("v:o:10,64,10"));
        assertTrue(ui.text("filter.all").contains("(6)"));
        assertTrue(ui.text("filter.leased").contains("(1)"));

        // Filter by Leased pill -> only Copper shown
        ui.click("filter.leased");
        assertTrue(ui.has("tile:Copper"));
        assertFalse(ui.has("tile:Azalea"));

        // Filter back to All and select alerts
        ui.click("filter.all");
        ui.click("batch.select.alerts");
        assertFalse(ui.shell.wall().multiSelected().isEmpty());
        int alertSelected = ui.shell.wall().multiSelected().size();
        assertEquals(alertSelected + " selected", ui.text("batch.selected"));

        // Batch goto
        ui.type("batch.arg", "100 64 200").click("batch.btn.goto");
        assertEquals(alertSelected, ui.sent().size());
        assertEquals("goto", ui.sent().get(0).formId());

        // Batch @swarm acquire routes to coordinator
        int beforeAcquire = ui.sent().size();
        ui.type("batch.arg", "oak_log 8").click("batch.btn.acquire");
        assertEquals(beforeAcquire + 1, ui.sent().size());
        assertEquals("@swarm acquire oak_log 8", ui.sent().get(beforeAcquire).line());

        // Batch stop
        int beforeStop = ui.sent().size();
        ui.click("batch.btn.stop");
        assertEquals(beforeStop + alertSelected, ui.sent().size());

        // Batch despawn requires 2 clicks within 3s (inline confirmation)
        int beforeDespawn = ui.sent().size();
        ui.click("batch.btn.despawn");
        assertEquals("first click must arm only", beforeDespawn, ui.sent().size());
        assertTrue(ui.text("batch.btn.despawn").startsWith("Confirm despawn"));
        ui.click("batch.btn.despawn");
        assertEquals(beforeDespawn + alertSelected, ui.sent().size());

        // Clear selection
        ui.click("batch.select.clear");
        assertEquals("0 selected", ui.text("batch.selected"));

        // All new action buttons must never take focus
        for (String name : List.of("header.density.auto", "header.density.cards", "header.density.roster",
                "header.density.cluster", "filter.all", "filter.alerts", "batch.select.filtered",
                "batch.select.alerts", "batch.select.clear", "batch.btn.goto", "batch.btn.mine",
                "batch.btn.acquire", "batch.btn.cancel", "batch.btn.stop", "batch.btn.despawn")) {
            assertFalse(name + " must not be focusable", ui.find(name).isFocusable());
        }
    }

    @Test
    public void rosterSortsExceptionsFirstAndSupportsInspectAndCancel() {
        AtomicLong now = new AtomicLong(1_000_000);
        FakeSwarm swarm = FakeSwarm.many(20, now::get);
        swarm.bot("Bot19").age(20_000); // BAD (offline)
        swarm.bot("Bot18").health(3);   // WARN (low health)
        UiHarness ui = new UiHarness(now, swarm, CenterTheme.DEFAULT_DARK, CenterLayout.WALL).refresh();

        assertEquals(DensityPolicy.Tier.ROSTER, ui.shell.activeWallTier());
        RosterTableView roster = ui.shell.wall().rosterView();
        JTable table = roster.table();
        assertEquals(20, table.getRowCount());
        assertFalse(table.isFocusable());

        // Row 0 must be BAD (✖), followed by WARN (▲), then OK (●), then IDLE (○)
        int prevRank = -1;
        for (int r = 0; r < table.getRowCount(); r++) {
            String statusBadge = String.valueOf(table.getValueAt(r, RosterTableView.COL_STATUS));
            int rank = statusBadge.startsWith(TileStatus.Level.BAD.glyph) ? 0
                    : statusBadge.startsWith(TileStatus.Level.WARN.glyph) ? 1
                    : statusBadge.startsWith(TileStatus.Level.OK.glyph) ? 2 : 3;
            assertTrue("rows must be sorted exceptions-first at row " + r + ": " + statusBadge, rank >= prevRank);
            prevRank = rank;
        }
        String row0Bot = roster.botAtRow(0);
        assertTrue("row 0 must be a BAD bot",
                String.valueOf(table.getValueAt(0, RosterTableView.COL_STATUS)).startsWith(TileStatus.Level.BAD.glyph));
        assertEquals("Birch", row0Bot); // failed execution (BAD) sorts alphabetically ahead of Bot19 (offline BAD)

        // Click checkbox in row 0 -> multi-selects row0Bot
        Rectangle checkCell = table.getCellRect(0, RosterTableView.COL_SELECT, true);
        clickAt(table, checkCell.x + 6, checkCell.y + 6, 0, 1);
        assertTrue(ui.shell.wall().multiSelected().contains(row0Bot));

        // Click bot name cell in row 1 -> opens FocusPanel + quick commands
        String row1Bot = roster.botAtRow(1);
        Rectangle nameCell = table.getCellRect(1, RosterTableView.COL_BOT, true);
        clickAt(table, nameCell.x + 6, nameCell.y + 6, 0, 1);
        assertTrue(ui.find("focus").isVisible());
        assertEquals(row1Bot, ui.text("focus:title"));

        // FocusPanel quick goto & 2-click despawn & Open on Map
        ui.type("focus:field:arg", "12 64 -8").click("focus:button:goto");
        assertEquals(1, ui.sent().size());
        assertEquals(row1Bot, ui.sent().get(0).bot());
        assertEquals("goto", ui.sent().get(0).formId());

        ui.click("focus:button:despawn");
        assertEquals(1, ui.sent().size());
        assertEquals("Confirm despawn", ui.text("focus:button:despawn"));
        ui.click("focus:button:despawn");
        assertEquals(2, ui.sent().size());
        assertEquals("despawn", ui.sent().get(1).formId());

        // Single-click row Cancel in Roster
        Rectangle cancelCell = table.getCellRect(0, RosterTableView.COL_CANCEL, true);
        clickAt(table, cancelCell.x + 6, cancelCell.y + 6, 0, 1);
        assertEquals(3, ui.sent().size());

        // FocusPanel Open on Map switches to MAP layout
        ui.click("focus:button:map");
        assertEquals(CenterLayout.MAP, ui.shell.shown());
    }

    @Test
    public void clusterHeatmapScalesTo200BotsWithExceptionRailLeaseGroupsAndDragSelect() {
        AtomicLong now = new AtomicLong(1_000_000);
        FakeSwarm swarm = FakeSwarm.many(200, now::get);
        swarm.bot("Copper").lease("vein:iron_ore", 1, "active", 25_000);
        swarm.bot("Azalea").lease("vein:iron_ore", 1, "active", 25_000);
        swarm.coordinator(new CoordinatorFeed("Copper", now.get(), 1, "active", "world",
                List.of(
                        new CoordinatorFeed.Lease("l1", "iron_ore", "vein", 'o',
                                new int[]{0, 60, 0, 2, 62, 2}, "Copper", 1, "granted", "mine", "running", "confirmed", ""),
                        new CoordinatorFeed.Lease("l2", "iron_ore", "vein", 'o',
                                new int[]{4, 60, 4, 6, 62, 6}, "Azalea", 1, "granted", "mine", "running", "confirmed", "")),
                List.of(), 0));
        UiHarness ui = new UiHarness(now, swarm, CenterTheme.DEFAULT_DARK, CenterLayout.WALL).refresh();

        assertEquals(DensityPolicy.Tier.CLUSTER, ui.shell.activeWallTier());
        assertFalse("no TileView panels in Cluster mode", ui.has("tile:Copper"));
        ClusterHeatmapView cluster = ui.shell.wall().clusterView();
        ClusterHeatmapView.HeatmapCanvas canvas = cluster.canvas();
        assertEquals(200, canvas.botCount());
        assertFalse(canvas.isFocusable());

        // Hover tooltip works for cell 0
        Rectangle c0 = canvas.cellBounds(0);
        String tip = canvas.getToolTipText(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, now.get(), 0,
                c0.x + 4, c0.y + 4, 0, false));
        assertNotNull(tip);
        assertTrue(tip, tip.contains("—"));

        // Click cell 0 -> opens FocusPanel; Shift-click cell 4 -> range selects 5 bots
        clickMousePressRelease(canvas, c0.x + 4, c0.y + 4, 0);
        assertTrue(ui.find("focus").isVisible());
        Rectangle c4 = canvas.cellBounds(4);
        clickMousePressRelease(canvas, c4.x + 4, c4.y + 4, InputEvent.SHIFT_DOWN_MASK);
        assertEquals(5, ui.shell.wall().multiSelected().size());

        // Drag selection across cells 0..2 replaces multi-selection
        Rectangle c2 = canvas.cellBounds(2);
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, now.get(), 0,
                c0.x + 2, c0.y + 2, 1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, now.get(),
                InputEvent.BUTTON1_DOWN_MASK, c2.x + c2.width - 2, c2.y + c2.height - 2, 0, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, now.get(), 0,
                c2.x + c2.width - 2, c2.y + c2.height - 2, 1, false, MouseEvent.BUTTON1));
        assertEquals(3, ui.shell.wall().multiSelected().size());

        // Select Group button on Lease Rollup Strip selects the leased group
        Component selectGroup0 = ui.find("cluster.lease.select.0");
        assertNotNull(selectGroup0);
        ((AbstractButton) selectGroup0).doClick();
        assertFalse(ui.shell.wall().multiSelected().isEmpty());

        // Cancel All Exceptions button dispatches cancels for alerting bots
        ui.click("cluster.exceptions.cancelAll");
        assertFalse("Cancel All Exceptions must dispatch commands", ui.sent().isEmpty());
    }

    @Test
    public void allFourCoreThemesRenderAllThreeTiersAndPreserveAdaptiveState() throws Exception {
        Map<String, CenterTheme> themes = new LinkedHashMap<>();
        themes.put("dark", CenterTheme.DEFAULT_DARK);
        themes.put("light", CenterTheme.LIGHT);
        themes.put("high-contrast", CenterTheme.HIGH_CONTRAST);
        themes.put("colorblind", CenterTheme.COLORBLIND_SAFE);

        for (Map.Entry<String, CenterTheme> e : themes.entrySet()) {
            // Render ROSTER (24 bots) and CLUSTER (60 bots) in every WCAG theme
            UiHarness rosterUi = UiHarness.many(24, e.getValue()).refresh();
            assertEquals(DensityPolicy.Tier.ROSTER, rosterUi.shell.activeWallTier());
            BufferedImage rosterImg = rosterUi.screenshot("adaptive-roster-" + e.getKey());
            assertEquals(e.getValue().background(), rosterImg.getRGB(UiHarness.WIDTH - 10, 400) & 0xFFFFFF);

            UiHarness clusterUi = UiHarness.many(60, e.getValue()).refresh();
            assertEquals(DensityPolicy.Tier.CLUSTER, clusterUi.shell.activeWallTier());
            BufferedImage clusterImg = clusterUi.screenshot("adaptive-cluster-" + e.getKey());
            assertEquals(e.getValue().background(), clusterImg.getRGB(UiHarness.WIDTH - 10, 400) & 0xFFFFFF);
        }

        // Theme rebuild preserves filter, search query, multi-selection, and density override
        UiHarness ui = UiHarness.many(24, CenterTheme.DEFAULT_DARK).refresh();
        ui.click("header.density.cluster");
        ui.click("filter.alerts");
        ui.type("filter.search", "Bot");
        ui.click("batch.select.filtered");
        var beforeSelected = ui.shell.wall().multiSelected();
        assertFalse(beforeSelected.isEmpty());

        ui.click("header.theme.high-contrast");
        assertEquals("high-contrast", ui.shell.themeName());
        assertEquals(DensityPolicy.Override.CLUSTER, ui.shell.densityOverride());
        assertEquals(DensityPolicy.Tier.CLUSTER, ui.shell.activeWallTier());
        assertEquals(FleetFilterBar.Filter.ALERTS, ui.shell.wall().activeFilter());
        assertEquals("Bot", ui.shell.wall().searchQuery());
        assertEquals(beforeSelected, ui.shell.wall().multiSelected());
    }

    private static void resizeAndLayout(UiHarness ui, int width, int height) {
        ui.shell.component().setSize(width, height);
        UiHarness.layout(ui.shell.component());
        UiHarness.layout(ui.shell.component());
    }

    private static void clickAt(Component c, int x, int y, int mods, int clicks) {
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), mods, x, y, clicks,
                false, MouseEvent.BUTTON1));
    }

    private static void clickMousePressRelease(Component c, int x, int y, int mods) {
        long t = System.currentTimeMillis();
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, t, mods, x, y, 1, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, t, mods, x, y, 1, false, MouseEvent.BUTTON1));
    }
}

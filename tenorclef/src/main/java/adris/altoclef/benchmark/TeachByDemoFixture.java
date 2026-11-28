package adris.altoclef.benchmark;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;

//#if MC >= 260000
//$$ import adris.altoclef.compose.CompositionRun;
//$$ import adris.altoclef.compose.DemoRoutine;
//$$ import adris.altoclef.compose.DemoService;
//$$ import adris.altoclef.compose.DemoStep;
//$$ import adris.altoclef.headless.BotClientBridge;
//$$ import adris.altoclef.util.helpers.StorageHelper;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.core.BlockPos;
//$$ import net.minecraft.core.Direction;
//$$ import net.minecraft.server.MinecraftServer;
//$$ import net.minecraft.server.level.ServerLevel;
//$$ import net.minecraft.server.level.ServerPlayer;
//$$ import net.minecraft.world.Container;
//$$ import net.minecraft.world.Difficulty;
//$$ import net.minecraft.world.InteractionHand;
//$$ import net.minecraft.world.entity.Entity;
//$$ import net.minecraft.world.entity.item.ItemEntity;
//$$ import net.minecraft.world.inventory.ChestMenu;
//$$ import net.minecraft.world.inventory.ContainerInput;
//$$ import net.minecraft.world.item.Item;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.item.Items;
//$$ import net.minecraft.world.level.block.BarrelBlock;
//$$ import net.minecraft.world.level.block.Blocks;
//$$ import net.minecraft.world.level.block.ChestBlock;
//$$ import net.minecraft.world.level.block.entity.BlockEntity;
//$$ import net.minecraft.world.phys.AABB;
//$$ import net.minecraft.world.phys.BlockHitResult;
//$$ import net.minecraft.world.phys.Vec3;
//$$
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//$$ import java.util.ArrayList;
//$$ import java.util.List;
//#endif

/**
 * Live interactive Minecraft Java 26.3 Fabric acceptance fixture for teach-by-demonstration:
 * "Watch me take two loaves from the pantry and put them in the serving barrel. Save that routine and run it at my other inn."
 * <p>
 * Exercises the entire workflow through the normal player entry point ({@code @teach ...}):
 * <ol>
 *     <li>Teach the 2-bread withdraw/deposit routine once at Site A via ordinary client container interactions.</li>
 *     <li>Edit the 2-step summary to name "pantry" and "serving barrel", then save.</li>
 *     <li>Replay on 10 rearranged Site B layouts (different container positions, different slot orders &amp; stack sizes,
 *     different starting player positions, decoy items) using ONLY station rebinding.</li>
 *     <li>Edge case 1: Missing-item trial -> fails cleanly and identifies the unsatisfied step.</li>
 *     <li>Edge case 2: Ambiguous demonstration step -> stays unresolved until explicitly edited/confirmed.</li>
 * </ol>
 */
public final class TeachByDemoFixture {

    private static volatile Thread running;

    private TeachByDemoFixture() {
    }

    public static String start(AltoClef mod) {
        return start(mod, Boolean.getBoolean("tenorclef.teachFixture.exit"));
    }

    public static String start(AltoClef mod, boolean exitOnFinish) {
        //#if MC < 260000
        Debug.logWarning("TEACH_FIXTURE is only supported on Minecraft 26.3 interactive Fabric client.");
        return "TEACH_FIXTURE unsupported on this MC version";
        //#else
        //$$ if (running != null && running.isAlive()) {
        //$$     return "TEACH_FIXTURE already running";
        //$$ }
        //$$ Minecraft mc = Minecraft.getInstance();
        //$$ if (mc == null || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
        //$$     return "TEACH_FIXTURE requires an interactive singleplayer world";
        //$$ }
        //$$ Thread t = new Thread(() -> {
        //$$     try {
        //$$         runAll(mc, mod);
        //$$     } catch (Throwable e) {
        //$$         Debug.logHarness("TEACH_FIXTURE FATAL: " + e);
        //$$         e.printStackTrace();
        //$$     } finally {
        //$$         running = null;
        //$$         if (exitOnFinish || Boolean.getBoolean("tenorclef.teachFixture.exit")) {
        //$$             Debug.logHarness("TEACH_FIXTURE exiting client as requested");
        //$$             mc.execute(mc::stop);
        //$$         }
        //$$     }
        //$$ }, "TeachByDemoFixture");
        //$$ t.setDaemon(true);
        //$$ running = t;
        //$$ t.start();
        //$$ return "TEACH_FIXTURE started";
        //#endif
    }

    //#if MC >= 260000
    //$$ private record SlotSpec(int slot, Item item, int count) {}
    //$$
    //$$ private record LayoutSpec(String name,
    //$$                           BlockPos pantryPos, boolean pantryIsBarrel, Direction pantryFacing,
    //$$                           BlockPos barrelPos, boolean barrelIsBarrel, Direction barrelFacing,
    //$$                           double playerX, double playerZ,
    //$$                           List<SlotSpec> pantrySlots,
    //$$                           List<SlotSpec> barrelSlots,
    //$$                           List<SlotSpec> playerSlots) {}
    //$$
    //$$ private record CaseReport(String name, boolean passed, String detail) {}
    //$$
    //$$ private static final int ARENA_X = 0;
    //$$ private static final int ARENA_Y = 140;
    //$$ private static final int ARENA_Z = 72;
    //$$ private static final BlockPos SITE_A_PANTRY = new BlockPos(ARENA_X - 4, ARENA_Y, ARENA_Z - 4);
    //$$ private static final BlockPos SITE_A_BARREL = new BlockPos(ARENA_X + 4, ARENA_Y, ARENA_Z - 4);
    //$$
    //$$ private static void runAll(Minecraft mc, AltoClef mod) throws Exception {
    //$$     MinecraftServer server = mc.getSingleplayerServer();
    //$$     if (server == null) {
    //$$         Debug.logHarness("TEACH_FIXTURE no integrated server");
    //$$         return;
    //$$     }
    //$$
    //$$     buildPlatform(server);
    //$$     Thread.sleep(600L);
    //$$
    //$$     List<CaseReport> reports = new ArrayList<>();
    //$$
    //$$     // Clean any previous demo files/bindings before teaching.
    //$$     mc.submit(() -> {
    //$$         mod.cancelUserTask();
    //$$         StorageHelper.closeScreen();
    //$$         DemoService.INSTANCE.bindings().clear();
    //$$         try {
    //$$             DemoService.INSTANCE.store().delete("inn_bread");
    //$$             DemoService.INSTANCE.store().delete("ambiguous_should_fail");
    //$$             DemoService.INSTANCE.store().delete("ambiguous_fixed");
    //$$         } catch (Exception ignored) {}
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     // =========================================================================
    //$$     // PART 1: Teach the 2-bread withdraw/deposit routine ONCE at Site A
    //$$     // =========================================================================
    //$$     CaseReport teachReport = runSiteADemonstration(mc, server, mod);
    //$$     reports.add(teachReport);
    //$$     Debug.logHarness("TEACH_FIXTURE CASE " + teachReport.name() + " => " + (teachReport.passed() ? "PASS" : "FAIL") + " | " + teachReport.detail());
    //$$
    //$$     // =========================================================================
    //$$     // PART 2: Replay on 10 rearranged Site B layouts using ONLY station rebinding
    //$$     // =========================================================================
    //$$     List<LayoutSpec> layouts = buildTenRearrangedLayouts();
    //$$     int layoutsPassed = 0;
    //$$     int unrelatedTransfers = 0;
    //$$     int falseSuccesses = 0;
    //$$     for (LayoutSpec layout : layouts) {
    //$$         CaseReport r = runReplayLayout(mc, server, mod, layout);
    //$$         reports.add(r);
    //$$         if (r.passed()) layoutsPassed++;
    //$$         if (r.detail().contains("UNRELATED_DRIFT")) unrelatedTransfers++;
    //$$         if (r.detail().contains("FALSE_SUCCESS")) falseSuccesses++;
    //$$         Debug.logHarness("TEACH_FIXTURE CASE " + r.name() + " => " + (r.passed() ? "PASS" : "FAIL") + " | " + r.detail());
    //$$     }
    //$$
    //$$     // =========================================================================
    //$$     // PART 3: Edge Case 1 — Missing-item trial
    //$$     // =========================================================================
    //$$     CaseReport missingReport = runMissingItemTrial(mc, server, mod);
    //$$     reports.add(missingReport);
    //$$     if (missingReport.detail().contains("FALSE_SUCCESS")) falseSuccesses++;
    //$$     Debug.logHarness("TEACH_FIXTURE CASE " + missingReport.name() + " => " + (missingReport.passed() ? "PASS" : "FAIL") + " | " + missingReport.detail());
    //$$
    //$$     // =========================================================================
    //$$     // PART 4: Edge Case 2 — Ambiguous demonstration step
    //$$     // =========================================================================
    //$$     CaseReport ambiguousReport = runAmbiguousDemoTrial(mc, server, mod);
    //$$     reports.add(ambiguousReport);
    //$$     Debug.logHarness("TEACH_FIXTURE CASE " + ambiguousReport.name() + " => " + (ambiguousReport.passed() ? "PASS" : "FAIL") + " | " + ambiguousReport.detail());
    //$$
    //$$     int totalPassed = 0;
    //$$     StringBuilder reportText = new StringBuilder();
    //$$     for (CaseReport r : reports) {
    //$$         if (r.passed()) totalPassed++;
    //$$         String line = "TEACH_FIXTURE CASE " + r.name() + " => " + (r.passed() ? "PASS" : "FAIL") + " | " + r.detail();
    //$$         reportText.append(line).append('\n');
    //$$     }
    //$$     String summary = "TEACH_FIXTURE SUMMARY passed=" + totalPassed + "/" + reports.size()
    //$$             + " layouts=" + layoutsPassed + "/10"
    //$$             + " unrelatedTransfers=" + unrelatedTransfers
    //$$             + " falseSuccesses=" + falseSuccesses;
    //$$     Debug.logHarness(summary);
    //$$     reportText.append(summary).append('\n');
    //$$
    //$$     Path outPath = mc.gameDirectory.toPath().resolve("teach-by-demo-fixture-report.txt");
    //$$     Files.writeString(outPath, reportText.toString());
    //$$     Debug.logHarness("TEACH_FIXTURE wrote report: " + outPath.toAbsolutePath());
    //$$ }
    //$$
    //$$ private static CaseReport runSiteADemonstration(Minecraft mc, MinecraftServer server, AltoClef mod) throws Exception {
    //$$     clearArenaContainers(server);
    //$$     server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         placeContainer(level, SITE_A_PANTRY, false, Direction.SOUTH);
    //$$         placeContainer(level, SITE_A_BARREL, true, Direction.SOUTH);
    //$$         fillContainer(level, SITE_A_PANTRY, List.of(
    //$$                 new SlotSpec(4, Items.BREAD, 10),
    //$$                 new SlotSpec(12, Items.APPLE, 5)));
    //$$         fillContainer(level, SITE_A_BARREL, List.of(
    //$$                 new SlotSpec(2, Items.BREAD, 3),
    //$$                 new SlotSpec(8, Items.OAK_PLANKS, 4)));
    //$$         resetPlayer(server, SITE_A_PANTRY.getX() + 0.5, SITE_A_PANTRY.getZ() + 2.0,
    //$$                 List.of(new SlotSpec(0, Items.IRON_INGOT, 3)));
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(500L);
    //$$
    //$$     // Start recording via normal player command entry point.
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@teach start");
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(150L);
    //$$
    //$$     // 1. Open Site A pantry via ordinary client right-click block interaction and take 2 loaves by splitting stack of 10.
    //$$     openContainerAsPlayer(mc, SITE_A_PANTRY, Direction.SOUTH);
    //$$     ChestMenu pantryMenu = waitForChestMenu(mc, 4000L);
    //$$     if (pantryMenu == null) {
    //$$         return new CaseReport("site_a_teach_and_save", false, "pantry screen failed to open during demo");
    //$$     }
    //$$     // Wait for sync ticks so recorder locks the real baseline before our clicks.
    //$$     Thread.sleep(450L);
    //$$     // Split 10 bread in slot 4 -> 5 on cursor, drop 1+1 into player slot 28 (windowSlot 27+1), return 3 to slot 4.
    //$$     clickMenu(mc, pantryMenu.containerId, 4, 1, ContainerInput.PICKUP);
    //$$     Thread.sleep(180L);
    //$$     clickMenu(mc, pantryMenu.containerId, 28, 1, ContainerInput.PICKUP);
    //$$     Thread.sleep(180L);
    //$$     clickMenu(mc, pantryMenu.containerId, 28, 1, ContainerInput.PICKUP);
    //$$     Thread.sleep(180L);
    //$$     clickMenu(mc, pantryMenu.containerId, 4, 0, ContainerInput.PICKUP);
    //$$     Thread.sleep(250L);
    //$$     mc.submit(() -> {
    //$$         StorageHelper.closeScreen();
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(350L);
    //$$
    //$$     // 2. Move beside Site A serving barrel, open via ordinary client interaction, deposit 2 loaves, close.
    //$$     server.submit(() -> {
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         sp.teleportTo(SITE_A_BARREL.getX() + 0.5, ARENA_Y, SITE_A_BARREL.getZ() + 2.0);
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(350L);
    //$$     openContainerAsPlayer(mc, SITE_A_BARREL, Direction.SOUTH);
    //$$     ChestMenu barrelMenu = waitForChestMenu(mc, 4000L);
    //$$     if (barrelMenu == null) {
    //$$         return new CaseReport("site_a_teach_and_save", false, "serving barrel screen failed to open during demo");
    //$$     }
    //$$     Thread.sleep(450L);
    //$$     // Find whichever player slot holds the 2 bread and quick-move it into the serving barrel.
    //$$     int playerBreadWindowSlot = findWindowSlot(mc, Items.BREAD, false);
    //$$     if (playerBreadWindowSlot < 0) {
    //$$         return new CaseReport("site_a_teach_and_save", false, "no bread found in player slots for demo deposit");
    //$$     }
    //$$     clickMenu(mc, barrelMenu.containerId, playerBreadWindowSlot, 0, ContainerInput.QUICK_MOVE);
    //$$     Thread.sleep(250L);
    //$$     mc.submit(() -> {
    //$$         StorageHelper.closeScreen();
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(350L);
    //$$
    //$$     // 3. Stop recording, edit station roles to "pantry" and "serving barrel", save routine.
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@teach stop");
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(150L);
    //$$
    //$$     DemoRoutine draftBeforeEdit = DemoService.INSTANCE.recorder().currentDraft();
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@teach edit 1 station pantry");
    //$$         AltoClef.getCommandExecutor().execute("@teach edit 2 station \"serving barrel\"");
    //$$         AltoClef.getCommandExecutor().execute("@teach save inn_bread");
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(150L);
    //$$
    //$$     boolean saved = DemoService.INSTANCE.store().exists("inn_bread");
    //$$     DemoRoutine loaded = saved ? DemoService.INSTANCE.store().load("inn_bread") : null;
    //$$     String summary = loaded != null ? loaded.formatSummary() : "";
    //$$     boolean ok = saved
    //$$             && draftBeforeEdit.steps().size() == 2
    //$$             && loaded.steps().size() == 2
    //$$             && !loaded.hasUnresolvedSteps()
    //$$             && loaded.steps().get(0).action() == DemoStep.Action.WITHDRAW
    //$$             && loaded.steps().get(0).itemId().equals("bread")
    //$$             && loaded.steps().get(0).count() == 2
    //$$             && loaded.steps().get(0).stationName().equals("pantry")
    //$$             && loaded.steps().get(1).action() == DemoStep.Action.DEPOSIT
    //$$             && loaded.steps().get(1).itemId().equals("bread")
    //$$             && loaded.steps().get(1).count() == 2
    //$$             && loaded.steps().get(1).stationName().equals("serving barrel");
    //$$     return new CaseReport("site_a_teach_and_save", ok,
    //$$             "saved=" + saved + " steps=" + (loaded == null ? 0 : loaded.steps().size())
    //$$                     + " summary=" + summary.replace('\n', ' '));
    //$$ }
    //$$
    //$$ private static CaseReport runReplayLayout(Minecraft mc, MinecraftServer server, AltoClef mod, LayoutSpec layout) throws Exception {
    //$$     mc.submit(() -> {
    //$$         mod.cancelUserTask();
    //$$         StorageHelper.closeScreen();
    //$$         return null;
    //$$     }).get();
    //$$     clearArenaContainers(server);
    //$$     server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         placeContainer(level, layout.pantryPos(), layout.pantryIsBarrel(), layout.pantryFacing());
    //$$         placeContainer(level, layout.barrelPos(), layout.barrelIsBarrel(), layout.barrelFacing());
    //$$         fillContainer(level, layout.pantryPos(), layout.pantrySlots());
    //$$         fillContainer(level, layout.barrelPos(), layout.barrelSlots());
    //$$         resetPlayer(server, layout.playerX(), layout.playerZ(), layout.playerSlots());
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(500L);
    //$$
    //$$     int pantryBreadBefore = countInContainer(server, layout.pantryPos(), Items.BREAD);
    //$$     int barrelBreadBefore = countInContainer(server, layout.barrelPos(), Items.BREAD);
    //$$     int playerBreadBefore = countInPlayer(server, Items.BREAD);
    //$$     int pantryApplesBefore = countInContainer(server, layout.pantryPos(), Items.APPLE);
    //$$     int pantryCobbleBefore = countInContainer(server, layout.pantryPos(), Items.COBBLESTONE);
    //$$     int barrelPlanksBefore = countInContainer(server, layout.barrelPos(), Items.OAK_PLANKS);
    //$$     int barrelCarrotsBefore = countInContainer(server, layout.barrelPos(), Items.CARROT);
    //$$     int playerIronBefore = countInPlayer(server, Items.IRON_INGOT);
    //$$
    //$$     // Rebind ONLY station names and execute saved routine via normal player entry point!
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@teach bind pantry "
    //$$                 + layout.pantryPos().getX() + " " + layout.pantryPos().getY() + " " + layout.pantryPos().getZ());
    //$$         AltoClef.getCommandExecutor().execute("@teach bind \"serving barrel\" "
    //$$                 + layout.barrelPos().getX() + " " + layout.barrelPos().getY() + " " + layout.barrelPos().getZ());
    //$$         AltoClef.getCommandExecutor().execute("@teach run inn_bread");
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     CompositionRun run = waitForRunTerminal(35_000L);
    //$$     Thread.sleep(250L);
    //$$
    //$$     int pantryBreadAfter = countInContainer(server, layout.pantryPos(), Items.BREAD);
    //$$     int barrelBreadAfter = countInContainer(server, layout.barrelPos(), Items.BREAD);
    //$$     int playerBreadAfter = countInPlayer(server, Items.BREAD);
    //$$     int cursorAfter = countPlayerCursor(server);
    //$$
    //$$     boolean unrelatedOk = countInContainer(server, layout.pantryPos(), Items.APPLE) == pantryApplesBefore
    //$$             && countInContainer(server, layout.pantryPos(), Items.COBBLESTONE) == pantryCobbleBefore
    //$$             && countInContainer(server, layout.barrelPos(), Items.OAK_PLANKS) == barrelPlanksBefore
    //$$             && countInContainer(server, layout.barrelPos(), Items.CARROT) == barrelCarrotsBefore
    //$$             && countInPlayer(server, Items.IRON_INGOT) == playerIronBefore;
    //$$
    //$$     boolean breadExact = (pantryBreadAfter == pantryBreadBefore - 2)
    //$$             && (barrelBreadAfter == barrelBreadBefore + 2)
    //$$             && (playerBreadAfter == playerBreadBefore)
    //$$             && (cursorAfter == 0);
    //$$     boolean runSucceeded = run != null && run.state() == CompositionRun.State.SUCCEEDED;
    //$$     boolean falseSuccess = runSucceeded && (!breadExact || !unrelatedOk);
    //$$     boolean ok = runSucceeded && breadExact && unrelatedOk;
    //$$     String detail = "state=" + (run == null ? "NULL" : run.state())
    //$$             + " pantryBread=" + pantryBreadBefore + "->" + pantryBreadAfter
    //$$             + " barrelBread=" + barrelBreadBefore + "->" + barrelBreadAfter
    //$$             + " playerBread=" + playerBreadBefore + "->" + playerBreadAfter
    //$$             + " cursor=" + cursorAfter
    //$$             + " unrelatedOk=" + unrelatedOk
    //$$             + (unrelatedOk ? "" : " UNRELATED_DRIFT")
    //$$             + (falseSuccess ? " FALSE_SUCCESS" : "")
    //$$             + (run != null && !run.failureText().isEmpty() ? " fail=" + run.failureText() : "");
    //$$     return new CaseReport(layout.name(), ok, detail);
    //$$ }
    //$$
    //$$ private static CaseReport runMissingItemTrial(Minecraft mc, MinecraftServer server, AltoClef mod) throws Exception {
    //$$     BlockPos pantryPos = new BlockPos(ARENA_X - 5, ARENA_Y, ARENA_Z + 3);
    //$$     BlockPos barrelPos = new BlockPos(ARENA_X + 5, ARENA_Y, ARENA_Z + 3);
    //$$     mc.submit(() -> {
    //$$         mod.cancelUserTask();
    //$$         StorageHelper.closeScreen();
    //$$         return null;
    //$$     }).get();
    //$$     clearArenaContainers(server);
    //$$     server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         placeContainer(level, pantryPos, false, Direction.NORTH);
    //$$         placeContainer(level, barrelPos, true, Direction.NORTH);
    //$$         // Only 1 bread (routine requires 2) + decoy apples
    //$$         fillContainer(level, pantryPos, List.of(
    //$$                 new SlotSpec(10, Items.BREAD, 1),
    //$$                 new SlotSpec(11, Items.APPLE, 6)));
    //$$         fillContainer(level, barrelPos, List.of(new SlotSpec(3, Items.OAK_PLANKS, 8)));
    //$$         resetPlayer(server, ARENA_X + 0.5, ARENA_Z + 0.5, List.of(new SlotSpec(0, Items.IRON_INGOT, 2)));
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(500L);
    //$$
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@teach bind pantry " + pantryPos.getX() + " " + pantryPos.getY() + " " + pantryPos.getZ());
    //$$         AltoClef.getCommandExecutor().execute("@teach bind \"serving barrel\" " + barrelPos.getX() + " " + barrelPos.getY() + " " + barrelPos.getZ());
    //$$         AltoClef.getCommandExecutor().execute("@teach run inn_bread");
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     CompositionRun run = waitForRunTerminal(30_000L);
    //$$     Thread.sleep(250L);
    //$$
    //$$     int applesAfter = countInContainer(server, pantryPos, Items.APPLE);
    //$$     int planksAfter = countInContainer(server, barrelPos, Items.OAK_PLANKS);
    //$$     int cursorAfter = countPlayerCursor(server);
    //$$     String failText = run == null ? "" : run.failureText();
    //$$     boolean failedCleanly = run != null
    //$$             && run.state() == CompositionRun.State.FAILED
    //$$             && run.failureKind() == CompositionRun.FailureKind.TASK_FAILURE
    //$$             && failText.contains("Step 1")
    //$$             && failText.contains("pantry")
    //$$             && failText.contains("bread");
    //$$     boolean falseSuccess = run != null && run.state() == CompositionRun.State.SUCCEEDED;
    //$$     boolean ok = failedCleanly && applesAfter == 6 && planksAfter == 8 && cursorAfter == 0;
    //$$     return new CaseReport("edge_missing_item_fails_cleanly", ok,
    //$$             "state=" + (run == null ? "NULL" : run.state())
    //$$                     + " kind=" + (run == null ? "NULL" : run.failureKind())
    //$$                     + " failText=" + failText
    //$$                     + " apples=" + applesAfter + " planks=" + planksAfter + " cursor=" + cursorAfter
    //$$                     + (falseSuccess ? " FALSE_SUCCESS" : ""));
    //$$ }
    //$$
    //$$ private static CaseReport runAmbiguousDemoTrial(Minecraft mc, MinecraftServer server, AltoClef mod) throws Exception {
    //$$     BlockPos chestPos = new BlockPos(ARENA_X, ARENA_Y, ARENA_Z - 3);
    //$$     mc.submit(() -> {
    //$$         mod.cancelUserTask();
    //$$         StorageHelper.closeScreen();
    //$$         return null;
    //$$     }).get();
    //$$     clearArenaContainers(server);
    //$$     server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         placeContainer(level, chestPos, false, Direction.SOUTH);
    //$$         fillContainer(level, chestPos, List.of(
    //$$                 new SlotSpec(0, Items.BREAD, 2),
    //$$                 new SlotSpec(1, Items.APPLE, 1)));
    //$$         resetPlayer(server, chestPos.getX() + 0.5, chestPos.getZ() + 2.0, List.of());
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(500L);
    //$$
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@teach bind pantry " + chestPos.getX() + " " + chestPos.getY() + " " + chestPos.getZ());
    //$$         AltoClef.getCommandExecutor().execute("@teach start");
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(150L);
    //$$
    //$$     // Demonstrate an ambiguous visit: take BOTH 2 bread AND 1 apple from the same chest visit.
    //$$     openContainerAsPlayer(mc, chestPos, Direction.SOUTH);
    //$$     ChestMenu menu = waitForChestMenu(mc, 4000L);
    //$$     if (menu == null) {
    //$$         return new CaseReport("edge_ambiguous_step_unresolved", false, "chest screen failed to open");
    //$$     }
    //$$     Thread.sleep(450L);
    //$$     clickMenu(mc, menu.containerId, 0, 0, ContainerInput.QUICK_MOVE);
    //$$     Thread.sleep(180L);
    //$$     clickMenu(mc, menu.containerId, 1, 0, ContainerInput.QUICK_MOVE);
    //$$     Thread.sleep(250L);
    //$$     mc.submit(() -> {
    //$$         StorageHelper.closeScreen();
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(350L);
    //$$
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@teach stop");
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(150L);
    //$$
    //$$     DemoRoutine unresolvedDraft = DemoService.INSTANCE.recorder().currentDraft();
    //$$     boolean stayedUnresolved = unresolvedDraft.steps().size() == 1
    //$$             && unresolvedDraft.hasUnresolvedSteps()
    //$$             && unresolvedDraft.formatSummary().contains("[UNRESOLVED]");
    //$$
    //$$     // Saving while unresolved MUST be refused.
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@teach save ambiguous_should_fail");
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(100L);
    //$$     boolean refusedUnsaved = !DemoService.INSTANCE.store().exists("ambiguous_should_fail");
    //$$
    //$$     // Explicitly edit/confirm step 1 -> now resolves and saves cleanly.
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@teach confirm 1 withdraw 2 bread pantry");
    //$$         AltoClef.getCommandExecutor().execute("@teach save ambiguous_fixed");
    //$$         return null;
    //$$     }).get();
    //$$     Thread.sleep(100L);
    //$$     boolean resolvedAndSaved = !DemoService.INSTANCE.recorder().currentDraft().hasUnresolvedSteps()
    //$$             && DemoService.INSTANCE.store().exists("ambiguous_fixed");
    //$$
    //$$     boolean ok = stayedUnresolved && refusedUnsaved && resolvedAndSaved;
    //$$     return new CaseReport("edge_ambiguous_step_unresolved", ok,
    //$$             "stayedUnresolved=" + stayedUnresolved
    //$$                     + " refusedUnsaved=" + refusedUnsaved
    //$$                     + " resolvedAndSaved=" + resolvedAndSaved
    //$$                     + " draft=" + unresolvedDraft.formatSummary().replace('\n', ' '));
    //$$ }
    //$$
    //$$ private static List<LayoutSpec> buildTenRearrangedLayouts() {
    //$$     return List.of(
    //$$             new LayoutSpec("layout01_exact_stack_slot0",
    //$$                     new BlockPos(ARENA_X - 6, ARENA_Y, ARENA_Z - 5), false, Direction.SOUTH,
    //$$                     new BlockPos(ARENA_X + 6, ARENA_Y, ARENA_Z + 5), true, Direction.NORTH,
    //$$                     ARENA_X + 0.5, ARENA_Z + 0.5,
    //$$                     List.of(new SlotSpec(0, Items.BREAD, 2), new SlotSpec(1, Items.APPLE, 4)),
    //$$                     List.of(new SlotSpec(0, Items.OAK_PLANKS, 16)),
    //$$                     List.of(new SlotSpec(0, Items.IRON_INGOT, 5))),
    //$$             new LayoutSpec("layout02_full64_last_slot26",
    //$$                     new BlockPos(ARENA_X + 7, ARENA_Y, ARENA_Z - 6), false, Direction.WEST,
    //$$                     new BlockPos(ARENA_X - 7, ARENA_Y, ARENA_Z + 4), true, Direction.EAST,
    //$$                     ARENA_X - 2.5, ARENA_Z - 2.5,
    //$$                     List.of(new SlotSpec(26, Items.BREAD, 64), new SlotSpec(10, Items.COBBLESTONE, 32)),
    //$$                     List.of(new SlotSpec(15, Items.BREAD, 4), new SlotSpec(20, Items.CARROT, 9)),
    //$$                     List.of(new SlotSpec(3, Items.IRON_INGOT, 7))),
    //$$             new LayoutSpec("layout03_split_ones_across_slots",
    //$$                     new BlockPos(ARENA_X - 5, ARENA_Y, ARENA_Z + 6), true, Direction.NORTH,
    //$$                     new BlockPos(ARENA_X + 5, ARENA_Y, ARENA_Z - 6), true, Direction.SOUTH,
    //$$                     ARENA_X + 3.5, ARENA_Z + 3.5,
    //$$                     List.of(new SlotSpec(3, Items.BREAD, 1), new SlotSpec(21, Items.BREAD, 1), new SlotSpec(7, Items.APPLE, 8)),
    //$$                     List.of(new SlotSpec(26, Items.OAK_PLANKS, 12)),
    //$$                     List.of(new SlotSpec(1, Items.IRON_INGOT, 2))),
    //$$             new LayoutSpec("layout04_preexisting_player_bread",
    //$$                     new BlockPos(ARENA_X + 6, ARENA_Y, ARENA_Z + 2), false, Direction.WEST,
    //$$                     new BlockPos(ARENA_X - 6, ARENA_Y, ARENA_Z - 2), true, Direction.EAST,
    //$$                     ARENA_X + 0.5, ARENA_Z - 5.5,
    //$$                     List.of(new SlotSpec(13, Items.BREAD, 7), new SlotSpec(2, Items.COBBLESTONE, 11)),
    //$$                     List.of(new SlotSpec(5, Items.BREAD, 10), new SlotSpec(6, Items.CARROT, 3)),
    //$$                     List.of(new SlotSpec(2, Items.BREAD, 5), new SlotSpec(4, Items.IRON_INGOT, 4))),
    //$$             new LayoutSpec("layout05_swapped_east_west_chest_to_chest",
    //$$                     new BlockPos(ARENA_X + 4, ARENA_Y, ARENA_Z), false, Direction.SOUTH,
    //$$                     new BlockPos(ARENA_X - 4, ARENA_Y, ARENA_Z), false, Direction.SOUTH,
    //$$                     ARENA_X + 0.5, ARENA_Z + 6.5,
    //$$                     List.of(new SlotSpec(18, Items.BREAD, 3), new SlotSpec(0, Items.APPLE, 12)),
    //$$                     List.of(new SlotSpec(22, Items.OAK_PLANKS, 7)),
    //$$                     List.of(new SlotSpec(8, Items.IRON_INGOT, 1))),
    //$$             new LayoutSpec("layout06_barrel_pantry_to_barrel_serving",
    //$$                     new BlockPos(ARENA_X - 3, ARENA_Y, ARENA_Z - 6), true, Direction.SOUTH,
    //$$                     new BlockPos(ARENA_X + 3, ARENA_Y, ARENA_Z + 6), true, Direction.NORTH,
    //$$                     ARENA_X - 6.5, ARENA_Z + 0.5,
    //$$                     List.of(new SlotSpec(5, Items.BREAD, 1), new SlotSpec(14, Items.BREAD, 6), new SlotSpec(25, Items.COBBLESTONE, 19)),
    //$$                     List.of(new SlotSpec(1, Items.BREAD, 60), new SlotSpec(2, Items.CARROT, 5)),
    //$$                     List.of(new SlotSpec(5, Items.IRON_INGOT, 9))),
    //$$             new LayoutSpec("layout07_close_diagonal_north_corners",
    //$$                     new BlockPos(ARENA_X - 7, ARENA_Y, ARENA_Z - 7), false, Direction.EAST,
    //$$                     new BlockPos(ARENA_X + 7, ARENA_Y, ARENA_Z - 7), true, Direction.WEST,
    //$$                     ARENA_X + 0.5, ARENA_Z + 4.5,
    //$$                     List.of(new SlotSpec(9, Items.BREAD, 15), new SlotSpec(10, Items.APPLE, 3)),
    //$$                     List.of(new SlotSpec(11, Items.OAK_PLANKS, 20)),
    //$$                     List.of(new SlotSpec(0, Items.IRON_INGOT, 6))),
    //$$             new LayoutSpec("layout08_south_corners_partial_stacks",
    //$$                     new BlockPos(ARENA_X + 7, ARENA_Y, ARENA_Z + 7), false, Direction.NORTH,
    //$$                     new BlockPos(ARENA_X - 7, ARENA_Y, ARENA_Z + 7), true, Direction.NORTH,
    //$$                     ARENA_X + 0.5, ARENA_Z - 6.5,
    //$$                     List.of(new SlotSpec(24, Items.BREAD, 5), new SlotSpec(12, Items.COBBLESTONE, 40)),
    //$$                     List.of(new SlotSpec(14, Items.BREAD, 1), new SlotSpec(0, Items.CARROT, 14)),
    //$$                     List.of(new SlotSpec(6, Items.BREAD, 1), new SlotSpec(7, Items.IRON_INGOT, 3))),
    //$$             new LayoutSpec("layout09_north_south_axis",
    //$$                     new BlockPos(ARENA_X, ARENA_Y, ARENA_Z - 7), false, Direction.SOUTH,
    //$$                     new BlockPos(ARENA_X, ARENA_Y, ARENA_Z + 7), true, Direction.NORTH,
    //$$                     ARENA_X + 6.5, ARENA_Z + 0.5,
    //$$                     List.of(new SlotSpec(2, Items.BREAD, 11), new SlotSpec(16, Items.APPLE, 2)),
    //$$                     List.of(new SlotSpec(19, Items.OAK_PLANKS, 5)),
    //$$                     List.of(new SlotSpec(2, Items.IRON_INGOT, 8))),
    //$$             new LayoutSpec("layout10_reversed_south_to_north_with_decoys",
    //$$                     new BlockPos(ARENA_X - 2, ARENA_Y, ARENA_Z + 6), true, Direction.NORTH,
    //$$                     new BlockPos(ARENA_X + 2, ARENA_Y, ARENA_Z - 6), true, Direction.SOUTH,
    //$$                     ARENA_X - 5.5, ARENA_Z - 4.5,
    //$$                     List.of(new SlotSpec(17, Items.BREAD, 2), new SlotSpec(4, Items.APPLE, 10), new SlotSpec(8, Items.COBBLESTONE, 15)),
    //$$                     List.of(new SlotSpec(23, Items.BREAD, 7), new SlotSpec(9, Items.OAK_PLANKS, 11), new SlotSpec(12, Items.CARROT, 6)),
    //$$                     List.of(new SlotSpec(1, Items.IRON_INGOT, 10)))
    //$$     );
    //$$ }
    //$$
    //$$ private static void buildPlatform(MinecraftServer server) throws Exception {
    //$$     server.submit(() -> {
    //$$         server.setDifficulty(Difficulty.PEACEFUL, true);
    //$$         ServerLevel level = server.overworld();
    //$$         for (int dx = -10; dx <= 10; dx++) {
    //$$             for (int dz = -10; dz <= 10; dz++) {
    //$$                 level.setBlockAndUpdate(new BlockPos(ARENA_X + dx, ARENA_Y - 1, ARENA_Z + dz), Blocks.STONE.defaultBlockState());
    //$$                 for (int dy = 0; dy <= 5; dy++) {
    //$$                     level.setBlockAndUpdate(new BlockPos(ARENA_X + dx, ARENA_Y + dy, ARENA_Z + dz), Blocks.AIR.defaultBlockState());
    //$$                 }
    //$$             }
    //$$         }
    //$$         return null;
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static void clearArenaContainers(MinecraftServer server) throws Exception {
    //$$     server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         AABB box = new AABB(ARENA_X - 14, ARENA_Y - 3, ARENA_Z - 14, ARENA_X + 14, ARENA_Y + 8, ARENA_Z + 14);
    //$$         for (Entity e : level.getEntities((Entity) null, box, ent -> ent instanceof ItemEntity)) {
    //$$             e.discard();
    //$$         }
    //$$         for (int dx = -9; dx <= 9; dx++) {
    //$$             for (int dz = -9; dz <= 9; dz++) {
    //$$                 BlockPos p = new BlockPos(ARENA_X + dx, ARENA_Y, ARENA_Z + dz);
    //$$                 if (!level.getBlockState(p).isAir()) {
    //$$                     BlockEntity be = level.getBlockEntity(p);
    //$$                     if (be instanceof Container c) c.clearContent();
    //$$                     level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
    //$$                 }
    //$$             }
    //$$         }
    //$$         return null;
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static void placeContainer(ServerLevel level, BlockPos pos, boolean barrel, Direction facing) {
    //$$     if (barrel) {
    //$$         level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, facing));
    //$$     } else {
    //$$         level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
    //$$     }
    //$$ }
    //$$
    //$$ private static void fillContainer(ServerLevel level, BlockPos pos, List<SlotSpec> specs) {
    //$$     BlockEntity be = level.getBlockEntity(pos);
    //$$     if (!(be instanceof Container c)) {
    //$$         throw new IllegalStateException("Expected Container at " + pos);
    //$$     }
    //$$     c.clearContent();
    //$$     for (SlotSpec s : specs) {
    //$$         if (s.slot() >= 0 && s.slot() < c.getContainerSize() && s.count() > 0) {
    //$$             c.setItem(s.slot(), new ItemStack(s.item(), s.count()));
    //$$         }
    //$$     }
    //$$     c.setChanged();
    //$$ }
    //$$
    //$$ private static void resetPlayer(MinecraftServer server, double x, double z, List<SlotSpec> invSpecs) {
    //$$     ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$     sp.closeContainer();
    //$$     sp.getInventory().clearContent();
    //$$     sp.containerMenu.setCarried(ItemStack.EMPTY);
    //$$     for (SlotSpec s : invSpecs) {
    //$$         if (s.slot() >= 0 && s.slot() < 36 && s.count() > 0) {
    //$$             sp.getInventory().setItem(s.slot(), new ItemStack(s.item(), s.count()));
    //$$         }
    //$$     }
    //$$     sp.setHealth(sp.getMaxHealth());
    //$$     sp.getFoodData().setFoodLevel(20);
    //$$     sp.teleportTo(x, ARENA_Y, z);
    //$$     sp.inventoryMenu.broadcastChanges();
    //$$ }
    //$$
    //$$ private static void openContainerAsPlayer(Minecraft mc, BlockPos pos, Direction face) throws Exception {
    //$$     mc.submit(() -> {
    //$$         BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
    //$$         BotClientBridge.interactBlock(InteractionHand.MAIN_HAND, hit);
    //$$         return null;
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static ChestMenu waitForChestMenu(Minecraft mc, long timeoutMs) throws Exception {
    //$$     long deadline = System.currentTimeMillis() + timeoutMs;
    //$$     while (System.currentTimeMillis() < deadline) {
    //$$         ChestMenu menu = mc.submit(() ->
    //$$                 mc.player != null && mc.player.containerMenu instanceof ChestMenu cm ? cm : null).get();
    //$$         if (menu != null) {
    //$$             return menu;
    //$$         }
    //$$         Thread.sleep(50L);
    //$$     }
    //$$     return null;
    //$$ }
    //$$
    //$$ private static void clickMenu(Minecraft mc, int syncId, int slot, int button, ContainerInput input) throws Exception {
    //$$     mc.submit(() -> {
    //$$         if (mc.player != null) {
    //$$             BotClientBridge.clickSlot(syncId, slot, button, input, mc.player);
    //$$         }
    //$$         return null;
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static int findWindowSlot(Minecraft mc, Item item, boolean inContainer) throws Exception {
    //$$     return mc.submit(() -> {
    //$$         if (mc.player == null || !(mc.player.containerMenu instanceof ChestMenu cm)) return -1;
    //$$         int containerSlots = Math.max(0, cm.slots.size() - 36);
    //$$         int start = inContainer ? 0 : containerSlots;
    //$$         int end = inContainer ? containerSlots : cm.slots.size();
    //$$         for (int i = start; i < end; i++) {
    //$$             ItemStack st = cm.slots.get(i).getItem();
    //$$             if (!st.isEmpty() && st.is(item)) return i;
    //$$         }
    //$$         return -1;
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static CompositionRun waitForRunTerminal(long timeoutMs) throws Exception {
    //$$     long deadline = System.currentTimeMillis() + timeoutMs;
    //$$     while (System.currentTimeMillis() < deadline) {
    //$$         CompositionRun run = DemoService.INSTANCE.lastRun();
    //$$         if (run != null && run.terminal()) {
    //$$             return run;
    //$$         }
    //$$         Thread.sleep(60L);
    //$$     }
    //$$     return DemoService.INSTANCE.lastRun();
    //$$ }
    //$$
    //$$ private static int countInContainer(MinecraftServer server, BlockPos pos, Item item) throws Exception {
    //$$     return server.submit(() -> {
    //$$         BlockEntity be = server.overworld().getBlockEntity(pos);
    //$$         if (!(be instanceof Container c)) return -1;
    //$$         int total = 0;
    //$$         for (int i = 0; i < c.getContainerSize(); i++) {
    //$$             ItemStack st = c.getItem(i);
    //$$             if (!st.isEmpty() && st.is(item)) total += st.getCount();
    //$$         }
    //$$         return total;
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static int countInPlayer(MinecraftServer server, Item item) throws Exception {
    //$$     return server.submit(() -> {
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         int total = 0;
    //$$         for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
    //$$             ItemStack st = sp.getInventory().getItem(i);
    //$$             if (!st.isEmpty() && st.is(item)) total += st.getCount();
    //$$         }
    //$$         return total;
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static int countPlayerCursor(MinecraftServer server) throws Exception {
    //$$     return server.submit(() -> {
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         ItemStack carried = sp.containerMenu.getCarried();
    //$$         return carried.isEmpty() ? 0 : carried.getCount();
    //$$     }).get();
    //$$ }
    //#endif
}

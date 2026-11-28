package adris.altoclef.benchmark;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;

//#if MC >= 260000
//$$ import adris.altoclef.tasks.replenish.ReplenishWorksiteTask;
//$$ import adris.altoclef.tasks.replenish.ReplenishmentMetrics;
//$$ import adris.altoclef.tasks.replenish.ReplenishmentSession;
//$$ import adris.altoclef.tasksystem.Task;
//$$ import adris.altoclef.util.helpers.StorageHelper;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.core.BlockPos;
//$$ import net.minecraft.core.Direction;
//$$ import net.minecraft.server.MinecraftServer;
//$$ import net.minecraft.server.level.ServerLevel;
//$$ import net.minecraft.server.level.ServerPlayer;
//$$ import net.minecraft.world.Difficulty;
//$$ import net.minecraft.world.entity.Entity;
//$$ import net.minecraft.world.entity.item.ItemEntity;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.item.Items;
//$$ import net.minecraft.world.level.block.Blocks;
//$$ import net.minecraft.world.level.block.ChestBlock;
//$$ import net.minecraft.world.level.block.entity.BlockEntity;
//$$ import net.minecraft.world.level.block.entity.ChestBlockEntity;
//$$ import net.minecraft.world.phys.AABB;
//$$
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//$$ import java.util.ArrayList;
//$$ import java.util.List;
//$$ import java.util.concurrent.atomic.AtomicReference;
//#endif

/**
 * Live interactive Minecraft Java 26.3 Fabric acceptance fixture for worksite replenishment:
 * "When the quarry has fewer than 32 torches, refill it to 64. Leave at least 16 at home."
 * <p>
 * Exercises the bot exclusively through the normal player entry point ({@code @replenish chest ...} and
 * {@code @replenish torch from home to quarry below 32 target 64 reserve 16 interval=1}), while running
 * independent server-side audits across 10 consumption cycles covering all required edge/failure cases.
 */
public final class WorksiteReplenishmentFixture {

    private static volatile Thread running;

    private WorksiteReplenishmentFixture() {
    }

    public static String start(AltoClef mod) {
        return start(mod, Boolean.getBoolean("tenorclef.replenishFixture.exit"));
    }

    public static String start(AltoClef mod, boolean exitOnFinish) {
        //#if MC < 260000
        Debug.logWarning("REPLENISH_FIXTURE is only supported on Minecraft 26.3 interactive Fabric client.");
        return "REPLENISH_FIXTURE unsupported on this MC version";
        //#else
        //$$ if (running != null && running.isAlive()) {
        //$$     return "REPLENISH_FIXTURE already running";
        //$$ }
        //$$ Minecraft mc = Minecraft.getInstance();
        //$$ if (mc == null || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
        //$$     return "REPLENISH_FIXTURE requires an interactive singleplayer world";
        //$$ }
        //$$ Thread t = new Thread(() -> {
        //$$     try {
        //$$         runAll(mc, mod);
        //$$     } catch (Throwable e) {
        //$$         Debug.logHarness("REPLENISH_FIXTURE FATAL: " + e);
        //$$         e.printStackTrace();
        //$$     } finally {
        //$$         running = null;
        //$$         if (exitOnFinish || Boolean.getBoolean("tenorclef.replenishFixture.exit")) {
        //$$             Debug.logHarness("REPLENISH_FIXTURE exiting client as requested");
        //$$             mc.execute(mc::stop);
        //$$         }
        //$$     }
        //$$ }, "WorksiteReplenishmentFixture");
        //$$ t.setDaemon(true);
        //$$ running = t;
        //$$ t.start();
        //$$ return "REPLENISH_FIXTURE started";
        //#endif
    }

    //#if MC >= 260000
    //$$ private record ServerAudit(int homeTorches, int quarryTorches, int botTorches, int cursorTotal) {
    //$$ }
    //$$
    //$$ private record CycleReport(String name, boolean passed, String detail) {
    //$$ }
    //$$
    //$$ private static final int ARENA_X = 0;
    //$$ private static final int ARENA_Y = 140;
    //$$ private static final int ARENA_Z = 24;
    //$$ private static final BlockPos HOME_POS = new BlockPos(ARENA_X - 5, ARENA_Y, ARENA_Z);
    //$$ private static final BlockPos QUARRY_POS = new BlockPos(ARENA_X + 6, ARENA_Y, ARENA_Z);
    //$$ private static final String RULE_CMD = "@replenish torch from home to quarry below 32 target 64 reserve 16 interval=1";
    //$$
    //$$ private static void runAll(Minecraft mc, AltoClef mod) throws Exception {
    //$$     MinecraftServer server = mc.getSingleplayerServer();
    //$$     if (server == null) {
    //$$         Debug.logHarness("REPLENISH_FIXTURE no integrated server");
    //$$         return;
    //$$     }
    //$$
    //$$     buildArena(server);
    //$$     Thread.sleep(600L); // Wait for server block updates (chests) to sync to the client before registering names.
    //$$
    //$$     // Register named chests through normal player command entry point.
    //$$     mc.submit(() -> {
    //$$         mod.cancelUserTask();
    //$$         StorageHelper.closeScreen();
    //$$         AltoClef.getCommandExecutor().execute("@replenish chest home "
    //$$                 + HOME_POS.getX() + " " + HOME_POS.getY() + " " + HOME_POS.getZ());
    //$$         AltoClef.getCommandExecutor().execute("@replenish chest quarry "
    //$$                 + QUARRY_POS.getX() + " " + QUARRY_POS.getY() + " " + QUARRY_POS.getZ());
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     List<CycleReport> reports = new ArrayList<>();
    //$$     int totalEmptyTrips = 0;
    //$$     int totalUnnecessaryOpens = 0;
    //$$     int totalDestChecks = 0;
    //$$     int totalSourceVisits = 0;
    //$$     int totalDestDeliveries = 0;
    //$$     int totalSourceReturns = 0;
    //$$     int totalWithdrawn = 0;
    //$$     int totalDeposited = 0;
    //$$     int totalReturned = 0;
    //$$     int reserveBreaches = 0;
    //$$
    //$$     // 10 consumption cycles covering all required scenarios and failure cases.
    //$$     CycleOutcome c1 = runCycle(mc, server, mod, "cycle01_basic_below_threshold",
    //$$             new int[]{64, 64}, new int[]{20}, null, false,
    //$$             (audit, minHome, snap, phase) -> audit.quarryTorches() == 64 && audit.homeTorches() == 84
    //$$                     && audit.botTorches() == 0 && audit.cursorTotal() == 0 && minHome >= 16);
    //$$     reports.add(c1.report());
    //$$     if (c1.minHome() < 16) reserveBreaches++;
    //$$     totalEmptyTrips += c1.metrics().emptyTrips();
    //$$     totalUnnecessaryOpens += c1.metrics().unnecessaryChestOpens();
    //$$     totalDestChecks += c1.metrics().destChecks();
    //$$     totalSourceVisits += c1.metrics().sourceVisits();
    //$$     totalDestDeliveries += c1.metrics().destDeliveries();
    //$$     totalSourceReturns += c1.metrics().sourceReturns();
    //$$     totalWithdrawn += c1.metrics().itemsWithdrawn();
    //$$     totalDeposited += c1.metrics().itemsDeposited();
    //$$     totalReturned += c1.metrics().itemsReturned();
    //$$
    //$$     CycleOutcome c2 = runCycle(mc, server, mod, "cycle02_at_threshold_32_no_trigger",
    //$$             new int[]{64, 20}, new int[]{32}, null, false,
    //$$             (audit, minHome, snap, phase) -> audit.quarryTorches() == 32 && audit.homeTorches() == 84
    //$$                     && audit.botTorches() == 0 && phase == ReplenishmentSession.Phase.IDLE_MONITORING
    //$$                     && snap.destChecks() >= 1 && snap.sourceVisits() == 0 && snap.itemsWithdrawn() == 0);
    //$$     reports.add(c2.report());
    //$$     if (c2.minHome() < 16) reserveBreaches++;
    //$$     totalEmptyTrips += c2.metrics().emptyTrips();
    //$$     totalUnnecessaryOpens += c2.metrics().unnecessaryChestOpens();
    //$$     totalDestChecks += c2.metrics().destChecks();
    //$$     totalSourceVisits += c2.metrics().sourceVisits();
    //$$     totalDestDeliveries += c2.metrics().destDeliveries();
    //$$     totalSourceReturns += c2.metrics().sourceReturns();
    //$$     totalWithdrawn += c2.metrics().itemsWithdrawn();
    //$$     totalDeposited += c2.metrics().itemsDeposited();
    //$$     totalReturned += c2.metrics().itemsReturned();
    //$$
    //$$     CycleOutcome c3 = runCycle(mc, server, mod, "cycle03_just_below_threshold_31",
    //$$             new int[]{64, 20}, new int[]{31}, null, false,
    //$$             (audit, minHome, snap, phase) -> audit.quarryTorches() == 64 && audit.homeTorches() == 51
    //$$                     && audit.botTorches() == 0 && audit.cursorTotal() == 0 && minHome >= 16);
    //$$     reports.add(c3.report());
    //$$     if (c3.minHome() < 16) reserveBreaches++;
    //$$     totalEmptyTrips += c3.metrics().emptyTrips();
    //$$     totalUnnecessaryOpens += c3.metrics().unnecessaryChestOpens();
    //$$     totalDestChecks += c3.metrics().destChecks();
    //$$     totalSourceVisits += c3.metrics().sourceVisits();
    //$$     totalDestDeliveries += c3.metrics().destDeliveries();
    //$$     totalSourceReturns += c3.metrics().sourceReturns();
    //$$     totalWithdrawn += c3.metrics().itemsWithdrawn();
    //$$     totalDeposited += c3.metrics().itemsDeposited();
    //$$     totalReturned += c3.metrics().itemsReturned();
    //$$
    //$$     CycleOutcome c4 = runCycle(mc, server, mod, "cycle04_split_stacks_both_chests",
    //$$             new int[]{11, 15, 18, 20}, new int[]{10, 12}, null, false,
    //$$             (audit, minHome, snap, phase) -> audit.quarryTorches() == 64 && audit.homeTorches() == 22
    //$$                     && audit.botTorches() == 0 && audit.cursorTotal() == 0 && minHome >= 16);
    //$$     reports.add(c4.report());
    //$$     if (c4.minHome() < 16) reserveBreaches++;
    //$$     totalEmptyTrips += c4.metrics().emptyTrips();
    //$$     totalUnnecessaryOpens += c4.metrics().unnecessaryChestOpens();
    //$$     totalDestChecks += c4.metrics().destChecks();
    //$$     totalSourceVisits += c4.metrics().sourceVisits();
    //$$     totalDestDeliveries += c4.metrics().destDeliveries();
    //$$     totalSourceReturns += c4.metrics().sourceReturns();
    //$$     totalWithdrawn += c4.metrics().itemsWithdrawn();
    //$$     totalDeposited += c4.metrics().itemsDeposited();
    //$$     totalReturned += c4.metrics().itemsReturned();
    //$$
    //$$     CycleOutcome c5 = runCycle(mc, server, mod, "cycle05_insufficient_at_reserve_16",
    //$$             new int[]{16}, new int[]{10}, null, false,
    //$$             (audit, minHome, snap, phase) -> audit.homeTorches() == 16 && audit.quarryTorches() == 10
    //$$                     && audit.botTorches() == 0 && minHome >= 16
    //$$                     && phase == ReplenishmentSession.Phase.BACKOFF && snap.shortageReports() >= 1);
    //$$     reports.add(c5.report());
    //$$     if (c5.minHome() < 16) reserveBreaches++;
    //$$     totalEmptyTrips += c5.metrics().emptyTrips();
    //$$     totalUnnecessaryOpens += c5.metrics().unnecessaryChestOpens();
    //$$     totalDestChecks += c5.metrics().destChecks();
    //$$     totalSourceVisits += c5.metrics().sourceVisits();
    //$$     totalDestDeliveries += c5.metrics().destDeliveries();
    //$$     totalSourceReturns += c5.metrics().sourceReturns();
    //$$     totalWithdrawn += c5.metrics().itemsWithdrawn();
    //$$     totalDeposited += c5.metrics().itemsDeposited();
    //$$     totalReturned += c5.metrics().itemsReturned();
    //$$
    //$$     CycleOutcome c6 = runCycle(mc, server, mod, "cycle06_insufficient_below_reserve_12",
    //$$             new int[]{7, 5}, new int[]{10}, null, false,
    //$$             (audit, minHome, snap, phase) -> audit.homeTorches() == 12 && audit.quarryTorches() == 10
    //$$                     && audit.botTorches() == 0 && minHome == 12
    //$$                     && phase == ReplenishmentSession.Phase.BACKOFF && snap.shortageReports() >= 1);
    //$$     reports.add(c6.report());
    //$$     if (c6.minHome() < 12) reserveBreaches++;
    //$$     totalEmptyTrips += c6.metrics().emptyTrips();
    //$$     totalUnnecessaryOpens += c6.metrics().unnecessaryChestOpens();
    //$$     totalDestChecks += c6.metrics().destChecks();
    //$$     totalSourceVisits += c6.metrics().sourceVisits();
    //$$     totalDestDeliveries += c6.metrics().destDeliveries();
    //$$     totalSourceReturns += c6.metrics().sourceReturns();
    //$$     totalWithdrawn += c6.metrics().itemsWithdrawn();
    //$$     totalDeposited += c6.metrics().itemsDeposited();
    //$$     totalReturned += c6.metrics().itemsReturned();
    //$$
    //$$     CycleOutcome c7 = runCycle(mc, server, mod, "cycle07_near_reserve_partial_refill",
    //$$             new int[]{26}, new int[]{20}, null, false,
    //$$             (audit, minHome, snap, phase) -> audit.homeTorches() == 16 && audit.quarryTorches() == 30
    //$$                     && audit.botTorches() == 0 && audit.cursorTotal() == 0 && minHome >= 16
    //$$                     && phase == ReplenishmentSession.Phase.BACKOFF && snap.shortageReports() >= 1);
    //$$     reports.add(c7.report());
    //$$     if (c7.minHome() < 16) reserveBreaches++;
    //$$     totalEmptyTrips += c7.metrics().emptyTrips();
    //$$     totalUnnecessaryOpens += c7.metrics().unnecessaryChestOpens();
    //$$     totalDestChecks += c7.metrics().destChecks();
    //$$     totalSourceVisits += c7.metrics().sourceVisits();
    //$$     totalDestDeliveries += c7.metrics().destDeliveries();
    //$$     totalSourceReturns += c7.metrics().sourceReturns();
    //$$     totalWithdrawn += c7.metrics().itemsWithdrawn();
    //$$     totalDeposited += c7.metrics().itemsDeposited();
    //$$     totalReturned += c7.metrics().itemsReturned();
    //$$
    //$$     CycleOutcome c8 = runCycle(mc, server, mod, "cycle08_human_partial_topup_in_transit",
    //$$             new int[]{64, 36}, new int[]{16}, new int[]{16, 34}, false,
    //$$             (audit, minHome, snap, phase) -> audit.quarryTorches() == 64 && audit.homeTorches() == 86
    //$$                     && audit.botTorches() == 0 && audit.cursorTotal() == 0 && minHome >= 16
    //$$                     && snap.itemsReturned() == 34);
    //$$     reports.add(c8.report());
    //$$     if (c8.minHome() < 16) reserveBreaches++;
    //$$     totalEmptyTrips += c8.metrics().emptyTrips();
    //$$     totalUnnecessaryOpens += c8.metrics().unnecessaryChestOpens();
    //$$     totalDestChecks += c8.metrics().destChecks();
    //$$     totalSourceVisits += c8.metrics().sourceVisits();
    //$$     totalDestDeliveries += c8.metrics().destDeliveries();
    //$$     totalSourceReturns += c8.metrics().sourceReturns();
    //$$     totalWithdrawn += c8.metrics().itemsWithdrawn();
    //$$     totalDeposited += c8.metrics().itemsDeposited();
    //$$     totalReturned += c8.metrics().itemsReturned();
    //$$
    //$$     CycleOutcome c9 = runCycle(mc, server, mod, "cycle09_human_full_topup_in_transit",
    //$$             new int[]{64, 36}, new int[]{20}, new int[]{64}, false,
    //$$             (audit, minHome, snap, phase) -> audit.quarryTorches() == 64 && audit.homeTorches() == 100
    //$$                     && audit.botTorches() == 0 && audit.cursorTotal() == 0 && minHome >= 16
    //$$                     && snap.itemsReturned() == 44);
    //$$     reports.add(c9.report());
    //$$     if (c9.minHome() < 16) reserveBreaches++;
    //$$     totalEmptyTrips += c9.metrics().emptyTrips();
    //$$     totalUnnecessaryOpens += c9.metrics().unnecessaryChestOpens();
    //$$     totalDestChecks += c9.metrics().destChecks();
    //$$     totalSourceVisits += c9.metrics().sourceVisits();
    //$$     totalDestDeliveries += c9.metrics().destDeliveries();
    //$$     totalSourceReturns += c9.metrics().sourceReturns();
    //$$     totalWithdrawn += c9.metrics().itemsWithdrawn();
    //$$     totalDeposited += c9.metrics().itemsDeposited();
    //$$     totalReturned += c9.metrics().itemsReturned();
    //$$
    //$$     CycleOutcome c10 = runCycle(mc, server, mod, "cycle10_bot_restart_in_transit",
    //$$             new int[]{64, 36}, new int[]{20}, null, true,
    //$$             (audit, minHome, snap, phase) -> audit.quarryTorches() == 64 && audit.homeTorches() == 56
    //$$                     && audit.botTorches() == 0 && audit.cursorTotal() == 0 && minHome >= 16
    //$$                     && snap.sourceVisits() == 1 && snap.interruptRecoveries() >= 1);
    //$$     reports.add(c10.report());
    //$$     if (c10.minHome() < 16) reserveBreaches++;
    //$$     totalEmptyTrips += c10.metrics().emptyTrips();
    //$$     totalUnnecessaryOpens += c10.metrics().unnecessaryChestOpens();
    //$$     totalDestChecks += c10.metrics().destChecks();
    //$$     totalSourceVisits += c10.metrics().sourceVisits();
    //$$     totalDestDeliveries += c10.metrics().destDeliveries();
    //$$     totalSourceReturns += c10.metrics().sourceReturns();
    //$$     totalWithdrawn += c10.metrics().itemsWithdrawn();
    //$$     totalDeposited += c10.metrics().itemsDeposited();
    //$$     totalReturned += c10.metrics().itemsReturned();
    //$$
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@replenish stop");
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     int passed = 0;
    //$$     StringBuilder reportText = new StringBuilder();
    //$$     for (CycleReport r : reports) {
    //$$         if (r.passed()) passed++;
    //$$         String line = "REPLENISH_FIXTURE CASE " + r.name() + " => " + (r.passed() ? "PASS" : "FAIL") + " | " + r.detail();
    //$$         Debug.logHarness(line);
    //$$         reportText.append(line).append('\n');
    //$$     }
    //$$     String costLine = "REPLENISH_FIXTURE COST reserveBreaches=" + reserveBreaches
    //$$             + " emptyTrips=" + totalEmptyTrips
    //$$             + " unnecessaryChestOpens=" + totalUnnecessaryOpens
    //$$             + " destChecks=" + totalDestChecks
    //$$             + " sourceVisits=" + totalSourceVisits
    //$$             + " destDeliveries=" + totalDestDeliveries
    //$$             + " sourceReturns=" + totalSourceReturns
    //$$             + " withdrawn=" + totalWithdrawn
    //$$             + " deposited=" + totalDeposited
    //$$             + " returned=" + totalReturned;
    //$$     Debug.logHarness(costLine);
    //$$     reportText.append(costLine).append('\n');
    //$$
    //$$     String summary = "REPLENISH_FIXTURE SUMMARY passed=" + passed + "/" + reports.size()
    //$$             + " reserveBreaches=" + reserveBreaches;
    //$$     Debug.logHarness(summary);
    //$$     reportText.append(summary).append('\n');
    //$$
    //$$     Path outPath = mc.gameDirectory.toPath().resolve("worksite-replenishment-fixture-report.txt");
    //$$     Files.writeString(outPath, reportText.toString());
    //$$     Debug.logHarness("REPLENISH_FIXTURE wrote report: " + outPath.toAbsolutePath());
    //$$ }
    //$$
    //$$ private record CycleOutcome(CycleReport report, int minHome, ReplenishmentMetrics.Snapshot metrics) {
    //$$ }
    //$$
    //$$ @FunctionalInterface
    //$$ private interface Verifier {
    //$$     boolean check(ServerAudit audit, int minHomeObserved, ReplenishmentMetrics.Snapshot metrics, ReplenishmentSession.Phase phase);
    //$$ }
    //$$
    //$$ private static void buildArena(MinecraftServer server) throws Exception {
    //$$     server.submit(() -> {
    //$$         server.setDifficulty(Difficulty.PEACEFUL, true);
    //$$         ServerLevel level = server.overworld();
    //$$         for (int dx = -8; dx <= 8; dx++) {
    //$$             for (int dz = -6; dz <= 6; dz++) {
    //$$                 level.setBlockAndUpdate(new BlockPos(ARENA_X + dx, ARENA_Y - 1, ARENA_Z + dz), Blocks.STONE.defaultBlockState());
    //$$                 for (int dy = 0; dy <= 5; dy++) {
    //$$                     level.setBlockAndUpdate(new BlockPos(ARENA_X + dx, ARENA_Y + dy, ARENA_Z + dz), Blocks.AIR.defaultBlockState());
    //$$                 }
    //$$             }
    //$$         }
    //$$         level.setBlockAndUpdate(HOME_POS, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
    //$$         level.setBlockAndUpdate(QUARRY_POS, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
    //$$         return null;
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static CycleOutcome runCycle(Minecraft mc,
    //$$                                      MinecraftServer server,
    //$$                                      AltoClef mod,
    //$$                                      String name,
    //$$                                      int[] homeStacks,
    //$$                                      int[] quarryStacks,
    //$$                                      int[] humanTopUpQuarryStacks,
    //$$                                      boolean restartInTransit,
    //$$                                      Verifier verifier) throws Exception {
    //$$     // Stop prior task, close screens, and clear journal for clean independent cycle measurement.
    //$$     mc.submit(() -> {
    //$$         Task cur = mod.getUserTaskChain().getCurrentTask();
    //$$         if (cur instanceof ReplenishWorksiteTask rwt) {
    //$$             rwt.getSession().clearJournal();
    //$$         }
    //$$         mod.cancelUserTask();
    //$$         StorageHelper.closeScreen();
    //$$         try {
    //$$             Files.deleteIfExists(mc.gameDirectory.toPath().resolve("altoclef").resolve(ReplenishWorksiteTask.JOURNAL_FILE_NAME));
    //$$         } catch (Exception ignored) {
    //$$         }
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     // Server-side setup of chests and player position/inventory.
    //$$     server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         AABB box = new AABB(ARENA_X - 16, ARENA_Y - 4, ARENA_Z - 16, ARENA_X + 16, ARENA_Y + 12, ARENA_Z + 16);
    //$$         for (Entity e : level.getEntities((Entity) null, box, ent -> ent instanceof ItemEntity)) {
    //$$             e.discard();
    //$$         }
    //$$         fillChest(level, HOME_POS, homeStacks);
    //$$         fillChest(level, QUARRY_POS, quarryStacks);
    //$$
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         sp.closeContainer();
    //$$         sp.getInventory().clearContent();
    //$$         sp.containerMenu.setCarried(ItemStack.EMPTY);
    //$$         sp.setHealth(sp.getMaxHealth());
    //$$         sp.getFoodData().setFoodLevel(20);
    //$$         sp.teleportTo(ARENA_X + 0.5, ARENA_Y, ARENA_Z + 2.5);
    //$$         sp.inventoryMenu.broadcastChanges();
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     Thread.sleep(400L);
    //$$
    //$$     // Start through the normal player entry point.
    //$$     AtomicReference<ReplenishWorksiteTask> taskRef = new AtomicReference<>();
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute(RULE_CMD);
    //$$         Task current = mod.getUserTaskChain().getCurrentTask();
    //$$         if (current instanceof ReplenishWorksiteTask rwt) {
    //$$             taskRef.set(rwt);
    //$$         }
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     boolean humanInjected = false;
    //$$     boolean restartInjected = false;
    //$$     int minHomeObserved = auditServer(server).homeTorches();
    //$$     long deadline = System.currentTimeMillis() + 45_000L;
    //$$     while (System.currentTimeMillis() < deadline) {
    //$$         Thread.sleep(60L);
    //$$         ReplenishWorksiteTask task = taskRef.get();
    //$$         if (task == null) {
    //$$             break;
    //$$         }
    //$$         ServerAudit live = auditServer(server);
    //$$         // Only track settled chest counts (when player is not holding a temporary split on cursor).
    //$$         if (live.cursorTotal() == 0 && live.homeTorches() < minHomeObserved) {
    //$$             minHomeObserved = live.homeTorches();
    //$$         }
    //$$         ReplenishmentSession.Phase phase = task.getSession().phase();
    //$$
    //$$         // Scenario 4: Human player tops up quarry while courier is walking in transit!
    //$$         if (humanTopUpQuarryStacks != null && !humanInjected
    //$$                 && phase == ReplenishmentSession.Phase.DELIVER_DEST && live.botTorches() > 0) {
    //$$             humanInjected = true;
    //$$             Debug.logHarness("REPLENISH_FIXTURE " + name + ": injecting human refill into quarry while courier carries " + live.botTorches());
    //$$             server.submit(() -> {
    //$$                 fillChest(server.overworld(), QUARRY_POS, humanTopUpQuarryStacks);
    //$$                 return null;
    //$$             }).get();
    //$$         }
    //$$
    //$$         // Scenario 5: Simulate full bot restart while courier carries torches in transit!
    //$$         if (restartInTransit && !restartInjected
    //$$                 && phase == ReplenishmentSession.Phase.DELIVER_DEST && live.botTorches() > 0) {
    //$$             restartInjected = true;
    //$$             Debug.logHarness("REPLENISH_FIXTURE " + name + ": injecting full bot restart while courier carries " + live.botTorches());
    //$$             mc.submit(() -> {
    //$$                 // Cancel user task completely (new task instance must reconstruct carried torches from persisted journal).
    //$$                 mod.cancelUserTask();
    //$$                 StorageHelper.closeScreen();
    //$$                 return null;
    //$$             }).get();
    //$$             Thread.sleep(300L);
    //$$             mc.submit(() -> {
    //$$                 AltoClef.getCommandExecutor().execute(RULE_CMD);
    //$$                 Task restarted = mod.getUserTaskChain().getCurrentTask();
    //$$                 if (restarted instanceof ReplenishWorksiteTask rwt) {
    //$$                     taskRef.set(rwt);
    //$$                 }
    //$$                 return null;
    //$$             }).get();
    //$$             continue;
    //$$         }
    //$$
    //$$         if (phase == ReplenishmentSession.Phase.IDLE_MONITORING
    //$$                 || phase == ReplenishmentSession.Phase.BACKOFF
    //$$                 || task.getSession().isTerminal()) {
    //$$             // Wait briefly to confirm nothing triggers unexpectedly after settling.
    //$$             Thread.sleep(350L);
    //$$             break;
    //$$         }
    //$$     }
    //$$
    //$$     ReplenishWorksiteTask finalTask = taskRef.get();
    //$$     ReplenishmentMetrics.Snapshot snap = finalTask != null
    //$$             ? finalTask.getSession().metrics().snapshot()
    //$$             : new ReplenishmentMetrics().snapshot();
    //$$     ReplenishmentSession.Phase finalPhase = finalTask != null
    //$$             ? finalTask.getSession().phase()
    //$$             : ReplenishmentSession.Phase.FAILED;
    //$$     ServerAudit audit = auditServer(server);
    //$$     if (audit.homeTorches() < minHomeObserved) {
    //$$         minHomeObserved = audit.homeTorches();
    //$$     }
    //$$
    //$$     boolean injectedOk = (humanTopUpQuarryStacks == null || humanInjected)
    //$$             && (!restartInTransit || restartInjected);
    //$$     boolean ok = injectedOk && verifier.check(audit, minHomeObserved, snap, finalPhase);
    //$$     String detail = "audit=" + audit + " minHome=" + minHomeObserved + " phase=" + finalPhase
    //$$             + " " + snap.summary()
    //$$             + (humanTopUpQuarryStacks != null ? " humanInjected=" + humanInjected : "")
    //$$             + (restartInTransit ? " restartInjected=" + restartInjected : "");
    //$$     return new CycleOutcome(new CycleReport(name, ok, detail), minHomeObserved, snap);
    //$$ }
    //$$
    //$$ private static void fillChest(ServerLevel level, BlockPos pos, int[] torchStacks) {
    //$$     BlockEntity be = level.getBlockEntity(pos);
    //$$     if (!(be instanceof ChestBlockEntity chest)) {
    //$$         throw new IllegalStateException("Expected ChestBlockEntity at " + pos);
    //$$     }
    //$$     chest.clearContent();
    //$$     for (int i = 0; i < torchStacks.length && i < chest.getContainerSize(); i++) {
    //$$         if (torchStacks[i] > 0) {
    //$$             chest.setItem(i, new ItemStack(Items.TORCH, torchStacks[i]));
    //$$         }
    //$$     }
    //$$     chest.setChanged();
    //$$ }
    //$$
    //$$ private static int countChestTorches(ServerLevel level, BlockPos pos) {
    //$$     BlockEntity be = level.getBlockEntity(pos);
    //$$     if (!(be instanceof ChestBlockEntity chest)) {
    //$$         return -1;
    //$$     }
    //$$     int total = 0;
    //$$     for (int i = 0; i < chest.getContainerSize(); i++) {
    //$$         ItemStack st = chest.getItem(i);
    //$$         if (!st.isEmpty() && st.is(Items.TORCH)) {
    //$$             total += st.getCount();
    //$$         }
    //$$     }
    //$$     return total;
    //$$ }
    //$$
    //$$ private static ServerAudit auditServer(MinecraftServer server) throws Exception {
    //$$     return server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         int home = countChestTorches(level, HOME_POS);
    //$$         int quarry = countChestTorches(level, QUARRY_POS);
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         int bot = 0;
    //$$         for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
    //$$             ItemStack s = sp.getInventory().getItem(i);
    //$$             if (!s.isEmpty() && s.is(Items.TORCH)) {
    //$$                 bot += s.getCount();
    //$$             }
    //$$         }
    //$$         ItemStack carried = sp.containerMenu.getCarried();
    //$$         int cursor = carried.isEmpty() ? 0 : carried.getCount();
    //$$         return new ServerAudit(home, quarry, bot, cursor);
    //$$     }).get();
    //$$ }
    //#endif
}

package adris.altoclef.benchmark;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;

//#if MC >= 260000
//$$ import adris.altoclef.tasks.husbandry.BreedCowsTask;
//$$ import adris.altoclef.tasks.husbandry.HusbandryOutcome;
//$$ import adris.altoclef.tasks.husbandry.HusbandrySession;
//$$ import adris.altoclef.tasksystem.Task;
//$$ import adris.altoclef.tasksystem.TaskResult;
//$$ import adris.altoclef.util.helpers.StorageHelper;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.core.BlockPos;
//$$ import net.minecraft.server.MinecraftServer;
//$$ import net.minecraft.server.level.ServerLevel;
//$$ import net.minecraft.server.level.ServerPlayer;
//$$ import net.minecraft.world.Difficulty;
//$$ import net.minecraft.world.entity.Entity;
//$$ import net.minecraft.world.entity.EntitySpawnReason;
//$$ import net.minecraft.world.entity.EntityTypes;
//$$ import net.minecraft.world.entity.animal.cow.Cow;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.item.Items;
//$$ import net.minecraft.world.level.block.Blocks;
//$$ import net.minecraft.world.level.block.FenceGateBlock;
//$$ import net.minecraft.world.phys.AABB;
//$$
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//$$ import java.util.ArrayList;
//$$ import java.util.List;
//$$ import java.util.concurrent.atomic.AtomicReference;
//#endif

/**
 * Live interactive Minecraft Java 26.3 Fabric acceptance fixture for animal husbandry:
 * "Grow the cows in this fenced pen from two to six, then stop."
 * <p>
 * Exercises the bot exclusively through the normal player entry point ({@code @breed cows 6})
 * under ordinary game timing while running independent server-side verification across:
 * <ol>
 *     <li>Pass case: 2 adult cows + sufficient wheat -> 6 contained live cows, zero escapes,
 *         then observe through one further breeding-cooldown opportunity (6000+ ticks) to confirm zero overshoot.</li>
 *     <li>Interruption case: interrupt between feeding a pair and observing the calf, resume ->
 *         reconciles actual pen count to 6 with zero overshoot and zero escapes.</li>
 *     <li>Empty-feed case: 0 wheat -> fails cleanly with {@code FEED_SHORTAGE}, zero false completions, zero escapes.</li>
 * </ol>
 */
public final class AnimalHusbandryFixture {

    private static volatile Thread running;

    private AnimalHusbandryFixture() {
    }

    public static String start(AltoClef mod) {
        return start(mod, Boolean.getBoolean("tenorclef.husbandryFixture.exit"));
    }

    public static String start(AltoClef mod, boolean exitOnFinish) {
        //#if MC < 260000
        Debug.logWarning("HUSBANDRY_FIXTURE is only supported on Minecraft 26.3 interactive Fabric client.");
        return "HUSBANDRY_FIXTURE unsupported on this MC version";
        //#else
        //$$ if (running != null && running.isAlive()) {
        //$$     return "HUSBANDRY_FIXTURE already running";
        //$$ }
        //$$ Minecraft mc = Minecraft.getInstance();
        //$$ if (mc == null || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
        //$$     return "HUSBANDRY_FIXTURE requires an interactive singleplayer world";
        //$$ }
        //$$ Thread t = new Thread(() -> {
        //$$     try {
        //$$         runAll(mc, mod);
        //$$     } catch (Throwable e) {
        //$$         Debug.logHarness("HUSBANDRY_FIXTURE FATAL: " + e);
        //$$         e.printStackTrace();
        //$$     } finally {
        //$$         running = null;
        //$$         if (exitOnFinish || Boolean.getBoolean("tenorclef.husbandryFixture.exit")) {
        //$$             Debug.logHarness("HUSBANDRY_FIXTURE exiting client as requested");
        //$$             mc.execute(mc::stop);
        //$$         }
        //$$     }
        //$$ }, "AnimalHusbandryFixture");
        //$$ t.setDaemon(true);
        //$$ running = t;
        //$$ t.start();
        //$$ return "HUSBANDRY_FIXTURE started";
        //#endif
    }

    //#if MC >= 260000
    //$$ private record ServerAudit(int containedTotal,
    //$$                            int containedAdults,
    //$$                            int containedCalves,
    //$$                            int escapedCows,
    //$$                            boolean gateStillClosed,
    //$$                            int playerWheatRemaining) {
    //$$ }
    //$$
    //$$ private record ScenarioReport(String name, boolean passed, String detail) {
    //$$ }
    //$$
    //$$ private static final int ARENA_X = 32;
    //$$ private static final int ARENA_Y = 140;
    //$$ private static final int ARENA_Z = 48;
    //$$ // Interior pen: 5x5 floor at [ARENA_X-2 .. ARENA_X+2] x [ARENA_Z-2 .. ARENA_Z+2]
    //$$ private static final int PEN_MIN_X = ARENA_X - 2;
    //$$ private static final int PEN_MAX_X = ARENA_X + 2;
    //$$ private static final int PEN_MIN_Z = ARENA_Z - 2;
    //$$ private static final int PEN_MAX_Z = ARENA_Z + 2;
    //$$ private static final BlockPos GATE_POS = new BlockPos(ARENA_X, ARENA_Y, PEN_MAX_Z + 1);
    //$$
    //$$ private static void runAll(Minecraft mc, AltoClef mod) throws Exception {
    //$$     MinecraftServer server = mc.getSingleplayerServer();
    //$$     if (server == null) {
    //$$         Debug.logHarness("HUSBANDRY_FIXTURE no integrated server");
    //$$         return;
    //$$     }
    //$$
    //$$     buildPenArena(server);
    //$$     Thread.sleep(600L);
    //$$
    //$$     List<ScenarioReport> reports = new ArrayList<>();
    //$$
    //$$     // 1. Empty-feed failure case (fast, runs first to validate fail-fast feed shortage).
    //$$     reports.add(runEmptyFeedScenario(mc, server, mod));
    //$$
    //$$     // 2. Pass case: 2 adult cows + 16 wheat -> 6 contained live cows, plus extra cooldown overshoot check.
    //$$     reports.add(runPassScenario(mc, server, mod));
    //$$
    //$$     // 3. Interrupt between feeding pair and observing calf -> resume reconciles cleanly.
    //$$     reports.add(runInterruptScenario(mc, server, mod));
    //$$
    //$$     int passed = 0;
    //$$     StringBuilder reportText = new StringBuilder();
    //$$     for (ScenarioReport r : reports) {
    //$$         if (r.passed()) passed++;
    //$$         String line = "HUSBANDRY_FIXTURE CASE " + r.name() + " => " + (r.passed() ? "PASS" : "FAIL") + " | " + r.detail();
    //$$         Debug.logHarness(line);
    //$$         reportText.append(line).append('\n');
    //$$     }
    //$$     String summary = "HUSBANDRY_FIXTURE SUMMARY passed=" + passed + "/" + reports.size();
    //$$     Debug.logHarness(summary);
    //$$     reportText.append(summary).append('\n');
    //$$
    //$$     Path outPath = mc.gameDirectory.toPath().resolve("animal-husbandry-fixture-report.txt");
    //$$     Files.writeString(outPath, reportText.toString());
    //$$     Debug.logHarness("HUSBANDRY_FIXTURE wrote report: " + outPath.toAbsolutePath());
    //$$ }
    //$$
    //$$ private static void buildPenArena(MinecraftServer server) throws Exception {
    //$$     server.submit(() -> {
    //$$         server.setDifficulty(Difficulty.PEACEFUL, true);
    //$$         ServerLevel level = server.overworld();
    //$$         for (int dx = -8; dx <= 8; dx++) {
    //$$             for (int dz = -8; dz <= 8; dz++) {
    //$$                 level.setBlockAndUpdate(new BlockPos(ARENA_X + dx, ARENA_Y - 1, ARENA_Z + dz), Blocks.GRASS_BLOCK.defaultBlockState());
    //$$                 for (int dy = 0; dy <= 5; dy++) {
    //$$                     level.setBlockAndUpdate(new BlockPos(ARENA_X + dx, ARENA_Y + dy, ARENA_Z + dz), Blocks.AIR.defaultBlockState());
    //$$                 }
    //$$             }
    //$$         }
    //$$         // Perimeter oak fences around interior [PEN_MIN_X..PEN_MAX_X] x [PEN_MIN_Z..PEN_MAX_Z]
    //$$         for (int x = PEN_MIN_X - 1; x <= PEN_MAX_X + 1; x++) {
    //$$             level.setBlockAndUpdate(new BlockPos(x, ARENA_Y, PEN_MIN_Z - 1), Blocks.OAK_FENCE.defaultBlockState());
    //$$             level.setBlockAndUpdate(new BlockPos(x, ARENA_Y, PEN_MAX_Z + 1), Blocks.OAK_FENCE.defaultBlockState());
    //$$         }
    //$$         for (int z = PEN_MIN_Z - 1; z <= PEN_MAX_Z + 1; z++) {
    //$$             level.setBlockAndUpdate(new BlockPos(PEN_MIN_X - 1, ARENA_Y, z), Blocks.OAK_FENCE.defaultBlockState());
    //$$             level.setBlockAndUpdate(new BlockPos(PEN_MAX_X + 1, ARENA_Y, z), Blocks.OAK_FENCE.defaultBlockState());
    //$$         }
    //$$         // Closed oak fence gate on south wall to verify bot never opens it.
    //$$         level.setBlockAndUpdate(GATE_POS, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, false));
    //$$         return null;
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static void resetScenario(Minecraft mc, MinecraftServer server, AltoClef mod, int wheatCount, int initialCalves) throws Exception {
    //$$     mc.submit(() -> {
    //$$         mod.cancelUserTask();
    //$$         StorageHelper.closeScreen();
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         AABB clearBox = new AABB(ARENA_X - 24, ARENA_Y - 6, ARENA_Z - 24, ARENA_X + 24, ARENA_Y + 12, ARENA_Z + 24);
    //$$         for (Entity e : level.getEntities((Entity) null, clearBox, ent -> ent instanceof Cow)) {
    //$$             e.discard();
    //$$         }
    //$$         level.setBlockAndUpdate(GATE_POS, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, false));
    //$$
    //$$         // Spawn 2 adult cows inside the fenced pen.
    //$$         spawnCow(level, ARENA_X - 0.5, ARENA_Y, ARENA_Z, false);
    //$$         spawnCow(level, ARENA_X + 1.5, ARENA_Y, ARENA_Z, false);
    //$$         for (int i = 0; i < initialCalves; i++) {
    //$$             spawnCow(level, ARENA_X + 0.5, ARENA_Y, ARENA_Z - 1.0 + i * 0.4, true);
    //$$         }
    //$$
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         sp.closeContainer();
    //$$         sp.getInventory().clearContent();
    //$$         sp.containerMenu.setCarried(ItemStack.EMPTY);
    //$$         if (wheatCount > 0) {
    //$$             sp.getInventory().setItem(0, new ItemStack(Items.WHEAT, wheatCount));
    //$$         }
    //$$         sp.setHealth(sp.getMaxHealth());
    //$$         sp.getFoodData().setFoodLevel(20);
    //$$         // Stand outside the south fence near the pen.
    //$$         sp.teleportTo(ARENA_X + 0.5, ARENA_Y, PEN_MAX_Z + 2.5);
    //$$         sp.inventoryMenu.broadcastChanges();
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     Thread.sleep(600L);
    //$$ }
    //$$
    //$$ private static void spawnCow(ServerLevel level, double x, double y, double z, boolean baby) {
    //$$     Cow cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
    //$$     if (cow != null) {
    //$$         cow.setPos(x, y, z);
    //$$         if (baby) {
    //$$             cow.setBaby(true);
    //$$         }
    //$$         level.addFreshEntity(cow);
    //$$     }
    //$$ }
    //$$
    //$$ private static ScenarioReport runEmptyFeedScenario(Minecraft mc, MinecraftServer server, AltoClef mod) throws Exception {
    //$$     resetScenario(mc, server, mod, 0, 0);
    //$$     BreedCowsTask task = startBreedCommand(mc, mod);
    //$$     long deadline = System.currentTimeMillis() + 30_000L;
    //$$     while (System.currentTimeMillis() < deadline && task != null && !task.isFinished()) {
    //$$         Thread.sleep(200L);
    //$$     }
    //$$     ServerAudit audit = auditServer(server);
    //$$     HusbandryOutcome out = task != null ? task.getOutcome() : null;
    //$$     TaskResult res = task != null ? task.getExplicitResult() : null;
    //$$     boolean ok = res == TaskResult.FAILURE
    //$$             && out != null && out.status() == HusbandryOutcome.Status.FEED_SHORTAGE
    //$$             && audit.containedTotal() == 2
    //$$             && audit.escapedCows() == 0
    //$$             && audit.gateStillClosed();
    //$$     return new ScenarioReport("fail_empty_feed", ok,
    //$$             "result=" + res + " outcome=" + out + " audit=" + audit);
    //$$ }
    //$$
    //$$ private static ScenarioReport runPassScenario(Minecraft mc, MinecraftServer server, AltoClef mod) throws Exception {
    //$$     int startWheat = 16;
    //$$     resetScenario(mc, server, mod, startWheat, 0);
    //$$     BreedCowsTask task = startBreedCommand(mc, mod);
    //$$     // Under ordinary game timing: 4 births from 2 adults requires 3x 5-minute cooldowns (~15 min) + buffer.
    //$$     long deadline = System.currentTimeMillis() + 22L * 60L * 1000L;
    //$$     long nextProgressLog = System.currentTimeMillis() + 30_000L;
    //$$     while (System.currentTimeMillis() < deadline && task != null && !task.isFinished()) {
    //$$         if (System.currentTimeMillis() >= nextProgressLog) {
    //$$             Debug.logHarness("HUSBANDRY_FIXTURE progress: " + task.getStatusLine());
    //$$             nextProgressLog = System.currentTimeMillis() + 30_000L;
    //$$         }
    //$$         Thread.sleep(500L);
    //$$     }
    //$$     ServerAudit auditAtStop = auditServer(server);
    //$$     HusbandryOutcome out = task != null ? task.getOutcome() : null;
    //$$     TaskResult res = task != null ? task.getExplicitResult() : null;
    //$$
    //$$     // Observe through one further breeding-cooldown opportunity (6000+ ticks = ~5 min 10s) to confirm zero overshoot.
    //$$     long overshootWaitEnd = System.currentTimeMillis() + 310_000L;
    //$$     while (System.currentTimeMillis() < overshootWaitEnd) {
    //$$         Thread.sleep(2_000L);
    //$$     }
    //$$     ServerAudit auditAfterCooldown = auditServer(server);
    //$$
    //$$     int wheatConsumed = startWheat - auditAfterCooldown.playerWheatRemaining();
    //$$     boolean ok = res == TaskResult.SUCCESS
    //$$             && out != null && out.status() == HusbandryOutcome.Status.SUCCESS
    //$$             && auditAtStop.containedTotal() == 6
    //$$             && auditAfterCooldown.containedTotal() == 6
    //$$             && auditAfterCooldown.escapedCows() == 0
    //$$             && auditAfterCooldown.gateStillClosed()
    //$$             && auditAfterCooldown.playerWheatRemaining() == auditAtStop.playerWheatRemaining()
    //$$             && wheatConsumed >= 8;
    //$$     return new ScenarioReport("pass_two_to_six_and_overshoot_check", ok,
    //$$             "result=" + res + " outcome=" + out
    //$$                     + " wheatConsumed=" + wheatConsumed
    //$$                     + " atStop=" + auditAtStop
    //$$                     + " afterCooldown=" + auditAfterCooldown);
    //$$ }
    //$$
    //$$ private static ScenarioReport runInterruptScenario(Minecraft mc, MinecraftServer server, AltoClef mod) throws Exception {
    //$$     // Start with 2 adults + 3 calves (= 5 cows) so one pair breed reaches 6; interrupt while waiting for calf.
    //$$     int startWheat = 8;
    //$$     resetScenario(mc, server, mod, startWheat, 3);
    //$$     BreedCowsTask task = startBreedCommand(mc, mod);
    //$$     boolean interrupted = false;
    //$$     long deadline = System.currentTimeMillis() + 120_000L;
    //$$     while (System.currentTimeMillis() < deadline && task != null && !task.isFinished()) {
    //$$         if (!interrupted && task.getSession().phase() == HusbandrySession.Phase.WAIT_FOR_BIRTH) {
    //$$             interrupted = true;
    //$$             mc.submit(() -> {
    //$$                 task.interrupt(null);
    //$$                 task.reset();
    //$$                 return null;
    //$$             }).get();
    //$$         }
    //$$         Thread.sleep(100L);
    //$$     }
    //$$     ServerAudit audit = auditServer(server);
    //$$     HusbandryOutcome out = task != null ? task.getOutcome() : null;
    //$$     TaskResult res = task != null ? task.getExplicitResult() : null;
    //$$     boolean ok = interrupted
    //$$             && res == TaskResult.SUCCESS
    //$$             && out != null && out.status() == HusbandryOutcome.Status.SUCCESS
    //$$             && audit.containedTotal() == 6
    //$$             && audit.escapedCows() == 0
    //$$             && audit.gateStillClosed()
    //$$             && (startWheat - audit.playerWheatRemaining()) == 2;
    //$$     return new ScenarioReport("edge_interrupt_before_calf_reconcile", ok,
    //$$             "interrupted=" + interrupted + " result=" + res + " outcome=" + out + " audit=" + audit);
    //$$ }
    //$$
    //$$ private static BreedCowsTask startBreedCommand(Minecraft mc, AltoClef mod) throws Exception {
    //$$     AtomicReference<BreedCowsTask> ref = new AtomicReference<>();
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@breed cows 6 at " + ARENA_X + " " + ARENA_Y + " " + ARENA_Z);
    //$$         Task current = mod.getUserTaskChain().getCurrentTask();
    //$$         if (current instanceof BreedCowsTask bct) {
    //$$             ref.set(bct);
    //$$         }
    //$$         return null;
    //$$     }).get();
    //$$     return ref.get();
    //$$ }
    //$$
    //$$ private static ServerAudit auditServer(MinecraftServer server) throws Exception {
    //$$     return server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         AABB scanBox = new AABB(ARENA_X - 24, ARENA_Y - 6, ARENA_Z - 24, ARENA_X + 24, ARENA_Y + 12, ARENA_Z + 24);
    //$$         int containedAdults = 0;
    //$$         int containedCalves = 0;
    //$$         int escaped = 0;
    //$$         for (Entity e : level.getEntities((Entity) null, scanBox, ent -> ent instanceof Cow && ent.isAlive())) {
    //$$             Cow cow = (Cow) e;
    //$$             boolean inside = cow.getX() >= PEN_MIN_X && cow.getX() < PEN_MAX_X + 1.0
    //$$                     && cow.getZ() >= PEN_MIN_Z && cow.getZ() < PEN_MAX_Z + 1.0
    //$$                     && cow.getY() >= ARENA_Y - 0.5 && cow.getY() <= ARENA_Y + 2.5;
    //$$             if (inside) {
    //$$                 if (cow.isBaby()) containedCalves++;
    //$$                 else containedAdults++;
    //$$             } else {
    //$$                 escaped++;
    //$$             }
    //$$         }
    //$$         boolean gateClosed = !level.getBlockState(GATE_POS).getValue(FenceGateBlock.OPEN);
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         int wheat = 0;
    //$$         for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
    //$$             ItemStack st = sp.getInventory().getItem(i);
    //$$             if (st.is(Items.WHEAT)) {
    //$$                 wheat += st.getCount();
    //$$             }
    //$$         }
    //$$         return new ServerAudit(containedAdults + containedCalves, containedAdults, containedCalves,
    //$$                 escaped, gateClosed, wheat);
    //$$     }).get();
    //$$ }
    //#endif
}

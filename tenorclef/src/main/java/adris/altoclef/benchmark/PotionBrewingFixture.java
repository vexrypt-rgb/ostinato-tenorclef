package adris.altoclef.benchmark;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;

//#if MC >= 260000
//$$ import adris.altoclef.tasks.brewing.BrewPotionTask;
//$$ import adris.altoclef.tasks.brewing.BrewingOutcome;
//$$ import adris.altoclef.tasksystem.Task;
//$$ import adris.altoclef.tasksystem.TaskResult;
//$$ import adris.altoclef.util.ItemComponentReader;
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
//$$ import net.minecraft.world.item.alchemy.PotionContents;
//$$ import net.minecraft.world.item.alchemy.Potions;
//$$ import net.minecraft.world.level.block.Blocks;
//$$ import net.minecraft.world.level.block.ChestBlock;
//$$ import net.minecraft.world.level.block.entity.BlockEntity;
//$$ import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
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
 * Live interactive Minecraft Java 26.3 Fabric acceptance fixture for potion brewing:
 * "Use the supplies beside this brewing stand to make three ordinary potions of healing."
 * <p>
 * Exercises the bot exclusively through the normal player entry point ({@code @brew healing 3})
 * while running independent server-side verification across all required scenarios:
 * <ol>
 *     <li>Pass case: stocked supplies beside brewing stand -> 3 exact ordinary Potions of Healing, exact ingredient consumption</li>
 *     <li>Interrupt after stage 1 (Awkward Potions complete) and resume -> same 3 final potions without consuming Nether Wart twice</li>
 *     <li>Missing fuel -> fails cleanly without consuming unrelated items or claiming completion</li>
 *     <li>Occupied stand (unexpected item / another batch) -> leaves stand contents untouched, reports conflict, never claims completion</li>
 * </ol>
 */
public final class PotionBrewingFixture {

    private static volatile Thread running;

    private PotionBrewingFixture() {
    }

    public static String start(AltoClef mod) {
        return start(mod, Boolean.getBoolean("tenorclef.brewFixture.exit"));
    }

    public static String start(AltoClef mod, boolean exitOnFinish) {
        //#if MC < 260000
        Debug.logWarning("BREW_FIXTURE is only supported on Minecraft 26.3 interactive Fabric client.");
        return "BREW_FIXTURE unsupported on this MC version";
        //#else
        //$$ if (running != null && running.isAlive()) {
        //$$     return "BREW_FIXTURE already running";
        //$$ }
        //$$ Minecraft mc = Minecraft.getInstance();
        //$$ if (mc == null || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
        //$$     return "BREW_FIXTURE requires an interactive singleplayer world";
        //$$ }
        //$$ Thread t = new Thread(() -> {
        //$$     try {
        //$$         runAll(mc, mod);
        //$$     } catch (Throwable e) {
        //$$         Debug.logHarness("BREW_FIXTURE FATAL: " + e);
        //$$         e.printStackTrace();
        //$$     } finally {
        //$$         running = null;
        //$$         if (exitOnFinish || Boolean.getBoolean("tenorclef.brewFixture.exit")) {
        //$$             Debug.logHarness("BREW_FIXTURE exiting client as requested");
        //$$             mc.execute(mc::stop);
        //$$         }
        //$$     }
        //$$ }, "PotionBrewingFixture");
        //$$ t.setDaemon(true);
        //$$ running = t;
        //$$ t.start();
        //$$ return "BREW_FIXTURE started";
        //#endif
    }

    //#if MC >= 260000
    //$$ private record ServerAudit(int playerExactHealing,
    //$$                            int playerWrongPotions,
    //$$                            int playerCursorCount,
    //$$                            int totalNetherWart,
    //$$                            int totalMelonSlices,
    //$$                            int totalBlazePowder,
    //$$                            int totalWaterBottles,
    //$$                            int chestDecoyDiamonds,
    //$$                            int standAwkwardCount,
    //$$                            int standNonEmptySlots,
    //$$                            String standSummary) {
    //$$ }
    //$$
    //$$ private record ScenarioReport(String name, boolean passed, String detail) {
    //$$ }
    //$$
    //$$ private static final int ARENA_X = 0;
    //$$ private static final int ARENA_Y = 140;
    //$$ private static final int ARENA_Z = 48;
    //$$ private static final BlockPos STAND_POS = new BlockPos(ARENA_X, ARENA_Y, ARENA_Z);
    //$$ private static final BlockPos CHEST_POS = new BlockPos(ARENA_X + 2, ARENA_Y, ARENA_Z);
    //$$
    //$$ private static void runAll(Minecraft mc, AltoClef mod) throws Exception {
    //$$     MinecraftServer server = mc.getSingleplayerServer();
    //$$     if (server == null) {
    //$$         Debug.logHarness("BREW_FIXTURE no integrated server");
    //$$         return;
    //$$     }
    //$$
    //$$     buildArena(server);
    //$$     Thread.sleep(600L);
    //$$
    //$$     List<ScenarioReport> reports = new ArrayList<>();
    //$$
    //$$     // 1. Pass case: 3 water bottles, 4 nether wart, 4 melon slices, 2 blaze powder, decoy items -> 3 exact ordinary Healing potions.
    //$$     reports.add(runScenario(mc, server, mod, "pass_three_ordinary_healing",
    //$$             true, false, false,
    //$$             (audit, outcome, result, interrupted, beforeStand) ->
    //$$                     audit.playerExactHealing() == 3
    //$$                             && audit.playerWrongPotions() == 0
    //$$                             && audit.playerCursorCount() == 0
    //$$                             && audit.totalWaterBottles() == 0
    //$$                             && audit.totalNetherWart() == 3      // started with 4 -> consumed exactly 1
    //$$                             && audit.totalMelonSlices() == 3     // started with 4 -> consumed exactly 1
    //$$                             && audit.totalBlazePowder() == 1     // started with 2 -> consumed exactly 1
    //$$                             && audit.chestDecoyDiamonds() == 5   // untouched
    //$$                             && result == TaskResult.SUCCESS
    //$$                             && outcome != null && outcome.status() == BrewingOutcome.Status.SUCCESS));
    //$$
    //$$     // 2. Edge case: interrupt after Stage 1 (3 Awkward Potions complete) and resume -> same 3 final potions, Nether Wart consumed once.
    //$$     reports.add(runScenario(mc, server, mod, "edge_interrupt_after_stage1_resume",
    //$$             true, false, true,
    //$$             (audit, outcome, result, interrupted, beforeStand) ->
    //$$                     interrupted
    //$$                             && audit.playerExactHealing() == 3
    //$$                             && audit.playerWrongPotions() == 0
    //$$                             && audit.playerCursorCount() == 0
    //$$                             && audit.totalWaterBottles() == 0
    //$$                             && audit.totalNetherWart() == 3      // consumed ONCE, NOT twice!
    //$$                             && audit.totalMelonSlices() == 3     // consumed once
    //$$                             && audit.totalBlazePowder() == 1     // consumed once
    //$$                             && audit.chestDecoyDiamonds() == 5
    //$$                             && result == TaskResult.SUCCESS
    //$$                             && outcome != null && outcome.status() == BrewingOutcome.Status.SUCCESS));
    //$$
    //$$     // 3. Failure case: missing fuel -> fails cleanly without consuming unrelated items or claiming completion.
    //$$     reports.add(runScenario(mc, server, mod, "fail_missing_fuel",
    //$$             false, false, false,
    //$$             (audit, outcome, result, interrupted, beforeStand) ->
    //$$                     audit.playerExactHealing() == 0
    //$$                             && audit.playerWrongPotions() == 0
    //$$                             && audit.playerCursorCount() == 0
    //$$                             && audit.totalWaterBottles() == 3    // untouched!
    //$$                             && audit.totalNetherWart() == 4      // untouched!
    //$$                             && audit.totalMelonSlices() == 4     // untouched!
    //$$                             && audit.totalBlazePowder() == 0
    //$$                             && audit.chestDecoyDiamonds() == 5   // untouched!
    //$$                             && audit.standNonEmptySlots() == 0   // stand untouched!
    //$$                             && result == TaskResult.FAILURE
    //$$                             && outcome != null && outcome.status() == BrewingOutcome.Status.MISSING_FUEL));
    //$$
    //$$     // 4. Failure case: occupied stand (another player's batch) -> leaves stand untouched, reports conflict.
    //$$     reports.add(runScenario(mc, server, mod, "fail_occupied_stand",
    //$$             true, true, false,
    //$$             (audit, outcome, result, interrupted, beforeStand) ->
    //$$                     audit.playerExactHealing() == 0
    //$$                             && audit.playerWrongPotions() == 0
    //$$                             && audit.playerCursorCount() == 0
    //$$                             && audit.totalWaterBottles() == 3    // chest untouched!
    //$$                             && audit.totalNetherWart() == 4      // chest untouched!
    //$$                             && audit.totalMelonSlices() == 4     // chest untouched!
    //$$                             && audit.totalBlazePowder() == 2     // chest untouched!
    //$$                             && audit.chestDecoyDiamonds() == 5   // chest untouched!
    //$$                             && audit.standSummary().equals(beforeStand) // stand 100% untouched!
    //$$                             && result == TaskResult.FAILURE
    //$$                             && outcome != null && outcome.status() == BrewingOutcome.Status.STAND_OCCUPIED));
    //$$
    //$$     int passed = 0;
    //$$     StringBuilder reportText = new StringBuilder();
    //$$     for (ScenarioReport r : reports) {
    //$$         if (r.passed()) passed++;
    //$$         String line = "BREW_FIXTURE CASE " + r.name() + " => " + (r.passed() ? "PASS" : "FAIL") + " | " + r.detail();
    //$$         Debug.logHarness(line);
    //$$         reportText.append(line).append('\n');
    //$$     }
    //$$     String summary = "BREW_FIXTURE SUMMARY passed=" + passed + "/" + reports.size();
    //$$     Debug.logHarness(summary);
    //$$     reportText.append(summary).append('\n');
    //$$
    //$$     Path outPath = mc.gameDirectory.toPath().resolve("potion-brewing-fixture-report.txt");
    //$$     Files.writeString(outPath, reportText.toString());
    //$$     Debug.logHarness("BREW_FIXTURE wrote report: " + outPath.toAbsolutePath());
    //$$ }
    //$$
    //$$ @FunctionalInterface
    //$$ private interface Verifier {
    //$$     boolean check(ServerAudit audit, BrewingOutcome outcome, TaskResult result, boolean interrupted, String standBefore);
    //$$ }
    //$$
    //$$ private static void buildArena(MinecraftServer server) throws Exception {
    //$$     server.submit(() -> {
    //$$         server.setDifficulty(Difficulty.PEACEFUL, true);
    //$$         ServerLevel level = server.overworld();
    //$$         for (int dx = -6; dx <= 6; dx++) {
    //$$             for (int dz = -6; dz <= 6; dz++) {
    //$$                 level.setBlockAndUpdate(new BlockPos(ARENA_X + dx, ARENA_Y - 1, ARENA_Z + dz), Blocks.STONE.defaultBlockState());
    //$$                 for (int dy = 0; dy <= 5; dy++) {
    //$$                     level.setBlockAndUpdate(new BlockPos(ARENA_X + dx, ARENA_Y + dy, ARENA_Z + dz), Blocks.AIR.defaultBlockState());
    //$$                 }
    //$$             }
    //$$         }
    //$$         level.setBlockAndUpdate(STAND_POS, Blocks.BREWING_STAND.defaultBlockState());
    //$$         level.setBlockAndUpdate(CHEST_POS, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
    //$$         return null;
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static ScenarioReport runScenario(Minecraft mc,
    //$$                                           MinecraftServer server,
    //$$                                           AltoClef mod,
    //$$                                           String name,
    //$$                                           boolean includeFuel,
    //$$                                           boolean occupyStand,
    //$$                                           boolean interruptAfterStage1,
    //$$                                           Verifier verifier) throws Exception {
    //$$     mc.submit(() -> {
    //$$         mod.cancelUserTask();
    //$$         StorageHelper.closeScreen();
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     // Reset brewing stand (fresh block entity so internal fuel/brewTime reset to 0), chest, and player state.
    //$$     String standBefore = server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         AABB box = new AABB(ARENA_X - 12, ARENA_Y - 4, ARENA_Z - 12, ARENA_X + 12, ARENA_Y + 10, ARENA_Z + 12);
    //$$         for (Entity e : level.getEntities((Entity) null, box, ent -> ent instanceof ItemEntity)) {
    //$$             e.discard();
    //$$         }
    //$$         // Recreate stand block so internal fuel gauge starts strictly at 0.
    //$$         level.setBlockAndUpdate(STAND_POS, Blocks.AIR.defaultBlockState());
    //$$         level.setBlockAndUpdate(STAND_POS, Blocks.BREWING_STAND.defaultBlockState());
    //$$         level.setBlockAndUpdate(CHEST_POS, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
    //$$
    //$$         BlockEntity standBe = level.getBlockEntity(STAND_POS);
    //$$         if (standBe instanceof BrewingStandBlockEntity stand) {
    //$$             stand.clearContent();
    //$$             if (occupyStand) {
    //$$                 stand.setItem(0, PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
    //$$                 stand.setItem(1, PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HEALING));
    //$$                 stand.setItem(3, new ItemStack(Items.REDSTONE, 1));
    //$$             }
    //$$             stand.setChanged();
    //$$         }
    //$$
    //$$         BlockEntity chestBe = level.getBlockEntity(CHEST_POS);
    //$$         if (chestBe instanceof ChestBlockEntity chest) {
    //$$             chest.clearContent();
    //$$             chest.setItem(0, PotionContents.createItemStack(Items.POTION, Potions.WATER));
    //$$             chest.setItem(1, PotionContents.createItemStack(Items.POTION, Potions.WATER));
    //$$             chest.setItem(2, PotionContents.createItemStack(Items.POTION, Potions.WATER));
    //$$             chest.setItem(3, new ItemStack(Items.NETHER_WART, 4));
    //$$             chest.setItem(4, new ItemStack(Items.GLISTERING_MELON_SLICE, 4));
    //$$             if (includeFuel) {
    //$$                 chest.setItem(5, new ItemStack(Items.BLAZE_POWDER, 2));
    //$$             }
    //$$             // Decoy items to verify exact potion form filtering and untouched unrelated items:
    //$$             chest.setItem(6, PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HEALING));
    //$$             chest.setItem(7, PotionContents.createItemStack(Items.POTION, Potions.STRONG_HEALING));
    //$$             chest.setItem(8, new ItemStack(Items.DIAMOND, 5));
    //$$             chest.setChanged();
    //$$         }
    //$$
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         sp.closeContainer();
    //$$         sp.getInventory().clearContent();
    //$$         sp.containerMenu.setCarried(ItemStack.EMPTY);
    //$$         sp.setHealth(sp.getMaxHealth());
    //$$         sp.getFoodData().setFoodLevel(20);
    //$$         sp.teleportTo(ARENA_X + 0.5, ARENA_Y, ARENA_Z + 2.5);
    //$$         sp.inventoryMenu.broadcastChanges();
    //$$         return summarizeStand(level);
    //$$     }).get();
    //$$
    //$$     Thread.sleep(600L);
    //$$
    //$$     // Dispatch normal player command through CommandExecutor -> BrewCommand -> BrewPotionTask.
    //$$     AtomicReference<BrewPotionTask> taskRef = new AtomicReference<>();
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@brew healing 3 at "
    //$$                 + STAND_POS.getX() + " " + STAND_POS.getY() + " " + STAND_POS.getZ());
    //$$         Task current = mod.getUserTaskChain().getCurrentTask();
    //$$         if (current instanceof BrewPotionTask bpt) {
    //$$             taskRef.set(bpt);
    //$$         }
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     boolean interruptedOnce = false;
    //$$     // Vanilla two-stage brew takes ~40s (20s per stage) + walk/click overhead -> 90s deadline.
    //$$     long deadline = System.currentTimeMillis() + 90_000L;
    //$$     while (System.currentTimeMillis() < deadline) {
    //$$         Thread.sleep(interruptAfterStage1 && !interruptedOnce ? 100L : 200L);
    //$$         BrewPotionTask bpt = taskRef.get();
    //$$         if (bpt == null) {
    //$$             break;
    //$$         }
    //$$         if (interruptAfterStage1 && !interruptedOnce) {
    //$$             ServerAudit mid = auditServer(server);
    //$$             if (mid.standAwkwardCount() == 3) {
    //$$                 interruptedOnce = true;
    //$$                 Debug.logHarness("BREW_FIXTURE injecting interruption right after Stage 1 (3 Awkward Potions complete): mid=" + mid);
    //$$                 mc.submit(() -> {
    //$$                     mod.getUserTaskChain().onInterrupt(null);
    //$$                     StorageHelper.closeScreen();
    //$$                     return null;
    //$$                 }).get();
    //$$             }
    //$$         }
    //$$         boolean chainActive = mc.submit(() -> mod.getUserTaskChain().isActive()).get();
    //$$         if (!chainActive || bpt.getSession().isTerminal()) {
    //$$             break;
    //$$         }
    //$$     }
    //$$
    //$$     Thread.sleep(400L);
    //$$
    //$$     BrewPotionTask bpt = taskRef.get();
    //$$     BrewingOutcome outcome = bpt != null ? bpt.getOutcome() : null;
    //$$     TaskResult result = bpt != null ? bpt.getLastResult() : null;
    //$$     ServerAudit audit = auditServer(server);
    //$$
    //$$     boolean ok = verifier.check(audit, outcome, result, interruptedOnce, standBefore);
    //$$     String detail = "audit=" + audit + " result=" + result + " outcome=" + outcome
    //$$             + (interruptAfterStage1 ? " interrupted=" + interruptedOnce : "");
    //$$     return new ScenarioReport(name, ok, detail);
    //$$ }
    //$$
    //$$ private static ServerAudit auditServer(MinecraftServer server) throws Exception {
    //$$     return server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         int exactHealing = 0;
    //$$         int wrongPotions = 0;
    //$$         int wart = 0;
    //$$         int melon = 0;
    //$$         int blaze = 0;
    //$$         int water = 0;
    //$$         int diamonds = 0;
    //$$         int standAwkward = 0;
    //$$         int standNonEmpty = 0;
    //$$
    //$$         for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
    //$$             ItemStack s = sp.getInventory().getItem(i);
    //$$             if (s.isEmpty()) continue;
    //$$             if (isExactOrdinaryPotion(s, "minecraft:healing")) {
    //$$                 exactHealing += s.getCount();
    //$$             } else if (s.is(Items.POTION) || s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)) {
    //$$                 if (isExactOrdinaryPotion(s, "minecraft:water")) {
    //$$                     water += s.getCount();
    //$$                 } else {
    //$$                     wrongPotions += s.getCount();
    //$$                 }
    //$$             } else if (s.is(Items.NETHER_WART)) {
    //$$                 wart += s.getCount();
    //$$             } else if (s.is(Items.GLISTERING_MELON_SLICE)) {
    //$$                 melon += s.getCount();
    //$$             } else if (s.is(Items.BLAZE_POWDER)) {
    //$$                 blaze += s.getCount();
    //$$             }
    //$$         }
    //$$         int cursorCount = sp.containerMenu.getCarried().isEmpty() ? 0 : sp.containerMenu.getCarried().getCount();
    //$$
    //$$         BlockEntity chestBe = level.getBlockEntity(CHEST_POS);
    //$$         if (chestBe instanceof ChestBlockEntity chest) {
    //$$             for (int i = 0; i < chest.getContainerSize(); i++) {
    //$$                 ItemStack s = chest.getItem(i);
    //$$                 if (s.isEmpty()) continue;
    //$$                 if (isExactOrdinaryPotion(s, "minecraft:water")) water += s.getCount();
    //$$                 else if (s.is(Items.NETHER_WART)) wart += s.getCount();
    //$$                 else if (s.is(Items.GLISTERING_MELON_SLICE)) melon += s.getCount();
    //$$                 else if (s.is(Items.BLAZE_POWDER)) blaze += s.getCount();
    //$$                 else if (s.is(Items.DIAMOND)) diamonds += s.getCount();
    //$$             }
    //$$         }
    //$$
    //$$         BlockEntity standBe = level.getBlockEntity(STAND_POS);
    //$$         if (standBe instanceof BrewingStandBlockEntity stand) {
    //$$             for (int i = 0; i < stand.getContainerSize(); i++) {
    //$$                 ItemStack s = stand.getItem(i);
    //$$                 if (s.isEmpty()) continue;
    //$$                 standNonEmpty++;
    //$$                 if (i < 3 && isExactOrdinaryPotion(s, "minecraft:awkward")) standAwkward++;
    //$$                 if (isExactOrdinaryPotion(s, "minecraft:water")) water += s.getCount();
    //$$                 else if (s.is(Items.NETHER_WART)) wart += s.getCount();
    //$$                 else if (s.is(Items.GLISTERING_MELON_SLICE)) melon += s.getCount();
    //$$                 else if (s.is(Items.BLAZE_POWDER)) blaze += s.getCount();
    //$$             }
    //$$         }
    //$$         return new ServerAudit(exactHealing, wrongPotions, cursorCount, wart, melon, blaze, water,
    //$$                 diamonds, standAwkward, standNonEmpty, summarizeStand(level));
    //$$     }).get();
    //$$ }
    //$$
    //$$ private static boolean isExactOrdinaryPotion(ItemStack stack, String potionId) {
    //$$     return stack != null && !stack.isEmpty()
    //$$             && stack.is(Items.POTION)
    //$$             && potionId.equals(ItemComponentReader.readPotionId(stack))
    //$$             && !ItemComponentReader.hasCustomPotionEffects(stack);
    //$$ }
    //$$
    //$$ private static String summarizeStand(ServerLevel level) {
    //$$     BlockEntity be = level.getBlockEntity(STAND_POS);
    //$$     if (!(be instanceof BrewingStandBlockEntity stand)) return "missing";
    //$$     StringBuilder sb = new StringBuilder();
    //$$     for (int i = 0; i < stand.getContainerSize(); i++) {
    //$$         ItemStack s = stand.getItem(i);
    //$$         if (i > 0) sb.append(';');
    //$$         sb.append(i).append('=');
    //$$         if (s.isEmpty()) {
    //$$             sb.append("empty");
    //$$         } else {
    //$$             sb.append(ItemComponentReader.itemId(s));
    //$$             String pid = ItemComponentReader.readPotionId(s);
    //$$             if (!pid.isEmpty()) sb.append('[').append(pid).append(']');
    //$$             sb.append('x').append(s.getCount());
    //$$         }
    //$$     }
    //$$     return sb.toString();
    //$$ }
    //#endif
}

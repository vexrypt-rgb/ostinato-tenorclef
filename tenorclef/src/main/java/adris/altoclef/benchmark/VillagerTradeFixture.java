package adris.altoclef.benchmark;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;

//#if MC >= 260000
//$$ import adris.altoclef.tasks.trading.TradeWithVillagerTask;
//$$ import adris.altoclef.tasks.trading.VillagerTradeOutcome;
//$$ import adris.altoclef.tasksystem.Task;
//$$ import adris.altoclef.tasksystem.TaskResult;
//$$ import adris.altoclef.util.ItemComponentReader;
//$$ import adris.altoclef.util.helpers.StorageHelper;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.core.BlockPos;
//$$ import net.minecraft.core.Holder;
//$$ import net.minecraft.core.component.DataComponents;
//$$ import net.minecraft.core.registries.Registries;
//$$ import net.minecraft.resources.ResourceKey;
//$$ import net.minecraft.server.MinecraftServer;
//$$ import net.minecraft.server.level.ServerLevel;
//$$ import net.minecraft.server.level.ServerPlayer;
//$$ import net.minecraft.world.Difficulty;
//$$ import net.minecraft.world.entity.Entity;
//$$ import net.minecraft.world.entity.EntitySpawnReason;
//$$ import net.minecraft.world.entity.EntityTypes;
//$$ import net.minecraft.world.entity.item.ItemEntity;
//$$ import net.minecraft.world.entity.npc.villager.Villager;
//$$ import net.minecraft.world.entity.npc.villager.VillagerData;
//$$ import net.minecraft.world.entity.npc.villager.VillagerProfession;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.item.Items;
//$$ import net.minecraft.world.item.enchantment.Enchantment;
//$$ import net.minecraft.world.item.enchantment.Enchantments;
//$$ import net.minecraft.world.item.enchantment.ItemEnchantments;
//$$ import net.minecraft.world.item.trading.ItemCost;
//$$ import net.minecraft.world.item.trading.MerchantOffer;
//$$ import net.minecraft.world.item.trading.MerchantOffers;
//$$ import net.minecraft.world.level.block.Blocks;
//$$ import net.minecraft.world.phys.AABB;
//$$
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//$$ import java.util.ArrayList;
//$$ import java.util.List;
//$$ import java.util.Map;
//$$ import java.util.Optional;
//$$ import java.util.concurrent.atomic.AtomicReference;
//#endif

/**
 * Live interactive Minecraft Java 26.3 Fabric acceptance fixture for villager trading.
 * <p>
 * Privileged fixture setup + independent server-side observer exercising the bot exclusively
 * through the normal player entry point ({@code @get enchanted_book{mending} 1 max_emeralds=20}).
 * Covers:
 * <ol>
 *     <li>Pass case: librarian with two look-alike enchanted book offers (Unbreaking III + Mending), 20-emerald cap</li>
 *     <li>Over-budget failure case: Mending costs 24 emeralds (> 20 cap) -> spends 0 emeralds, reports OVER_BUDGET</li>
 *     <li>Sold-out failure case: Mending offer has 0 uses remaining -> spends 0 emeralds, reports SOLD_OUT</li>
 *     <li>Post-commit interruption: interrupts task & closes screen right after taking output -> reconciles on resume, never buys a 2nd book</li>
 * </ol>
 */
public final class VillagerTradeFixture {

    private static volatile Thread running;

    private VillagerTradeFixture() {
    }

    public static String start(AltoClef mod) {
        //#if MC < 260000
        Debug.logWarning("VILLAGER_FIXTURE is only supported on Minecraft 26.3 interactive Fabric client.");
        return "VILLAGER_FIXTURE unsupported on this MC version";
        //#else
        //$$ if (running != null && running.isAlive()) {
        //$$     return "VILLAGER_FIXTURE already running";
        //$$ }
        //$$ Minecraft mc = Minecraft.getInstance();
        //$$ if (mc == null || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
        //$$     return "VILLAGER_FIXTURE requires an interactive singleplayer world";
        //$$ }
        //$$ Thread t = new Thread(() -> {
        //$$     try {
        //$$         runAll(mc, mod);
        //$$     } catch (Throwable e) {
        //$$         Debug.logHarness("VILLAGER_FIXTURE FATAL: " + e);
        //$$         e.printStackTrace();
        //$$     } finally {
        //$$         running = null;
        //$$         if (Boolean.getBoolean("tenorclef.villagerFixture.exit")) {
        //$$             Debug.logHarness("VILLAGER_FIXTURE exiting client as requested");
        //$$             mc.execute(mc::stop);
        //$$         }
        //$$     }
        //$$ }, "VillagerTradeFixture");
        //$$ t.setDaemon(true);
        //$$ running = t;
        //$$ t.start();
        //$$ return "VILLAGER_FIXTURE started";
        //#endif
    }

    //#if MC >= 260000
    //$$ private record ServerInventoryAudit(int mendingBooks, int otherEnchantedBooks, int emeralds, int plainBooks, int cursorTotal) {
    //$$ }
    //$$
    //$$ private record ScenarioReport(String name, boolean passed, String detail) {
    //$$ }
    //$$
    //$$ private static final int ARENA_X = 0;
    //$$ private static final int ARENA_Y = 140;
    //$$ private static final int ARENA_Z = 0;
    //$$
    //$$ private static void runAll(Minecraft mc, AltoClef mod) throws Exception {
    //$$     MinecraftServer server = mc.getSingleplayerServer();
    //$$     if (server == null) {
    //$$         Debug.logHarness("VILLAGER_FIXTURE no integrated server");
    //$$         return;
    //$$     }
    //$$
    //$$     buildArena(server);
    //$$     List<ScenarioReport> reports = new ArrayList<>();
    //$$
    //$$     reports.add(runScenario(mc, server, mod, "pass_mending_within_budget",
    //$$             20, 1, 14, 0, 12, false,
    //$$             (audit, outcome, result, commits) ->
    //$$                     audit.mendingBooks() == 1
    //$$                             && audit.otherEnchantedBooks() == 0
    //$$                             && audit.emeralds() == 6
    //$$                             && audit.plainBooks() == 0
    //$$                             && audit.cursorTotal() == 0
    //$$                             && result == TaskResult.SUCCESS
    //$$                             && outcome != null && outcome.status() == VillagerTradeOutcome.Status.SUCCESS
    //$$                             && outcome.emeraldsSpent() == 14));
    //$$
    //$$     reports.add(runScenario(mc, server, mod, "fail_over_budget",
    //$$             30, 1, 24, 0, 12, false,
    //$$             (audit, outcome, result, commits) ->
    //$$                     audit.mendingBooks() == 0
    //$$                             && audit.otherEnchantedBooks() == 0
    //$$                             && audit.emeralds() == 30
    //$$                             && audit.plainBooks() == 1
    //$$                             && result == TaskResult.FAILURE
    //$$                             && outcome != null && outcome.status() == VillagerTradeOutcome.Status.OVER_BUDGET
    //$$                             && outcome.emeraldsSpent() == 0));
    //$$
    //$$     reports.add(runScenario(mc, server, mod, "fail_sold_out",
    //$$             20, 1, 14, 12, 12, false,
    //$$             (audit, outcome, result, commits) ->
    //$$                     audit.mendingBooks() == 0
    //$$                             && audit.otherEnchantedBooks() == 0
    //$$                             && audit.emeralds() == 20
    //$$                             && audit.plainBooks() == 1
    //$$                             && result == TaskResult.FAILURE
    //$$                             && outcome != null && outcome.status() == VillagerTradeOutcome.Status.SOLD_OUT
    //$$                             && outcome.emeraldsSpent() == 0));
    //$$
    //$$     reports.add(runScenario(mc, server, mod, "edge_interrupt_after_output_taken",
    //$$             40, 2, 14, 0, 12, true,
    //$$             (audit, outcome, result, commits) ->
    //$$                     audit.mendingBooks() == 1
    //$$                             && audit.otherEnchantedBooks() == 0
    //$$                             && audit.emeralds() == 26
    //$$                             && audit.plainBooks() == 1
    //$$                             && audit.cursorTotal() == 0
    //$$                             && commits == 1
    //$$                             && result == TaskResult.SUCCESS
    //$$                             && outcome != null && outcome.status() == VillagerTradeOutcome.Status.SUCCESS
    //$$                             && outcome.emeraldsSpent() == 14));
    //$$
    //$$     int passed = 0;
    //$$     StringBuilder reportText = new StringBuilder();
    //$$     for (ScenarioReport r : reports) {
    //$$         if (r.passed()) passed++;
    //$$         String line = "VILLAGER_FIXTURE CASE " + r.name() + " => " + (r.passed() ? "PASS" : "FAIL") + " | " + r.detail();
    //$$         Debug.logHarness(line);
    //$$         reportText.append(line).append('\n');
    //$$     }
    //$$     String summary = "VILLAGER_FIXTURE SUMMARY passed=" + passed + "/" + reports.size();
    //$$     Debug.logHarness(summary);
    //$$     reportText.append(summary).append('\n');
    //$$
    //$$     Path outPath = mc.gameDirectory.toPath().resolve("villager-trade-fixture-report.txt");
    //$$     Files.writeString(outPath, reportText.toString());
    //$$     Debug.logHarness("VILLAGER_FIXTURE wrote report: " + outPath.toAbsolutePath());
    //$$ }
    //$$
    //$$ @FunctionalInterface
    //$$ private interface Verifier {
    //$$     boolean check(ServerInventoryAudit audit, VillagerTradeOutcome outcome, TaskResult result, int commitAttempts);
    //$$ }
    //$$
    //$$ private static void buildArena(MinecraftServer server) throws Exception {
    //$$     server.submit(() -> {
    //$$         server.setDifficulty(Difficulty.PEACEFUL, true);
    //$$         ServerLevel level = server.overworld();
    //$$         for (int dx = -7; dx <= 7; dx++) {
    //$$             for (int dz = -7; dz <= 7; dz++) {
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
    //$$ private static ScenarioReport runScenario(Minecraft mc,
    //$$                                           MinecraftServer server,
    //$$                                           AltoClef mod,
    //$$                                           String name,
    //$$                                           int startEmeralds,
    //$$                                           int startBooks,
    //$$                                           int mendingPrice,
    //$$                                           int mendingUses,
    //$$                                           int mendingMaxUses,
    //$$                                           boolean interruptAfterTake,
    //$$                                           Verifier verifier) throws Exception {
    //$$     // Cancel any running task and close screens on client first.
    //$$     mc.submit(() -> {
    //$$         mod.cancelUserTask();
    //$$         StorageHelper.closeScreen();
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     // Privileged server-side fixture setup: reset player inventory/pos and spawn librarian with look-alike + target offers.
    //$$     server.submit(() -> {
    //$$         ServerLevel level = server.overworld();
    //$$         AABB box = new AABB(ARENA_X - 16, ARENA_Y - 4, ARENA_Z - 16, ARENA_X + 16, ARENA_Y + 12, ARENA_Z + 16);
    //$$         for (Entity e : level.getEntities((Entity) null, box, ent -> ent instanceof Villager || ent instanceof ItemEntity)) {
    //$$             e.discard();
    //$$         }
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         sp.closeContainer();
    //$$         sp.getInventory().clearContent();
    //$$         sp.containerMenu.setCarried(ItemStack.EMPTY);
    //$$         if (startEmeralds > 0) {
    //$$             sp.getInventory().add(new ItemStack(Items.EMERALD, startEmeralds));
    //$$         }
    //$$         if (startBooks > 0) {
    //$$             sp.getInventory().add(new ItemStack(Items.BOOK, startBooks));
    //$$         }
    //$$         sp.setHealth(sp.getMaxHealth());
    //$$         sp.getFoodData().setFoodLevel(20);
    //$$         sp.teleportTo(ARENA_X + 0.5, ARENA_Y, ARENA_Z + 0.5);
    //$$         sp.inventoryMenu.broadcastChanges();
    //$$
    //$$         Villager librarian = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
    //$$         if (librarian == null) {
    //$$             throw new IllegalStateException("Failed to create Villager entity");
    //$$         }
    //$$         librarian.setPos(ARENA_X + 4.5, ARENA_Y, ARENA_Z + 0.5);
    //$$         librarian.setNoAi(true);
    //$$         librarian.setPermanentlyInvulnerable(true);
    //$$         VillagerData vd = librarian.getVillagerData()
    //$$                 .withProfession(level.registryAccess(), VillagerProfession.LIBRARIAN)
    //$$                 .withLevel(2);
    //$$         librarian.setVillagerData(vd);
    //$$
    //$$         MerchantOffers offers = new MerchantOffers();
    //$$         ItemStack lookAlikeBook = makeEnchantedBook(level, Enchantments.UNBREAKING, 3);
    //$$         ItemStack mendingBook = makeEnchantedBook(level, Enchantments.MENDING, 1);
    //$$         offers.add(new MerchantOffer(
    //$$                 new ItemCost(Items.EMERALD, 10),
    //$$                 Optional.of(new ItemCost(Items.BOOK, 1)),
    //$$                 lookAlikeBook,
    //$$                 0, 12, 1, 0.2f));
    //$$         offers.add(new MerchantOffer(
    //$$                 new ItemCost(Items.EMERALD, mendingPrice),
    //$$                 Optional.of(new ItemCost(Items.BOOK, 1)),
    //$$                 mendingBook,
    //$$                 mendingUses, mendingMaxUses, 1, 0.2f));
    //$$         librarian.setOffers(offers);
    //$$         level.addFreshEntity(librarian);
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     Thread.sleep(600L);
    //$$
    //$$     // Dispatch normal player command through CommandExecutor -> GetCommand -> TaskCatalogue -> TradeWithVillagerTask.
    //$$     AtomicReference<TradeWithVillagerTask> taskRef = new AtomicReference<>();
    //$$     mc.submit(() -> {
    //$$         AltoClef.getCommandExecutor().execute("@get enchanted_book{mending} 1 max_emeralds=20");
    //$$         Task current = mod.getUserTaskChain().getCurrentTask();
    //$$         if (current instanceof TradeWithVillagerTask tvt) {
    //$$             taskRef.set(tvt);
    //$$         }
    //$$         return null;
    //$$     }).get();
    //$$
    //$$     boolean interruptedOnce = false;
    //$$     long deadline = System.currentTimeMillis() + 20_000L;
    //$$     while (System.currentTimeMillis() < deadline) {
    //$$         Thread.sleep(interruptAfterTake && !interruptedOnce ? 10L : 50L);
    //$$         TradeWithVillagerTask tvt = taskRef.get();
    //$$         if (tvt == null) {
    //$$             break;
    //$$         }
    //$$         if (interruptAfterTake && !interruptedOnce) {
    //$$             if (tvt.getSession().commitAttempts() >= 1) {
    //$$                 ServerInventoryAudit midAudit = auditServerInventory(server);
    //$$                 if (midAudit.mendingBooks() >= 1 || midAudit.cursorTotal() >= 1) {
    //$$                     interruptedOnce = true;
    //$$                     Debug.logHarness("VILLAGER_FIXTURE injecting interruption right after output taken: midAudit=" + midAudit
    //$$                             + " commitAttempts=" + tvt.getSession().commitAttempts());
    //$$                     mc.submit(() -> {
    //$$                         // Simulate real chain interruption + screen close; SingleTaskChain will call reset() and resume!
    //$$                         mod.getUserTaskChain().onInterrupt(null);
    //$$                         StorageHelper.closeScreen();
    //$$                         return null;
    //$$                     }).get();
    //$$                 }
    //$$             }
    //$$         }
    //$$         boolean chainActive = mc.submit(() -> mod.getUserTaskChain().isActive()).get();
    //$$         if (!chainActive || tvt.getSession().isTerminal()) {
    //$$             break;
    //$$         }
    //$$     }
    //$$
    //$$     // Allow a brief settle so server container close returns any leftover payment-slot emeralds.
    //$$     Thread.sleep(400L);
    //$$
    //$$     TradeWithVillagerTask tvt = taskRef.get();
    //$$     VillagerTradeOutcome outcome = tvt != null ? tvt.getOutcome() : null;
    //$$     TaskResult result = tvt != null ? tvt.getLastResult() : null;
    //$$     int commits = tvt != null ? tvt.getSession().commitAttempts() : -1;
    //$$     ServerInventoryAudit audit = auditServerInventory(server);
    //$$
    //$$     boolean ok = verifier.check(audit, outcome, result, commits)
    //$$             && (!interruptAfterTake || interruptedOnce);
    //$$     String detail = "audit=" + audit + " result=" + result + " outcome=" + outcome
    //$$             + " commitAttempts=" + commits + (interruptAfterTake ? " interrupted=" + interruptedOnce : "");
    //$$     return new ScenarioReport(name, ok, detail);
    //$$ }
    //$$
    //$$ private static ItemStack makeEnchantedBook(ServerLevel level,
    //$$                                            ResourceKey<Enchantment> key,
    //$$                                            int enchantLevel) {
    //$$     Holder<Enchantment> holder = level.registryAccess()
    //$$             .lookupOrThrow(Registries.ENCHANTMENT)
    //$$             .getOrThrow(key);
    //$$     ItemEnchantments.Mutable mut = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
    //$$     mut.set(holder, enchantLevel);
    //$$     ItemStack book = new ItemStack(Items.ENCHANTED_BOOK, 1);
    //$$     book.set(DataComponents.STORED_ENCHANTMENTS, mut.toImmutable());
    //$$     return book;
    //$$ }
    //$$
    //$$ private static ServerInventoryAudit auditServerInventory(MinecraftServer server) throws Exception {
    //$$     return server.submit(() -> {
    //$$         ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
    //$$         int mending = 0;
    //$$         int otherBooks = 0;
    //$$         int emeralds = 0;
    //$$         int plainBooks = 0;
    //$$         for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
    //$$             ItemStack s = sp.getInventory().getItem(i);
    //$$             if (s.isEmpty()) continue;
    //$$             if (s.is(Items.EMERALD)) {
    //$$                 emeralds += s.getCount();
    //$$             } else if (s.is(Items.BOOK)) {
    //$$                 plainBooks += s.getCount();
    //$$             } else if (s.is(Items.ENCHANTED_BOOK)) {
    //$$                 Map<String, Integer> ench = ItemComponentReader.readEnchantments(s);
    //$$                 if (ench.size() == 1 && Integer.valueOf(1).equals(ench.get("minecraft:mending"))) {
    //$$                     mending += s.getCount();
    //$$                 } else {
    //$$                     otherBooks += s.getCount();
    //$$                 }
    //$$             }
    //$$         }
    //$$         ItemStack carried = sp.containerMenu.getCarried();
    //$$         int cursorTotal = carried.isEmpty() ? 0 : carried.getCount();
    //$$         return new ServerInventoryAudit(mending, otherBooks, emeralds, plainBooks, cursorTotal);
    //$$     }).get();
    //$$ }
    //#endif
}

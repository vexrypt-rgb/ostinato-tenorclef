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

package baritone.harness;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.behavior.RegionUpkeep;
import baritone.swarm.rts.BlueprintRepairSession;
import baritone.swarm.rts.RegionBaseline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Live interactive Minecraft Java 26.3 Fabric acceptance fixture for region upkeep ({@code #baseline}).
 * <pre>
 *   -Dostinato.testHarness=true -Dostinato.upkeeptest=setup  -Dostinato.upkeeptest.world=NAME [-Dostinato.upkeeptest.exit=true]
 *   -Dostinato.testHarness=true -Dostinato.upkeeptest=verify -Dostinato.upkeeptest.world=NAME [-Dostinato.upkeeptest.exit=true]
 * </pre>
 * {@code setup} creates a fresh Peaceful flat survival world, builds a stone-brick wall with a chest in it, saves a
 * baseline, registers a doorway as a deliberate edit, then removes an approved patch and a second, unregistered
 * opening, and quits. {@code verify} is a separate client launch that reopens the saved world, so the baseline has
 * crossed a real restart. It checks unloaded cells stay unknown, that a review is reported for the unclassified
 * opening, then repairs only the explicitly selected patch and a standing-policy cell. All player actions go through
 * {@code #baseline} commands; every pass/fail judgement is read independently from the integrated server's world.
 */
public final class RegionUpkeepLiveTest implements AbstractGameEventListener {

    private static final int WALL_X0 = 4;
    private static final int WALL_X1 = 13;
    private static final int WALL_Z = 6;
    private static final int WALL_H = 4;
    private static final int CHEST_X = 10;
    private static final int DOOR_X = 7;
    private static final int OPENING_X = 5;
    private static final int PATCH_X0 = 11;
    private static final int PATCH_X1 = 12;
    private static final int POLICY_X = 9;
    private static final int REPAIR_TIMEOUT_TICKS = 2400;
    private static final int FAR_BLOCKS = 600;

    private enum State {
        TITLE, WAIT_WORLD, BUILD, WAIT_SYNC, RUN,
        FAR_TELEPORT, FAR_WAIT_UNLOAD, FAR_CHECK, NEAR_TELEPORT, NEAR_WAIT_LOAD, NEAR_CHECK,
        REPAIR_WAIT, POLICY_PREP, POLICY_CHECK, POLICY_REPAIR_WAIT, DONE
    }

    private final Baritone baritone;
    private final boolean verifyPhase;
    private final String world;
    private State state = State.TITLE;
    private int ticks;
    private int wait;
    private boolean opening;
    private BufferedWriter logOut;
    private final List<String> failures = new ArrayList<>();

    private int baseY;
    private int stage;
    private Map<Long, BlockState> serverBefore = new HashMap<>();
    private List<ItemStack> chestBefore = List.of();

    private RegionUpkeepLiveTest(Baritone baritone, boolean verifyPhase) {
        this.baritone = baritone;
        this.verifyPhase = verifyPhase;
        this.world = System.getProperty("ostinato.upkeeptest.world", "upkeep_fixture");
    }

    public static void install(Baritone b) {
        String mode = System.getProperty("ostinato.upkeeptest");
        if (mode == null || mode.isEmpty()) {
            return;
        }
        if (!"setup".equals(mode) && !"verify".equals(mode)) {
            System.out.println("[upkeeptest] unknown mode " + mode + " (setup|verify)");
            return;
        }
        System.out.println("[upkeeptest] installed mode=" + mode);
        b.getGameEventHandler().registerEventListener(new RegionUpkeepLiveTest(b, "verify".equals(mode)));
    }

    // ---- logging / assertions ------------------------------------------------------------------------------------

    private void log(String line) {
        String tag = "[upkeeptest " + (verifyPhase ? "verify" : "setup") + "] ";
        System.out.println(tag + line);
        try {
            if (logOut == null && Minecraft.getInstance() != null) {
                logOut = Files.newBufferedWriter(Minecraft.getInstance().gameDirectory.toPath().resolve("upkeeptest-result.txt"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (logOut != null) {
                logOut.write(tag + line);
                logOut.newLine();
                logOut.flush();
            }
        } catch (IOException ignored) {
            // stdout still has it
        }
    }

    private void check(boolean ok, String what) {
        log((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            failures.add(what);
        }
    }

    private void cmd(String c) {
        log("player command: #" + c);
        check(baritone.getCommandManager().execute(c), "command accepted: #" + c);
    }

    /** True once {@code n} ticks have passed since {@link #restartWait}. */
    private boolean waited(int n) {
        return ++wait >= n;
    }

    private void restartWait() {
        wait = 0;
    }

    private Path fixtureFile(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("upkeeptest-" + world + ".properties");
    }

    // ---- fixture geometry ----------------------------------------------------------------------------------------

    private BlockPos wall(int x, int dy) {
        return new BlockPos(x, baseY + dy, WALL_Z);
    }

    private String boxText(int x0, int dy0, int x1, int dy1) {
        return x0 + "," + (baseY + dy0) + "," + WALL_Z + ":" + x1 + "," + (baseY + dy1) + "," + WALL_Z;
    }

    private String wallBox() {
        return boxText(WALL_X0, 0, WALL_X1, WALL_H - 1);
    }

    private int managedCells() {
        return (WALL_X1 - WALL_X0 + 1) * WALL_H - 1; // the chest is left unmanaged
    }

    // ---- tick ----------------------------------------------------------------------------------------------------

    @Override
    public void onTick(TickEvent event) {
        if (event.getState() != EventState.PRE || state == State.DONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        try {
            step(mc, event);
        } catch (RuntimeException | Error t) {
            log("HARNESS ERROR " + t);
            t.printStackTrace();
            failures.add("harness error " + t);
            finish(mc);
        }
    }

    private void step(Minecraft mc, TickEvent event) {
        if (state == State.TITLE) {
            title(mc);
            return;
        }
        if (event.getType() != TickEvent.Type.IN || mc.player == null || mc.level == null
                || mc.getSingleplayerServer() == null) {
            return;
        }
        ServerLevel level = mc.getSingleplayerServer().overworld();
        ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        if (level == null || player == null) {
            return;
        }
        switch (state) {
            case WAIT_WORLD -> {
                if (++ticks >= (verifyPhase ? 120 : 60)) {
                    mc.options.pauseOnLostFocus = false;
                    mc.getSingleplayerServer().submit(() -> {
                        var srv = mc.getSingleplayerServer();
                        srv.setDifficulty(Difficulty.PEACEFUL, true);
                        level.getGameRules().set(GameRules.SPAWN_MOBS, false, srv);
                        srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(), "gamerule advance_time false");
                        srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(), "time set noon");
                    }).join();
                    ticks = 0;
                    state = State.BUILD;
                }
            }
            case BUILD -> {
                if (verifyPhase) {
                    loadFixture(mc);
                } else {
                    buildFixture(mc, level, player);
                }
                wait = 0;
                state = State.WAIT_SYNC;
            }
            case WAIT_SYNC -> {
                if (++wait < 40 || !clientSeesWall(mc)) {
                    if (wait > 400) {
                        failures.add("client never loaded the wall");
                        log("FAIL client never loaded the wall");
                        finish(mc);
                    }
                    return;
                }
                state = State.RUN;
            }
            case RUN -> {
                if (verifyPhase) {
                    startVerify(mc, level, player);
                } else {
                    runSetup(mc, level, player);
                }
            }
            case FAR_TELEPORT -> {
                player.teleportTo(0.5 + FAR_BLOCKS, baseY + 2.0, 0.5);
                wait = 0;
                state = State.FAR_WAIT_UNLOAD;
            }
            case FAR_WAIT_UNLOAD -> {
                if (wait == 0 || wait % 200 == 0) {
                    log("far wait " + wait + ": client player at " + mc.player.blockPosition().toShortString()
                            + ", wall chunk held=" + mc.level.getChunkSource().hasChunk(WALL_X0 >> 4, WALL_Z >> 4));
                }
                if (!mc.level.getChunkSource().hasChunk(WALL_X0 >> 4, WALL_Z >> 4)) {
                    wait = 0;
                    state = State.FAR_CHECK;
                } else if (++wait > 900) {
                    check(false, "client chunk of the wall unloaded after teleporting " + FAR_BLOCKS + " blocks away");
                    state = State.NEAR_TELEPORT;
                }
            }
            case FAR_CHECK -> {
                if (++wait < 10) {
                    return;
                }
                farCheck(mc, level);
                state = State.NEAR_TELEPORT;
            }
            case NEAR_TELEPORT -> {
                player.teleportTo(8.5, baseY, 2.5);
                wait = 0;
                state = State.NEAR_WAIT_LOAD;
            }
            case NEAR_WAIT_LOAD -> {
                if (++wait < 40 || !clientSeesWall(mc)) {
                    if (wait > 600) {
                        check(false, "client reloaded the wall after returning");
                        finish(mc);
                    }
                    return;
                }
                state = State.NEAR_CHECK;
            }
            case NEAR_CHECK -> repairPhase(mc, level);
            case REPAIR_WAIT -> waitRepair(mc, level, false);
            case POLICY_PREP -> policyPrep(mc, level);
            case POLICY_CHECK -> policyCheck(mc, level);
            case POLICY_REPAIR_WAIT -> waitRepair(mc, level, true);
            default -> {
            }
        }
    }

    // ---- title / world -------------------------------------------------------------------------------------------

    private void title(Minecraft mc) {
        if (mc.player != null && mc.level != null && mc.getSingleplayerServer() != null) {
            state = State.WAIT_WORLD;
            ticks = 0;
            return;
        }
        if (mc.gui.screen() != null && mc.gui.screen().getClass().getSimpleName().contains("Onboarding")) {
            mc.options.onboardAccessibility = false;
            mc.options.save();
            mc.gui.setScreen(new TitleScreen());
            return;
        }
        if (!(mc.gui.screen() instanceof TitleScreen) || opening) {
            if (opening && ++wait % 40 == 0 && mc.gui.screen() != null) {
                log("waiting for world; screen=" + mc.gui.screen().getClass().getSimpleName());
            }
            return;
        }
        opening = true;
        mc.options.pauseOnLostFocus = false;
        Path save = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (verifyPhase) {
            if (!Files.isDirectory(save)) {
                failures.add("saved world " + world + " does not exist; run setup first");
                log("FAIL saved world " + world + " does not exist; run setup first");
                finish(mc);
                return;
            }
            log("restart: reopening saved world " + world);
            mc.createWorldOpenFlows().openWorld(world, () -> log("FAIL open cancelled"));
        } else {
            if (Files.exists(save)) {
                failures.add("world " + world + " already exists; pick a new -Dostinato.upkeeptest.world");
                log("FAIL world " + world + " already exists; pick a new -Dostinato.upkeeptest.world");
                finish(mc);
                return;
            }
            log("creating fresh flat survival world " + world);
            LevelSettings settings = new LevelSettings(world, GameType.SURVIVAL,
                    LevelSettings.DifficultySettings.DEFAULT, false, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(90263L, false, false),
                    registries -> registries.lookupOrThrow(Registries.WORLD_PRESET)
                            .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    mc.gui.screen());
        }
    }

    // ---- setup session -------------------------------------------------------------------------------------------

    private void buildFixture(Minecraft mc, ServerLevel level, ServerPlayer player) {
        baseY = level.getHeight(Heightmap.Types.WORLD_SURFACE, WALL_X0, WALL_Z);
        mc.getSingleplayerServer().submit(() -> {
            player.setGameMode(GameType.SURVIVAL);
            for (int x = WALL_X0 - 2; x <= WALL_X1 + 2; x++) {
                for (int dy = 0; dy < WALL_H + 3; dy++) {
                    level.setBlock(wall(x, dy), Blocks.AIR.defaultBlockState(), 3);
                }
            }
            for (int x = WALL_X0; x <= WALL_X1; x++) {
                for (int dy = 0; dy < WALL_H; dy++) {
                    level.setBlock(wall(x, dy), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                }
            }
            level.setBlock(wall(CHEST_X, 0), Blocks.CHEST.defaultBlockState(), 3);
            if (level.getBlockEntity(wall(CHEST_X, 0)) instanceof Container c) {
                c.setItem(0, new ItemStack(Items.DIAMOND, 5));
                c.setItem(4, new ItemStack(Items.GOLD_INGOT, 7));
                c.setItem(13, new ItemStack(Items.NAME_TAG, 1));
                c.setChanged();
                log("chest filled: " + c.getClass().getSimpleName() + " stacks=" + chestItems(level).size());
            } else {
                log("FAIL chest has no container block entity");
            }
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(Items.STONE_BRICKS, 64)); // pre-stocked materials
            player.getInventory().add(new ItemStack(Items.COBBLESTONE, 32)); // throwaway scaffolding, as a player carries
            player.getInventory().add(new ItemStack(Items.DIRT, 32));
            player.teleportTo(8.5, baseY, 2.5);
        }).join();
        log("fixture: wall x" + WALL_X0 + ".." + WALL_X1 + " y" + baseY + ".." + (baseY + WALL_H - 1) + " z" + WALL_Z
                + ", chest at " + wall(CHEST_X, 0).toShortString());
    }

    private void runSetup(Minecraft mc, ServerLevel level, ServerPlayer player) {
        switch (stage) {
            case 0 -> {
                cmd("baseline save wall " + wallBox());
                RegionUpkeep up = new RegionUpkeep(baritone);
                RegionBaseline b = up.load("wall");
                check(b.size() == managedCells(), "snapshot manages " + managedCells() + " masonry cells (saw " + b.size() + ")");
                check(b.exclusions().size() == 1, "the chest is recorded as an explicit exclusion");
                check(b.sameWorld(up.worldId(), up.dimensionId()),
                        "baseline is tagged with this world and dimension (" + b.world() + " " + b.dimension() + ")");
                // The player adds a doorway ...
                setServer(mc, level, wall(DOOR_X, 0), Blocks.AIR.defaultBlockState());
                setServer(mc, level, wall(DOOR_X, 1), Blocks.AIR.defaultBlockState());
                restartWait();
                stage = 1;
            }
            case 1 -> {
                if (!waited(20)) {
                    return;
                }
                // ... and registers it as a deliberate edit.
                cmd("baseline accept wall cells=" + cell(DOOR_X, 0) + ";" + cell(DOOR_X, 1));
                // Damage: a separate patch of approved masonry is gone.
                for (int x = PATCH_X0; x <= PATCH_X1; x++) {
                    for (int dy = 2; dy <= 3; dy++) {
                        setServer(mc, level, wall(x, dy), Blocks.AIR.defaultBlockState());
                    }
                }
                // A second opening nobody registered.
                setServer(mc, level, wall(OPENING_X, 0), Blocks.AIR.defaultBlockState());
                setServer(mc, level, wall(OPENING_X, 1), Blocks.AIR.defaultBlockState());
                restartWait();
                stage = 2;
            }
            case 2 -> {
                if (!waited(20)) {
                    return;
                }
                RegionBaseline after = new RegionUpkeep(baritone).load("wall");
                check(after.at(DOOR_X, baseY, WALL_Z) != null && after.at(DOOR_X, baseY, WALL_Z).isAir()
                                && after.at(DOOR_X, baseY + 1, WALL_Z).isAir(),
                        "doorway is stored as an accepted air state");
                Properties p = new Properties();
                p.setProperty("baseY", Integer.toString(baseY));
                try (var w = Files.newBufferedWriter(fixtureFile(mc))) {
                    p.store(w, "region upkeep fixture");
                } catch (IOException e) {
                    check(false, "fixture file written: " + e.getMessage());
                }
                log("chest before save: stacks=" + chestItems(level).size());
                mc.getSingleplayerServer().saveEverything(true, true, true);
                log("baseline file: " + RegionUpkeep.dir().resolve(RegionBaseline.fileName("wall")));
                finish(mc);
            }
            default -> {
            }
        }
    }

    // ---- verify session ------------------------------------------------------------------------------------------

    private void loadFixture(Minecraft mc) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(Files.readString(fixtureFile(mc))));
        } catch (IOException e) {
            throw new IllegalStateException("fixture file missing; run setup first: " + e.getMessage());
        }
        baseY = Integer.parseInt(p.getProperty("baseY"));
        log("restart: loaded fixture baseY=" + baseY + " (world id now " + new RegionUpkeep(baritone).worldId() + ")");
    }

    private void startVerify(Minecraft mc, ServerLevel level, ServerPlayer player) {
        // The world was saved with the damage in place. Confirm that, from the server, before anything runs.
        check(isAir(level, wall(DOOR_X, 0)) && isAir(level, wall(DOOR_X, 1)), "after restart the doorway is still open");
        check(isAir(level, wall(OPENING_X, 0)) && isAir(level, wall(OPENING_X, 1)), "after restart the second opening is still open");
        check(isAir(level, wall(PATCH_X0, 2)) && isAir(level, wall(PATCH_X1, 3)), "after restart the damaged patch is still missing");
        chestBefore = chestItems(level);
        check(chestBefore.size() == 3, "chest still holds its contents after restart (" + chestBefore.size() + " stacks)");
        // Baseline file survived the restart.
        RegionBaseline b = new RegionUpkeep(baritone).load("wall");
        check(b.size() == managedCells(), "baseline survived the restart with " + b.size() + " managed cells");
        // Everything is now far from the wall: unknown, never missing.
        state = State.FAR_TELEPORT;
    }

    private void farCheck(Minecraft mc, ServerLevel level) {
        log("player is " + FAR_BLOCKS + " blocks away; the wall's chunk is not loaded on the client");
        serverBefore = snapshot(level);
        cmd("baseline check wall");
        RegionBaseline.Report r = new RegionUpkeep(baritone).check("wall");
        check(r.unknown() == managedCells(), "unloaded: every managed cell is unknown (" + r.unknown() + "/" + managedCells() + ")");
        check(r.restore() == 0 && r.review() == 0 && r.matched() == 0,
                "unloaded cells are never treated as missing (restore=" + r.restore() + " review=" + r.review()
                        + " matched=" + r.matched() + ")");
        // Even an explicit selection over the whole wall restores nothing while it is unloaded.
        cmd("baseline repair wall box=" + wallBox());
        check(baritone.getBlueprintBehavior().repairSession() == null
                        || !baritone.getBlueprintBehavior().repairSession().isActive(),
                "no repair starts while the region is unloaded");
        check(sameAs(level, serverBefore), "the server world is unchanged while the region is unloaded");
    }

    private void logFindings(RegionBaseline.Report r) {
        for (RegionBaseline.Finding f : r.findings()) {
            log("  " + f.verdict() + " " + f.x() + "," + f.y() + "," + f.z() + " want " + f.expected() + " saw " + f.actual() + " (" + f.reason() + ")");
        }
    }

    private void repairPhase(Minecraft mc, ServerLevel level) {
        cmd("baseline check wall");
        RegionBaseline.Report r = new RegionUpkeep(baritone).check("wall");
        logFindings(r);
        check(r.unknown() == 0, "revisited: no cell is unknown any more (" + r.unknown() + ")");
        check(r.restore() == 0, "nothing is authorized yet, so nothing is queued for restore (" + r.restore() + ")");
        check(r.review() == 6, "before authorization the opening (2) and the damaged patch (4) are review items (" + r.review() + ")");
        check(r.of(RegionBaseline.Verdict.REVIEW).stream().filter(f -> f.x() == OPENING_X).count() == 2,
                "the unclassified opening is reported for review");
        check(r.of(RegionBaseline.Verdict.REVIEW).stream().noneMatch(f -> f.x() == DOOR_X),
                "the registered doorway is not reported");
        check(r.matched() == managedCells() - 4 - 2, "all untouched cells match (" + r.matched() + ")");

        serverBefore = snapshot(level);
        cmd("baseline repair wall box=" + boxText(PATCH_X0, 2, PATCH_X1, 3));
        check(baritone.getBlueprintBehavior().repairSession() != null, "a scoped repair session started");
        ticks = 0;
        state = State.REPAIR_WAIT;
    }

    private void waitRepair(Minecraft mc, ServerLevel level, boolean policy) {
        BlueprintRepairSession s = baritone.getBlueprintBehavior().repairSession();
        if (s == null) {
            check(false, "repair session exists while waiting");
            finish(mc);
            return;
        }
        if (s.isActive() && ++ticks < REPAIR_TIMEOUT_TICKS) {
            if (ticks % 200 == 0) {
                log("repairing... " + s.statusNote());
            }
            return;
        }
        if (s.isActive()) {
            cmd("baseline stop");
            check(false, "repair finished within " + REPAIR_TIMEOUT_TICKS + " ticks (stopped via #baseline stop)");
            finish(mc);
            return;
        }
        log("repair ended after " + ticks + " ticks: " + s.statusNote());
        check(s.state() == BlueprintRepairSession.SessionState.SUCCEEDED, "repair session SUCCEEDED (" + s.state() + ")");
        // Independent verification from the server world.
        if (!policy) {
            for (int x = PATCH_X0; x <= PATCH_X1; x++) {
                for (int dy = 2; dy <= 3; dy++) {
                    check(level.getBlockState(wall(x, dy)).is(Blocks.STONE_BRICKS),
                            "server: approved masonry restored at " + wall(x, dy).toShortString());
                }
            }
            check(isAir(level, wall(DOOR_X, 0)) && isAir(level, wall(DOOR_X, 1)), "server: the registered doorway stayed open and untouched");
            check(isAir(level, wall(OPENING_X, 0)) && isAir(level, wall(OPENING_X, 1)),
                    "server: the unclassified opening was NOT repaired");
            checkChest(level);
            int changed = countChangedOutside(level, serverBefore, PATCH_X0, PATCH_X1, 2, 3);
            check(changed == 0, "server: nothing else in the wall region changed (" + changed + " other cells differ)");
            cmd("baseline check wall");
            RegionBaseline.Report r = new RegionUpkeep(baritone).check("wall");
            logFindings(r);
            check(r.restore() == 0 && r.review() == 2 && r.unknown() == 0 && r.matched() == managedCells() - 2,
                    "client re-check: " + r.summary());
            ticks = 0;
            stage = 0;
            state = State.POLICY_PREP;
        } else {
            check(level.getBlockState(wall(POLICY_X, 3)).is(Blocks.STONE_BRICKS),
                    "server: standing-policy cell restored at " + wall(POLICY_X, 3).toShortString());
            check(isAir(level, wall(DOOR_X, 0)) && isAir(level, wall(OPENING_X, 0)), "server: doorway and opening still untouched");
            checkChest(level);
            cmd("baseline check wall");
            RegionBaseline.Report r = new RegionUpkeep(baritone).check("wall");
            check(r.restore() == 0 && r.review() == 2 && r.unknown() == 0,
                    "final client check: " + r.summary());
            finish(mc);
        }
    }

    private void policyPrep(Minecraft mc, ServerLevel level) {
        if (stage == 0) {
            // Another block is knocked out. Without authorization it is only reported.
            setServer(mc, level, wall(POLICY_X, 3), Blocks.AIR.defaultBlockState());
            restartWait();
            stage = 1;
            return;
        }
        if (!waited(20)) {
            return;
        }
        serverBefore = snapshot(level);
        cmd("baseline check wall");
        RegionBaseline.Report r = new RegionUpkeep(baritone).check("wall");
        check(r.review() == 3 && r.restore() == 0, "a new unauthorized gap is reported for review, not restored (" + r.summary() + ")");
        cmd("baseline repair wall");
        BlueprintRepairSession s = baritone.getBlueprintBehavior().repairSession();
        check(s == null || !s.isActive(), "repair with no selection and no standing policy starts nothing");
        check(isAir(level, wall(POLICY_X, 3)), "server: the unauthorized gap is still open");
        // Standing policy for that cell only.
        cmd("baseline policy wall box=" + boxText(POLICY_X, 3, POLICY_X, 3));
        restartWait();
        state = State.POLICY_CHECK;
    }

    private void policyCheck(Minecraft mc, ServerLevel level) {
        RegionBaseline.Report r = new RegionUpkeep(baritone).check("wall");
        check(r.restore() == 1 && r.review() == 2, "standing policy authorizes exactly that gap (" + r.summary() + ")");
        cmd("baseline repair wall");
        ticks = 0;
        state = State.POLICY_REPAIR_WAIT;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private String cell(int x, int dy) {
        return x + "," + (baseY + dy) + "," + WALL_Z;
    }

    private void setServer(Minecraft mc, ServerLevel level, BlockPos pos, BlockState state) {
        mc.getSingleplayerServer().submit(() -> level.setBlock(pos, state, 3)).join();
    }

    private boolean clientSeesWall(Minecraft mc) {
        return mc.level.getChunkSource().hasChunk(WALL_X0 >> 4, WALL_Z >> 4) && mc.level.getChunkSource().hasChunk(WALL_X1 >> 4, WALL_Z >> 4)
                && !mc.level.getBlockState(wall(WALL_X0, 0)).isAir();
    }

    private static boolean isAir(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir();
    }

    private Map<Long, BlockState> snapshot(ServerLevel level) {
        Map<Long, BlockState> out = new HashMap<>();
        for (int x = WALL_X0 - 3; x <= WALL_X1 + 3; x++) {
            for (int dy = 0; dy < WALL_H + 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos p = new BlockPos(x, baseY + dy, WALL_Z + dz);
                    out.put(p.asLong(), level.getBlockState(p));
                }
            }
        }
        return out;
    }

    private boolean sameAs(ServerLevel level, Map<Long, BlockState> before) {
        return countChangedOutside(level, before, 1, 0, 1, 0) == 0;
    }

    /** Cells that differ from {@code before} other than the (x0..x1, dy0..dy1) wall patch. */
    private int countChangedOutside(ServerLevel level, Map<Long, BlockState> before, int x0, int x1, int dy0, int dy1) {
        int changed = 0;
        for (Map.Entry<Long, BlockState> e : before.entrySet()) {
            BlockPos p = BlockPos.of(e.getKey());
            boolean inPatch = p.getZ() == WALL_Z && p.getX() >= x0 && p.getX() <= x1
                    && p.getY() - baseY >= dy0 && p.getY() - baseY <= dy1;
            boolean inBaseline = p.getZ() == WALL_Z && p.getX() >= WALL_X0 && p.getX() <= WALL_X1
                    && p.getY() - baseY >= 0 && p.getY() - baseY < WALL_H;
            if (!inPatch && level.getBlockState(p) != e.getValue()) {
                if (inBaseline) {
                    changed++;
                }
                log("  changed " + (inBaseline ? "IN BASELINE " : "outside baseline ") + p.toShortString() + " "
                        + e.getValue().getBlock() + " -> " + level.getBlockState(p).getBlock());
            }
        }
        return changed;
    }

    /** Read on the server thread: a ServerLevel's block entities are not safe to read from the client thread. */
    private List<ItemStack> chestItems(ServerLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null && !mc.getSingleplayerServer().isSameThread()) {
            return mc.getSingleplayerServer().submit(() -> chestItems(level)).join();
        }
        List<ItemStack> out = new ArrayList<>();
        if (level.getBlockEntity(wall(CHEST_X, 0)) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (!c.getItem(i).isEmpty()) {
                    out.add(c.getItem(i).copy());
                }
            }
        } else {
            log("chest block entity missing at " + wall(CHEST_X, 0).toShortString() + ": " + level.getBlockState(wall(CHEST_X, 0)));
        }
        return out;
    }

    private void checkChest(ServerLevel level) {
        List<ItemStack> now = chestItems(level);
        boolean same = level.getBlockState(wall(CHEST_X, 0)).is(Blocks.CHEST) && now.size() == chestBefore.size();
        for (int i = 0; same && i < now.size(); i++) {
            same = ItemStack.matches(now.get(i), chestBefore.get(i));
        }
        check(same, "server: the chest and its contents are untouched (" + now.size() + " stacks)");
    }

    private void finish(Minecraft mc) {
        state = State.DONE;
        log(String.format(Locale.ROOT, "SUMMARY %s failures=%d%s", failures.isEmpty() ? "PASS" : "FAIL", failures.size(),
                failures.isEmpty() ? "" : " " + failures));
        if (Boolean.getBoolean("ostinato.upkeeptest.exit")) {
            mc.execute(mc::stop);
        }
    }
}

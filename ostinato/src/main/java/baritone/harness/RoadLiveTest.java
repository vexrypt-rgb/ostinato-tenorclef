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
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.process.RoadBuildProcess;
import baritone.road.ProtectedZone;
import baritone.road.RoadCorridorFixtures;
import baritone.road.RoadPlanner;
import baritone.road.RoadProbe;
import baritone.road.RoadSpec;
import baritone.road.RoadWorldProbe;
import baritone.road.WidenedWalkingBaseline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live interactive Minecraft Java 26.3 Fabric acceptance harness for terrain road building
 * ({@code -Dostinato.testHarness=true -Dostinato.roadtest=true [-Dostinato.roadtest.exit=true]}).
 * <p>
 * Auto-creates a fresh Peaceful singleplayer world (if started at the title screen), materializes the 5 held-out
 * 40–80-block corridors on elevated clean test platforms, invokes the bot exclusively through the normal player entry
 * point ({@code #road build ...}), audits server-side protected-plot immutability and the 48-block crossing budget,
 * and runs an independent second-traveler live walk in both directions with jumping, digging, and placing disabled.
 */
public final class RoadLiveTest implements AbstractGameEventListener {

    private static final int BUILD_TIMEOUT_TICKS = 4800;
    private static final int WALK_TIMEOUT_TICKS = 1000;
    private static final int ARENA_DECK_Y = 128;
    private static final int ARENA_STRIDE_Z = 160;
    private static final int ARENA_ORIGIN_X = 200;
    private static final int ARENA_ORIGIN_Z = 200;

    private enum State {
        TITLE_OPEN_CREATE,
        TITLE_CLICK_CREATE,
        WAIT_WORLD,
        SETUP_FIXTURE,
        WAIT_CLIENT_SYNC,
        RUN_COMMAND,
        WAIT_BUILD,
        WALK_FORWARD,
        WALK_REVERSE,
        DONE
    }

    private final Baritone baritone;
    private final List<RoadCorridorFixtures.Fixture> fixtures = RoadCorridorFixtures.heldOutFive();
    private State state = State.TITLE_OPEN_CREATE;
    private int fixtureIdx;
    private int ticks;
    private int syncWait;
    private int titleWait;
    private BufferedWriter logOut;

    private RoadSpec liveSpec;
    private Map<Long, BlockState> serverProtectedBefore = Map.of();
    private Map<Long, BlockState> serverCorridorBefore = Map.of();
    private WidenedWalkingBaseline.BaselineResult liveBaseline;
    private int forwardJumps;
    private int reverseJumps;
    private int forwardWaterTicks;
    private int reverseWaterTicks;
    private boolean forwardReached;
    private boolean reverseReached;
    private int feasiblePassed;
    private int infeasiblePassed;

    private List<BlockPos> walkWaypoints = List.of();
    private int walkWpIdx;

    private RoadLiveTest(Baritone baritone) {
        this.baritone = baritone;
    }

    public static void install(Baritone b) {
        if (!Boolean.getBoolean("ostinato.roadtest")) {
            return;
        }
        System.out.println("[roadtest] installed (5 held-out corridors)");
        b.getGameEventHandler().registerEventListener(new RoadLiveTest(b));
    }

    private void log(String line) {
        System.out.println("[roadtest] " + line);
        try {
            if (logOut == null && Minecraft.getInstance() != null) {
                logOut = Files.newBufferedWriter(
                        Minecraft.getInstance().gameDirectory.toPath().resolve("roadtest-result.txt"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (logOut != null) {
                logOut.write("[roadtest] " + line);
                logOut.newLine();
                logOut.flush();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getState() != EventState.PRE || state == State.DONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }

        // Auto-create fresh disposable singleplayer world when launched directly to TitleScreen or Onboarding screen.
        if (state == State.TITLE_OPEN_CREATE) {
            if (mc.player != null && mc.level != null && mc.getSingleplayerServer() != null) {
                state = State.WAIT_WORLD;
                ticks = 0;
            } else if (mc.gui.screen() instanceof TitleScreen) {
                mc.options.pauseOnLostFocus = false;
                log("Opening CreateWorldScreen from TitleScreen");
                CreateWorldScreen.openFresh(mc, () -> {});
                titleWait = 40;
                state = State.TITLE_CLICK_CREATE;
            } else if (mc.gui.screen() != null && mc.gui.screen().getClass().getSimpleName().contains("Onboarding")) {
                mc.options.onboardAccessibility = false;
                mc.options.save();
                log("Dismissing " + mc.gui.screen().getClass().getSimpleName() + " -> TitleScreen");
                mc.gui.setScreen(new TitleScreen());
            }
            return;
        }
        if (state == State.TITLE_CLICK_CREATE) {
            if (--titleWait <= 0 && mc.gui.screen() instanceof CreateWorldScreen screen) {
                screen.getUiState().setSeed(System.getProperty("ostinato.seed", "90263"));
                for (var child : screen.children()) {
                    if (child instanceof Button button && button.getMessage().getString().contains("Create New World")) {
                        button.onPress(new net.minecraft.client.input.InputWithModifiers() {
                            @Override public int input() { return 0; }
                            @Override public int modifiers() { return 0; }
                        });
                        state = State.WAIT_WORLD;
                        ticks = 0;
                        break;
                    }
                }
            }
            return;
        }

        if (event.getType() != TickEvent.Type.IN || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            return;
        }
        ServerLevel serverLevel = mc.getSingleplayerServer().overworld();
        ServerPlayer serverPlayer = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        if (serverLevel == null || serverPlayer == null) {
            return;
        }

        switch (state) {
            case WAIT_WORLD -> {
                if (++ticks >= 60) {
                    mc.options.pauseOnLostFocus = false;
                    mc.getSingleplayerServer().submit(() -> {
                        var srv = mc.getSingleplayerServer();
                        srv.setDifficulty(Difficulty.PEACEFUL, true);
                        serverLevel.getGameRules().set(GameRules.SPAWN_MOBS, false, srv);
                        srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(), "gamerule advance_time false");
                        srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(), "time set noon");
                    }).join();
                    ticks = 0;
                    state = State.SETUP_FIXTURE;
                }
            }
            case SETUP_FIXTURE -> {
                if (fixtureIdx >= fixtures.size()) {
                    log(String.format(Locale.ROOT, "SUMMARY feasiblePassed=%d/4 infeasiblePassed=%d/1",
                            feasiblePassed, infeasiblePassed));
                    state = State.DONE;
                    if (Boolean.getBoolean("ostinato.roadtest.exit")) {
                        mc.execute(mc::stop);
                    }
                    return;
                }
                setupCurrentFixture(mc, serverLevel, serverPlayer);
                syncWait = 0;
                state = State.WAIT_CLIENT_SYNC;
            }
            case WAIT_CLIENT_SYNC -> {
                syncWait++;
                if (syncWait < 40) return;
                // Wait until client world has received the entire corridor chunk box (or timeout 200 ticks).
                if (syncWait < 200 && !corridorLoadedOnClient(mc, liveSpec)) {
                    return;
                }
                // Keep high noon so wheat crops in protected plots never pop off in nighttime darkness.
                mc.getSingleplayerServer().submit(() -> {
                    var srv = mc.getSingleplayerServer();
                    srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(), "time set noon");
                }).join();
                // Capture authoritative server-side protected-plot snapshot right before issuing #road build.
                serverProtectedBefore = snapshotServerProtected(mc, serverLevel, liveSpec);
                liveBaseline = WidenedWalkingBaseline.evaluate(liveSpec, new RoadWorldProbe(mc.level));
                state = State.RUN_COMMAND;
            }
            case RUN_COMMAND -> {
                RoadCorridorFixtures.Fixture f = fixtures.get(fixtureIdx);
                String cmd = buildPlayerCommand(liveSpec);
                log("[" + f.id() + "] executing: #" + cmd);
                baritone.getCommandManager().execute(cmd);
                ticks = 0;
                state = State.WAIT_BUILD;
            }
            case WAIT_BUILD -> {
                RoadCorridorFixtures.Fixture f = fixtures.get(fixtureIdx);
                RoadBuildProcess proc = baritone.getRoadProcess();
                ticks++;
                boolean terminal = !proc.isBuilding() && proc.outcome().terminal();
                if (!terminal && ticks < BUILD_TIMEOUT_TICKS) {
                    return;
                }
                if (!terminal) {
                    proc.cancel();
                    log("[" + f.id() + "] TIMEOUT after " + ticks + " ticks status=" + proc.status());
                }
                int serverProtEdits = countServerDiffs(serverLevel, serverProtectedBefore);
                int serverPlaced = countServerPlaced(serverLevel, serverCorridorBefore, liveSpec.deckY());
                RoadPlanner.PlanResult plan = proc.lastPlan();

                if (!f.expectedFeasible()) {
                    boolean rejected = plan != null && !plan.feasible();
                    boolean exactBlocker = rejected && f.expectedFailureKinds().stream().allMatch(plan::hasFailure);
                    boolean ok = rejected && exactBlocker && serverProtEdits == 0 && serverPlaced == 0;
                    if (ok) infeasiblePassed++;
                    log(String.format(Locale.ROOT,
                            "[%s] INFEASIBLE ok=%s protEdits=%d placed=%d planner=%.2fms blockers=%s | %s",
                            f.id(), ok, serverProtEdits, serverPlaced,
                            plan == null ? -1.0 : plan.plannerTimeMs(),
                            plan == null ? "none" : plan.failures(),
                            liveBaseline.oneLineSummary(liveSpec.maxPlacedBlocks())));
                    fixtureIdx++;
                    state = State.SETUP_FIXTURE;
                    return;
                }

                if (!proc.outcome().succeeded() || serverProtEdits > 0 || serverPlaced > liveSpec.maxPlacedBlocks()) {
                    log(String.format(Locale.ROOT,
                            "[%s] BUILD FAIL outcome=%s protEdits=%d placed=%d/%d status=%s | %s",
                            f.id(), proc.outcome(), serverProtEdits, serverPlaced, liveSpec.maxPlacedBlocks(),
                            proc.status(), liveBaseline.oneLineSummary(liveSpec.maxPlacedBlocks())));
                    fixtureIdx++;
                    state = State.SETUP_FIXTURE;
                    return;
                }

                // Independent second traveler live walk: hall -> village (strictly no jump, no dig, no place, no swimming).
                startNoJumpWalk(mc, serverPlayer, liveSpec.startX(), liveSpec.startY(), liveSpec.startZ(),
                        liveSpec.endX(), liveSpec.endY(), liveSpec.endZ());
                forwardJumps = 0;
                forwardWaterTicks = 0;
                forwardReached = false;
                ticks = 0;
                state = State.WALK_FORWARD;
            }
            case WALK_FORWARD -> {
                stepIndependentNoJumpWalk(mc);
                if (baritone.getInputOverrideHandler().isInputForcedDown(Input.JUMP)
                        || Math.abs(mc.player.getY() - liveSpec.startY()) > 0.15) {
                    forwardJumps++;
                }
                if (mc.player.isInWater()) {
                    forwardWaterTicks++;
                }
                if (atEndpoint(mc, liveSpec.endX(), liveSpec.endY(), liveSpec.endZ())) {
                    forwardReached = true;
                }
                if (forwardReached || walkWaypoints.isEmpty() || ++ticks >= WALK_TIMEOUT_TICKS) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    startNoJumpWalk(mc, serverPlayer, liveSpec.endX(), liveSpec.endY(), liveSpec.endZ(),
                            liveSpec.startX(), liveSpec.startY(), liveSpec.startZ());
                    reverseJumps = 0;
                    reverseWaterTicks = 0;
                    reverseReached = false;
                    ticks = 0;
                    state = State.WALK_REVERSE;
                }
            }
            case WALK_REVERSE -> {
                stepIndependentNoJumpWalk(mc);
                if (baritone.getInputOverrideHandler().isInputForcedDown(Input.JUMP)
                        || Math.abs(mc.player.getY() - liveSpec.startY()) > 0.15) {
                    reverseJumps++;
                }
                if (mc.player.isInWater()) {
                    reverseWaterTicks++;
                }
                if (atEndpoint(mc, liveSpec.startX(), liveSpec.startY(), liveSpec.startZ())) {
                    reverseReached = true;
                }
                if (reverseReached || walkWaypoints.isEmpty() || ++ticks >= WALK_TIMEOUT_TICKS) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    RoadCorridorFixtures.Fixture f = fixtures.get(fixtureIdx);
                    int serverProtEdits = countServerDiffs(serverLevel, serverProtectedBefore);
                    int serverPlaced = countServerPlaced(serverLevel, serverCorridorBefore, liveSpec.deckY());
                    boolean ok = forwardReached && reverseReached
                            && forwardJumps == 0 && reverseJumps == 0
                            && forwardWaterTicks == 0 && reverseWaterTicks == 0
                            && serverProtEdits == 0 && serverPlaced <= liveSpec.maxPlacedBlocks();
                    if (ok) feasiblePassed++;
                    RoadPlanner.PlanResult plan = baritone.getRoadProcess().lastPlan();
                    log(String.format(Locale.ROOT,
                            "[%s] FEASIBLE ok=%s placed=%d/%d protEdits=%d planner=%.2fms fwd=(%s,j=%d,w=%d) rev=(%s,j=%d,w=%d) | %s",
                            f.id(), ok, serverPlaced, liveSpec.maxPlacedBlocks(), serverProtEdits,
                            plan == null ? -1.0 : plan.plannerTimeMs(),
                            forwardReached, forwardJumps, forwardWaterTicks,
                            reverseReached, reverseJumps, reverseWaterTicks,
                            liveBaseline.oneLineSummary(liveSpec.maxPlacedBlocks())));
                    fixtureIdx++;
                    state = State.SETUP_FIXTURE;
                }
            }
            case DONE, TITLE_OPEN_CREATE, TITLE_CLICK_CREATE -> {}
        }
    }

    private void setupCurrentFixture(Minecraft mc, ServerLevel level, ServerPlayer player) {
        RoadCorridorFixtures.Fixture f = fixtures.get(fixtureIdx);
        RoadSpec orig = f.spec();
        int ox = ARENA_ORIGIN_X;
        int oz = ARENA_ORIGIN_Z + fixtureIdx * ARENA_STRIDE_Z;
        int dy = ARENA_DECK_Y - orig.deckY();

        List<ProtectedZone> shiftedZones = orig.protectedZones().stream()
                .map(z -> new ProtectedZone(z.name(), z.minX() + ox, z.minY(), z.minZ() + oz, z.maxX() + ox, z.maxY(), z.maxZ() + oz))
                .toList();
        liveSpec = new RoadSpec(
                orig.startLabel(), orig.startX() + ox, orig.startY() + dy, orig.startZ() + oz,
                orig.endLabel(), orig.endX() + ox, orig.endY() + dy, orig.endZ() + oz,
                orig.width(), orig.headroom(), orig.maxPlacedBlocks(), orig.corridorMargin(),
                orig.deckBlockId(), shiftedZones);

        Map<Long, BlockState> protSnap = new HashMap<>();
        Map<Long, BlockState> corrSnap = new HashMap<>();
        int deckY = liveSpec.deckY();

        mc.getSingleplayerServer().submit(() -> {
            // Teleport player first so surrounding chunks load around the elevated platform.
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(liveSpec.startX() + 0.5, liveSpec.startY() + 1.0, liveSpec.startZ() + 0.5);

            for (int x = liveSpec.minCorridorX() - 2; x <= liveSpec.maxCorridorX() + 2; x++) {
                for (int z = liveSpec.minCorridorZ() - 2; z <= liveSpec.maxCorridorZ() + 2; z++) {
                    for (int y = deckY - 2; y <= deckY + 6; y++) {
                        RoadProbe.CellKind k = f.world().get(x - ox, y - dy, z - oz);
                        RoadProbe.CellKind belowKind = f.world().get(x - ox, y - dy - 1, z - oz);
                        BlockState bs = toBlockState(k, belowKind, y, deckY);
                        BlockPos bp = new BlockPos(x, y, z);
                        level.setBlock(bp, bs, 3);
                        if (y == deckY && x >= liveSpec.minCorridorX() && x <= liveSpec.maxCorridorX()
                                && z >= liveSpec.minCorridorZ() && z <= liveSpec.maxCorridorZ()) {
                            corrSnap.put(ProtectedZone.key(x, y, z), bs);
                        }
                    }
                }
            }
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
            player.getInventory().add(new ItemStack(Items.IRON_PICKAXE, 1));
            player.getInventory().add(new ItemStack(Items.IRON_AXE, 1));
            player.getInventory().add(new ItemStack(Items.IRON_SHOVEL, 1));
            player.teleportTo(liveSpec.startX() + 0.5, liveSpec.startY(), liveSpec.startZ() + 0.5);
        }).join();

        serverProtectedBefore = protSnap;
        serverCorridorBefore = corrSnap;
    }

    private static Map<Long, BlockState> snapshotServerProtected(Minecraft mc, ServerLevel level, RoadSpec spec) {
        Map<Long, BlockState> snap = new HashMap<>();
        int deckY = spec.deckY();
        mc.getSingleplayerServer().submit(() -> {
            for (int x = spec.minCorridorX(); x <= spec.maxCorridorX(); x++) {
                for (int z = spec.minCorridorZ(); z <= spec.maxCorridorZ(); z++) {
                    if (!spec.isProtectedColumn(x, z)) continue;
                    for (int y = deckY - 1; y <= deckY + spec.headroom() + 1; y++) {
                        if (spec.isProtected(x, y, z)) {
                            snap.put(ProtectedZone.key(x, y, z), level.getBlockState(new BlockPos(x, y, z)));
                        }
                    }
                }
            }
        }).join();
        return snap;
    }

    private static boolean corridorLoadedOnClient(Minecraft mc, RoadSpec spec) {
        if (mc.level == null) return false;
        int deckY = spec.deckY();
        for (int x = spec.minCorridorX(); x <= spec.maxCorridorX(); x += 8) {
            for (int z = spec.minCorridorZ(); z <= spec.maxCorridorZ(); z += 8) {
                if (!mc.level.isLoaded(new BlockPos(x, deckY, z))) {
                    return false;
                }
            }
        }
        return mc.level.isLoaded(new BlockPos(spec.endX(), deckY, spec.endZ()));
    }

    private void startNoJumpWalk(Minecraft mc, ServerPlayer player, int sx, int sy, int sz, int ex, int ey, int ez) {
        baritone.getPathingBehavior().cancelEverything();
        baritone.getInputOverrideHandler().clearAllKeys();
        // Empty hotbar so placing/digging tools are not even held; only MOVE_FORWARD + yaw are ever used.
        mc.getSingleplayerServer().submit(() -> {
            player.getInventory().clearContent();
            player.teleportTo(sx + 0.5, sy, sz + 0.5);
        }).join();
        walkWaypoints = findIndependentLevelPath(mc, liveSpec, sx, sz, ex, ez);
        walkWpIdx = 0;
    }

    /**
     * Drives the real Minecraft player strictly via {@code Input.MOVE_FORWARD} and yaw steering (jumping, digging, and
     * placing are completely disabled — {@code Input.JUMP}, {@code CLICK_LEFT}, {@code CLICK_RIGHT} are never pressed).
     */
    private void stepIndependentNoJumpWalk(Minecraft mc) {
        baritone.getInputOverrideHandler().clearAllKeys();
        if (walkWaypoints.isEmpty() || walkWpIdx >= walkWaypoints.size()) {
            return;
        }
        while (walkWpIdx < walkWaypoints.size() - 1) {
            BlockPos wp = walkWaypoints.get(walkWpIdx);
            double dx = mc.player.getX() - (wp.getX() + 0.5);
            double dz = mc.player.getZ() - (wp.getZ() + 0.5);
            if (dx * dx + dz * dz <= 0.45 * 0.45) {
                walkWpIdx++;
            } else {
                break;
            }
        }
        BlockPos target = walkWaypoints.get(walkWpIdx);
        Vec3 targetVec = new Vec3(target.getX() + 0.5, mc.player.getEyeY(), target.getZ() + 0.5);
        Rotation rot = RotationUtils.calcRotationFromVec3d(mc.player.getEyePosition(), targetVec,
                new Rotation(mc.player.getYRot(), mc.player.getXRot()));
        mc.player.setYRot(rot.getYaw());
        mc.player.setXRot(0.0f);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
    }

    /**
     * Independent level-walking BFS over raw client-visible Minecraft blocks: solid full floor at {@code deckY},
     * clear air/plant headroom at {@code deckY+1..deckY+headroom}, zero protected-zone entry, zero jumps.
     */
    private static List<BlockPos> findIndependentLevelPath(Minecraft mc, RoadSpec spec, int sx, int sz, int ex, int ez) {
        RoadWorldProbe probe = new RoadWorldProbe(mc.level);
        int deckY = spec.deckY();
        int headroom = spec.headroom();
        int minX = spec.minCorridorX(), maxX = spec.maxCorridorX();
        int minZ = spec.minCorridorZ(), maxZ = spec.maxCorridorZ();

        long startKey = ProtectedZone.colKey(sx, sz);
        long endKey = ProtectedZone.colKey(ex, ez);
        Map<Long, Long> parent = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        visited.add(startKey);
        q.add(startKey);

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        boolean found = false;
        while (!q.isEmpty()) {
            long cur = q.poll();
            if (cur == endKey) {
                found = true;
                break;
            }
            int cx = ProtectedZone.colX(cur);
            int cz = ProtectedZone.colZ(cur);
            for (int[] d : dirs) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                if (spec.isProtectedColumn(nx, nz)) continue;
                long nk = ProtectedZone.colKey(nx, nz);
                if (visited.contains(nk)) continue;
                if (!probe.get(nx, deckY, nz).isWalkableFullDeck()) continue;
                boolean clear = true;
                for (int dy = 1; dy <= headroom; dy++) {
                    if (!probe.get(nx, deckY + dy, nz).isPassableHeadroom()) {
                        clear = false;
                        break;
                    }
                }
                if (!clear) continue;
                visited.add(nk);
                parent.put(nk, cur);
                q.add(nk);
            }
        }
        if (!found) {
            return List.of();
        }
        List<BlockPos> path = new ArrayList<>();
        for (Long k = endKey; k != null; k = parent.get(k)) {
            path.add(new BlockPos(ProtectedZone.colX(k), deckY + 1, ProtectedZone.colZ(k)));
        }
        Collections.reverse(path);
        return path;
    }

    private static boolean atEndpoint(Minecraft mc, int ex, int ey, int ez) {
        double dx = mc.player.getX() - (ex + 0.5);
        double dz = mc.player.getZ() - (ez + 0.5);
        return Math.abs(mc.player.getY() - ey) < 0.5 && dx * dx + dz * dz <= 2.25;
    }

    private static String buildPlayerCommand(RoadSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("road build ")
                .append(spec.startX()).append(',').append(spec.startY()).append(',').append(spec.startZ()).append(' ')
                .append(spec.endX()).append(',').append(spec.endY()).append(',').append(spec.endZ())
                .append(" budget=").append(spec.maxPlacedBlocks())
                .append(" width=").append(spec.width())
                .append(" block=").append(spec.deckBlockId());
        for (ProtectedZone z : spec.protectedZones()) {
            sb.append(" protect=").append(z.name()).append('@')
                    .append(z.minX()).append(',').append(z.minZ()).append(':')
                    .append(z.maxX()).append(',').append(z.maxZ());
        }
        return sb.toString();
    }

    private static BlockState toBlockState(RoadProbe.CellKind kind, RoadProbe.CellKind belowKind, int y, int deckY) {
        return switch (kind) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case PASSABLE_PLANT -> belowKind == RoadProbe.CellKind.SOLID_NON_FULL_CLEARABLE
                    ? Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)
                    : Blocks.POPPY.defaultBlockState();
            case SOLID_FULL -> y == deckY ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            case SOLID_NON_FULL_CLEARABLE -> y == deckY
                    ? Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7)
                    : Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
            case FLUID -> Blocks.WATER.defaultBlockState();
            case HAZARD -> Blocks.LAVA.defaultBlockState();
            case UNBREAKABLE -> Blocks.BEDROCK.defaultBlockState();
            case UNKNOWN -> Blocks.AIR.defaultBlockState();
        };
    }

    private static int countServerDiffs(ServerLevel level, Map<Long, BlockState> snapshot) {
        int diffs = 0;
        for (Map.Entry<Long, BlockState> e : snapshot.entrySet()) {
            long k = e.getKey();
            BlockState now = level.getBlockState(new BlockPos(ProtectedZone.x(k), ProtectedZone.y(k), ProtectedZone.z(k)));
            // Compare block identity (ignores farmland moisture evaporation while catching any trampling/breaking/placing).
            if (now.getBlock() != e.getValue().getBlock()) diffs++;
        }
        return diffs;
    }

    private static int countServerPlaced(ServerLevel level, Map<Long, BlockState> beforeDeck, int deckY) {
        int placed = 0;
        for (Map.Entry<Long, BlockState> e : beforeDeck.entrySet()) {
            long k = e.getKey();
            BlockState now = level.getBlockState(new BlockPos(ProtectedZone.x(k), deckY, ProtectedZone.z(k)));
            if (!now.equals(e.getValue()) && now.is(Blocks.COBBLESTONE)) {
                placed++;
            }
        }
        return placed;
    }
}

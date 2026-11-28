package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.tasks.husbandry.BreedCowsTask;
import adris.altoclef.tasks.husbandry.HusbandryRequest;
import adris.altoclef.tasks.husbandry.HusbandryRequestParser;
import adris.altoclef.tasks.husbandry.PenBounds;
import adris.altoclef.tasks.husbandry.PenDetector;
import adris.altoclef.tasksystem.Task;
//#if MC < 260000
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.CowEntity;
//#else
//$$ import net.minecraft.world.entity.Entity;
//$$ import net.minecraft.world.entity.animal.cow.Cow;
//#endif
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Normal player command entry point for animal husbandry:
 * <ul>
 *     <li>{@code @breed cows 6}</li>
 *     <li>{@code @breed cows 6 at <x> <y> <z>}</li>
 *     <li>{@code @breed cows 6 box <minX> <minY> <minZ> <maxX> <maxY> <maxZ>}</li>
 *     <li>{@code @breed status}</li>
 *     <li>{@code @breed stop}</li>
 * </ul>
 */
public class BreedCommand extends Command {

    private static final int MAX_ARGS = 10;

    public BreedCommand() {
        super("breed",
                "Grow cows in a designated fenced pen to a target count (e.g. @breed cows 6)",
                buildOptionalArgs());
    }

    private static StringArg[] buildOptionalArgs() {
        StringArg[] args = new StringArg[MAX_ARGS];
        for (int i = 0; i < MAX_ARGS; i++) {
            args[i] = new StringArg("arg" + (i + 1), "");
        }
        return args;
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < MAX_ARGS; i++) {
            String tok = parser.get(String.class);
            if (tok == null || tok.isBlank()) {
                break;
            }
            tokens.add(tok.trim());
        }

        HusbandryRequestParser.ParsedCommand parsed;
        try {
            parsed = HusbandryRequestParser.parseTokens(tokens);
        } catch (IllegalArgumentException e) {
            Debug.logWarning("BREED: " + e.getMessage());
            finish();
            return;
        }

        switch (parsed.action()) {
            case STATUS -> {
                handleStatus(mod);
                finish();
            }
            case STOP -> {
                handleStop(mod);
                finish();
            }
            case START -> handleStart(mod, parsed.request());
        }
    }

    private void handleStatus(AltoClef mod) {
        Task current = mod.getUserTaskChain().getCurrentTask();
        if (current instanceof BreedCowsTask task) {
            Debug.logMessage("BREED: " + task.getStatusLine());
        } else {
            Debug.logMessage("BREED: idle (no active husbandry task)");
        }
    }

    private void handleStop(AltoClef mod) {
        Task current = mod.getUserTaskChain().getCurrentTask();
        if (current instanceof BreedCowsTask task) {
            task.requestCancel();
            mod.cancelUserTask();
            Debug.logMessage("BREED: stopped (" + task.getOutcome() + ")");
        } else {
            Debug.logMessage("BREED: no active husbandry task to stop");
        }
    }

    private void handleStart(AltoClef mod, HusbandryRequest request) {
        if (mod.getWorld() == null || mod.getPlayer() == null) {
            Debug.logWarning("BREED: not in a world");
            finish();
            return;
        }

        PenBounds pen = resolvePen(mod, request);
        if (pen == null) {
            Debug.logWarning("BREED: no enclosed fenced pen found nearby (look at the pen, stand inside/beside it, or pass 'at <x> <y> <z>' / 'box ...')");
            finish();
            return;
        }

        BreedCowsTask task = new BreedCowsTask(request, pen);
        Debug.logMessage("BREED: starting " + request.describe() + " in pen " + pen.describe());
        mod.runUserTask(task, this::finish);
    }

    private static PenBounds resolvePen(AltoClef mod, HusbandryRequest request) {
        if (request.hasExplicitBox()) {
            return PenDetector.fromExplicitBox(mod,
                    request.minX(), request.minY(), request.minZ(),
                    request.maxX(), request.maxY(), request.maxZ());
        }
        if (request.hasExplicitSeedPos()) {
            BlockPos seed = new BlockPos(request.seedX(), request.seedY(), request.seedZ());
            return PenDetector.detectAround(mod, seed).orElse(null);
        }

        // 1. Prefer a pen the player is directly looking at ("this fenced pen").
        HitResult hit = BotClientBridge.crosshairTarget();
        if (hit instanceof BlockHitResult bhr) {
            Optional<PenBounds> lookedPen = PenDetector.detectAround(mod, bhr.getBlockPos());
            if (lookedPen.isPresent()) {
                return lookedPen.get();
            }
            Optional<PenBounds> aboveLooked = PenDetector.detectAround(mod, bhr.getBlockPos().up());
            if (aboveLooked.isPresent()) {
                return aboveLooked.get();
            }
        }

        // 2. If the player is standing inside a fenced pen, use it.
        Optional<PenBounds> playerPen = PenDetector.detectAround(mod, mod.getPlayer().getBlockPos());
        if (playerPen.isPresent()) {
            return playerPen.get();
        }

        // 3. Otherwise, seed from the nearest visible cow within 16 blocks.
        Entity nearestCow = findNearestCow(mod, 16.0);
        if (nearestCow != null) {
            Optional<PenBounds> cowPen = PenDetector.detectAround(mod, nearestCow.getBlockPos());
            if (cowPen.isPresent()) {
                return cowPen.get();
            }
        }
        return null;
    }

    private static Entity findNearestCow(AltoClef mod, double maxRange) {
        Entity best = null;
        double bestDistSq = maxRange * maxRange;
        for (Entity e : BotClientBridge.entities()) {
            if (!isPlainCow(e) || !e.isAlive()) continue;
            double d2 = e.squaredDistanceTo(mod.getPlayer());
            if (d2 < bestDistSq) {
                bestDistSq = d2;
                best = e;
            }
        }
        return best;
    }

    private static boolean isPlainCow(Entity entity) {
        if (entity == null) return false;
        //#if MC >= 260000
        //$$ return entity instanceof Cow;
        //#else
        return entity.getClass() == CowEntity.class;
        //#endif
    }
}

package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.tasks.brewing.BrewPotionTask;
import adris.altoclef.tasks.brewing.BrewRequest;
import adris.altoclef.tasks.brewing.BrewRequestParser;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Normal player command entry point for potion brewing:
 * <ul>
 *     <li>{@code @brew healing [count] [at <x> <y> <z>] [radius=<r>]}</li>
 *     <li>{@code @brew status}</li>
 *     <li>{@code @brew stop}</li>
 * </ul>
 */
public class BrewCommand extends Command {

    private static final int MAX_ARGS = 10;

    public BrewCommand() {
        super("brew",
                "Brew potions using stocked supplies beside a brewing stand (e.g. @brew healing 3)",
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

        BrewRequestParser.ParsedCommand parsed;
        try {
            parsed = BrewRequestParser.parseTokens(tokens);
        } catch (IllegalArgumentException e) {
            Debug.logWarning("BREW: " + e.getMessage());
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
        if (current instanceof BrewPotionTask task) {
            Debug.logMessage("BREW: " + task.getStatusLine());
        } else {
            Debug.logMessage("BREW: idle (no active brewing task)");
        }
    }

    private void handleStop(AltoClef mod) {
        Task current = mod.getUserTaskChain().getCurrentTask();
        if (current instanceof BrewPotionTask task) {
            task.requestCancel();
            mod.cancelUserTask();
            Debug.logMessage("BREW: stopped (" + task.getOutcome() + ")");
        } else {
            Debug.logMessage("BREW: no active brewing task to stop");
        }
    }

    private void handleStart(AltoClef mod, BrewRequest request) {
        if (mod.getWorld() == null || mod.getPlayer() == null) {
            Debug.logWarning("BREW: not in a world");
            finish();
            return;
        }
        BlockPos standPos = resolveBrewingStand(mod, request);
        if (standPos == null) {
            Debug.logWarning("BREW: no reachable brewing stand found nearby (look at a brewing stand or pass 'at <x> <y> <z>')");
            finish();
            return;
        }
        BrewPotionTask task = new BrewPotionTask(request, standPos);
        Debug.logMessage("BREW: starting " + request.describe() + " at stand " + standPos.toShortString());
        mod.runUserTask(task, this::finish);
    }

    private static BlockPos resolveBrewingStand(AltoClef mod, BrewRequest request) {
        if (request.hasExplicitStandPos()) {
            return new BlockPos(request.standX(), request.standY(), request.standZ());
        }
        // Prefer a brewing stand the player is directly looking at ("this brewing stand").
        HitResult hit = BotClientBridge.crosshairTarget();
        if (hit instanceof BlockHitResult bhr) {
            BlockPos looked = bhr.getBlockPos();
            if (mod.getChunkTracker().isChunkLoaded(looked)
                    && mod.getWorld().getBlockState(looked).getBlock() == Blocks.BREWING_STAND) {
                return looked.toImmutable();
            }
        }
        // Otherwise locate the nearest reachable brewing stand around the player.
        Optional<BlockPos> nearest = mod.getBlockScanner().getNearestBlock(
                mod.getPlayer().getPos(), WorldHelper::canReach, Blocks.BREWING_STAND);
        if (nearest.isPresent()) {
            return nearest.get().toImmutable();
        }
        // Fallback: direct local scan within 16 blocks in case block scanner has not indexed it yet.
        BlockPos center = mod.getPlayer().getBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (int dx = -16; dx <= 16; dx++) {
            for (int dy = -6; dy <= 6; dy++) {
                for (int dz = -16; dz <= 16; dz++) {
                    BlockPos p = center.add(dx, dy, dz);
                    if (!mod.getChunkTracker().isChunkLoaded(p)) continue;
                    if (mod.getWorld().getBlockState(p).getBlock() == Blocks.BREWING_STAND) {
                        double d2 = p.getSquaredDistance(center);
                        if (d2 < bestDistSq) {
                            bestDistSq = d2;
                            best = p.toImmutable();
                        }
                    }
                }
            }
        }
        return best;
    }
}

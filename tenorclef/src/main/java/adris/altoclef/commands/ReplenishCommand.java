package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.tasks.replenish.NamedChestRegistry;
import adris.altoclef.tasks.replenish.ReplenishCommandParser;
import adris.altoclef.tasks.replenish.ReplenishWorksiteTask;
import adris.altoclef.tasks.replenish.ReplenishmentRule;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Normal player command entry point for worksite replenishment:
 * <ul>
 *     <li>{@code @replenish chest <name> [<x> <y> <z>]}</li>
 *     <li>{@code @replenish chests}</li>
 *     <li>{@code @replenish <item> from <source> to <dest> below <threshold> target <target> reserve <reserve> [interval=<sec>]}</li>
 *     <li>{@code @replenish status}</li>
 *     <li>{@code @replenish stop}</li>
 * </ul>
 */
public class ReplenishCommand extends Command {

    private static final int MAX_ARGS = 16;

    public ReplenishCommand() {
        super(List.of("replenish", "courier"),
                "Maintain worksite chest stock from a named source chest while preserving source reserve",
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

        ReplenishCommandParser.ParsedCommand parsed;
        try {
            parsed = ReplenishCommandParser.parseTokens(tokens);
        } catch (IllegalArgumentException e) {
            Debug.logWarning("REPLENISH: " + e.getMessage());
            finish();
            return;
        }

        NamedChestRegistry registry = NamedChestRegistry.defaultRegistry();

        switch (parsed.action()) {
            case REGISTER_CHEST -> handleRegisterChest(mod, registry, parsed);
            case LIST_CHESTS -> handleListChests(registry);
            case STATUS -> handleStatus(mod);
            case STOP -> handleStop(mod);
            case START_RULE -> {
                handleStartRule(mod, registry, parsed.rule());
                return; // finish() is wired to runUserTask callback
            }
        }
        finish();
    }

    private void handleRegisterChest(AltoClef mod,
                                     NamedChestRegistry registry,
                                     ReplenishCommandParser.ParsedCommand parsed) {
        if (mod.getWorld() == null || mod.getPlayer() == null) {
            Debug.logWarning("REPLENISH: not in a world");
            return;
        }
        BlockPos pos;
        if (parsed.hasCoords()) {
            pos = new BlockPos(parsed.x(), parsed.y(), parsed.z());
        } else {
            HitResult hit = BotClientBridge.crosshairTarget();
            if (hit instanceof BlockHitResult bhr) {
                pos = bhr.getBlockPos();
            } else {
                Debug.logWarning("REPLENISH: look at a chest or pass explicit coordinates: @replenish chest " + parsed.chestName() + " <x> <y> <z>");
                return;
            }
        }
        if (mod.getChunkTracker().isChunkLoaded(pos)) {
            Block block = mod.getWorld().getBlockState(pos).getBlock();
            if (ContainerType.getFromBlock(block) == ContainerType.EMPTY) {
                Debug.logWarning("REPLENISH: block at " + pos.toShortString() + " is not a storage container");
                return;
            }
        }
        Dimension dim = WorldHelper.getCurrentDimension();
        registry.register(parsed.chestName(), dim, pos);
        Debug.logMessage("REPLENISH: registered chest '" + parsed.chestName() + "' at " + pos.toShortString() + " (" + dim + ")");
    }

    private void handleListChests(NamedChestRegistry registry) {
        Map<String, NamedChestRegistry.NamedChest> all = registry.all();
        if (all.isEmpty()) {
            Debug.logMessage("REPLENISH: no named chests registered (use @replenish chest <name> <x> <y> <z> or Baritone #wp save <name>)");
            return;
        }
        StringBuilder sb = new StringBuilder("REPLENISH chests: ");
        boolean first = true;
        for (NamedChestRegistry.NamedChest nc : all.values()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(nc.describe());
        }
        Debug.logMessage(sb.toString());
    }

    private void handleStatus(AltoClef mod) {
        Task current = mod.getUserTaskChain().getCurrentTask();
        if (current instanceof ReplenishWorksiteTask task) {
            Debug.logMessage(task.getStatusLine());
        } else {
            Debug.logMessage("REPLENISH: idle (no active worksite replenishment task)");
        }
    }

    private void handleStop(AltoClef mod) {
        Task current = mod.getUserTaskChain().getCurrentTask();
        if (current instanceof ReplenishWorksiteTask task) {
            task.requestCancel();
            mod.cancelUserTask();
            Debug.logMessage("REPLENISH: stopped (" + task.getSession().metrics().summary() + ")");
        } else {
            Debug.logMessage("REPLENISH: no active replenishment task to stop");
        }
    }

    private void handleStartRule(AltoClef mod, NamedChestRegistry registry, ReplenishmentRule rule) {
        Optional<NamedChestRegistry.NamedChest> src = registry.resolve(mod, rule.sourceName());
        if (src.isEmpty()) {
            Debug.logWarning("REPLENISH: unknown source chest '" + rule.sourceName()
                    + "' (register with @replenish chest " + rule.sourceName() + " <x> <y> <z>)");
            finish();
            return;
        }
        Optional<NamedChestRegistry.NamedChest> dst = registry.resolve(mod, rule.destinationName());
        if (dst.isEmpty()) {
            Debug.logWarning("REPLENISH: unknown destination chest '" + rule.destinationName()
                    + "' (register with @replenish chest " + rule.destinationName() + " <x> <y> <z>)");
            finish();
            return;
        }
        if (src.get().dimension() != dst.get().dimension()) {
            Debug.logWarning("REPLENISH: source '" + rule.sourceName() + "' (" + src.get().dimension()
                    + ") and destination '" + rule.destinationName() + "' (" + dst.get().dimension()
                    + ") must be in the same dimension");
            finish();
            return;
        }
        if (src.get().pos().equals(dst.get().pos())) {
            Debug.logWarning("REPLENISH: source and destination point to the same chest at " + src.get().pos().toShortString());
            finish();
            return;
        }
        try {
            ReplenishWorksiteTask task = new ReplenishWorksiteTask(rule, src.get().pos(), dst.get().pos(), src.get().dimension());
            Debug.logMessage("REPLENISH: starting " + rule.describe()
                    + " [src=" + src.get().pos().toShortString() + ", dst=" + dst.get().pos().toShortString() + "]");
            mod.runUserTask(task, this::finish);
        } catch (IllegalArgumentException e) {
            Debug.logWarning("REPLENISH: " + e.getMessage());
            finish();
        }
    }
}

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.utils.player.ControlInput;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Ostinato: anticheat control experiment. Holds plain vanilla KeyMappings for N ticks while Baritone does nothing, so a
 * flag can be shown (or not) on pure vanilla input. Usage: vkeys <ticks> forward,back,left,right,jump,sneak,sprint,use,attack
 */
public class VanillaKeysCommand extends Command {

    public VanillaKeysCommand(IBaritone baritone) {
        super(baritone, "vkeys");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        int ticks = args.getAs(Integer.class);
        String keys = args.hasAny() ? args.getString() : "";
        baritone.getPathingBehavior().cancelEverything();
        ControlInput.holdVanillaKeys(ticks, keys.split(","));
        logDirect("vkeys holding " + keys + " for " + ticks + " ticks");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Hold plain vanilla keys for N ticks (anticheat control runs)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Usage:", "> vkeys <ticks> forward,jump");
    }
}

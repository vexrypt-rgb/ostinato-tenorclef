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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.CommandPermission;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Ostinato: RTS live build. {@code #blueprint live <name>}, {@code #blueprint stop}, {@code #blueprint status}. */
public class BlueprintCommand extends Command {

    public BlueprintCommand(IBaritone baritone) {
        super(baritone, "blueprint");
    }

    @Override
    public CommandPermission getPermission() {
        return CommandPermission.SWARM;
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!(baritone instanceof Baritone b)) {
            throw new CommandInvalidStateException("blueprints are not available");
        }
        String sub = args.hasAny() ? args.getString() : "status";
        switch (sub) {
            case "live" -> {
                args.requireExactly(1);
                try {
                    b.getBlueprintBehavior().live(args.getString());
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                logDirect("blueprint: " + b.getBlueprintBehavior().describe());
            }
            case "stop" -> {
                args.requireMax(0);
                b.getBlueprintBehavior().stop();
                logDirect("blueprint: stopping");
            }
            case "status" -> logDirect("blueprint: " + b.getBlueprintBehavior().describe());
            default -> throw new CommandInvalidStateException("blueprint live <name> | stop | status");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return args.hasExactlyOne() ? Stream.of("live", "stop", "status") : Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Build a command-center blueprint live as it is edited";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Watches <swarmCenterDir>/blueprints/<name>.ost (written by the command center's RTS panel) and",
                "rebuilds whenever it changes. Cells already right in the world are skipped.",
                "",
                "Usage:",
                "> blueprint live <name>",
                "> blueprint stop",
                "> blueprint status"
        );
    }
}

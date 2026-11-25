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
import baritone.swarm.rts.RoleOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Ostinato: RTS mode. {@code #role <farmer|miner|storage|armorer|idle> [key=value...]} and {@code #role status}. */
public class RoleCommand extends Command {

    public RoleCommand(IBaritone baritone) {
        super(baritone, "role");
    }

    @Override
    public CommandPermission getPermission() {
        return CommandPermission.SWARM;
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!(baritone instanceof Baritone b)) {
            throw new CommandInvalidStateException("roles are not available");
        }
        List<String> tokens = new ArrayList<>();
        while (args.hasAny()) {
            tokens.add(args.getString());
        }
        if (tokens.isEmpty() || tokens.get(0).equalsIgnoreCase("status")) {
            logDirect("role: " + b.getRoleBehavior().describe());
            return;
        }
        RoleOrder order;
        try {
            order = RoleOrder.parse(tokens);
        } catch (IllegalArgumentException e) {
            throw new CommandInvalidStateException(e.getMessage());
        }
        b.getRoleBehavior().assign(order);
        logDirect("role assigned: " + order.line());
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.of("farmer", "miner", "storage", "armorer", "idle", "status");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Give this bot a standing job (RTS mode)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "A role repeats its job and stores the output in a chest. See docs/rts-mode.md.",
                "",
                "Usage:",
                "> role farmer [at=x,y,z] [r=16] [chest=x,y,z] [batch=64]",
                "> role miner ore=iron_ore,coal_ore [chest=x,y,z] [batch=64] [keep=32]",
                "> role storage chests=x,y,z;x,y,z",
                "> role armorer [chest=x,y,z] [set=copper|iron|leather]",
                "> role idle - stop the loop",
                "> role status"
        );
    }
}

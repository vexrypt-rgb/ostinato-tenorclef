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
import baritone.swarm.rts.Blueprint;
import baritone.swarm.rts.RoleOrder;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Ostinato: RTS live blueprint command.
 * {@code #blueprint live <name> [part=...]}, {@code #blueprint verify [<name>]},
 * {@code #blueprint repair [<name>] [cells=x,y,z;... | wrong-state | all]},
 * {@code #blueprint stop}, {@code #blueprint status}.
 */
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
        String sub = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "live" -> {
                args.requireMin(1);
                args.requireMax(2);
                try {
                    String name = args.getString();
                    int[] part = null;
                    if (args.hasAny()) {
                        String p = args.getString();
                        if (!p.startsWith("part=")) {
                            throw new IllegalArgumentException("blueprint live <name> [part=x1,y1,z1:x2,y2,z2]");
                        }
                        part = RoleOrder.box(p.substring(5));
                    }
                    b.getBlueprintBehavior().live(name, part); // Ostinato: part = this bot's share of a split build
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                logDirect("blueprint: " + b.getBlueprintBehavior().describe());
            }
            case "verify", "diff" -> {
                args.requireMax(1);
                String name = args.hasAny() ? args.getString() : null;
                try {
                    logDirect("blueprint: " + b.getBlueprintBehavior().verify(name));
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
            }
            case "repair" -> {
                args.requireMax(2);
                String name = null;
                Set<Long> requested = null;
                boolean wrongStateOnly = true;
                while (args.hasAny()) {
                    String tok = args.getString();
                    String low = tok.toLowerCase(Locale.ROOT);
                    if (low.startsWith("cells=")) {
                        requested = parseCells(tok.substring(6));
                        wrongStateOnly = false;
                    } else if ("wrong-state".equals(low) || "misoriented".equals(low)) {
                        wrongStateOnly = true;
                    } else if ("all".equals(low)) {
                        wrongStateOnly = false;
                    } else if (name == null) {
                        name = tok;
                    } else {
                        throw new CommandInvalidStateException(
                                "blueprint repair [<name>] [cells=x,y,z;... | wrong-state | all]");
                    }
                }
                try {
                    logDirect("blueprint: " + b.getBlueprintBehavior().repair(name, requested, wrongStateOnly));
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
            }
            case "stop" -> {
                args.requireMax(0);
                b.getBlueprintBehavior().stop();
                logDirect("blueprint: stopping");
            }
            case "status" -> logDirect("blueprint: " + b.getBlueprintBehavior().describe());
            default -> throw new CommandInvalidStateException(
                    "blueprint live <name> [part=...] | verify [<name>] | repair [<name>] [cells=... | wrong-state | all] | stop | status");
        }
    }

    static Set<Long> parseCells(String raw) throws CommandInvalidStateException {
        if (raw == null || raw.isBlank()) {
            throw new CommandInvalidStateException("cells= expects x,y,z[;x,y,z...]");
        }
        Set<Long> out = new LinkedHashSet<>();
        for (String item : raw.split(";")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] coords = trimmed.split(",");
            if (coords.length != 3) {
                throw new CommandInvalidStateException("bad cell coordinate: " + trimmed);
            }
            try {
                int x = Integer.parseInt(coords[0].trim());
                int y = Integer.parseInt(coords[1].trim());
                int z = Integer.parseInt(coords[2].trim());
                out.add(Blueprint.cellKey(x, y, z));
            } catch (NumberFormatException e) {
                throw new CommandInvalidStateException("bad cell coordinate: " + trimmed);
            }
        }
        if (out.isEmpty()) {
            throw new CommandInvalidStateException("cells= expects at least one x,y,z coordinate");
        }
        return out;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return args.hasExactlyOne() ? Stream.of("live", "verify", "repair", "stop", "status") : Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Build, verify, or repair a command-center blueprint live as it is edited";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Watches <swarmCenterDir>/blueprints/<name>.ost (written by the command center's editor/RTS panel)",
                "and compares/builds/repairs blocks and supported explicit states (log axis, slab type, straight stair facing/half).",
                "",
                "Usage:",
                "> blueprint live <name> [part=x1,y1,z1:x2,y2,z2] - watch and build live",
                "> blueprint verify [<name>] - compare against live world and list discrepancies",
                "> blueprint repair [<name>] [cells=x,y,z;... | wrong-state | all] - repair scoped cells",
                "> blueprint stop",
                "> blueprint status"
        );
    }
}

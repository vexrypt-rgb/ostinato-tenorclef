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
import baritone.api.Settings;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.BetterBlockPos;
import baritone.behavior.SwarmBehavior;
import baritone.swarm.SwarmBuild;
import baritone.swarm.SwarmControl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** {@code #swarm ping [group]}, {@code #swarm status}, {@code #swarm reload}, {@code #swarm build}, {@code #swarm stop}. */
public class SwarmCommand extends Command {

    public SwarmCommand(IBaritone baritone) {
        super(baritone, "swarm");
    }

    private SwarmBehavior swarm() throws CommandInvalidStateException {
        if (!(baritone instanceof Baritone)) {
            throw new CommandInvalidStateException("swarm is not available");
        }
        return ((Baritone) baritone).getSwarmBehavior();
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        SwarmBehavior swarm = swarm();
        switch (action) {
            case "status": {
                args.requireMax(0);
                swarm.statusLines().forEach(this::logDirect);
                return;
            }
            case "ping": {
                args.requireMax(1);
                String group = args.hasAny() ? args.getString() : null;
                SwarmControl control = swarm.control();
                if (control == null) {
                    throw new CommandInvalidStateException("swarm link is not running (" + swarm.state() + ")");
                }
                int n;
                try {
                    n = control.ping(group);
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                logDirect("swarm: pinged " + n + " group(s); replies arrive as rate limits allow, see #swarm status");
                return;
            }
            case "reload": {
                args.requireMax(0);
                swarm.reload();
                logDirect("swarm: reloading roster and keyring");
                return;
            }
            case "build": {
                args.requireMin(2);
                String group = args.getString();
                String file = args.getString();
                BetterBlockPos origin = ctx.playerFeet();
                if (args.has(3)) {
                    origin = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
                }
                args.requireMax(0);
                Settings s = Baritone.settings();
                try {
                    running(swarm).start(group, file, origin.x, origin.y, origin.z, s.buildPartitionStrategy.value,
                            s.buildPartitionAxis.value, s.buildPartitionSeamWidth.value, s.buildPartitionGridColumns.value);
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                return;
            }
            case "stop": {
                args.requireMax(0);
                running(swarm).stop();
                logDirect("swarm: build stopped");
                return;
            }
            case "send": {
                // Ostinato: one registry command, sealed, with an id. Flag off keeps the old error.
                if (!Baritone.settings().swarmCommandProtocol.value) {
                    throw new CommandInvalidTypeException(args.consumed(), "ping, status, reload, build or stop");
                }
                args.requireMin(2);
                String target = args.getString();
                StringBuilder line = new StringBuilder();
                while (args.hasAny()) {
                    if (line.length() > 0) {
                        line.append(' ');
                    }
                    line.append(args.getString());
                }
                logDirect(swarm.submitCommand(target, line.toString()));
                return;
            }
            case "outcome": {
                if (!Baritone.settings().swarmCommandProtocol.value) {
                    throw new CommandInvalidTypeException(args.consumed(), "ping, status, reload, build or stop");
                }
                args.requireExactly(1);
                logDirect(swarm.commandOutcome(args.getString()));
                return;
            }
            case "cancel": {
                if (!Baritone.settings().swarmCommandProtocol.value) {
                    throw new CommandInvalidTypeException(args.consumed(), "ping, status, reload, build or stop");
                }
                args.requireExactly(1);
                logDirect(swarm.cancelCommand(args.getString()));
                return;
            }
            default:
                // Ostinato: the extra words appear only while swarmCommandProtocol is on.
                String expected = Baritone.settings().swarmCommandProtocol.value
                        ? "ping, status, reload, build, stop, send, outcome or cancel"
                        : "ping, status, reload, build or stop";
                throw new CommandInvalidTypeException(args.consumed(), expected);
        }
    }

    private static SwarmBuild running(SwarmBehavior swarm) throws CommandInvalidStateException {
        SwarmControl control = swarm.control();
        if (control == null || control.build() == null) {
            throw new CommandInvalidStateException("swarm link is not running (" + swarm.state() + ")");
        }
        return control.build();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            TabCompleteHelper help = new TabCompleteHelper().append("ping", "status", "reload", "build", "stop");
            if (Baritone.settings().swarmCommandProtocol.value) {
                help.append("send", "outcome", "cancel");
            }
            return help.filterPrefix(args.getString()).stream();
        }
        if (args.hasExactly(2) && (args.peekString().equalsIgnoreCase("ping") || args.peekString().equalsIgnoreCase("build"))) {
            SwarmControl control = baritone instanceof Baritone ? ((Baritone) baritone).getSwarmBehavior().control() : null;
            args.get();
            List<String> groups = control == null ? Collections.emptyList() : control.myGroups();
            return new TabCompleteHelper().append(groups.stream()).filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Swarm link: ping members, show status, split builds";
    }

    @Override
    public List<String> getLongDesc() {
        List<String> lines = new ArrayList<>(Arrays.asList(
                "Controls the encrypted swarm link (needs swarmEnabled, a roster file and a sigil keyring).",
                "Every line sent is a sealed sigil token; there is no plaintext mode.",
                "",
                "Usage:",
                "> swarm status - roster, last-seen times and link health",
                "> swarm ping - ping every member of each of your groups",
                "> swarm ping <group> - ping the members of one group",
                "> swarm reload - reload the roster and keyring",
                "> swarm build <group> <file> [x y z] - as the group's lead, split a schematic (in the schematics",
                "  folder) over the group, one region per member, using the buildPartition* settings",
                "> swarm stop - stop the build job you lead, and your own region"
        ));
        if (Baritone.settings().swarmCommandProtocol.value) {
            lines.add("> swarm send <bot> <command> - send one command and wait for its ack");
            lines.add("> swarm outcome <id> - the ack and execution state for that command id");
            lines.add("> swarm cancel <id> - ask that bot to stop the command; canceled only after it confirms");
        }
        return lines;
    }
}

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

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.process.IRoadProcess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Ostinato: {@code #road} command for terrain-aware 2-block-wide level road planning, preview, guarded construction,
 * and post-build physical verification.
 * <ul>
 *     <li>{@code #road plan <from> <to> [protect=...] [budget=48] [width=2] [block=cobblestone]} — survey, compare alignments, preview BOM</li>
 *     <li>{@code #road preview} — display the staged road preview and bill of materials</li>
 *     <li>{@code #road accept} — accept the feasible staged plan and begin guarded build</li>
 *     <li>{@code #road build <from> <to> [options...]} — plan, preview, and build immediately if feasible &amp; stocked</li>
 *     <li>{@code #road verify} — verify completed physical deck, clearance, protected zones, and no-jump traversal</li>
 *     <li>{@code #road status} — report live progress, planner time, placed-block count, retries, and outcome</li>
 *     <li>{@code #road cancel|stop} — cancel construction and release all guards</li>
 * </ul>
 */
public class RoadCommand extends Command {

    private static final List<String> SUBCOMMANDS = List.of("plan", "preview", "accept", "build", "verify", "status", "cancel", "stop");

    public RoadCommand(IBaritone baritone) {
        super(baritone, "road");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        IRoadProcess road = baritone.getRoadProcess();
        if (road == null) {
            throw new CommandInvalidStateException("Road process is not available");
        }
        String sub = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        String out;
        switch (sub) {
            case "plan" -> out = road.plan(collectRest(args));
            case "preview", "bom" -> out = road.preview();
            case "accept", "confirm", "go" -> out = road.accept();
            case "build" -> out = road.planAndBuild(collectRest(args));
            case "verify", "check" -> out = road.verify();
            case "status" -> out = road.status();
            case "cancel", "stop" -> out = road.cancel();
            default -> throw new CommandInvalidStateException(
                    "road plan <from> <to> [options] | preview | accept | build <from> <to> [options] | verify | status | cancel");
        }
        if (out != null && !out.isEmpty()) {
            for (String line : out.split("\n")) {
                logDirect(line);
            }
        }
    }

    private static String collectRest(IArgConsumer args) throws CommandException {
        List<String> rest = new ArrayList<>();
        while (args.hasAny()) {
            rest.add(args.getString());
        }
        return String.join(" ", rest);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasExactlyOne()) return Stream.empty();
        String pre = args.peekString().toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(s -> s.startsWith(pre));
    }

    @Override
    public String getShortDesc() {
        return "Plan, preview, build, and verify a level 2-block-wide terrain road";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Surveys a bounded corridor, compares candidate 2-wide level alignments respecting protected plots and",
                "a placed-block budget (default 48), previews the footprint and bill of materials, builds safely in",
                "wavefront stages, and verifies bidirectional no-jump walkability.",
                "",
                "Usage:",
                "> road plan <from> <to> [protect=x1,z1:x2,z2 | protect=sel | protect=wp:r] [budget=48] [block=cobblestone]",
                "> road preview - show staged preview and bill of materials",
                "> road accept - build the accepted feasible plan",
                "> road build <from> <to> [options...] - plan, preview, and build immediately if feasible",
                "> road verify - verify physical deck, headroom clearance, protected plots, and bidirectional no-jump walk",
                "> road status - progress and metrics",
                "> road cancel - cancel construction and release guards"
        );
    }
}

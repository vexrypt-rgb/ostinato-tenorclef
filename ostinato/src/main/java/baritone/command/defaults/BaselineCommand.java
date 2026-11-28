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
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.selection.ISelection;
import baritone.behavior.RegionUpkeep;
import baritone.swarm.rts.RoleOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Ostinato: region upkeep. {@code #baseline save <name> [region]}, {@code accept}, {@code exclude}, {@code policy},
 * {@code check}, {@code repair}, {@code stop}, {@code list}.
 * Owner-only: a baseline is what the player approved, so no roster member may change or apply one.
 */
public class BaselineCommand extends Command {

    private static final String USAGE = "baseline save <name> [x1,y1,z1:x2,y2,z2] | accept <name> <cells=..|box=..> | "
            + "exclude <name> <cells=..|box=..> | policy <name> [box=..|clear] | check <name> | "
            + "repair <name> [cells=..|box=.. ...] | stop | list";

    public BaselineCommand(IBaritone baritone) {
        super(baritone, "baseline");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!(baritone instanceof Baritone b)) {
            throw new CommandInvalidStateException("baselines are not available");
        }
        String sub = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "list";
        RegionUpkeep up = new RegionUpkeep(b);
        try {
            switch (sub) {
                case "save" -> {
                    args.requireMin(1);
                    args.requireMax(2);
                    String name = args.getString();
                    int[] box = args.hasAny() ? RoleOrder.box(args.getString()) : selectedBox(b);
                    logDirect("baseline: " + up.save(name, box));
                }
                case "accept" -> {
                    args.requireMin(2);
                    String name = args.getString();
                    RegionUpkeep.Picks picks = picks(args, true);
                    logDirect("baseline: " + up.accept(name, picks));
                }
                case "exclude", "unmanage" -> {
                    args.requireMin(2);
                    String name = args.getString();
                    RegionUpkeep.Picks picks = picks(args, true);
                    logDirect("baseline: " + up.exclude(name, picks, "player"));
                }
                case "policy" -> {
                    args.requireMin(1);
                    args.requireMax(2);
                    String name = args.getString();
                    int[] zone = null;
                    boolean clear = false;
                    if (args.hasAny()) {
                        String tok = args.getString();
                        if ("clear".equalsIgnoreCase(tok)) {
                            clear = true;
                        } else if (tok.toLowerCase(Locale.ROOT).startsWith("box=")) {
                            zone = RoleOrder.box(tok.substring(4));
                        } else {
                            throw new CommandInvalidStateException("baseline policy <name> [box=x1,y1,z1:x2,y2,z2 | clear]");
                        }
                    }
                    logDirect("baseline: " + up.policy(name, zone, clear));
                }
                case "check", "verify", "diff" -> {
                    args.requireExactly(1);
                    logDirect("baseline: " + up.checkText(args.getString()));
                }
                case "repair" -> {
                    args.requireMin(1);
                    String name = args.getString();
                    RegionUpkeep.Picks picks = picks(args, false);
                    logDirect("baseline: " + up.repair(name, picks));
                }
                case "stop" -> {
                    args.requireMax(0);
                    b.getBlueprintBehavior().stop();
                    logDirect("baseline: stopping");
                }
                case "list" -> {
                    List<String> all = up.list();
                    logDirect("baseline: " + (all.isEmpty() ? "none saved" : String.join("\n  ", all)));
                }
                default -> throw new CommandInvalidStateException(USAGE);
            }
        } catch (IllegalArgumentException e) {
            throw new CommandInvalidStateException(e.getMessage());
        }
    }

    /** The only {@code #sel} selection, so a player can select a wall and then {@code #baseline save wall}. */
    private static int[] selectedBox(Baritone b) throws CommandInvalidStateException {
        ISelection[] sels = b.getSelectionManager().getSelections();
        if (sels.length != 1) {
            throw new CommandInvalidStateException(sels.length == 0
                    ? "baseline save <name> x1,y1,z1:x2,y2,z2 (or select the wall with #sel first)"
                    : "more than one #sel selection; pass the region as x1,y1,z1:x2,y2,z2");
        }
        var lo = sels[0].min();
        var hi = sels[0].max();
        return RoleOrder.box(lo.x + "," + lo.y + "," + lo.z + ":" + hi.x + "," + hi.y + "," + hi.z);
    }

    /** {@code cells=x,y,z;x,y,z} and {@code box=x1,y1,z1:x2,y2,z2} tokens, in any number. */
    static RegionUpkeep.Picks picks(IArgConsumer args, boolean required) throws CommandException {
        List<int[]> cells = new ArrayList<>();
        List<int[]> boxes = new ArrayList<>();
        while (args.hasAny()) {
            String tok = args.getString();
            String low = tok.toLowerCase(Locale.ROOT);
            if (low.startsWith("cells=")) {
                for (String item : tok.substring(6).split(";")) {
                    if (!item.isBlank()) {
                        cells.add(cell(item));
                    }
                }
            } else if (low.startsWith("box=")) {
                boxes.add(RoleOrder.box(tok.substring(4)));
            } else if (!"policy".equals(low)) { // "repair <name> policy": standing zones only, no extra selection
                throw new CommandInvalidStateException("expected cells=x,y,z;... or box=x1,y1,z1:x2,y2,z2, got " + tok);
            }
        }
        RegionUpkeep.Picks p = new RegionUpkeep.Picks(cells, boxes);
        if (required && p.isEmpty()) {
            throw new CommandInvalidStateException("pick cells=x,y,z;... or box=x1,y1,z1:x2,y2,z2");
        }
        return p;
    }

    private static int[] cell(String item) throws CommandInvalidStateException {
        String[] c = item.trim().split(",");
        if (c.length != 3) {
            throw new CommandInvalidStateException("bad cell coordinate: " + item.trim());
        }
        try {
            return new int[]{Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()), Integer.parseInt(c[2].trim())};
        } catch (NumberFormatException e) {
            throw new CommandInvalidStateException("bad cell coordinate: " + item.trim());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return args.hasExactlyOne()
                ? Stream.of("save", "accept", "exclude", "policy", "check", "repair", "stop", "list")
                : Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Keep a saved wall in its player-approved state: accept edits, review surprises, restore damage";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Saves a bounded region of static masonry as a baseline tagged with this world and dimension, and later",
                "restores only the damage you authorize. Uses only what the client can see; unloaded cells stay unknown.",
                "",
                "Usage:",
                "> baseline save <name> [x1,y1,z1:x2,y2,z2] - snapshot the region (defaults to your #sel selection)",
                "> baseline accept <name> cells=x,y,z;... | box=... - approve the current state (a doorway) as intended",
                "> baseline exclude <name> cells=... | box=... - mark cells unmanaged: never compared or restored",
                "> baseline policy <name> [box=... | clear] - standing zones where damage is restored without a selection",
                "> baseline check <name> - what matches, needs review, would restore, or is unknown",
                "> baseline repair <name> [cells=... | box=...] - restore authorized damage, verify it in the world",
                "> baseline stop | list",
                "",
                "Containers are never overwritten, refilled or cleared. Unclassified differences are only reported."
        );
    }
}

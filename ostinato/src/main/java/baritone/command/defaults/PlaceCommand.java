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
import baritone.api.command.CommandPermission;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.schematic.FillSchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Ostinato: one block at one cell. The crash ledger already treats {@code place x y z block} as the place effect.
 * This is the command that actually puts the block there, through the builder, so a relaunch can see it.
 */
public class PlaceCommand extends Command {

    public PlaceCommand(IBaritone baritone) {
        super(baritone, "place");
    }

    // Ostinato: roster members may send this when swarmCommandProtocol is on. Local # chat is unchanged.
    @Override
    public CommandPermission getPermission() {
        return CommandPermission.SWARM;
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        PlaceCell cell = PlaceCell.parse(args.rawRest());
        while (args.hasAny()) {
            args.get();
        }
        if (cell == null) {
            throw new CommandInvalidStateException("place needs x y z and a block, for example: place 10 64 10 stone");
        }
        BlockOptionalMeta block;
        try {
            block = new BlockOptionalMeta(cell.block);
        } catch (RuntimeException e) {
            throw new CommandInvalidStateException("unknown block " + cell.block);
        }
        baritone.getBuilderProcess().build("place", new FillSchematic(1, 1, 1, block),
                new BetterBlockPos(cell.x, cell.y, cell.z));
        logDirect("Placing " + cell.block + " at " + cell.x + " " + cell.y + " " + cell.z);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Place one block";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Places one block at a cell. A crash after it is down checks that cell before placing again.",
                "",
                "Usage:",
                "> place <x> <y> <z> <block> - place that block at that cell"
        );
    }
}

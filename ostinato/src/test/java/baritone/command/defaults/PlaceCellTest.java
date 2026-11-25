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

import baritone.swarm.protocol.EffectNote;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The cell {@code place} builds is the cell the crash note checks. */
public class PlaceCellTest {

    @Test
    public void thePlacedCellIsTheCrashNote() {
        String args = "10 64 10 stone";
        PlaceCell cell = PlaceCell.parse(args);
        String note = EffectNote.capture("place", args, null);
        assertEquals("p:10,64,10,stone", note);
        assertEquals(note.substring(2), cell.x + "," + cell.y + "," + cell.z + "," + cell.block);
    }

    @Test
    public void blockTokenBeforeTheCoordinatesStillNamesThatCell() {
        String args = "stone 10 64 10";
        PlaceCell cell = PlaceCell.parse(args);
        String note = EffectNote.capture("place", args, null);
        assertEquals("p:" + cell.x + "," + cell.y + "," + cell.z + "," + cell.block, note);
        assertEquals("stone", cell.block);
    }

    @Test
    public void aLineWithNoCellIsNotAPlace() {
        assertNull(PlaceCell.parse("stone"));
        assertNull(PlaceCell.parse("10 64"));
        assertNull(PlaceCell.parse(null));
        assertEquals("", EffectNote.capture("place", "stone", null));
    }
}

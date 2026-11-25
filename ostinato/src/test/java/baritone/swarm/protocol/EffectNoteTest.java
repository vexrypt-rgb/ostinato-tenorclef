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

package baritone.swarm.protocol;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The block and inventory rules, with no client and no protocol. */
public class EffectNoteTest {

    @Test
    public void mineDoneWhenTheSnapshottedOreIsGone() {
        MapView world = new MapView();
        world.blocks.put("1,64,0", "minecraft:emerald_ore");
        String note = EffectNote.capture("mine", "1 emerald_ore", world);
        assertTrue(note.contains("1,64,0"));
        world.blocks.put("1,64,0", "minecraft:air");
        assertEquals(EffectCheck.Verdict.DONE, EffectNote.evaluate("mine", "1 emerald_ore", note, world));
        world.blocks.put("1,64,0", "minecraft:emerald_ore");
        assertEquals(EffectCheck.Verdict.NOT_DONE, EffectNote.evaluate("mine", "1 emerald_ore", note, world));
        world.blocks.remove("1,64,0");
        assertEquals(EffectCheck.Verdict.UNKNOWN, EffectNote.evaluate("mine", "1 emerald_ore", note, world));
    }

    @Test
    public void mineWithNothingVisibleIsNotDone() {
        MapView world = new MapView();
        String note = EffectNote.capture("mine", "1 diamond_ore", world);
        assertEquals(EffectCheck.Verdict.NOT_DONE, EffectNote.evaluate("mine", "1 diamond_ore", note, world));
    }

    @Test
    public void placeDoneWhenTheCellIsThatBlock() {
        MapView world = new MapView();
        String note = EffectNote.capture("place", "10 64 10 stone", world);
        assertEquals("p:10,64,10,stone", note);
        world.blocks.put("10,64,10", "minecraft:stone");
        assertEquals(EffectCheck.Verdict.DONE, EffectNote.evaluate("place", "10 64 10 stone", note, world));
        world.blocks.put("10,64,10", "minecraft:air");
        assertEquals(EffectCheck.Verdict.NOT_DONE, EffectNote.evaluate("place", "10 64 10 stone", note, world));
        world.blocks.remove("10,64,10");
        assertEquals(EffectCheck.Verdict.UNKNOWN, EffectNote.evaluate("place", "10 64 10 stone", note, world));
    }

    @Test
    public void pickupDoneWhenTheCountRisesAndDropWhenItFalls() {
        MapView world = new MapView();
        world.counts.put("minecraft:diamond", 0);
        String up = EffectNote.capture("pickup", "diamond", world);
        assertEquals(EffectCheck.Verdict.NOT_DONE, EffectNote.evaluate("pickup", "diamond", up, world));
        world.counts.put("minecraft:diamond", 1);
        assertEquals(EffectCheck.Verdict.DONE, EffectNote.evaluate("pickup", "diamond", up, world));

        world.counts.put("minecraft:cobblestone", 3);
        String down = EffectNote.capture("drop", "cobblestone", world);
        assertEquals(EffectCheck.Verdict.NOT_DONE, EffectNote.evaluate("drop", "cobblestone", down, world));
        world.counts.put("minecraft:cobblestone", 2);
        assertEquals(EffectCheck.Verdict.DONE, EffectNote.evaluate("drop", "cobblestone", down, world));
    }

    @Test
    public void unreadableInventoryIsUnknown() {
        MapView world = new MapView();
        world.inventory = false;
        assertEquals("", EffectNote.capture("pickup", "diamond", world));
        assertEquals(EffectCheck.Verdict.UNKNOWN, EffectNote.evaluate("pickup", "diamond", "", world));
    }

    private static final class MapView implements EffectNote.View {
        final Map<String, String> blocks = new HashMap<>();
        final Map<String, Integer> counts = new HashMap<>();
        boolean inventory = true;
        int[] feet = {0, 64, 0};

        @Override
        public String blockAt(int x, int y, int z) {
            return blocks.get(x + "," + y + "," + z);
        }

        @Override
        public int itemCount(String itemId) {
            if (!inventory) {
                return -1;
            }
            if (itemId == null || itemId.isEmpty()) {
                int n = 0;
                for (int v : counts.values()) {
                    n += v;
                }
                return n;
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (EffectNote.sameId(e.getKey(), itemId)) {
                    return e.getValue();
                }
            }
            return 0;
        }

        @Override
        public int[] feet() {
            return feet;
        }
    }
}

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

package baritone.swarm.world;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: greedy one-bot-per-vein assignment by C(b,t). */
public class GatherAssignTest {

    private static GatherAssign.Bot bot(String name, int x, long sw) {
        return new GatherAssign.Bot(name, x, 0, 0, 0, sw);
    }

    private static GatherAssign.Vein vein(String key, int x, int blocks) {
        return new GatherAssign.Vein(key, x, 0, 0, blocks);
    }

    @Test
    public void threeBotsTakeThreeVeinsWithNoDoubleOwner() {
        List<GatherAssign.Bot> bots = List.of(bot("Azalea", 0, 0), bot("Spelunker", 100, 0), bot("Copper", 200, 0));
        List<GatherAssign.Vein> veins = List.of(vein("v:near", 2, 1), vein("v:mid", 100, 1), vein("v:far", 198, 1));
        List<GatherAssign.Assignment> a = GatherAssign.assign(bots, veins, 3);
        assertEquals(3, a.size());
        // The zero-travel mid pair costs 2 and is taken first. The two travel-2 pairs cost 4.
        assertEquals("Spelunker", a.get(0).bot);
        assertEquals("v:mid", a.get(0).key);
        assertEquals(2L, a.get(0).cost);
        assertEquals("Azalea", a.get(1).bot);
        assertEquals("v:near", a.get(1).key);
        assertEquals(4L, a.get(1).cost);
        assertEquals("Copper", a.get(2).bot);
        assertEquals("v:far", a.get(2).key);
        assertEquals(4L, a.get(2).cost);
        assertEquals(3, a.stream().map(x -> x.bot).distinct().count());
        assertEquals(3, a.stream().map(x -> x.key).distinct().count());
    }

    @Test
    public void aBusyBotLosesToAnIdleOne() {
        List<GatherAssign.Bot> bots = List.of(bot("Azalea", 0, GatherAssign.SWITCH), bot("Spelunker", 30, 0));
        List<GatherAssign.Vein> veins = List.of(vein("v:a", 1, 1));
        List<GatherAssign.Assignment> a = GatherAssign.assign(bots, veins, 1);
        assertEquals("Spelunker", a.get(0).bot);
    }

    @Test
    public void theCountCapsTheAssignment() {
        List<GatherAssign.Bot> bots = List.of(bot("Azalea", 0, 0), bot("Spelunker", 10, 0));
        List<GatherAssign.Vein> veins = List.of(vein("v:a", 0, 1), vein("v:b", 10, 1), vein("v:c", 20, 1));
        assertEquals(2, GatherAssign.assign(bots, veins, 2).size());
        assertTrue(GatherAssign.assign(bots, veins, 0).isEmpty());
        assertTrue(GatherAssign.assign(List.of(), veins, 3).isEmpty());
    }

    @Test
    public void theLocalBotIsACandidateBesideThePeers() {
        List<GatherAssign.Named> peers = List.of(
                new GatherAssign.Named("Azalea", "0,150,0", "idle"),
                new GatherAssign.Named("Spelunker", "100,150,0", "idle"));
        List<GatherAssign.Bot> bots = GatherAssign.candidates(peers,
                new GatherAssign.Named("Copper", "200,150,0", "idle"));
        assertEquals(3, bots.size());
        assertEquals("Copper", bots.get(2).name);
        assertEquals(200, bots.get(2).x);
        assertEquals(0L, bots.get(2).sw);
    }

    @Test
    public void aBusyLocalBotPaysTheSwitchPenalty() {
        List<GatherAssign.Bot> bots = GatherAssign.candidates(List.of(),
                new GatherAssign.Named("Copper", "1,2,3", "Mine"));
        assertEquals(1, bots.size());
        assertEquals(GatherAssign.SWITCH, bots.get(0).sw);
    }

    @Test
    public void theLocalBotIsNotListedTwice() {
        List<GatherAssign.Named> peers = List.of(new GatherAssign.Named("Copper", "5,150,5", "idle"));
        List<GatherAssign.Bot> bots = GatherAssign.candidates(peers,
                new GatherAssign.Named("copper", "5,150,5", "idle"));
        assertEquals(1, bots.size());
    }

    @Test
    public void aMissingPositionIsNotACandidate() {
        assertTrue(GatherAssign.candidates(List.of(new GatherAssign.Named("Azalea", "?", "idle")),
                new GatherAssign.Named("Copper", null, "idle")).isEmpty());
    }

    @Test
    public void equalCostBreaksByNameThenKey() {
        List<GatherAssign.Bot> bots = List.of(bot("Spelunker", 0, 0), bot("Azalea", 0, 0));
        List<GatherAssign.Vein> veins = List.of(vein("v:b", 0, 1), vein("v:a", 0, 1));
        List<GatherAssign.Assignment> a = GatherAssign.assign(bots, veins, 2);
        assertEquals("Azalea", a.get(0).bot);
        assertEquals("v:a", a.get(0).key);
        assertEquals("Spelunker", a.get(1).bot);
        assertEquals("v:b", a.get(1).key);
    }
}

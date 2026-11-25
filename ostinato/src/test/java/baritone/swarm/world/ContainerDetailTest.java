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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Ostinato: the canonical contents detail of an opened container. */
public class ContainerDetailTest {

    @Test
    public void countsAreSortedAndNamespacedIdsAreShort() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("minecraft:stick", 2);
        counts.put("diamond", 4);
        assertEquals("diamond.4,stick.2", ContainerDetail.of(counts));
        assertTrue(ContainerDetail.isContents("diamond.4,stick.2"));
    }

    @Test
    public void emptyIsAContentsDetailAndABlockIdIsNot() {
        assertEquals("empty", ContainerDetail.of(Map.of()));
        assertTrue(ContainerDetail.isContents("empty"));
        assertFalse(ContainerDetail.isContents("chest"));
        assertFalse(ContainerDetail.isContents("trapped_chest"));
        assertFalse(ContainerDetail.isContents(null));
        assertFalse(ContainerDetail.isContents("diamond.0"));
    }
}

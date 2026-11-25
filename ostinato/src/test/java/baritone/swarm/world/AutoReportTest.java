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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: automatic structure and entity records stay inside the existing key and cap rules. */
public class AutoReportTest {

    @Test
    public void nearestStructuresBecomeCanonicalRecords() {
        List<AutoReport.Structure> found = List.of(
                new AutoReport.Structure("fortress", null, 100, 64, -20, 90),
                new AutoReport.Structure("bastion_remnant", "stables", 10, 70, 10, 100),
                new AutoReport.Structure("not a structure", null, 0, 64, 0, 50));
        List<ObservationReporter.Seen> seen = AutoReport.structures('n', 0, 64, 0, found);
        assertEquals(2, seen.size());
        assertEquals("s:n:10,70,10", seen.get(0).key);
        assertEquals("bastion_remnant.stables", seen.get(0).detail);
        assertEquals(100, seen.get(0).confidence);
        assertEquals("s:n:100,64,-20", seen.get(1).key);
        assertEquals("fortress", seen.get(1).detail);
        assertEquals(ObservationRecord.Kind.STRUCTURE, ObservationRecord.kindOf(seen.get(0).key));
    }

    @Test
    public void structureCountStopsAtTheCap() {
        List<AutoReport.Structure> found = new ArrayList<>();
        for (int i = 0; i < AutoReport.STRUCTURE_MAX + 8; i++) {
            found.add(new AutoReport.Structure("portal", null, i * 10, 64, 0, 80));
        }
        assertEquals(AutoReport.STRUCTURE_MAX, AutoReport.structures('o', 0, 64, 0, found).size());
    }

    @Test
    public void nearbyMobsAreEntitiesAndAFarOneIsLeftOut() {
        List<AutoReport.Mob> found = List.of(
                new AutoReport.Mob("zombie", 12, 3, 64, 9),
                new AutoReport.Mob("cow", 4, 40, 64, 0),
                new AutoReport.Mob("pig", -1, 1, 64, 1));
        List<ObservationReporter.Seen> seen = AutoReport.entities('o', 0, 64, 0, found);
        assertEquals(1, seen.size());
        assertEquals("e:o:zombie-12", seen.get(0).key);
        assertEquals("zombie@3,64,9", seen.get(0).detail);
        assertEquals(ObservationRecord.Kind.ENTITY, ObservationRecord.kindOf(seen.get(0).key));
    }

    @Test
    public void entityCountStopsAtTheCapAndKeepsTheNearest() {
        List<AutoReport.Mob> found = new ArrayList<>();
        for (int i = 0; i < AutoReport.ENTITY_MAX + 5; i++) {
            found.add(new AutoReport.Mob("zombie", i, i, 64, 0));
        }
        List<ObservationReporter.Seen> seen = AutoReport.entities('o', 0, 64, 0, found);
        assertEquals(AutoReport.ENTITY_MAX, seen.size());
        assertEquals("e:o:zombie-0", seen.get(0).key);
        assertEquals("e:o:zombie-" + (AutoReport.ENTITY_MAX - 1), seen.get(seen.size() - 1).key);
    }

    @Test
    public void noteResendsEntitiesInsideTheirTtlAndDoesNotCallThemGone() {
        ObservationReporter rep = new ObservationReporter();
        assertTrue(rep.setWorld("w1"));
        List<ObservationMessage> sent = new ArrayList<>();
        ObservationReporter.Sender sender = m -> {
            sent.add(m);
            return true;
        };
        long t0 = 1_000_000L;
        rep.note(AutoReport.entities('o', 0, 64, 0, List.of(new AutoReport.Mob("zombie", 12, 3, 64, 9))), t0,
                AutoReport.ENTITY_RESEND_MS);
        assertTrue(rep.tick(t0, true, sender));
        assertEquals(ObservationMessage.Op.R, sent.get(0).op);
        assertEquals("e:o:zombie-12", sent.get(0).entries.get(0).key);
        rep.note(AutoReport.entities('o', 0, 64, 0, List.of(new AutoReport.Mob("zombie", 12, 3, 64, 9))),
                t0 + AutoReport.ENTITY_RESEND_MS - 1, AutoReport.ENTITY_RESEND_MS);
        assertEquals(0, rep.pending());
        rep.note(Collections.emptyList(), t0 + AutoReport.ENTITY_RESEND_MS - 1, AutoReport.ENTITY_RESEND_MS);
        assertEquals("an entity that left the radius is not a gone report", 0, rep.pending());
        rep.note(AutoReport.entities('o', 0, 64, 0, List.of(new AutoReport.Mob("zombie", 12, 4, 64, 9))),
                t0 + AutoReport.ENTITY_RESEND_MS, AutoReport.ENTITY_RESEND_MS);
        assertEquals(1, rep.pending());
        assertTrue(rep.tick(t0 + ObservationReporter.SPOOL_GAP_MS, true, sender));
        assertEquals("zombie@4,64,9", sent.get(sent.size() - 1).entries.get(0).detail);
        assertEquals(ObservationMessage.Op.R, sent.get(sent.size() - 1).op);
    }
}

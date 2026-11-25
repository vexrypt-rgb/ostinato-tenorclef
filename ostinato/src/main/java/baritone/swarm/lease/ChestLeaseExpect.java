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

package baritone.swarm.lease;

import baritone.swarm.world.ContainerDetail;
import baritone.swarm.world.ObservationRecord;
import baritone.swarm.world.WorldKnowledge;

/**
 * Ostinato: Phase 2c part 2. A chest lease offered from a stored contents detail carries that detail, so the on-open
 * check can refuse a mismatch. A block id such as {@code chest} is not a contents claim. Vein and region leases are
 * left to their own paths.
 */
public final class ChestLeaseExpect {

    private ChestLeaseExpect() {}

    /** The contents claim to put on a lease of {@code key}, or null when this lease should carry none. */
    public static String of(WorldKnowledge wk, String key) {
        if (wk == null || key == null || key.isEmpty() || key.charAt(0) != 'c') {
            return null;
        }
        ObservationRecord rec = wk.get(key);
        if (rec == null || rec.gone() || !ContainerDetail.isContents(rec.detail)) {
            return null;
        }
        return rec.detail;
    }
}

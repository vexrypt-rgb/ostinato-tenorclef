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

import java.util.UUID;

/**
 * Ostinato: Phase 2c part 2. Whether a place, break or transfer already happened, so a relaunch can avoid doing it
 * twice. Called under the protocol lock and off it: must not call back into the protocol.
 */
public interface EffectCheck {

    enum Verdict {
        /** The block or inventory already shows the effect. */
        DONE,
        /** It does not. Running the command once is safe. */
        NOT_DONE,
        /** The world could not be read (chunk not held, no player). Do not act. */
        UNKNOWN
    }

    /**
     * A durable note of what "done" means, taken just before the command runs. Empty when this command has nothing
     * the check can see.
     */
    String capture(UUID id, String name, String args);

    /** {@code note} is what {@link #capture} returned for this same command. */
    Verdict evaluate(UUID id, String name, String args, String note);
}

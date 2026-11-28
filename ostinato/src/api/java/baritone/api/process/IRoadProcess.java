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

package baritone.api.process;

/**
 * Ostinato: terrain-aware road planning, preview, guarded construction, and physical verification process.
 * Exposed through {@code baritone.api.*} so high-level drivers (e.g. TenorClef) can invoke road building without
 * importing Ostinato internals.
 */
public interface IRoadProcess extends IBaritoneProcess {

    /**
     * Surveys the bounded corridor and plans a candidate alignment without editing the world.
     *
     * @param specText road command arguments (same syntax as {@code #road plan ...})
     * @return human-readable preview, bill of materials, or exact infeasibility diagnosis
     */
    String plan(String specText);

    /**
     * Returns the current planned road preview and bill of materials, or a status message if none is staged.
     */
    String preview();

    /**
     * Accepts the staged feasible road plan and begins guarded construction.
     *
     * @return status summary or refusal reason
     */
    String accept();

    /**
     * Plans, previews, validates, and immediately starts building if the best alignment is feasible and stocked.
     * Fails before any world edits when infeasible.
     *
     * @param specText road command arguments (same syntax as {@code #road build ...})
     * @return preview + start status, or exact infeasibility diagnosis
     */
    String planAndBuild(String specText);

    /**
     * Re-verifies the completed physical deck, headroom clearance, protected-plot integrity, and bidirectional
     * no-jump walkability in the client-visible world.
     */
    String verify();

    /**
     * Returns current road build status, metrics (planner time, placed blocks, retries), and last outcome.
     */
    String status();

    /**
     * Cancels active construction or clears any staged preview and releases all temporary guards.
     */
    String cancel();

    /**
     * Terminal/live outcome of the most recent road request.
     */
    @Override
    default ProcessOutcome outcome() {
        return ProcessOutcome.NONE;
    }
}

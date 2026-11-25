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

package baritone.utils;
import baritone.api.utils.IPlayerContext;
import baritone.utils.player.ControlInput;
// Ostinato: vanilla key handling owns delays, mining state, offhand fallback and swings.
public final class BlockBreakHelper {
    BlockBreakHelper(IPlayerContext ctx) {}
    // Ostinato: publish through the controls-only input boundary.
    public void stopBreakingBlock() { ControlInput.releaseAttack(); }
    // Ostinato: publish through the controls-only input boundary.
    public void tick(boolean down) { ControlInput.holdAttack(down); }
}

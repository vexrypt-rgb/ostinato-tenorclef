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

/**
 * Ostinato: the cell a {@code place} line names. The crash note reads the same three coordinates and the same
 * block token, so a relaunch that finds that block there does not place it again.
 */
public final class PlaceCell {

    public final int x;
    public final int y;
    public final int z;
    public final String block;

    private PlaceCell(int x, int y, int z, String block) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block;
    }

    /** Null when the line has no coordinate triple or no block token. */
    public static PlaceCell parse(String args) {
        if (args == null) {
            return null;
        }
        String[] tokens = args.trim().split("\\s+");
        int[] pos = null;
        for (int i = 0; i + 2 < tokens.length; i++) {
            Integer x = intOrNull(tokens[i]);
            Integer y = intOrNull(tokens[i + 1]);
            Integer z = intOrNull(tokens[i + 2]);
            if (x != null && y != null && z != null) {
                pos = new int[]{x, y, z};
                break;
            }
        }
        String block = null;
        for (String token : tokens) {
            if (token.isEmpty() || intOrNull(token) != null) {
                continue;
            }
            block = token;
            break;
        }
        if (pos == null || block == null) {
            return null;
        }
        return new PlaceCell(pos[0], pos[1], pos[2], block);
    }

    private static Integer intOrNull(String token) {
        try {
            return Integer.valueOf(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

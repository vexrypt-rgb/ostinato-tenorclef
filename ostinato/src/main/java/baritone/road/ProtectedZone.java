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

package baritone.road;

import java.util.Objects;

/**
 * Axis-aligned exclusion zone (e.g. a protected farm field or structure plot).
 * Neither road edits (place or break) nor road footprint / builder access may violate a protected zone.
 */
public record ProtectedZone(String name, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public ProtectedZone {
        name = (name == null || name.isBlank()) ? "protected" : name.trim();
        int x0 = Math.min(minX, maxX);
        int x1 = Math.max(minX, maxX);
        int y0 = Math.min(minY, maxY);
        int y1 = Math.max(minY, maxY);
        int z0 = Math.min(minZ, maxZ);
        int z1 = Math.max(minZ, maxZ);
        minX = x0;
        maxX = x1;
        minY = y0;
        maxY = y1;
        minZ = z0;
        maxZ = z1;
    }

    /** Full-height column exclusion zone covering all Y levels across {@code [x1..x2] x [z1..z2]}. */
    public static ProtectedZone columns(String name, int x1, int z1, int x2, int z2) {
        return new ProtectedZone(name, x1, Integer.MIN_VALUE, z1, x2, Integer.MAX_VALUE, z2);
    }

    /** 3D box exclusion zone covering {@code [x1..x2] x [y1..y2] x [z1..z2]}. */
    public static ProtectedZone box(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new ProtectedZone(name, x1, y1, z1, x2, y2, z2);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** True when column {@code (x, z)} overlaps this zone at any Y in {@code [yLo, yHi]}. */
    public boolean overlapsColumn(int x, int z, int yLo, int yHi) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && maxY >= yLo && minY <= yHi;
    }

    public int columnCount() {
        return (maxX - minX + 1) * (maxZ - minZ + 1);
    }

    public String describe() {
        if (minY == Integer.MIN_VALUE && maxY == Integer.MAX_VALUE) {
            return name + "(" + minX + "," + minZ + ".." + maxX + "," + maxZ + ")";
        }
        return name + "(" + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ + ")";
    }

    /** Packs (x, y, z) identically to {@link baritone.maze.ConnectedPlan#key(int, int, int)}. */
    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static int x(long k) {
        return (int) (k >> 38);
    }

    public static int y(long k) {
        return (int) (k << 52 >> 52);
    }

    public static int z(long k) {
        return (int) (k << 26 >> 38);
    }

    /** Packs a 2D (x, z) column coordinate. */
    public static long colKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public static int colX(long k) {
        return (int) (k >> 32);
    }

    public static int colZ(long k) {
        return (int) k;
    }

    @Override
    public String toString() {
        return describe();
    }
}

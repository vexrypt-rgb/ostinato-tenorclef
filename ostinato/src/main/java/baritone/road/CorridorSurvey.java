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

import baritone.road.RoadProbe.CellKind;

/**
 * Bounded terrain survey for a single corridor between {@link RoadSpec#startX()}..{@link RoadSpec#endX()}.
 * Strictly probes only columns inside {@code [minX..maxX] x [minZ..maxZ]} and vertical slice
 * {@code [deckY - 1 .. deckY + headroom]}.
 */
public final class CorridorSurvey {

    public static final int FLAG_PROTECTED = 1 << 0;
    public static final int FLAG_UNLOADED = 1 << 1;
    public static final int FLAG_CLEARANCE_BLOCKED = 1 << 2;
    public static final int FLAG_DECK_IMPASSABLE = 1 << 3;
    public static final int FLAG_SOLID_DECK = 1 << 4;
    public static final int FLAG_DECK_NEEDS_CLEAR = 1 << 5;
    public static final int FLAG_STREAM = 1 << 6;
    public static final int FLAG_SOLID_BELOW = 1 << 7;

    public final int minX, maxX, minZ, maxZ;
    public final int sizeX, sizeZ;
    public final int deckY, headroom;
    public final int surveyedColumns;
    public final int surveyedBlocks;
    public final int protectedColumns;
    public final int streamColumns;
    public final int unloadedColumns;
    public final long surveyTimeNanos;

    private final int[] flags;
    private final byte[] placedCost;
    private final byte[] clearCost;
    /** Bitmask of clearable Y offsets relative to {@code deckY}: bit 0 = deckY, bit 1 = deckY+1, ... */
    private final byte[] clearMask;

    private CorridorSurvey(
            int minX, int maxX, int minZ, int maxZ,
            int deckY, int headroom,
            int surveyedColumns, int surveyedBlocks,
            int protectedColumns, int streamColumns, int unloadedColumns,
            long surveyTimeNanos,
            int[] flags, byte[] placedCost, byte[] clearCost, byte[] clearMask) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.sizeX = maxX - minX + 1;
        this.sizeZ = maxZ - minZ + 1;
        this.deckY = deckY;
        this.headroom = headroom;
        this.surveyedColumns = surveyedColumns;
        this.surveyedBlocks = surveyedBlocks;
        this.protectedColumns = protectedColumns;
        this.streamColumns = streamColumns;
        this.unloadedColumns = unloadedColumns;
        this.surveyTimeNanos = surveyTimeNanos;
        this.flags = flags;
        this.placedCost = placedCost;
        this.clearCost = clearCost;
        this.clearMask = clearMask;
    }

    public static CorridorSurvey survey(RoadSpec spec, RoadProbe probe) {
        long t0 = System.nanoTime();
        int minX = spec.minCorridorX();
        int maxX = spec.maxCorridorX();
        int minZ = spec.minCorridorZ();
        int maxZ = spec.maxCorridorZ();
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        int totalCols = sizeX * sizeZ;
        int deckY = spec.deckY();
        int headroom = spec.headroom();

        int[] flags = new int[totalCols];
        byte[] placedCost = new byte[totalCols];
        byte[] clearCost = new byte[totalCols];
        byte[] clearMask = new byte[totalCols];

        int blocks = 0;
        int protCount = 0;
        int streamCount = 0;
        int unloadedCount = 0;

        for (int x = minX; x <= maxX; x++) {
            int ox = x - minX;
            for (int z = minZ; z <= maxZ; z++) {
                int idx = ox * sizeZ + (z - minZ);
                int f = 0;
                byte pCost = 0;
                byte cCost = 0;
                byte cMask = 0;

                if (spec.isProtectedColumn(x, z)) {
                    f |= FLAG_PROTECTED;
                    protCount++;
                }

                CellKind below = probe.get(x, deckY - 1, z);
                CellKind deck = probe.get(x, deckY, z);
                blocks += 2;

                if (below == CellKind.UNKNOWN || deck == CellKind.UNKNOWN) {
                    f |= FLAG_UNLOADED;
                }
                if (below.canPlaceAgainst()) {
                    f |= FLAG_SOLID_BELOW;
                }
                if (deck == CellKind.FLUID || (deck.isPassableHeadroom() && below == CellKind.FLUID)) {
                    f |= FLAG_STREAM;
                    streamCount++;
                }

                if (deck.isWalkableFullDeck()) {
                    f |= FLAG_SOLID_DECK;
                    pCost = 0;
                } else if (deck.isReplaceableForDeck()) {
                    pCost = 1;
                } else if (deck == CellKind.SOLID_NON_FULL_CLEARABLE) {
                    f |= FLAG_DECK_NEEDS_CLEAR;
                    pCost = 1;
                    cCost++;
                    cMask |= 1;
                } else if (deck == CellKind.HAZARD) {
                    f |= FLAG_DECK_IMPASSABLE;
                }
                // CellKind.UNKNOWN is already tracked via FLAG_UNLOADED so unloaded diagnostics can isolate it.

                for (int dy = 1; dy <= headroom; dy++) {
                    CellKind above = probe.get(x, deckY + dy, z);
                    blocks++;
                    if (above == CellKind.UNKNOWN) {
                        f |= FLAG_UNLOADED;
                    } else if (above.isPassableHeadroom()) {
                        // already clear
                    } else if (above.isClearableObstacle()) {
                        cCost++;
                        cMask |= (byte) (1 << dy);
                    } else {
                        // FLUID, HAZARD, UNBREAKABLE in headroom
                        f |= FLAG_CLEARANCE_BLOCKED;
                    }
                }

                if ((f & FLAG_UNLOADED) != 0) {
                    unloadedCount++;
                }
                flags[idx] = f;
                placedCost[idx] = pCost;
                clearCost[idx] = cCost;
                clearMask[idx] = cMask;
            }
        }

        long elapsed = System.nanoTime() - t0;
        return new CorridorSurvey(
                minX, maxX, minZ, maxZ, deckY, headroom,
                totalCols, blocks, protCount, streamCount, unloadedCount, elapsed,
                flags, placedCost, clearCost, clearMask);
    }

    public boolean inBounds(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private int index(int x, int z) {
        return (x - minX) * sizeZ + (z - minZ);
    }

    public int flags(int x, int z) {
        return inBounds(x, z) ? flags[index(x, z)] : FLAG_UNLOADED;
    }

    public boolean isProtected(int x, int z) {
        return (flags(x, z) & FLAG_PROTECTED) != 0;
    }

    public boolean isUnloaded(int x, int z) {
        return (flags(x, z) & FLAG_UNLOADED) != 0;
    }

    public boolean isClearanceBlocked(int x, int z) {
        return (flags(x, z) & FLAG_CLEARANCE_BLOCKED) != 0;
    }

    public boolean isDeckImpassable(int x, int z) {
        return (flags(x, z) & FLAG_DECK_IMPASSABLE) != 0;
    }

    public boolean hasSolidDeck(int x, int z) {
        return (flags(x, z) & FLAG_SOLID_DECK) != 0;
    }

    public boolean hasSolidBelow(int x, int z) {
        return (flags(x, z) & FLAG_SOLID_BELOW) != 0;
    }

    public boolean isStream(int x, int z) {
        return (flags(x, z) & FLAG_STREAM) != 0;
    }

    public int placedCost(int x, int z) {
        return inBounds(x, z) ? placedCost[index(x, z)] : 1;
    }

    public int clearCost(int x, int z) {
        return inBounds(x, z) ? clearCost[index(x, z)] : 0;
    }

    public int clearMask(int x, int z) {
        return inBounds(x, z) ? (clearMask[index(x, z)] & 0xFF) : 0;
    }

    /** True when this column is currently walkable at {@code deckY + 1} without any edits or protected-zone entry. */
    public boolean isCurrentlyWalkableSafe(int x, int z) {
        int f = flags(x, z);
        if ((f & (FLAG_PROTECTED | FLAG_UNLOADED | FLAG_CLEARANCE_BLOCKED | FLAG_DECK_IMPASSABLE)) != 0) {
            return false;
        }
        return (f & FLAG_SOLID_DECK) != 0 && clearCost(x, z) == 0;
    }

    /** True when this column can be part of a road footprint under the given constraint relaxations. */
    public boolean isColumnUsable(int x, int z, boolean ignoreProtected, boolean ignoreClearance, boolean ignoreUnloaded) {
        if (!inBounds(x, z)) {
            return false;
        }
        int f = flags[index(x, z)];
        if (!ignoreUnloaded && (f & FLAG_UNLOADED) != 0) {
            return false;
        }
        if (!ignoreProtected && (f & FLAG_PROTECTED) != 0) {
            return false;
        }
        if ((f & FLAG_DECK_IMPASSABLE) != 0) {
            return false;
        }
        if (!ignoreClearance && (f & FLAG_CLEARANCE_BLOCKED) != 0) {
            return false;
        }
        return true;
    }
}

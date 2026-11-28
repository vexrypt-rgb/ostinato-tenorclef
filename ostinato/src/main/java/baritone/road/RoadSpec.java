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

import java.util.List;

/**
 * Specification for a terrain road request (first playable slice: level full-block 2-wide bidirectional no-jump deck).
 */
public record RoadSpec(
        String startLabel,
        int startX,
        int startY,
        int startZ,
        String endLabel,
        int endX,
        int endY,
        int endZ,
        int width,
        int headroom,
        int maxPlacedBlocks,
        int corridorMargin,
        String deckBlockId,
        List<ProtectedZone> protectedZones
) {
    public static final int DEFAULT_WIDTH = 2;
    public static final int DEFAULT_HEADROOM = 2;
    public static final int DEFAULT_MAX_PLACED_BLOCKS = 48;
    public static final int DEFAULT_CORRIDOR_MARGIN = 24;
    public static final int MIN_CORRIDOR_MARGIN = 4;
    public static final int MAX_CORRIDOR_MARGIN = 48;
    public static final String DEFAULT_DECK_BLOCK_ID = "cobblestone";

    public RoadSpec {
        startLabel = (startLabel == null || startLabel.isBlank()) ? (startX + "," + startY + "," + startZ) : startLabel;
        endLabel = (endLabel == null || endLabel.isBlank()) ? (endX + "," + endY + "," + endZ) : endLabel;
        if (width < 1 || width > 4) {
            throw new IllegalArgumentException("Road width must be 1..4, got " + width);
        }
        if (headroom < 2 || headroom > 6) {
            throw new IllegalArgumentException("Road headroom must be 2..6, got " + headroom);
        }
        if (maxPlacedBlocks < 0) {
            throw new IllegalArgumentException("maxPlacedBlocks must be >= 0, got " + maxPlacedBlocks);
        }
        corridorMargin = Math.max(MIN_CORRIDOR_MARGIN, Math.min(MAX_CORRIDOR_MARGIN, corridorMargin));
        deckBlockId = (deckBlockId == null || deckBlockId.isBlank()) ? DEFAULT_DECK_BLOCK_ID : normalizeBlockId(deckBlockId);
        protectedZones = protectedZones == null ? List.of() : List.copyOf(protectedZones);
    }

    /** Convenience factory with slice-1 defaults (width=2, headroom=2, budget=48, margin=24, cobblestone). */
    public static RoadSpec levelTwoWide(
            String startLabel, int startX, int startY, int startZ,
            String endLabel, int endX, int endY, int endZ,
            int maxPlacedBlocks,
            List<ProtectedZone> protectedZones) {
        return new RoadSpec(
                startLabel, startX, startY, startZ,
                endLabel, endX, endY, endZ,
                DEFAULT_WIDTH, DEFAULT_HEADROOM, maxPlacedBlocks, DEFAULT_CORRIDOR_MARGIN,
                DEFAULT_DECK_BLOCK_ID, protectedZones);
    }

    /** Deck block Y beneath the start feet position. */
    public int deckY() {
        return startY - 1;
    }

    /** True when start and end feet share the same elevation (level full-block deck permitted). */
    public boolean isLevelEndpoints() {
        return startY == endY;
    }

    public int minCorridorX() {
        return Math.min(startX, endX) - corridorMargin;
    }

    public int maxCorridorX() {
        return Math.max(startX, endX) + corridorMargin;
    }

    public int minCorridorZ() {
        return Math.min(startZ, endZ) - corridorMargin;
    }

    public int maxCorridorZ() {
        return Math.max(startZ, endZ) + corridorMargin;
    }

    /** True if {@code (x, y, z)} lies inside any protected zone. */
    public boolean isProtected(int x, int y, int z) {
        for (int i = 0; i < protectedZones.size(); i++) {
            if (protectedZones.get(i).contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** True if column {@code (x, z)} overlaps any protected zone across {@code [deckY - 1, deckY + headroom]}. */
    public boolean isProtectedColumn(int x, int z) {
        int yLo = deckY() - 1;
        int yHi = deckY() + headroom;
        for (int i = 0; i < protectedZones.size(); i++) {
            if (protectedZones.get(i).overlapsColumn(x, z, yLo, yHi)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeBlockId(String raw) {
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }
}

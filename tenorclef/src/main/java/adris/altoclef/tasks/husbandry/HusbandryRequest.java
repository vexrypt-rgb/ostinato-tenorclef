package adris.altoclef.tasks.husbandry;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable request describing an animal husbandry pen-growth goal.
 * <p>
 * First playable slice supports one species ({@code cow}) and one designated fenced pen,
 * counting both contained live adults and calves toward {@link #targetCount()}.
 */
public final class HusbandryRequest {

    public static final String SPECIES_COW = "cow";

    private final String species;
    private final int targetCount;
    private final boolean hasExplicitSeedPos;
    private final int seedX;
    private final int seedY;
    private final int seedZ;
    private final boolean hasExplicitBox;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    private HusbandryRequest(String species,
                             int targetCount,
                             boolean hasExplicitSeedPos,
                             int seedX,
                             int seedY,
                             int seedZ,
                             boolean hasExplicitBox,
                             int minX,
                             int minY,
                             int minZ,
                             int maxX,
                             int maxY,
                             int maxZ) {
        if (targetCount < 1) {
            throw new IllegalArgumentException("targetCount must be >= 1, got " + targetCount);
        }
        String norm = normalizeSpecies(species);
        if (!SPECIES_COW.equals(norm)) {
            throw new IllegalArgumentException(
                    "Unsupported species '" + species + "' (only cows are supported in this husbandry slice)");
        }
        this.species = norm;
        this.targetCount = targetCount;
        this.hasExplicitSeedPos = hasExplicitSeedPos;
        this.seedX = seedX;
        this.seedY = seedY;
        this.seedZ = seedZ;
        this.hasExplicitBox = hasExplicitBox;
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
    }

    public static String normalizeSpecies(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("cow") || s.equals("cows")) {
            return SPECIES_COW;
        }
        return s;
    }

    public static HusbandryRequest growCows(int targetCount) {
        return new HusbandryRequest(SPECIES_COW, targetCount,
                false, 0, 0, 0,
                false, 0, 0, 0, 0, 0, 0);
    }

    public static HusbandryRequest growCowsAt(int targetCount, int seedX, int seedY, int seedZ) {
        return new HusbandryRequest(SPECIES_COW, targetCount,
                true, seedX, seedY, seedZ,
                false, 0, 0, 0, 0, 0, 0);
    }

    public static HusbandryRequest growCowsInBox(int targetCount,
                                                 int minX, int minY, int minZ,
                                                 int maxX, int maxY, int maxZ) {
        return new HusbandryRequest(SPECIES_COW, targetCount,
                false, 0, 0, 0,
                true, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public String species() {
        return species;
    }

    public int targetCount() {
        return targetCount;
    }

    public boolean hasExplicitSeedPos() {
        return hasExplicitSeedPos;
    }

    public int seedX() {
        return seedX;
    }

    public int seedY() {
        return seedY;
    }

    public int seedZ() {
        return seedZ;
    }

    public boolean hasExplicitBox() {
        return hasExplicitBox;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    public String describe() {
        String base = "grow cows to " + targetCount;
        if (hasExplicitBox) {
            return base + " in box[" + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ + "]";
        }
        if (hasExplicitSeedPos) {
            return base + " at (" + seedX + "," + seedY + "," + seedZ + ")";
        }
        return base;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HusbandryRequest that)) return false;
        return targetCount == that.targetCount
                && hasExplicitSeedPos == that.hasExplicitSeedPos
                && seedX == that.seedX && seedY == that.seedY && seedZ == that.seedZ
                && hasExplicitBox == that.hasExplicitBox
                && minX == that.minX && minY == that.minY && minZ == that.minZ
                && maxX == that.maxX && maxY == that.maxY && maxZ == that.maxZ
                && Objects.equals(species, that.species);
    }

    @Override
    public int hashCode() {
        return Objects.hash(species, targetCount, hasExplicitSeedPos, seedX, seedY, seedZ,
                hasExplicitBox, minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public String toString() {
        return describe();
    }
}

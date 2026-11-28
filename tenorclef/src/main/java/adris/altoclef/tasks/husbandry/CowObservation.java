package adris.altoclef.tasks.husbandry;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure-Java client-visible observation of a live cow and a pen tick snapshot.
 * Uses only ordinary player-visible state (entity id/uuid, position, baby flag, alive flag,
 * loaded chunks, and player's own wheat count).
 */
public record CowObservation(int entityId,
                             UUID uuid,
                             double x,
                             double y,
                             double z,
                             boolean baby,
                             boolean alive) {

    public CowObservation {
        Objects.requireNonNull(uuid, "uuid");
    }

    public boolean isAdult() {
        return alive && !baby;
    }

    public boolean isCalf() {
        return alive && baby;
    }

    public double squaredDistanceTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Full client-visible observation of the designated pen for one tick.
     */
    public record PenSnapshot(long gameTick,
                              boolean allChunksLoaded,
                              boolean playerWithinObservationRange,
                              int playerWheatCount,
                              List<CowObservation> containedCows) {

        public PenSnapshot {
            containedCows = containedCows == null ? Collections.emptyList() : List.copyOf(containedCows);
        }

        public int totalCount() {
            int n = 0;
            for (CowObservation c : containedCows) {
                if (c.alive()) n++;
            }
            return n;
        }

        public int adultCount() {
            int n = 0;
            for (CowObservation c : containedCows) {
                if (c.isAdult()) n++;
            }
            return n;
        }

        public int calfCount() {
            int n = 0;
            for (CowObservation c : containedCows) {
                if (c.isCalf()) n++;
            }
            return n;
        }

        public CowObservation findById(int entityId) {
            for (CowObservation c : containedCows) {
                if (c.alive() && c.entityId() == entityId) {
                    return c;
                }
            }
            return null;
        }
    }
}

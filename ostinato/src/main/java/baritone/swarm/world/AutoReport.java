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

package baritone.swarm.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ostinato: Phase 2c part 2. Turns structures and entities the bot already knows into observation records.
 * Structures share the terrain store ({@link WorldKnowledge#TERRAIN_CAP}, 10 min). Entities use the entity store
 * ({@link WorldKnowledge#ENTITY_CAP}, 5 s) and must be re-sent inside that window on the local spool.
 * Chests are already in the block scan. This does not allocate a second store.
 */
public final class AutoReport {

    /** Nearest structures kept in one pass. Well under the terrain cap, so veins are not pushed out. */
    public static final int STRUCTURE_MAX = 32;
    /** Nearest living entities kept in one pass. Well under the entity cap. */
    public static final int ENTITY_MAX = 32;
    /** Blocks from the bot. Farther entities are left to expire. */
    public static final int ENTITY_RADIUS = 32;
    /**
     * Unchanged entity records are told again after this. One local message then carries the whole nearby set, so
     * the steady rate is 15 a minute, under the coordinator's 25 line budget. The entity store drops a record after
     * 5 s, and this lands inside that window. Chat's 15 s gap cannot, so the entity feed is local only.
     */
    public static final long ENTITY_RESEND_MS = 4_000L;

    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[a-z0-9_]+");

    public static final class Structure {
        public final String id;
        public final String variant;
        public final int x, y, z;
        public final int confidence;

        public Structure(String id, String variant, int x, int y, int z, int confidence) {
            this.id = id;
            this.variant = variant;
            this.x = x;
            this.y = y;
            this.z = z;
            this.confidence = confidence;
        }
    }

    public static final class Mob {
        public final String type;
        public final int id;
        public final int x, y, z;

        public Mob(String type, int id, int x, int y, int z) {
            this.type = type;
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private AutoReport() {}

    /** Nearest {@link #STRUCTURE_MAX} structures, as {@code s:<dim>:x,y,z} with detail {@code id} or {@code id.variant}. */
    public static List<ObservationReporter.Seen> structures(char dim, int px, int py, int pz, List<Structure> found) {
        List<ObservationReporter.Seen> out = new ArrayList<>();
        if (found == null || (dim != 'o' && dim != 'n' && dim != 'e')) {
            return out;
        }
        List<Structure> sorted = new ArrayList<>();
        for (Structure s : found) {
            if (s != null && token(s.id)) {
                sorted.add(s);
            }
        }
        sorted.sort(Comparator.comparingLong((Structure s) -> dist2(px, py, pz, s.x, s.y, s.z))
                .thenComparing(s -> key(dim, s)));
        for (Structure s : sorted) {
            if (out.size() >= STRUCTURE_MAX) {
                break;
            }
            String detail = token(s.variant) ? s.id + "." + s.variant : s.id;
            if (!ObservationMessage.validDetail(detail)) {
                detail = s.id;
            }
            String key = "s:" + dim + ":" + s.x + "," + s.y + "," + s.z;
            if (!ObservationMessage.validKey(key)) {
                continue;
            }
            int conf = Math.max(0, Math.min(100, s.confidence));
            out.add(new ObservationReporter.Seen(key, detail, conf));
        }
        return out;
    }

    /** Nearest {@link #ENTITY_MAX} mobs inside {@link #ENTITY_RADIUS}, as {@code e:<dim>:<type>-<id>} / {@code type@x,y,z}. */
    public static List<ObservationReporter.Seen> entities(char dim, int px, int py, int pz, List<Mob> found) {
        List<ObservationReporter.Seen> out = new ArrayList<>();
        if (found == null || (dim != 'o' && dim != 'n' && dim != 'e')) {
            return out;
        }
        long r2 = (long) ENTITY_RADIUS * ENTITY_RADIUS;
        List<Mob> sorted = new ArrayList<>();
        for (Mob m : found) {
            if (m == null || m.id < 0 || !token(m.type)) {
                continue;
            }
            if (dist2(px, py, pz, m.x, m.y, m.z) > r2) {
                continue;
            }
            sorted.add(m);
        }
        sorted.sort(Comparator.comparingLong((Mob m) -> dist2(px, py, pz, m.x, m.y, m.z))
                .thenComparing(m -> m.type + "-" + m.id));
        for (Mob m : sorted) {
            if (out.size() >= ENTITY_MAX) {
                break;
            }
            String key = "e:" + dim + ":" + m.type + "-" + m.id;
            String detail = m.type + "@" + m.x + "," + m.y + "," + m.z;
            if (!ObservationMessage.validKey(key) || !ObservationMessage.validDetail(detail)) {
                continue;
            }
            out.add(new ObservationReporter.Seen(key, detail, ObservationReporter.SCAN_CONFIDENCE));
        }
        return out;
    }

    private static boolean token(String s) {
        return s != null && TOKEN.matcher(s).matches();
    }

    private static String key(char dim, Structure s) {
        return "s:" + dim + ":" + s.x + "," + s.y + "," + s.z;
    }

    private static long dist2(int ax, int ay, int az, int bx, int by, int bz) {
        long dx = (long) ax - bx;
        long dy = (long) ay - by;
        long dz = (long) az - bz;
        return dx * dx + dy * dy + dz * dz;
    }
}

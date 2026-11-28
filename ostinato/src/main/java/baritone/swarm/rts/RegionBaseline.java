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

package baritone.swarm.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Ostinato: a player-approved, persisted baseline of one bounded region of static masonry ({@code #baseline}).
 * <p>
 * Unlike a {@link Blueprint} (a design: absent cell = "don't care", air cannot be expected), a baseline records what
 * the player approved: the managed cells with their structural block states, <em>accepted edits</em> (a cell whose
 * approved state is now something else, including air for a doorway), and explicit exclusions (unmanaged cells).
 * It is tagged with the world and dimension it was saved in and refuses to be applied anywhere else.
 * <p>
 * {@link #diff} classifies each managed cell using only client-visible observations:
 * <ul>
 *   <li>{@code UNKNOWN}: the chunk is not loaded. Never treated as missing; stays unknown until revisited.</li>
 *   <li>{@code MATCH}: the cell is in its approved state.</li>
 *   <li>{@code RESTORE}: the cell differs and an explicit repair selection or a standing zone authorizes restoring it.</li>
 *   <li>{@code REVIEW}: the cell differs and nothing authorizes it (client observation cannot tell a player edit
 *       from an explosion), or it is a container / accepted opening, which is never overwritten or refilled.</li>
 * </ul>
 * Pure Java: no Minecraft classes, so it is unit-testable and the command center can read it.
 */
public final class RegionBaseline {

    public static final int MAX_CELLS = Blueprint.MAX_CELLS;
    public static final int MAX_EDGE = RoleOrder.MAX_REGION_EDGE;
    public static final int MAX_ZONES = 32;
    private static final String HEADER = "rb1";

    public enum Origin {
        /** Captured from the world when the baseline was saved. */
        SNAPSHOT,
        /** The player accepted the current in-world state as the new approved state. */
        ACCEPTED
    }

    public enum Verdict {
        MATCH, UNKNOWN, RESTORE, REVIEW
    }

    /** One managed cell: {@code block} is {@code "air"} for an accepted opening. */
    public record Entry(int x, int y, int z, String block, Map<String, String> props, Origin origin) {
        public Entry {
            if (origin == null) {
                throw new IllegalArgumentException("origin is required");
            }
            if (block == null || block.isBlank()) {
                throw new IllegalArgumentException("block is required");
            }
            String id = BlockStateSpec.baseBlockId(block);
            if (isAirId(id)) {
                block = "air";
                props = Map.of();
            } else {
                BlockStateSpec.Parsed p = BlockStateSpec.of(id, props);
                block = p.block();
                props = p.properties();
            }
        }

        public boolean isAir() {
            return "air".equals(block);
        }

        public String spec() {
            return isAir() ? "air" : BlockStateSpec.format(block, props);
        }
    }

    public record Finding(int x, int y, int z, Verdict verdict, String expected, String actual, String reason) {
    }

    public record Exclusion(int x, int y, int z, String reason) {
    }

    /** What the player explicitly authorized for one repair: individual cells and/or boxes (x1,y1,z1,x2,y2,z2). */
    public record Selection(Set<Long> cells, List<int[]> boxes) {
        public static final Selection NONE = new Selection(Set.of(), List.of());

        public Selection {
            cells = cells == null ? Set.of() : Set.copyOf(cells);
            List<int[]> copy = new ArrayList<>();
            if (boxes != null) {
                for (int[] b : boxes) {
                    copy.add(b.clone());
                }
            }
            boxes = Collections.unmodifiableList(copy);
        }

        public boolean isEmpty() {
            return cells.isEmpty() && boxes.isEmpty();
        }

        boolean covers(int x, int y, int z) {
            return cells.contains(Blueprint.cellKey(x, y, z)) || inAny(boxes, x, y, z);
        }
    }

    public static final class Report {
        private final List<Finding> findings;
        private final int matched;
        private final int excluded;

        Report(List<Finding> findings, int matched, int excluded) {
            this.findings = List.copyOf(findings);
            this.matched = matched;
            this.excluded = excluded;
        }

        /** Every non-matching managed cell, in baseline order. */
        public List<Finding> findings() {
            return findings;
        }

        public List<Finding> of(Verdict v) {
            List<Finding> out = new ArrayList<>();
            for (Finding f : findings) {
                if (f.verdict() == v) {
                    out.add(f);
                }
            }
            return List.copyOf(out);
        }

        public int matched() {
            return matched;
        }

        public int unknown() {
            return of(Verdict.UNKNOWN).size();
        }

        public int restore() {
            return of(Verdict.RESTORE).size();
        }

        public int review() {
            return of(Verdict.REVIEW).size();
        }

        public int excluded() {
            return excluded;
        }

        public String summary() {
            return matched + " ok, " + restore() + " to restore, " + review() + " need review, "
                    + unknown() + " unknown (not loaded), " + excluded + " unmanaged";
        }
    }

    private final String name;
    private final String world;
    private final String dimension;
    private final int[] box;
    private long savedAt;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private final Map<Long, Exclusion> exclusions = new LinkedHashMap<>();
    private final List<int[]> zones = new ArrayList<>();

    public RegionBaseline(String name, String world, String dimension, int[] box, long savedAt) {
        this.name = safeName(name);
        this.world = token(world, "world");
        this.dimension = token(dimension, "dimension");
        if (box == null || box.length != 6) {
            throw new IllegalArgumentException("region wants x1,y1,z1:x2,y2,z2");
        }
        this.box = normalize(box);
        this.savedAt = savedAt;
    }

    // ---- identity ------------------------------------------------------------------------------------------------

    public String name() {
        return name;
    }

    public String world() {
        return world;
    }

    public String dimension() {
        return dimension;
    }

    public int[] box() {
        return box.clone();
    }

    public long savedAt() {
        return savedAt;
    }

    public boolean sameWorld(String otherWorld, String otherDimension) {
        return world.equals(otherWorld) && dimension.equals(otherDimension);
    }

    public int size() {
        return entries.size();
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public Entry at(int x, int y, int z) {
        return entries.get(Blueprint.cellKey(x, y, z));
    }

    public List<Exclusion> exclusions() {
        return List.copyOf(exclusions.values());
    }

    public boolean isExcluded(int x, int y, int z) {
        return exclusions.containsKey(Blueprint.cellKey(x, y, z));
    }

    public List<int[]> zones() {
        List<int[]> out = new ArrayList<>();
        for (int[] z : zones) {
            out.add(z.clone());
        }
        return out;
    }

    public boolean contains(int x, int y, int z) {
        return x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
    }

    // ---- editing -------------------------------------------------------------------------------------------------

    /** Records a snapshot cell. Excluded cells are skipped (returns false). Throws past {@link #MAX_CELLS}. */
    public boolean capture(Entry e) {
        requireInside(e.x(), e.y(), e.z());
        long k = Blueprint.cellKey(e.x(), e.y(), e.z());
        if (exclusions.containsKey(k)) {
            return false;
        }
        putEntry(k, e);
        return true;
    }

    /**
     * The player accepts the current in-world state of a cell as the approved one (a doorway: the wall block is now
     * air). Clears any exclusion, so accepting a cell also brings it back under management.
     */
    public boolean accept(int x, int y, int z, String block, Map<String, String> props) {
        requireInside(x, y, z);
        long k = Blueprint.cellKey(x, y, z);
        exclusions.remove(k);
        Entry n = new Entry(x, y, z, block, props, Origin.ACCEPTED);
        Entry old = entries.get(k);
        if (old != null && old.equals(n)) {
            return false;
        }
        putEntry(k, n);
        return true;
    }

    /** Marks a cell unmanaged: it is dropped from the baseline and never compared or restored. */
    public boolean exclude(int x, int y, int z, String reason) {
        requireInside(x, y, z);
        long k = Blueprint.cellKey(x, y, z);
        boolean had = entries.remove(k) != null;
        Exclusion n = new Exclusion(x, y, z, reason == null || reason.isBlank() ? "player" : cleanReason(reason));
        Exclusion old = exclusions.put(k, n);
        return had || old == null || !old.equals(n);
    }

    /** Standing policy: discrepancies inside {@code zone} are authorized for restore without an explicit selection. */
    public boolean addZone(int[] zone) {
        int[] z = normalize(zone);
        if (z[0] < box[0] || z[1] < box[1] || z[2] < box[2] || z[3] > box[3] || z[4] > box[4] || z[5] > box[5]) {
            throw new IllegalArgumentException("zone must lie inside the baseline region " + RoleOrder.boxText(box));
        }
        for (int[] have : zones) {
            if (java.util.Arrays.equals(have, z)) {
                return false;
            }
        }
        if (zones.size() >= MAX_ZONES) {
            throw new IllegalArgumentException("at most " + MAX_ZONES + " standing zones");
        }
        zones.add(z);
        return true;
    }

    public void clearZones() {
        zones.clear();
    }

    private void putEntry(long k, Entry e) {
        if (!entries.containsKey(k) && entries.size() >= MAX_CELLS) {
            throw new IllegalArgumentException("baseline has more than " + MAX_CELLS + " managed cells");
        }
        entries.put(k, e);
    }

    private void requireInside(int x, int y, int z) {
        if (!contains(x, y, z)) {
            throw new IllegalArgumentException("cell " + x + "," + y + "," + z + " is outside the baseline region "
                    + RoleOrder.boxText(box));
        }
    }

    // ---- diff ----------------------------------------------------------------------------------------------------

    /**
     * Compares every managed cell with the client-visible world. Never touches anything outside the saved entries, so
     * the work is bounded by the baseline size. {@code protectedBlock} says whether a block id carries contents that
     * must never be overwritten (containers and other block entities).
     */
    public Report diff(BlueprintRepairSession.WorldReader world, Predicate<String> protectedBlock, Selection selection) {
        Selection sel = selection == null ? Selection.NONE : selection;
        Predicate<String> guard = protectedBlock == null ? id -> false : protectedBlock;
        List<Finding> findings = new ArrayList<>();
        int matched = 0;
        for (Entry e : entries.values()) {
            BlueprintRepairSession.CellObservation obs = world == null ? null : world.read(e.x(), e.y(), e.z());
            if (obs == null || !obs.loaded()) {
                findings.add(new Finding(e.x(), e.y(), e.z(), Verdict.UNKNOWN, e.spec(), "-", "chunk not loaded"));
                continue;
            }
            if (e.isAir()) {
                if (obs.isAir()) {
                    matched++;
                } else {
                    findings.add(new Finding(e.x(), e.y(), e.z(), Verdict.REVIEW, "air", obs.block(),
                            "accepted opening is now filled; never restored automatically"));
                }
                continue;
            }
            if (BlockStateSpec.matches(e.block(), e.props(), obs.block(), obs.properties())) {
                matched++;
                continue;
            }
            String actual = obs.isAir() ? "air" : obs.block();
            if (guard.test(e.block()) || (!obs.isAir() && guard.test(obs.block()))) {
                findings.add(new Finding(e.x(), e.y(), e.z(), Verdict.REVIEW, e.spec(), actual,
                        "container or block entity; contents are never overwritten or cleared"));
                continue;
            }
            String what = obs.isAir() ? "missing block"
                    : e.block().equals(obs.block()) ? "wrong state" : "found " + obs.block();
            boolean authorized = sel.covers(e.x(), e.y(), e.z()) || inAny(zones, e.x(), e.y(), e.z());
            if (authorized) {
                findings.add(new Finding(e.x(), e.y(), e.z(), Verdict.RESTORE, e.spec(), actual, what));
            } else {
                findings.add(new Finding(e.x(), e.y(), e.z(), Verdict.REVIEW, e.spec(), actual,
                        what + "; not authorized, review it"));
            }
        }
        return new Report(findings, matched, exclusions.size());
    }

    /** The authorized {@code RESTORE} cells of {@code report} as a repairable blueprint named {@code blueprintName}. */
    public Blueprint restoreBlueprint(Report report, String blueprintName) {
        Blueprint bp = new Blueprint(blueprintName);
        for (Finding f : report.of(Verdict.RESTORE)) {
            Entry e = entries.get(Blueprint.cellKey(f.x(), f.y(), f.z()));
            if (e != null && !e.isAir()) {
                bp.paint(e.x(), e.y(), e.z(), e.block(), e.props());
            }
        }
        return bp;
    }

    // ---- structural state ----------------------------------------------------------------------------------------

    /**
     * The properties of an observed block that a baseline tracks: log axis, slab bottom/top, straight-stair facing and
     * half. Everything else (waterlogged, stair shape, powered, ...) is transient or neighbour-derived and is neither
     * compared nor forced.
     */
    public static Map<String, String> structuralProperties(String block, Map<String, String> observed) {
        if (observed == null || observed.isEmpty()) {
            return Map.of();
        }
        String id = BlockStateSpec.baseBlockId(block);
        Map<String, String> out = new TreeMap<>();
        if (BlockStateSpec.isPillarBlock(id)) {
            keep(out, observed, "axis", BlockStateSpec.VALID_AXES);
        } else if (BlockStateSpec.isSlabBlock(id)) {
            keep(out, observed, "type", BlockStateSpec.VALID_SLAB_HALVES);
        } else if (BlockStateSpec.isStairBlock(id)) {
            keep(out, observed, "facing", BlockStateSpec.VALID_FACINGS);
            keep(out, observed, "half", BlockStateSpec.VALID_STAIR_HALVES);
        }
        return out;
    }

    private static void keep(Map<String, String> out, Map<String, String> observed, String key, Set<String> valid) {
        String v = observed.get(key);
        if (v != null && valid.contains(v)) {
            out.put(key, v);
        }
    }

    // ---- persistence ---------------------------------------------------------------------------------------------

    /** Line-based text: header, identity, region, standing zones, exclusions, then one line per managed cell. */
    public String write() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        sb.append("name ").append(name).append('\n');
        sb.append("world ").append(world).append('\n');
        sb.append("dim ").append(dimension).append('\n');
        sb.append("box ").append(box[0]).append(' ').append(box[1]).append(' ').append(box[2]).append(' ')
                .append(box[3]).append(' ').append(box[4]).append(' ').append(box[5]).append('\n');
        sb.append("saved ").append(savedAt).append('\n');
        for (int[] z : zones) {
            sb.append("zone ").append(z[0]).append(' ').append(z[1]).append(' ').append(z[2]).append(' ')
                    .append(z[3]).append(' ').append(z[4]).append(' ').append(z[5]).append('\n');
        }
        for (Exclusion x : exclusions.values()) {
            sb.append("x ").append(x.x()).append(' ').append(x.y()).append(' ').append(x.z()).append(' ')
                    .append(x.reason()).append('\n');
        }
        for (Entry e : entries.values()) {
            sb.append("c ").append(e.x()).append(' ').append(e.y()).append(' ').append(e.z()).append(' ')
                    .append(e.origin() == Origin.ACCEPTED ? 'a' : 's').append(' ').append(e.spec()).append('\n');
        }
        return sb.toString();
    }

    /** Reads {@link #write} output. Throws {@link IllegalArgumentException} on anything else. */
    public static RegionBaseline read(String text) {
        if (text == null) {
            throw new IllegalArgumentException("empty baseline");
        }
        String[] lines = text.split("\\R");
        if (lines.length == 0 || !HEADER.equals(lines[0].trim())) {
            throw new IllegalArgumentException("not a region baseline (expected " + HEADER + ")");
        }
        String name = null, world = null, dim = null;
        int[] box = null;
        long saved = 0;
        List<int[]> zoneList = new ArrayList<>();
        List<Exclusion> xs = new ArrayList<>();
        List<Entry> es = new ArrayList<>();
        try {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String key = line.contains(" ") ? line.substring(0, line.indexOf(' ')) : line;
                // an exclusion keeps its free-text reason whole; a cell keeps its block spec whole
                String[] w = line.split(" ", "x".equals(key) ? 5 : "c".equals(key) ? 6 : -1);
                switch (w[0]) {
                    case "name" -> name = w[1];
                    case "world" -> world = w[1];
                    case "dim" -> dim = w[1];
                    case "box" -> box = ints(w, 1, 6, line);
                    case "saved" -> saved = Long.parseLong(w[1]);
                    case "zone" -> zoneList.add(ints(w, 1, 6, line));
                    case "x" -> xs.add(new Exclusion(Integer.parseInt(w[1]), Integer.parseInt(w[2]),
                            Integer.parseInt(w[3]), w.length > 4 ? w[4] : "player"));
                    case "c" -> {
                        if (w.length < 6) {
                            throw new IllegalArgumentException("bad cell line: " + line);
                        }
                        BlockStateSpec.Parsed p = "air".equals(w[5]) ? null : BlockStateSpec.parse(w[5]);
                        es.add(new Entry(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]),
                                p == null ? "air" : p.block(), p == null ? Map.of() : p.properties(),
                                "a".equals(w[4]) ? Origin.ACCEPTED : Origin.SNAPSHOT));
                    }
                    default -> throw new IllegalArgumentException("unknown baseline line: " + line);
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("corrupt baseline: " + e.getMessage());
        }
        if (name == null || world == null || dim == null || box == null) {
            throw new IllegalArgumentException("corrupt baseline: missing name, world, dim or box");
        }
        RegionBaseline b = new RegionBaseline(name, world, dim, box, saved);
        for (int[] z : zoneList) {
            b.addZone(z);
        }
        for (Exclusion x : xs) {
            b.exclusions.put(Blueprint.cellKey(x.x(), x.y(), x.z()), x);
        }
        for (Entry e : es) {
            b.requireInside(e.x(), e.y(), e.z());
            b.putEntry(Blueprint.cellKey(e.x(), e.y(), e.z()), e);
        }
        return b;
    }

    private static int[] ints(String[] w, int from, int count, String line) {
        if (w.length < from + count) {
            throw new IllegalArgumentException("bad line: " + line);
        }
        int[] r = new int[count];
        for (int i = 0; i < count; i++) {
            r[i] = Integer.parseInt(w[from + i]);
        }
        return r;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    public static String safeName(String raw) {
        String n = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.length() > 24) {
            throw new IllegalArgumentException("baseline name must be 1..24 letters, digits, - or _");
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                throw new IllegalArgumentException("baseline name must be 1..24 letters, digits, - or _");
            }
        }
        return n;
    }

    public static String fileName(String name) {
        return safeName(name) + ".baseline";
    }

    /** The repair blueprint's name for baseline {@code name}: its own status file, never a design's. */
    public static String repairBlueprintName(String name) {
        return safeName(name) + "-upkeep";
    }

    private static int[] normalize(int[] b) {
        int[] r = {Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
                Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])};
        if (r[3] - r[0] >= MAX_EDGE || r[4] - r[1] >= MAX_EDGE || r[5] - r[2] >= MAX_EDGE) {
            throw new IllegalArgumentException("region edge over " + MAX_EDGE);
        }
        return r;
    }

    private static boolean inAny(List<int[]> boxes, int x, int y, int z) {
        for (int[] b : boxes) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAirId(String id) {
        return "air".equals(id) || "cave_air".equals(id) || "void_air".equals(id);
    }

    private static String token(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        String t = raw.trim();
        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i)) || Character.isISOControl(t.charAt(i))) {
                throw new IllegalArgumentException(what + " must not contain whitespace");
            }
        }
        return t;
    }

    private static String cleanReason(String r) {
        return r.replace('\r', ' ').replace('\n', ' ').trim();
    }
}

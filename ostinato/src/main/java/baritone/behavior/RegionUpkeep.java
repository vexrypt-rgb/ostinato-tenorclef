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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.host.CenterFeedService;
import baritone.swarm.rts.Blueprint;
import baritone.swarm.rts.BlueprintRepairSession;
import baritone.swarm.rts.RegionBaseline;
import baritone.swarm.rts.RegionBaseline.Entry;
import baritone.swarm.rts.RegionBaseline.Origin;
import baritone.swarm.rts.RegionBaseline.Report;
import baritone.swarm.rts.RegionBaseline.Selection;
import baritone.swarm.rts.RegionBaseline.Verdict;
import baritone.swarm.rts.RoleOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Ostinato: the player's side of region upkeep ({@code #baseline}). Saves, edits, checks and repairs a
 * {@link RegionBaseline} using only what an ordinary client can see: loaded chunks and block states.
 * <p>
 * Nothing here ticks or scans in the background. Every call reads exactly the saved region (capped at
 * {@value RegionBaseline#MAX_EDGE} blocks an edge and {@value RegionBaseline#MAX_CELLS} managed cells), and a repair
 * is handed to {@link BlueprintBehavior}'s scoped, settle-verified, retry-bounded, cancellable repair session.
 */
public final class RegionUpkeep {

    private final Baritone baritone;
    private final IPlayerContext ctx;

    public RegionUpkeep(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    /** What the player pointed at: individual cells and/or boxes (x1,y1,z1,x2,y2,z2), all inside the baseline. */
    public record Picks(List<int[]> cells, List<int[]> boxes) {
        public Picks {
            cells = cells == null ? List.of() : List.copyOf(cells);
            boxes = boxes == null ? List.of() : List.copyOf(boxes);
        }

        public boolean isEmpty() {
            return cells.isEmpty() && boxes.isEmpty();
        }

        Selection selection() {
            java.util.Set<Long> keys = new java.util.LinkedHashSet<>();
            for (int[] c : cells) {
                keys.add(Blueprint.cellKey(c[0], c[1], c[2]));
            }
            return new Selection(keys, boxes);
        }
    }

    public static Path dir() {
        Path game = Minecraft.getInstance().gameDirectory.toPath();
        return CenterFeedService.dir(Baritone.settings(), game).resolve("baselines");
    }

    // ---- identity ------------------------------------------------------------------------------------------------

    /** The save folder for singleplayer, the server address for multiplayer. No whitespace, stable across restarts. */
    public String worldId() {
        Minecraft mc = ctx.minecraft();
        if (mc.hasSingleplayerServer()) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path leaf = root.getFileName();
            return "sp:" + (leaf == null ? "world" : leaf.toString().replaceAll("\\s", "_"));
        }
        var server = mc.getCurrentServer();
        if (server == null) {
            throw new IllegalArgumentException("not connected to a world");
        }
        return (server.isRealm() ? "realm:" : "mp:") + server.ip.trim().toLowerCase(Locale.ROOT).replaceAll("\\s", "_");
    }

    public String dimensionId() {
        ClientLevel w = ctx.minecraft().level;
        if (w == null) {
            throw new IllegalArgumentException("world is not loaded");
        }
        return w.dimension().identifier().toString();
    }

    private void requireWorld() {
        if (ctx.world() == null || ctx.player() == null) {
            throw new IllegalArgumentException("world is not loaded");
        }
    }

    // ---- files ---------------------------------------------------------------------------------------------------

    public RegionBaseline load(String name) {
        Path file = dir().resolve(RegionBaseline.fileName(name));
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("no baseline named " + RegionBaseline.safeName(name)
                    + "; baseline save <name> [region] first");
        }
        try {
            return RegionBaseline.read(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalArgumentException("could not read baseline: " + e.getMessage());
        }
    }

    /** Loads {@code name} and refuses it unless it belongs to the world and dimension the player is in. */
    private RegionBaseline loadHere(String name) {
        requireWorld();
        RegionBaseline b = load(name);
        if (!b.sameWorld(worldId(), dimensionId())) {
            throw new IllegalArgumentException("baseline " + b.name() + " belongs to " + b.world() + " "
                    + b.dimension() + ", not this world (" + worldId() + " " + dimensionId() + "); nothing was touched");
        }
        return b;
    }

    private void store(RegionBaseline b) {
        try {
            CenterFeed.writeAtomic(dir().resolve(RegionBaseline.fileName(b.name())), b.write());
        } catch (IOException e) {
            throw new IllegalArgumentException("could not save baseline: " + e.getMessage());
        }
    }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        Path d = dir();
        if (!Files.isDirectory(d)) {
            return out;
        }
        try (Stream<Path> s = Files.list(d)) {
            s.filter(p -> p.getFileName().toString().endsWith(".baseline")).sorted().forEach(p -> {
                try {
                    RegionBaseline b = RegionBaseline.read(Files.readString(p));
                    out.add(b.name() + " " + RoleOrder.boxText(b.box()) + " " + b.size() + " cells, " + b.world() + " "
                            + b.dimension());
                } catch (IOException | RuntimeException e) {
                    out.add(p.getFileName() + " (unreadable: " + e.getMessage() + ")");
                }
            });
        } catch (IOException ignored) {
            // empty listing
        }
        return out;
    }

    // ---- observation ---------------------------------------------------------------------------------------------

    private BlueprintRepairSession.WorldReader reader() {
        return (x, y, z) -> BlueprintStateHelper.observe(ctx.world(), new BlockPos(x, y, z));
    }

    /** Contents that must never be overwritten or cleared: anything with a block entity (chests, barrels, furnaces...). */
    static boolean isProtected(String blockId) {
        Block b = BlueprintStateHelper.lookupBlock(blockId);
        return b != null && b.defaultBlockState().hasBlockEntity();
    }

    /** Static masonry the baseline manages: full, light-blocking blocks (and stairs/slabs), no fluids, no containers. */
    private static boolean isStaticMasonry(BlockState s) {
        return s.canOcclude() && s.getFluidState().isEmpty() && !s.hasBlockEntity();
    }

    // ---- commands ------------------------------------------------------------------------------------------------

    /**
     * Snapshots the region from the loaded world. Refuses when any cell is in an unloaded chunk, so a baseline never
     * silently omits part of the wall. Containers are recorded as explicit exclusions, other non-masonry (plants,
     * glass, fluids) is simply not managed. A baseline of the same name is replaced.
     */
    public String save(String name, int[] box) {
        requireWorld();
        RegionBaseline b = new RegionBaseline(name, worldId(), dimensionId(), box, System.currentTimeMillis());
        int[] r = b.box();
        List<String> unloaded = new ArrayList<>();
        int skipped = 0;
        int containers = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = r[0]; x <= r[3]; x++) {
            for (int y = r[1]; y <= r[4]; y++) {
                for (int z = r[2]; z <= r[5]; z++) {
                    pos.set(x, y, z);
                    if (!ctx.world().getChunkSource().hasChunk(x >> 4, z >> 4)) {
                        if (unloaded.size() < 3) {
                            unloaded.add(x + "," + y + "," + z);
                        }
                        skipped = -1;
                        continue;
                    }
                    BlockState s = ctx.world().getBlockState(pos);
                    if (s.isAir()) {
                        continue;
                    }
                    if (s.hasBlockEntity()) {
                        b.exclude(x, y, z, "container or block entity");
                        containers++;
                        continue;
                    }
                    if (!isStaticMasonry(s)) {
                        if (skipped >= 0) {
                            skipped++;
                        }
                        continue;
                    }
                    BlueprintRepairSession.CellObservation o = BlueprintStateHelper.observeState(s);
                    b.capture(new Entry(x, y, z, o.block(), RegionBaseline.structuralProperties(o.block(), o.properties()),
                            Origin.SNAPSHOT));
                }
            }
        }
        if (skipped < 0) {
            throw new IllegalArgumentException("part of " + RoleOrder.boxText(r) + " is not loaded (e.g. "
                    + String.join(" ", unloaded) + "); stand near it so the whole region is loaded, then save again");
        }
        if (b.size() == 0) {
            throw new IllegalArgumentException("no static masonry found in " + RoleOrder.boxText(r));
        }
        store(b);
        return b.name() + ": saved " + b.size() + " cells in " + RoleOrder.boxText(r) + " (" + b.world() + " "
                + b.dimension() + ")" + (containers > 0 ? ", " + containers + " containers left unmanaged" : "")
                + (skipped > 0 ? ", " + skipped + " non-masonry cells ignored" : "");
    }

    /** Accepts the current in-world state of the selected cells as the approved one (a doorway reads as air). */
    public String accept(String name, Picks picks) {
        RegionBaseline b = loadHere(name);
        int accepted = 0, unchanged = 0, unloaded = 0, refused = 0;
        for (int[] c : cellsOf(b, picks)) {
            BlueprintRepairSession.CellObservation o = BlueprintStateHelper.observe(ctx.world(), new BlockPos(c[0], c[1], c[2]));
            if (!o.loaded()) {
                unloaded++;
            } else if (!o.isAir() && isProtected(o.block())) {
                refused++; // a container is never baselined; exclude it instead
            } else if (b.accept(c[0], c[1], c[2], o.isAir() ? "air" : o.block(),
                    o.isAir() ? Map.of() : RegionBaseline.structuralProperties(o.block(), o.properties()))) {
                accepted++;
            } else {
                unchanged++;
            }
        }
        store(b);
        return b.name() + ": accepted " + accepted + " cells as the approved state"
                + (unchanged > 0 ? ", " + unchanged + " already approved" : "")
                + (unloaded > 0 ? ", " + unloaded + " not loaded (skipped)" : "")
                + (refused > 0 ? ", " + refused + " containers refused (use exclude)" : "");
    }

    /** Marks the selected cells unmanaged: never compared, restored or reported. */
    public String exclude(String name, Picks picks, String reason) {
        RegionBaseline b = loadHere(name);
        int n = 0;
        for (int[] c : cellsOf(b, picks)) {
            if (b.exclude(c[0], c[1], c[2], reason)) {
                n++;
            }
        }
        store(b);
        return b.name() + ": " + n + " cells are now unmanaged (" + b.exclusions().size() + " in all)";
    }

    public String policy(String name, int[] zone, boolean clear) {
        RegionBaseline b = loadHere(name);
        if (clear) {
            b.clearZones();
        } else if (zone != null) {
            b.addZone(zone);
        }
        store(b);
        StringBuilder sb = new StringBuilder(b.name()).append(": standing restore zones ");
        if (b.zones().isEmpty()) {
            return sb.append("none (every difference needs an explicit repair selection)").toString();
        }
        for (int[] z : b.zones()) {
            sb.append(RoleOrder.boxText(z)).append(' ');
        }
        return sb.toString().trim();
    }

    /** The diff of the saved region against what is loaded now; authorizes only the baseline's standing zones. */
    public Report check(String name) {
        return loadHere(name).diff(reader(), RegionUpkeep::isProtected, Selection.NONE);
    }

    public String checkText(String name) {
        RegionBaseline b = loadHere(name);
        Report r = b.diff(reader(), RegionUpkeep::isProtected, Selection.NONE);
        StringBuilder sb = new StringBuilder(b.name()).append(": ").append(r.summary());
        append(sb, "review", r.of(Verdict.REVIEW), 8);
        append(sb, "restore (standing policy)", r.of(Verdict.RESTORE), 4);
        if (r.unknown() > 0) {
            sb.append("\n  unknown: ").append(r.unknown()).append(" cells in unloaded chunks stay unknown until revisited");
        }
        return sb.toString();
    }

    /**
     * Restores the differences authorized by {@code picks} or by the baseline's standing zones, then hands exactly those
     * cells to the settle-verified repair session. Everything else (accepted edits, unmanaged cells, unloaded cells,
     * unclassified differences, containers) is left alone and reported.
     */
    public String repair(String name, Picks picks) {
        RegionBaseline b = loadHere(name);
        Report r = b.diff(reader(), RegionUpkeep::isProtected, picks.selection());
        StringBuilder sb = new StringBuilder(b.name()).append(": ").append(r.summary());
        append(sb, "review", r.of(Verdict.REVIEW), 8);
        if (r.unknown() > 0) {
            sb.append("\n  unknown: ").append(r.unknown()).append(" cells in unloaded chunks were left alone");
        }
        if (r.restore() == 0) {
            return sb.append("\n  nothing authorized to restore").toString();
        }
        Blueprint bp = b.restoreBlueprint(r, RegionBaseline.repairBlueprintName(b.name()));
        String shortage = shortage(bp);
        if (shortage != null) {
            // Materials are pre-stocked in this slice; replenishing them is a later one. Say exactly what is missing.
            return sb.append("\n  repair not started, short of materials: ").append(shortage).toString();
        }
        // Everything in the saved region except the authorized cells is off limits to the builder and pathfinder:
        // approved masonry, accepted openings and unclassified differences must survive the repair untouched.
        int[] region = b.box();
        String started = baritone.getBlueprintBehavior().repairCells(bp, (x, y, z) ->
                x >= region[0] && x <= region[3] && y >= region[1] && y <= region[4] && z >= region[2] && z <= region[5]
                        && bp.at(x, y, z) == null, region);
        return sb.append("\n  ").append(started).toString();
    }

    /** Blocks the repair needs that the player does not carry, as {@code stone_bricks 4 short (has 2)}; null when covered. */
    private String shortage(Blueprint bp) {
        if (ctx.player().getAbilities().instabuild) {
            return null;
        }
        Map<String, Integer> need = new java.util.TreeMap<>();
        for (Blueprint.Cell c : bp.cells()) {
            need.merge(c.block(), 1, Integer::sum);
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            Block block = BlueprintStateHelper.lookupBlock(e.getKey());
            int have = 0;
            if (block != null) {
                for (net.minecraft.world.item.ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
                    if (s.getItem() == block.asItem()) {
                        have += s.getCount();
                    }
                }
            }
            if (have < e.getValue()) {
                missing.add(e.getKey() + " " + (e.getValue() - have) + " short (has " + have + ", needs " + e.getValue() + ")");
            }
        }
        return missing.isEmpty() ? null : String.join(", ", missing);
    }

    // ---- selection helpers ---------------------------------------------------------------------------------------

    /** The distinct cells of {@code picks}, clipped to the baseline region; an explicit cell outside it is an error. */
    private static List<int[]> cellsOf(RegionBaseline b, Picks picks) {
        int[] r = b.box();
        List<int[]> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int[] c : picks.cells()) {
            if (!b.contains(c[0], c[1], c[2])) {
                throw new IllegalArgumentException("cell " + c[0] + "," + c[1] + "," + c[2]
                        + " is outside the baseline region " + RoleOrder.boxText(r));
            }
            if (seen.add(Blueprint.cellKey(c[0], c[1], c[2]))) {
                out.add(c);
            }
        }
        for (int[] box : picks.boxes()) {
            for (int x = Math.max(box[0], r[0]); x <= Math.min(box[3], r[3]); x++) {
                for (int y = Math.max(box[1], r[1]); y <= Math.min(box[4], r[4]); y++) {
                    for (int z = Math.max(box[2], r[2]); z <= Math.min(box[5], r[5]); z++) {
                        if (seen.add(Blueprint.cellKey(x, y, z))) {
                            out.add(new int[]{x, y, z});
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void append(StringBuilder sb, String label, List<RegionBaseline.Finding> list, int limit) {
        if (list.isEmpty()) {
            return;
        }
        sb.append("\n  ").append(label).append(':');
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            RegionBaseline.Finding f = list.get(i);
            sb.append("\n    ").append(f.x()).append(',').append(f.y()).append(',').append(f.z()).append(' ')
                    .append(f.expected()).append(" was ").append(f.actual()).append(" (").append(f.reason()).append(')');
        }
        if (list.size() > limit) {
            sb.append("\n    ... and ").append(list.size() - limit).append(" more");
        }
    }
}

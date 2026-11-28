package baritone.swarm.center.app;

import baritone.swarm.center.TileStatus;
import baritone.swarm.center.feed.Alerts;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.center.feed.TerrainFeed;

import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: P2 Danger overlay model for the War room / RTS map (both 2D top-down and 3D isometric).
 * Pure: extracts lava pools, steep fall cliffs (4+ block drop), tracked entity observations, and endangered
 * bots (dead, low health <= 8, or BAD alert status) without any Minecraft imports.
 */
public final class MapDanger {

    /** Minimum vertical drop in blocks to flag a cliff edge as a fall hazard. */
    public static final int FALL_DROP_BLOCKS = 4;
    /** Health threshold (inclusive, out of 20) for a low-health danger halo. */
    public static final int LOW_HEALTH = 8;
    /** Cap per-frame terrain hazard marks so large views stay fast. */
    public static final int MAX_TERRAIN_MARKS = 400;

    public enum Kind { LAVA, FALL, ENTITY, BOT_DANGER }

    public record Hazard(Kind kind, double x, double y, double z, String label, String tip) {}

    private MapDanger() {}

    /** Collect terrain lava + steep fall edges inside world XZ bounds {@code [x0..x1, z0..z1]}. */
    public static List<Hazard> terrainHazards(MapTerrain.Grid grid, int x0, int z0, int x1, int z1) {
        List<Hazard> out = new ArrayList<>();
        if (grid == null || grid.isEmpty()) {
            return out;
        }
        int minX = Math.max(grid.minX, x0);
        int minZ = Math.max(grid.minZ, z0);
        int maxX = Math.min(grid.minX + grid.width - 1, x1);
        int maxZ = Math.min(grid.minZ + grid.depth - 1, z1);
        if (minX > maxX || minZ > maxZ) {
            return out;
        }
        int stride = (maxX - minX) * (maxZ - minZ) > 30_000 ? 2 : 1;
        for (int z = minZ; z <= maxZ; z += stride) {
            for (int x = minX; x <= maxX; x += stride) {
                if (out.size() >= MAX_TERRAIN_MARKS) {
                    return out;
                }
                int h = grid.heightAt(x, z);
                if (h == TerrainFeed.UNKNOWN_Y) {
                    continue;
                }
                byte k = grid.kindAt(x, z);
                if (k == MapTerrain.Grid.KIND_LAVA) {
                    out.add(new Hazard(Kind.LAVA, x + 0.5, h + 1.0, z + 0.5, "lava",
                            "Lava hazard at " + x + ", " + h + ", " + z));
                    continue;
                }
                if (k == MapTerrain.Grid.KIND_WATER) {
                    continue;
                }
                int drop = maxDrop(grid, x, z, h);
                if (drop >= FALL_DROP_BLOCKS) {
                    out.add(new Hazard(Kind.FALL, x + 0.5, h + 1.0, z + 0.5, "-" + drop,
                            "Fall hazard (-" + drop + " blocks) at " + x + ", " + h + ", " + z));
                }
            }
        }
        return out;
    }

    private static int maxDrop(MapTerrain.Grid grid, int x, int z, int h) {
        int best = 0;
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        for (int i = 0; i < 4; i++) {
            int nh = grid.heightAt(x + dx[i], z + dz[i]);
            if (nh != TerrainFeed.UNKNOWN_Y && grid.kindAt(x + dx[i], z + dz[i]) != MapTerrain.Grid.KIND_WATER) {
                int d = h - nh;
                if (d > best) {
                    best = d;
                }
            }
        }
        return best;
    }

    /** Entity observations from the coordinator in {@code dim} plus endangered bots. */
    public static List<Hazard> dynamicHazards(SwarmView view, char dim) {
        List<Hazard> out = new ArrayList<>();
        if (view == null) {
            return out;
        }
        CoordinatorFeed coord = view.coordinator();
        if (coord != null) {
            for (CoordinatorFeed.Observation o : coord.observations()) {
                if (o.dim() != dim || o.box() == null || o.box().length < 6) {
                    continue;
                }
                if (!"entity".equalsIgnoreCase(o.kind()) && !"mob".equalsIgnoreCase(o.kind())
                        && !"player".equalsIgnoreCase(o.kind())) {
                    continue;
                }
                if ("gone".equalsIgnoreCase(o.state())) {
                    continue;
                }
                int[] b = o.box();
                double cx = (b[0] + b[3] + 1) * 0.5;
                double cy = (b[1] + b[4] + 1) * 0.5;
                double cz = (b[2] + b[5] + 1) * 0.5;
                String label = o.detail().isEmpty() ? o.kind() : o.detail();
                String tip = "Nearby " + label + " · seen by " + o.bot() + " · "
                        + (o.ageMs() < 0 ? "age n/a" : (o.ageMs() / 1000) + " s");
                out.add(new Hazard(Kind.ENTITY, cx, cy, cz, label, tip));
            }
        }
        for (SwarmView.BotState b : view.bots()) {
            BotFeed.Pos p = b.feed().pos();
            if (p == null || p.dim() != dim) {
                continue;
            }
            TileStatus status = Alerts.status(b, view.alertsFor(b.name()));
            boolean lowHp = b.feed().health() >= 0 && b.feed().health() <= LOW_HEALTH;
            if (b.feed().dead() || lowHp || status.level() == TileStatus.Level.BAD) {
                String why = b.feed().dead() ? "DEAD"
                        : lowHp ? "LOW HP (" + b.feed().health() + "/" + b.feed().maxHealth() + ")"
                        : status.word();
                out.add(new Hazard(Kind.BOT_DANGER, p.x(), p.y(), p.z(), why,
                        b.name() + ": " + why));
            }
        }
        return out;
    }
}

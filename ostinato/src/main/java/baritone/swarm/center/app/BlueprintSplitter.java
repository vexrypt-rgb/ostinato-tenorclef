package baritone.swarm.center.app;

import baritone.swarm.rts.Blueprint;

import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: P3 Schematic Editor region partitioner supporting {@code STRIPS}, {@code GRID}, and {@code LAYERS}
 * strategies (matching {@code PartitionStrategy} in the core mod). Pure, JDK-only, emits lease-bound
 * {@code blueprint live <name> part=<box>} orders per bot.
 */
public final class BlueprintSplitter {

    public enum Strategy {
        STRIPS("Strips"),
        GRID("Grid"),
        LAYERS("Layers");

        public final String label;

        Strategy(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private BlueprintSplitter() {}

    /** Partitions {@code bp} across {@code bots} using {@code strategy}. */
    public static RtsOrders.Plan split(List<String> bots, char dim, Blueprint bp, Strategy strategy) {
        if (bp == null || bp.size() == 0) {
            return RtsOrders.Plan.refuse("paint some blocks first");
        }
        if (bots == null || bots.isEmpty()) {
            return RtsOrders.Plan.refuse(AreaOrders.SELECT_REASON);
        }
        Strategy strat = strategy == null ? Strategy.STRIPS : strategy;
        int[] b = bp.bounds();
        AreaOrders.Box whole = new AreaOrders.Box(b[0], b[1], b[2], b[3], b[4], b[5]);
        if (bots.size() == 1) {
            String bot = bots.get(0);
            String line = "swarm lease " + bot + " " + RtsOrders.key(dim, whole)
                    + " blueprint live " + bp.name() + " part=" + RtsOrders.boxText(whole);
            return new RtsOrders.Plan(List.of(new RtsOrders.Order(bot, line)), null);
        }
        if (strat == Strategy.STRIPS) {
            return RtsOrders.splitBlueprint(bots, dim, bp);
        }
        List<AreaOrders.Box> boxes = strat == Strategy.GRID ? gridBoxes(whole, bots.size()) : layerBoxes(whole, bots.size());
        if (boxes.size() < bots.size()) {
            return RtsOrders.Plan.refuse("blueprint too small for " + bots.size() + " bots in " + strat.label + " mode");
        }
        List<RtsOrders.Order> orders = new ArrayList<>();
        for (int i = 0; i < bots.size(); i++) {
            AreaOrders.Box sub = boxes.get(i);
            if (sub.spanX() > AreaOrders.MAX_EDGE || sub.spanY() > AreaOrders.MAX_EDGE || sub.spanZ() > AreaOrders.MAX_EDGE) {
                return RtsOrders.Plan.refuse(AreaOrders.EDGE_REASON);
            }
            String bot = bots.get(i);
            orders.add(new RtsOrders.Order(bot, "swarm lease " + bot + " " + RtsOrders.key(dim, sub)
                    + " blueprint live " + bp.name() + " part=" + RtsOrders.boxText(sub)));
        }
        return new RtsOrders.Plan(orders, null);
    }

    static List<AreaOrders.Box> gridBoxes(AreaOrders.Box whole, int count) {
        int cols = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil(count / (double) cols);
        if (whole.spanX() < cols || whole.spanZ() < rows) {
            return List.of();
        }
        int[] xs = cut(whole.x1(), whole.x2(), cols);
        int[] zs = cut(whole.z1(), whole.z2(), rows);
        List<AreaOrders.Box> out = new ArrayList<>();
        int idx = 0;
        for (int r = 0; r < rows && idx < count; r++) {
            for (int c = 0; c < cols && idx < count; c++) {
                int x1 = xs[c];
                int x2 = (r == rows - 1 && idx == count - 1) ? whole.x2() : xs[c + 1] - 1;
                int z1 = zs[r];
                int z2 = zs[r + 1] - 1;
                out.add(new AreaOrders.Box(x1, whole.y1(), z1, x2, whole.y2(), z2));
                idx++;
            }
        }
        return out;
    }

    static List<AreaOrders.Box> layerBoxes(AreaOrders.Box whole, int count) {
        if (whole.spanY() < count) {
            return List.of();
        }
        int[] ys = cut(whole.y1(), whole.y2(), count);
        List<AreaOrders.Box> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new AreaOrders.Box(whole.x1(), ys[i], whole.z1(), whole.x2(), ys[i + 1] - 1, whole.z2()));
        }
        return out;
    }

    private static int[] cut(int lo, int hi, int parts) {
        int span = hi - lo + 1;
        int base = span / parts;
        int rem = span % parts;
        int[] edges = new int[parts + 1];
        int cur = lo;
        for (int i = 0; i < parts; i++) {
            edges[i] = cur;
            cur += base + (i < rem ? 1 : 0);
        }
        edges[parts] = hi + 1;
        return edges;
    }
}

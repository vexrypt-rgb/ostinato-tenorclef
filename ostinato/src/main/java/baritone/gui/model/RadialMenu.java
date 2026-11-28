package baritone.gui.model;

import java.util.List;

/**
 * Ostinato: P3 hold-key radial quick menu model (design doc: In-game menus).
 * Pure (no Minecraft imports, headless-testable): 6 crosshair-aimed slices that resolve to exact registry
 * {@code #} commands for the selected target bot (self or a swarm member).
 */
public final class RadialMenu {

    public record Slice(String id, String label, String glyph) {}

    /** Crosshair target snapshot supplied by the Minecraft client (or a test fixture). */
    public record Crosshair(boolean hasBlock, int x, int y, int z, String blockId, String entityName) {
        public static final Crosshair NONE = new Crosshair(false, 0, 64, 0, "", "");
    }

    public static final double DEADZONE_PX = 18.0;

    /** The 6 slices matching the design doc radial mockup, clockwise from top (12 o'clock). */
    public static final List<Slice> SLICES = List.of(
            new Slice("goto", "Go here", "◎"),
            new Slice("mine", "Mine this", "⛏"),
            new Slice("follow", "Follow", "➤"),
            new Slice("build", "Build here", "▦"),
            new Slice("stop", "Stop", "■"),
            new Slice("pov", "Switch POV", "◉")
    );

    private RadialMenu() {}

    /**
     * Slice index {@code 0..SLICES.size()-1} for cursor offset {@code (dx, dy)} from screen center,
     * or {@code -1} inside the center deadzone. 0 is centered at 12 o'clock (-Y), proceeding clockwise.
     */
    public static int sliceAt(double dx, double dy, double deadzone) {
        double dist = Math.hypot(dx, dy);
        if (dist < deadzone) {
            return -1;
        }
        // Angle clockwise from -Y (12 o'clock): atan2(dx, -dy) in [-pi, pi]
        double angle = Math.atan2(dx, -dy);
        if (angle < 0) {
            angle += 2.0 * Math.PI;
        }
        int n = SLICES.size();
        double sector = (2.0 * Math.PI) / n;
        int idx = (int) Math.floor(((angle + sector * 0.5) % (2.0 * Math.PI)) / sector);
        return Math.max(0, Math.min(n - 1, idx));
    }

    /** Exact registry command line (without leading {@code #}) for a slice, target bot, and crosshair hit. */
    public static String commandFor(int sliceIndex, String targetBot, Crosshair hit) {
        if (sliceIndex < 0 || sliceIndex >= SLICES.size()) {
            return "";
        }
        Crosshair ch = hit == null ? Crosshair.NONE : hit;
        String base = switch (SLICES.get(sliceIndex).id()) {
            case "goto" -> ch.hasBlock() ? "goto " + ch.x() + " " + ch.y() + " " + ch.z() : "path";
            case "mine" -> {
                String blk = stripPrefix(ch.blockId());
                yield ch.hasBlock() && !blk.isEmpty() ? "mine " + blk : "mine";
            }
            case "follow" -> (ch.entityName() != null && !ch.entityName().isBlank())
                    ? "follow player " + ch.entityName().trim()
                    : "follow players";
            case "build" -> "blueprint live base";
            case "stop" -> "stop";
            case "pov" -> "pov show";
            default -> "";
        };
        if (base.isEmpty()) {
            return "";
        }
        if (targetBot == null || targetBot.isBlank() || "self".equalsIgnoreCase(targetBot)) {
            return base;
        }
        return "swarm send " + targetBot.trim() + " " + base;
    }

    private static String stripPrefix(String id) {
        if (id == null) return "";
        String t = id.trim();
        return t.startsWith("minecraft:") ? t.substring("minecraft:".length()) : t;
    }
}

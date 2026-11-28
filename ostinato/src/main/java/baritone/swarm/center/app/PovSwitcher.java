package baritone.swarm.center.app;

import baritone.swarm.center.TileRoute;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ostinato: P4 Bot POV switching state machine (design doc: Bot POV switching).
 * Pure: selects render-swap for Hidden bots (reusing {@code RenderSwap.requestPresent/releasePresent} via
 * {@code #pov show|hide}) and full-stream view for Headless bots, generates exact routed registry command lines,
 * and supports cycling through bots or returning to the user's own player.
 */
public final class PovSwitcher {

    public enum Mode {
        NONE("none"),
        RENDER_SWAP("render-swap"),
        STREAM("stream");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    public record Transition(String watching, Mode mode, List<String> commands, String note) {}

    private String watching;
    private Mode mode = Mode.NONE;

    public String watching() {
        return watching;
    }

    public Mode mode() {
        return mode;
    }

    public boolean isWatching() {
        return watching != null && !watching.isEmpty();
    }

    /** Capability-driven POV mode for one bot's {@code runMode} string (matches {@code ViewCapability}). */
    public static Mode modeFor(BotFeed feed) {
        if (feed == null || feed.runMode() == null) {
            return Mode.STREAM;
        }
        String rm = feed.runMode().trim().toLowerCase(Locale.ROOT);
        if ("hidden".equals(rm)) {
            return Mode.RENDER_SWAP;
        }
        if ("headless".equals(rm)) {
            return Mode.STREAM;
        }
        // Visible/normal clients can still be watched via stream preview in the command center.
        return Mode.STREAM;
    }

    /** Switch POV to {@code bot}. Releases any previously render-swapped bot first. */
    public Transition watch(String bot, SwarmView view) {
        if (bot == null || bot.isBlank() || view == null || view.bot(bot) == null) {
            return new Transition(watching, mode, List.of(), "bot not found");
        }
        List<String> cmds = new ArrayList<>();
        String host = view.hostBot();
        if (watching != null && !watching.equals(bot) && mode == Mode.RENDER_SWAP) {
            cmds.add(routed(watching, host, "pov hide"));
        }
        SwarmView.BotState target = view.bot(bot);
        Mode nextMode = modeFor(target.feed());
        if (nextMode == Mode.RENDER_SWAP) {
            cmds.add(routed(bot, host, "pov show"));
        }
        watching = bot;
        mode = nextMode;
        String note = "Watching " + bot + " (" + nextMode.label + ")";
        return new Transition(watching, mode, cmds, note);
    }

    /** Stop watching any bot and return to the user's own player. */
    public Transition returnToOwn(SwarmView view) {
        List<String> cmds = new ArrayList<>();
        if (watching != null && mode == Mode.RENDER_SWAP) {
            String host = view == null ? null : view.hostBot();
            cmds.add(routed(watching, host, "pov hide"));
        }
        String prev = watching;
        watching = null;
        mode = Mode.NONE;
        return new Transition(null, Mode.NONE, cmds, prev == null ? "own POV" : "returned from " + prev);
    }

    /** Cycle to the next (delta +1) or previous (delta -1) bot in {@code view}. */
    public Transition cycle(SwarmView view, int delta) {
        if (view == null || view.bots().isEmpty()) {
            return new Transition(watching, mode, List.of(), "no bots");
        }
        List<SwarmView.BotState> bots = view.bots();
        int idx = -1;
        for (int i = 0; i < bots.size(); i++) {
            if (bots.get(i).name().equals(watching)) {
                idx = i;
                break;
            }
        }
        int next = idx < 0 ? 0 : Math.floorMod(idx + (delta >= 0 ? 1 : -1), bots.size());
        return watch(bots.get(next).name(), view);
    }

    private static String routed(String bot, String host, String line) {
        String target = TileRoute.target(bot, host);
        return target == null ? line : "swarm send " + target + " " + line;
    }
}

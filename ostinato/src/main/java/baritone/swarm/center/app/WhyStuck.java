package baritone.swarm.center.app;

import baritone.swarm.center.feed.Alert;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: P2 Why-stuck diagnostic tracker for the command center.
 * Pure: observes {@link SwarmView} refreshes to detect bots whose position has stalled while an active process
 * or path is running, tracks path-calculation failures and last attempted goal, and produces both a concise
 * diagnostic summary and a one-click repath command line.
 */
public final class WhyStuck {

    /** A bot with an active process that moves less than 0.5 blocks for this long is flagged stalled. */
    public static final long STALL_MS = 5_000L;

    public record Report(boolean stuck, String reason, int failCount, String lastGoal, String repathLine,
                         long stalledMs) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(stuck ? "STUCK: " + reason : "OK: " + reason);
            if (failCount > 0) {
                sb.append(" · failures ").append(failCount);
            }
            if (stalledMs >= STALL_MS) {
                sb.append(" · stalled ").append(stalledMs / 1000).append(" s");
            }
            if (lastGoal != null && !lastGoal.isEmpty()) {
                sb.append(" · last goal ").append(lastGoal);
            }
            return sb.toString();
        }
    }

    private static final class Track {
        double lastX, lastY, lastZ;
        boolean havePos;
        long movedAtMs;
        int failCount;
        String lastFailState = "";
        int[] lastGoalPos;
        String lastGoalText = "";
    }

    private final Map<String, Track> tracks = new HashMap<>();

    /** Record the latest view snapshot so stall duration and failure counts accumulate across refreshes. */
    public void observe(SwarmView view) {
        if (view == null) {
            return;
        }
        long now = view.readAtMs();
        for (SwarmView.BotState b : view.bots()) {
            Track t = tracks.computeIfAbsent(b.name(), k -> new Track());
            BotFeed f = b.feed();
            BotFeed.Pos p = f.pos();
            if (p != null) {
                if (!t.havePos || distSq(p.x(), p.y(), p.z(), t.lastX, t.lastY, t.lastZ) > 0.25) {
                    t.lastX = p.x();
                    t.lastY = p.y();
                    t.lastZ = p.z();
                    t.havePos = true;
                    t.movedAtMs = now;
                }
            }
            BotFeed.Route route = f.path();
            if (route.goalPos() != null && route.goalPos().length >= 3) {
                t.lastGoalPos = route.goalPos().clone();
            }
            if (route.goal() != null && !route.goal().isBlank()) {
                t.lastGoalText = route.goal();
            }
            String st = route.state() == null ? "" : route.state().toLowerCase(java.util.Locale.ROOT);
            boolean failing = st.contains("fail") || st.contains("stuck");
            if (failing && !st.equals(t.lastFailState)) {
                t.failCount++;
            }
            t.lastFailState = st;
        }
    }

    /** Compute the current Why-stuck report for {@code bot}. */
    public Report report(SwarmView view, String bot) {
        if (view == null || bot == null) {
            return new Report(false, "no bot selected", 0, "", "path", 0);
        }
        SwarmView.BotState b = view.bot(bot);
        if (b == null) {
            return new Report(false, "bot not in view", 0, "", "path", 0);
        }
        Track t = tracks.get(bot);
        BotFeed f = b.feed();
        List<Alert> alerts = view.alertsFor(bot);
        int fails = t == null ? 0 : t.failCount;
        int[] goalPos = f.path().goalPos() != null ? f.path().goalPos() : (t == null ? null : t.lastGoalPos);
        String goalStr = goalPos != null && goalPos.length >= 3
                ? goalPos[0] + " " + goalPos[1] + " " + goalPos[2]
                : (t != null && !t.lastGoalText.isEmpty() ? t.lastGoalText : "");
        String repathLine = goalPos != null && goalPos.length >= 3
                ? "goto " + goalPos[0] + " " + goalPos[1] + " " + goalPos[2]
                : "path";

        // Check executions for failed/unknown outcomes
        for (BotFeed.Execution ex : f.executions()) {
            if ("failed".equalsIgnoreCase(ex.execution()) && fails == 0) {
                fails = 1;
            }
        }

        if (f.dead()) {
            return new Report(true, "bot is dead", fails, goalStr, repathLine, 0);
        }
        for (Alert a : alerts) {
            if (a.code() == Alert.Code.OFFLINE || a.code() == Alert.Code.LINK_DOWN) {
                return new Report(true, a.text(), fails, goalStr, repathLine, 0);
            }
            if (a.code() == Alert.Code.STUCK || a.code() == Alert.Code.FAILED) {
                long stalled = stalledMs(view, b, t);
                return new Report(true, a.text(), Math.max(fails, 1), goalStr, repathLine, stalled);
            }
        }
        String pState = f.path().state() == null ? "idle" : f.path().state();
        if (pState.toLowerCase(java.util.Locale.ROOT).contains("fail")) {
            return new Report(true, "path calculation failed (" + pState + ")", Math.max(fails, 1),
                    goalStr, repathLine, stalledMs(view, b, t));
        }
        long stalled = stalledMs(view, b, t);
        boolean active = !"idle".equalsIgnoreCase(f.process()) || !"idle".equalsIgnoreCase(pState);
        if (active && stalled >= STALL_MS) {
            return new Report(true, "no movement during " + f.process() + " (" + pState + ")", fails,
                    goalStr, repathLine, stalled);
        }
        if ("idle".equalsIgnoreCase(f.process()) && "idle".equalsIgnoreCase(pState)) {
            return new Report(false, "idle", fails, goalStr, repathLine, 0);
        }
        return new Report(false, f.process() + " · " + pState, fails, goalStr, repathLine, 0);
    }

    private static long stalledMs(SwarmView view, SwarmView.BotState b, Track t) {
        if (t == null || !t.havePos) {
            return 0;
        }
        return Math.max(0, view.readAtMs() - t.movedAtMs);
    }

    private static double distSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2, dy = y1 - y2, dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }
}

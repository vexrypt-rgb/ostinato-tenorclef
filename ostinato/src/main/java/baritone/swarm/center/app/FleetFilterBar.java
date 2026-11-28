package baritone.swarm.center.app;

import baritone.swarm.center.TileStatus;
import baritone.swarm.center.feed.Alerts;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: fleet filter strip at the top of the Mission control wall. Provides live status/lease filter pills with
 * live counts, an incremental text filter, a {@link CoordinatorFeed} mode/lease summary, and aggregate TPS / JVM RAM
 * readouts.
 *
 * <p>Names: {@code filter.bar}, {@code filter.all}, {@code filter.alerts}, {@code filter.active},
 * {@code filter.leased}, {@code filter.idle}, {@code filter.search}, {@code filter.coord}, {@code filter.metrics}.
 */
public final class FleetFilterBar extends JPanel {

    public enum Filter {
        ALL("all", "All"),
        ALERTS("alerts", TileStatus.Level.WARN.glyph + " Alerts"),
        ACTIVE("active", TileStatus.Level.OK.glyph + " Active"),
        LEASED("leased", "Leased"),
        IDLE("idle", TileStatus.Level.IDLE.glyph + " Idle");

        private final String wire;
        private final String label;

        Filter(String wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        public String wire() {
            return wire;
        }

        public String label() {
            return label;
        }
    }

    public record Counts(int all, int alerts, int active, int leased, int idle) {
        public int forFilter(Filter filter) {
            return switch (filter) {
                case ALL -> all;
                case ALERTS -> alerts;
                case ACTIVE -> active;
                case LEASED -> leased;
                case IDLE -> idle;
            };
        }
    }

    private final AppStyle style;
    private final Map<Filter, JToggleButton> pills = new EnumMap<>(Filter.class);
    private final JTextField search;
    private final JLabel coord;
    private final JLabel metrics;
    private Filter active = Filter.ALL;
    private Runnable onChange = () -> {};
    private boolean suppressing;

    public FleetFilterBar(AppStyle style) {
        super(new BorderLayout(8, 0));
        this.style = style;
        setName("filter.bar");
        setBackground(style.surface());
        setOpaque(true);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, style.line()),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));

        JPanel left = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 0), null);
        ButtonGroup group = new ButtonGroup();
        for (Filter f : Filter.values()) {
            JToggleButton pill = style.toggle(f.label() + " (0)");
            pill.setName("filter." + f.wire());
            pill.setFont(AppStyle.font(Font.PLAIN, 11f));
            pill.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(style.line(), 1),
                    BorderFactory.createEmptyBorder(2, 7, 2, 7)));
            pill.addActionListener(e -> {
                if (!suppressing && active != f) {
                    active = f;
                    refreshPillBorders();
                    onChange.run();
                }
            });
            group.add(pill);
            pills.put(f, pill);
            left.add(pill);
        }
        pills.get(Filter.ALL).setSelected(true);
        refreshPillBorders();
        add(left, BorderLayout.WEST);

        JPanel center = style.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), null);
        search = style.field(14, "Filter bots...", "Filter by bot name, task, status, or lease");
        search.setName("filter.search");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { notifySearch(); }
            @Override public void removeUpdate(DocumentEvent e) { notifySearch(); }
            @Override public void changedUpdate(DocumentEvent e) { notifySearch(); }
        });
        center.add(search);
        add(center, BorderLayout.CENTER);

        JPanel right = style.panel(new FlowLayout(FlowLayout.RIGHT, 10, 0), null);
        coord = style.label("No coordinator", style.muted(), Font.PLAIN, 11f);
        coord.setName("filter.coord");
        metrics = style.label("TPS n/a · RAM n/a", style.muted(), Font.PLAIN, 11f);
        metrics.setName("filter.metrics");
        right.add(coord);
        right.add(metrics);
        add(right, BorderLayout.EAST);
    }

    private void notifySearch() {
        if (!suppressing) {
            onChange.run();
        }
    }

    public void onChange(Runnable listener) {
        onChange = listener == null ? () -> {} : listener;
    }

    public Filter activeFilter() {
        return active;
    }

    public void setActiveFilter(Filter filter) {
        Filter next = filter == null ? Filter.ALL : filter;
        suppressing = true;
        try {
            active = next;
            JToggleButton b = pills.get(next);
            if (b != null) {
                b.setSelected(true);
            }
            refreshPillBorders();
        } finally {
            suppressing = false;
        }
    }

    public String query() {
        return search.getText();
    }

    public void setQuery(String text) {
        suppressing = true;
        try {
            search.setText(text == null ? "" : text);
        } finally {
            suppressing = false;
        }
    }

    public void update(SwarmView view) {
        SwarmView safe = view == null ? SwarmView.EMPTY : view;
        Counts c = count(safe);
        for (Filter f : Filter.values()) {
            JToggleButton b = pills.get(f);
            if (b != null) {
                int n = c.forFilter(f);
                b.setText(f.label() + " (" + n + ")");
                if (f == Filter.ALERTS) {
                    b.setForeground(n > 0 ? style.warn() : style.muted());
                } else if (f == Filter.ACTIVE) {
                    b.setForeground(n > 0 ? style.ok() : style.muted());
                } else {
                    b.setForeground(style.text());
                }
            }
        }
        coord.setText(coordinatorSummary(safe.coordinator()));
        coord.setForeground(safe.coordinator() == null ? style.muted()
                : "refused".equalsIgnoreCase(safe.coordinator().mode()) ? style.warn() : style.text());
        metrics.setText(fleetMetrics(safe));
        refreshPillBorders();
    }

    private void refreshPillBorders() {
        for (Map.Entry<Filter, JToggleButton> e : pills.entrySet()) {
            boolean sel = e.getKey() == active;
            e.getValue().setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(sel ? style.accent() : style.line(), sel ? 2 : 1),
                    BorderFactory.createEmptyBorder(sel ? 1 : 2, sel ? 6 : 7, sel ? 1 : 2, sel ? 6 : 7)));
        }
    }

    /** Filters {@code view.bots()} by active pill and case-insensitive substring query. */
    public static List<SwarmView.BotState> filter(SwarmView view, Filter filter, String query) {
        SwarmView safe = view == null ? SwarmView.EMPTY : view;
        Filter f = filter == null ? Filter.ALL : filter;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<SwarmView.BotState> out = new ArrayList<>();
        for (SwarmView.BotState b : safe.bots()) {
            if (!matchesFilter(safe, b, f)) {
                continue;
            }
            if (!q.isEmpty() && !matchesQuery(safe, b, q)) {
                continue;
            }
            out.add(b);
        }
        return out;
    }

    public static Counts count(SwarmView view) {
        SwarmView safe = view == null ? SwarmView.EMPTY : view;
        int all = 0, alerts = 0, active = 0, leased = 0, idle = 0;
        for (SwarmView.BotState b : safe.bots()) {
            all++;
            TileStatus s = Alerts.status(b, safe.alertsFor(b.name()));
            if (s.alert()) {
                alerts++;
            } else if (s.level() == TileStatus.Level.OK) {
                active++;
            } else {
                idle++;
            }
            if (isLeased(safe, b)) {
                leased++;
            }
        }
        return new Counts(all, alerts, active, leased, idle);
    }

    public static boolean isLeased(SwarmView view, SwarmView.BotState bot) {
        if (bot == null) {
            return false;
        }
        for (BotFeed.HeldLease l : bot.feed().leases()) {
            if (l.active() || !l.resource().isEmpty()) {
                return true;
            }
        }
        if (view != null && view.coordinator() != null) {
            for (CoordinatorFeed.Lease l : view.coordinator().leases()) {
                if (l.live() && l.bot().equalsIgnoreCase(bot.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String leaseSummary(SwarmView view, SwarmView.BotState bot) {
        if (bot == null) {
            return "";
        }
        for (BotFeed.HeldLease l : bot.feed().leases()) {
            if (l.active() || !l.resource().isEmpty()) {
                String left = l.leftMs() < 0 ? "" : " " + (l.leftMs() / 1000) + "s";
                String phase = l.phase().isEmpty() ? (l.active() ? "active" : "") : l.phase();
                return l.resource() + (phase.isEmpty() && left.isEmpty() ? "" : " (" + (phase + left).trim() + ")");
            }
        }
        if (view != null && view.coordinator() != null) {
            for (CoordinatorFeed.Lease l : view.coordinator().leases()) {
                if (l.live() && l.bot().equalsIgnoreCase(bot.name())) {
                    return l.resource() + " (" + l.state() + ")";
                }
            }
        }
        return "";
    }

    public static String coordinatorSummary(CoordinatorFeed coord) {
        if (coord == null) {
            return "Coord none";
        }
        int live = 0;
        for (CoordinatorFeed.Lease l : coord.leases()) {
            if (l.live()) {
                live++;
            }
        }
        String mode = coord.mode().isEmpty() ? "idle" : coord.mode();
        return "Coord " + mode + " · " + live + (live == 1 ? " lease" : " leases");
    }

    public static String fleetMetrics(SwarmView view) {
        SwarmView safe = view == null ? SwarmView.EMPTY : view;
        long tpsSum = 0;
        int tpsCount = 0;
        long ramUsed = 0;
        long ramMax = 0;
        int ramCount = 0;
        for (SwarmView.BotState b : safe.bots()) {
            BotFeed.Perf p = b.feed().perf();
            if (p.tpsX10() >= 0) {
                tpsSum += p.tpsX10();
                tpsCount++;
            }
            if (p.ramUsedMb() >= 0) {
                ramUsed += p.ramUsedMb();
                if (p.ramMaxMb() > 0) {
                    ramMax += p.ramMaxMb();
                }
                ramCount++;
            }
        }
        String tps = tpsCount == 0 ? "TPS n/a"
                : String.format(Locale.ROOT, "%.1f tps", (tpsSum / (double) tpsCount) / 10.0);
        String ram = ramCount == 0 ? "RAM n/a"
                : (ramMax > 0 ? ramUsed + "/" + ramMax + " MB" : ramUsed + " MB");
        return tps + " · " + ram;
    }

    private static boolean matchesFilter(SwarmView view, SwarmView.BotState bot, Filter filter) {
        TileStatus s = Alerts.status(bot, view.alertsFor(bot.name()));
        return switch (filter) {
            case ALL -> true;
            case ALERTS -> s.alert();
            case ACTIVE -> !s.alert() && s.level() == TileStatus.Level.OK;
            case LEASED -> isLeased(view, bot);
            case IDLE -> !s.alert() && s.level() == TileStatus.Level.IDLE;
        };
    }

    private static boolean matchesQuery(SwarmView view, SwarmView.BotState bot, String q) {
        if (bot.name().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (TileText.task(bot.feed()).toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        TileStatus s = Alerts.status(bot, view.alertsFor(bot.name()));
        if (s.word().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        String lease = leaseSummary(view, bot);
        return !lease.isEmpty() && lease.toLowerCase(Locale.ROOT).contains(q);
    }
}

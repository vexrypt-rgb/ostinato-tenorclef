package baritone.swarm.center.app;

import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileRoute;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.feed.Alerts;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.ToolTipManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Ostinato: Tier 3 exception-first cluster heatmap ({@link DensityPolicy.Tier#CLUSTER}). Designed for large fleets
 * (50, 100, 500+ bots) with {@code O(1)} Swing component overhead:
 * <ol>
 *   <li>Pinned <b>Exception Rail</b> at top showing alerting bots with a one-click {@code Cancel All Exceptions}.</li>
 *   <li><b>Squad / Lease Rollup Strip</b> grouping bots by active {@link CoordinatorFeed} / {@code HeldLease} resource
 *       with one-click {@code Select Group}.</li>
 *   <li>Custom-painted {@code O(1)} {@link HeatmapCanvas} supporting click-to-inspect, Shift-click / drag range
 *       selection, and hover tooltips.</li>
 * </ol>
 *
 * <p>Names: {@code wall.cluster}, {@code cluster.exceptions}, {@code cluster.exceptions.summary},
 * {@code cluster.exceptions.cancelAll}, {@code cluster.leases}, {@code cluster.heatmap}.
 */
final class ClusterHeatmapView extends JPanel {

    static final int MAX_EXCEPTION_CHIPS = 9;
    static final int MAX_LEASE_GROUPS = 10;

    public record LeaseGroup(String key, List<String> bots, int alerts, int active) {}

    private final AppStyle style;
    private final Commands commands;
    private final JLabel exceptionSummary;
    private final JButton cancelAllExceptions;
    private final JPanel exceptionChips;
    private final JPanel leaseStrip;
    private final HeatmapCanvas canvas;
    private SwarmView last = SwarmView.EMPTY;
    private List<SwarmView.BotState> currentExceptions = List.of();
    private List<SwarmView.BotState> currentBots = List.of();
    private Set<String> multiSelected = new LinkedHashSet<>();
    private String inspected;
    private Consumer<String> onInspect = n -> {};
    private Consumer<String> onOpen = n -> {};
    private Consumer<Set<String>> onMultiSelect = s -> {};

    ClusterHeatmapView(AppStyle style, Commands commands) {
        super(new BorderLayout(0, 6));
        this.style = style;
        this.commands = commands;
        setName("wall.cluster");
        setBackground(style.bg());
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JPanel top = style.panel(null, style.bg());
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        // 1. Pinned Exception Rail
        JPanel excPanel = style.panel(new BorderLayout(6, 4), style.surface());
        excPanel.setName("cluster.exceptions");
        excPanel.setBorder(style.box(style.line(), 1, 6));
        excPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel excHead = style.panel(new BorderLayout(8, 0), null);
        exceptionSummary = style.label(TileStatus.Level.IDLE.glyph + " No exceptions", style.muted(), Font.BOLD, 11f);
        exceptionSummary.setName("cluster.exceptions.summary");
        cancelAllExceptions = style.dangerButton("Cancel All Exceptions");
        cancelAllExceptions.setName("cluster.exceptions.cancelAll");
        cancelAllExceptions.setFont(AppStyle.font(Font.PLAIN, 11f));
        cancelAllExceptions.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(style.danger(), 1),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        cancelAllExceptions.addActionListener(e -> cancelExceptions());
        excHead.add(exceptionSummary, BorderLayout.WEST);
        excHead.add(cancelAllExceptions, BorderLayout.EAST);
        excPanel.add(excHead, BorderLayout.NORTH);

        exceptionChips = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 2), null);
        excPanel.add(exceptionChips, BorderLayout.CENTER);
        top.add(excPanel);
        top.add(Box.createVerticalStrut(6));

        // 2. Squad / Lease Rollup Strip
        leaseStrip = style.panel(new FlowLayout(FlowLayout.LEFT, 6, 3), style.surface());
        leaseStrip.setName("cluster.leases");
        leaseStrip.setBorder(style.box(style.line(), 1, 4));
        leaseStrip.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(leaseStrip);
        add(top, BorderLayout.NORTH);

        // 3. O(1) Custom-painted HeatmapCanvas
        canvas = new HeatmapCanvas();
        JScrollPane scroll = new JScrollPane(canvas);
        scroll.setBackground(style.bg());
        scroll.getViewport().setBackground(style.surface());
        scroll.setBorder(BorderFactory.createLineBorder(style.line(), 1));
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);
    }

    void onInspect(Consumer<String> listener) {
        onInspect = listener == null ? n -> {} : listener;
    }

    void onOpenOnMap(Consumer<String> listener) {
        onOpen = listener == null ? n -> {} : listener;
    }

    void onMultiSelectChanged(Consumer<Set<String>> listener) {
        onMultiSelect = listener == null ? s -> {} : listener;
    }

    HeatmapCanvas canvas() {
        return canvas;
    }

    void update(SwarmView view, List<SwarmView.BotState> filtered, String inspectedBot, Set<String> selected) {
        last = view == null ? SwarmView.EMPTY : view;
        inspected = inspectedBot;
        multiSelected = selected == null ? new LinkedHashSet<>() : new LinkedHashSet<>(selected);
        currentBots = RosterTableView.sortExceptionsFirst(last, filtered);

        List<SwarmView.BotState> exc = new ArrayList<>();
        int bad = 0, warn = 0;
        for (SwarmView.BotState b : currentBots) {
            TileStatus s = Alerts.status(b, last.alertsFor(b.name()));
            if (s.alert()) {
                exc.add(b);
                if (s.level() == TileStatus.Level.BAD) {
                    bad++;
                } else {
                    warn++;
                }
            }
        }
        currentExceptions = List.copyOf(exc);
        rebuildExceptionRail(exc, bad, warn);
        rebuildLeaseRollup(groupByLease(last, currentBots));
        canvas.setData(last, currentBots, inspected, multiSelected);
    }

    private void rebuildExceptionRail(List<SwarmView.BotState> exc, int bad, int warn) {
        if (exc.isEmpty()) {
            exceptionSummary.setText(TileStatus.Level.IDLE.glyph + " No exceptions");
            exceptionSummary.setForeground(style.muted());
            cancelAllExceptions.setEnabled(false);
        } else {
            exceptionSummary.setText(TileStatus.Level.BAD.glyph + " " + bad + " critical · "
                    + TileStatus.Level.WARN.glyph + " " + warn + " warnings (" + exc.size() + " total)");
            exceptionSummary.setForeground(bad > 0 ? style.danger() : style.warn());
            cancelAllExceptions.setEnabled(true);
        }
        exceptionChips.removeAll();
        int shown = Math.min(exc.size(), MAX_EXCEPTION_CHIPS);
        for (int i = 0; i < shown; i++) {
            SwarmView.BotState b = exc.get(i);
            TileStatus s = Alerts.status(b, last.alertsFor(b.name()));
            JButton chip = style.button(s.level().glyph + " " + b.name() + " · " + s.word());
            chip.setFont(AppStyle.font(Font.PLAIN, 10.5f));
            chip.setForeground(style.level(s.level()));
            chip.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(style.level(s.level()), 1),
                    BorderFactory.createEmptyBorder(1, 6, 1, 6)));
            String botName = b.name();
            chip.addActionListener(e -> onInspect.accept(botName));
            exceptionChips.add(chip);
        }
        if (exc.size() > shown) {
            exceptionChips.add(style.label("+" + (exc.size() - shown) + " more", style.muted(), Font.PLAIN, 11f));
        }
        exceptionChips.setVisible(!exc.isEmpty());
        exceptionChips.revalidate();
        exceptionChips.repaint();
    }

    private void rebuildLeaseRollup(List<LeaseGroup> groups) {
        leaseStrip.removeAll();
        leaseStrip.add(style.label("Squads / Leases:", style.muted(), Font.BOLD, 11f));
        if (groups.isEmpty()) {
            leaseStrip.add(style.label("none", style.faint(), Font.PLAIN, 11f));
        } else {
            int shown = Math.min(groups.size(), MAX_LEASE_GROUPS);
            for (int i = 0; i < shown; i++) {
                LeaseGroup g = groups.get(i);
                JPanel card = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 1), style.raised());
                card.setBorder(BorderFactory.createLineBorder(style.line(), 1));
                String summary = g.key() + " (" + g.bots().size() + ")";
                if (g.alerts() > 0) {
                    summary += " " + TileStatus.Level.WARN.glyph + g.alerts();
                } else if (g.active() > 0) {
                    summary += " " + TileStatus.Level.OK.glyph + g.active();
                }
                JLabel lbl = style.label(summary, g.alerts() > 0 ? style.warn() : style.text(), Font.PLAIN, 11f);
                JButton selectBtn = style.button("Select Group");
                selectBtn.setName("cluster.lease.select." + i);
                selectBtn.setFont(AppStyle.font(Font.PLAIN, 10f));
                selectBtn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(style.accent(), 1),
                        BorderFactory.createEmptyBorder(1, 5, 1, 5)));
                List<String> groupBots = g.bots();
                selectBtn.addActionListener(e -> {
                    Set<String> next = new LinkedHashSet<>(groupBots);
                    multiSelected = next;
                    onMultiSelect.accept(next);
                    canvas.repaint();
                });
                card.add(lbl);
                card.add(selectBtn);
                leaseStrip.add(card);
            }
            if (groups.size() > shown) {
                leaseStrip.add(style.label("+" + (groups.size() - shown) + " groups", style.muted(), Font.PLAIN, 11f));
            }
        }
        leaseStrip.revalidate();
        leaseStrip.repaint();
    }

    private void cancelExceptions() {
        ButtonModel cancelForm = cancelForm();
        for (SwarmView.BotState b : currentExceptions) {
            String bot = b.name();
            String cancelId = TileText.cancelTarget(b.feed().executions());
            if (cancelForm != null && cancelId != null) {
                commands.press(cancelForm, Map.of("id", cancelId), bot, null);
            } else {
                String target = TileRoute.target(bot, last.hostBot());
                commands.line(target == null ? "cancel" : "swarm send " + target + " cancel", bot, null);
            }
        }
    }

    private ButtonModel cancelForm() {
        if (last.catalog() == null) {
            return null;
        }
        for (ButtonModel m : last.catalog().models(FormPlacement.TILE)) {
            if ("cancel".equals(m.form().id())) {
                return m;
            }
        }
        return null;
    }

    /** Groups bots by active {@link CoordinatorFeed} lease or held lease resource (fallback {@code "Unleased"}). */
    public static List<LeaseGroup> groupByLease(SwarmView view, List<SwarmView.BotState> bots) {
        if (bots == null || bots.isEmpty()) {
            return List.of();
        }
        SwarmView safe = view == null ? SwarmView.EMPTY : view;
        Map<String, String> botLease = new LinkedHashMap<>();
        if (safe.coordinator() != null) {
            for (CoordinatorFeed.Lease l : safe.coordinator().leases()) {
                if (l.live() && !l.bot().isBlank()) {
                    String label = leaseBucket(l.type(), l.resource());
                    botLease.putIfAbsent(l.bot().toLowerCase(Locale.ROOT), label);
                }
            }
        }
        Map<String, List<SwarmView.BotState>> grouped = new LinkedHashMap<>();
        for (SwarmView.BotState b : bots) {
            String bucket = botLease.get(b.name().toLowerCase(Locale.ROOT));
            if (bucket == null) {
                for (BotFeed.HeldLease hl : b.feed().leases()) {
                    if (hl.active() || !hl.resource().isEmpty()) {
                        bucket = leaseBucket("", hl.resource());
                        break;
                    }
                }
            }
            if (bucket == null || bucket.isBlank()) {
                bucket = "Unleased";
            }
            grouped.computeIfAbsent(bucket, k -> new ArrayList<>()).add(b);
        }
        List<LeaseGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<SwarmView.BotState>> e : grouped.entrySet()) {
            List<String> names = new ArrayList<>();
            int alerts = 0;
            int active = 0;
            for (SwarmView.BotState b : e.getValue()) {
                names.add(b.name());
                TileStatus s = Alerts.status(b, safe.alertsFor(b.name()));
                if (s.alert()) {
                    alerts++;
                } else if (s.level() == TileStatus.Level.OK) {
                    active++;
                }
            }
            out.add(new LeaseGroup(e.getKey(), List.copyOf(names), alerts, active));
        }
        return out;
    }

    private static String leaseBucket(String type, String resource) {
        String r = resource == null ? "" : resource.trim();
        if (r.isEmpty()) {
            return type == null || type.isBlank() ? "Unleased" : type;
        }
        if (r.length() > 22) {
            r = r.substring(0, 19) + "...";
        }
        return (type == null || type.isBlank() || "key".equals(type)) ? r : type + ":" + r;
    }

    /** Custom-painted {@code O(1)} fleet grid canvas with click, Shift-range, drag-select, and hover tooltips. */
    final class HeatmapCanvas extends JComponent implements Scrollable {

        private static final int PAD = 8;
        private static final int GAP = 4;

        private SwarmView view = SwarmView.EMPTY;
        private List<SwarmView.BotState> bots = List.of();
        private String inspectedBot;
        private Set<String> selectedBots = Set.of();
        private int anchorIndex = -1;
        private Point dragStart;
        private Point dragCurrent;

        HeatmapCanvas() {
            setName("cluster.heatmap");
            setFocusable(false);
            setOpaque(true);
            setBackground(style.surface());
            ToolTipManager.sharedInstance().registerComponent(this);

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON1) {
                        return;
                    }
                    dragStart = e.getPoint();
                    dragCurrent = null;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragStart != null) {
                        dragCurrent = e.getPoint();
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON1) {
                        return;
                    }
                    if (dragStart != null && dragCurrent != null
                            && dragStart.distance(dragCurrent) >= 6.0) {
                        applyDragSelection(dragRect(dragStart, dragCurrent), e.isShiftDown());
                        dragStart = null;
                        dragCurrent = null;
                        repaint();
                        return;
                    }
                    dragStart = null;
                    dragCurrent = null;
                    int idx = indexAt(e.getX(), e.getY());
                    if (idx < 0 || idx >= bots.size()) {
                        return;
                    }
                    String name = bots.get(idx).name();
                    if (e.isShiftDown() && anchorIndex >= 0 && anchorIndex < bots.size()) {
                        applyRangeSelection(anchorIndex, idx);
                    } else {
                        anchorIndex = idx;
                    }
                    onInspect.accept(name);
                    if (e.getClickCount() >= 2) {
                        onOpen.accept(name);
                    }
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        void setData(SwarmView view, List<SwarmView.BotState> bots, String inspected, Set<String> selected) {
            this.view = view == null ? SwarmView.EMPTY : view;
            this.bots = bots == null ? List.of() : List.copyOf(bots);
            this.inspectedBot = inspected;
            this.selectedBots = selected == null ? Set.of() : Set.copyOf(selected);
            revalidate();
            repaint();
        }

        int botCount() {
            return bots.size();
        }

        int cellWidth() {
            int n = bots.size();
            if (n > 240) {
                return 32;
            }
            if (n > 100) {
                return 52;
            }
            return 74;
        }

        int cellHeight() {
            return bots.size() > 240 ? 24 : 28;
        }

        int columns(int width) {
            int cw = cellWidth();
            int avail = Math.max(cw, width - PAD * 2);
            return Math.max(1, (avail + GAP) / (cw + GAP));
        }

        Rectangle cellBounds(int index) {
            int cols = columns(getWidth() > 0 ? getWidth() : 1000);
            int cw = cellWidth();
            int ch = cellHeight();
            int col = index % cols;
            int row = index / cols;
            return new Rectangle(PAD + col * (cw + GAP), PAD + row * (ch + GAP), cw, ch);
        }

        int indexAt(int x, int y) {
            if (bots.isEmpty() || x < PAD || y < PAD) {
                return -1;
            }
            int cw = cellWidth();
            int ch = cellHeight();
            int cols = columns(getWidth() > 0 ? getWidth() : 1000);
            int col = (x - PAD) / (cw + GAP);
            int row = (y - PAD) / (ch + GAP);
            if (col < 0 || col >= cols || row < 0) {
                return -1;
            }
            int cx = PAD + col * (cw + GAP);
            int cy = PAD + row * (ch + GAP);
            if (x > cx + cw || y > cy + ch) {
                return -1;
            }
            int idx = row * cols + col;
            return idx < bots.size() ? idx : -1;
        }

        private void applyRangeSelection(int from, int to) {
            int a = Math.min(from, to);
            int b = Math.max(from, to);
            Set<String> next = new LinkedHashSet<>(selectedBots);
            for (int i = a; i <= b; i++) {
                next.add(bots.get(i).name());
            }
            selectedBots = next;
            onMultiSelect.accept(next);
        }

        private void applyDragSelection(Rectangle rect, boolean additive) {
            Set<String> next = additive ? new LinkedHashSet<>(selectedBots) : new LinkedHashSet<>();
            for (int i = 0; i < bots.size(); i++) {
                if (rect.intersects(cellBounds(i))) {
                    next.add(bots.get(i).name());
                }
            }
            selectedBots = next;
            onMultiSelect.accept(next);
        }

        private static Rectangle dragRect(Point a, Point b) {
            int x = Math.min(a.x, b.x);
            int y = Math.min(a.y, b.y);
            return new Rectangle(x, y, Math.abs(a.x - b.x), Math.abs(a.y - b.y));
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int idx = indexAt(event.getX(), event.getY());
            if (idx < 0 || idx >= bots.size()) {
                return null;
            }
            SwarmView.BotState b = bots.get(idx);
            TileStatus s = Alerts.status(b, view.alertsFor(b.name()));
            String lease = FleetFilterBar.leaseSummary(view, b);
            return s.level().glyph + " " + b.name() + " — " + s.word()
                    + " · " + TileText.task(b.feed())
                    + (lease.isEmpty() ? "" : " · Lease " + lease)
                    + " · " + TileText.position(b.feed());
        }

        @Override
        public Dimension getPreferredSize() {
            int w = getParent() != null && getParent().getWidth() > 0 ? getParent().getWidth() : 1000;
            int cols = columns(w);
            int rows = bots.isEmpty() ? 1 : (bots.size() + cols - 1) / cols;
            int h = PAD * 2 + rows * (cellHeight() + GAP);
            return new Dimension(w, Math.max(120, h));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(style.surface());
                g.fillRect(0, 0, getWidth(), getHeight());

                if (bots.isEmpty()) {
                    g.setColor(style.muted());
                    g.setFont(AppStyle.font(Font.PLAIN, 12f));
                    g.drawString("No bots match the active filter.", PAD + 4, PAD + 20);
                    return;
                }

                int cols = columns(getWidth());
                int cw = cellWidth();
                int ch = cellHeight();
                Font labelFont = AppStyle.font(Font.PLAIN, cw <= 36 ? 10f : 11f);
                g.setFont(labelFont);
                FontMetrics fm = g.getFontMetrics();
                Rectangle clip = g.getClipBounds();

                for (int i = 0; i < bots.size(); i++) {
                    int col = i % cols;
                    int row = i / cols;
                    int x = PAD + col * (cw + GAP);
                    int y = PAD + row * (ch + GAP);
                    if (clip != null && (y + ch < clip.y || y > clip.y + clip.height)) {
                        continue;
                    }
                    SwarmView.BotState b = bots.get(i);
                    TileStatus s = Alerts.status(b, view.alertsFor(b.name()));
                    Color levelColor = style.level(s.level());
                    boolean isInspected = inspectedBot != null && inspectedBot.equalsIgnoreCase(b.name());
                    boolean isMulti = selectedBots.contains(b.name());

                    g.setColor(isInspected || isMulti ? style.raised() : style.bg());
                    g.fillRect(x, y, cw, ch);

                    // Left status accent stripe (always paired with glyph inside cell)
                    g.setColor(levelColor);
                    g.fillRect(x, y, 4, ch);

                    // Cell border
                    Color border = isInspected || isMulti ? style.accent()
                            : (s.alert() ? levelColor : style.line());
                    g.setColor(border);
                    g.drawRect(x, y, cw - 1, ch - 1);
                    if (isInspected || isMulti) {
                        g.drawRect(x + 1, y + 1, cw - 3, ch - 3);
                    }

                    // Glyph + abbreviated bot name
                    int baseline = y + (ch - fm.getHeight()) / 2 + fm.getAscent();
                    g.setColor(levelColor);
                    g.drawString(s.level().glyph, x + 6, baseline);
                    if (cw > 36) {
                        int glyphW = fm.stringWidth(s.level().glyph) + 3;
                        String text = fit(b.name(), fm, cw - 9 - glyphW);
                        g.setColor(style.text());
                        g.drawString(text, x + 6 + glyphW, baseline);
                    }
                }

                if (dragStart != null && dragCurrent != null) {
                    Rectangle r = dragRect(dragStart, dragCurrent);
                    Color accent = style.accent();
                    g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 48));
                    g.fillRect(r.x, r.y, r.width, r.height);
                    g.setColor(accent);
                    g.drawRect(r.x, r.y, r.width, r.height);
                }
            } finally {
                g.dispose();
            }
        }

        private static String fit(String s, FontMetrics fm, int maxW) {
            if (maxW <= 4 || fm.stringWidth(s) <= maxW) {
                return s;
            }
            String out = s;
            while (out.length() > 1 && fm.stringWidth(out + "…") > maxW) {
                out = out.substring(0, out.length() - 1);
            }
            return out + "…";
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return cellHeight() + GAP; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(cellHeight(), r.height - cellHeight()); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}

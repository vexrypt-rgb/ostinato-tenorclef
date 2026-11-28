package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.feed.Alerts;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Ostinato: Phase 3 window, the adaptive Mission control wall (default layout). Uses viewport-relative
 * {@link DensityPolicy} thresholds to switch smoothly between:
 * <ul>
 *   <li>{@link DensityPolicy.Tier#CARDS} — full interactive {@link TileView} grid ({@code wall.grid},
 *       {@code tile:<bot>:*}).</li>
 *   <li>{@link DensityPolicy.Tier#ROSTER} — virtualized {@code O(1)} {@link RosterTableView} sorted
 *       exceptions-first.</li>
 *   <li>{@link DensityPolicy.Tier#CLUSTER} — exception-first {@link ClusterHeatmapView} with pinned exception rail,
 *       lease rollup strip, and custom-painted {@code O(1)} heatmap canvas.</li>
 * </ul>
 * Includes {@link FleetFilterBar} and {@link BatchDispatchBar} at top and the upgraded {@link FocusPanel} at right.
 */
public final class WallView implements LayoutView {

    public static final int TILE_WIDTH = 330;

    private final AppStyle style;
    private final Commands commands;
    private final DensityPolicy policy;
    private final FleetFilterBar filterBar;
    private final BatchDispatchBar batchBar;
    private final CardLayout tierCards = new CardLayout();
    private final ViewportHost centerHost;
    private final Grid grid;
    private final JScrollPane scroll;
    private final JLabel empty;
    private final JPanel holder;
    private final RosterTableView roster;
    private final ClusterHeatmapView cluster;
    private final JPanel root;
    private final FocusPanel focus;
    private final Map<String, TileView> tiles = new LinkedHashMap<>();
    private List<String> shown = List.of();
    private List<SwarmView.BotState> filteredBots = List.of();
    private String selected;
    private final Set<String> multiSelected = new LinkedHashSet<>();
    private SwarmView last = SwarmView.EMPTY;
    private DensityPolicy.Override densityOverride = DensityPolicy.Override.AUTO;
    private DensityPolicy.Tier activeTier;
    private Consumer<String> onOpen = name -> {};
    private Consumer<String> onWatchPov = name -> {};
    private Consumer<DensityPolicy.Tier> onTierChanged = tier -> {};

    public WallView(AppStyle style, Commands commands) {
        this(style, commands, DensityPolicy.DEFAULT);
    }

    public WallView(AppStyle style, Commands commands, DensityPolicy policy) {
        this.style = style;
        this.commands = commands;
        this.policy = policy == null ? DensityPolicy.DEFAULT : policy;

        filterBar = new FleetFilterBar(style);
        filterBar.onChange(this::applyFilterAndRender);

        batchBar = new BatchDispatchBar(style, commands);
        batchBar.onSelectionActions(this::selectFiltered, this::selectAlerts, this::clearMultiSelection);

        JPanel topBars = style.panel(null, style.surface());
        topBars.setLayout(new BoxLayout(topBars, BoxLayout.Y_AXIS));
        topBars.add(filterBar);
        topBars.add(batchBar);

        grid = new Grid();
        grid.setBackground(style.bg());
        grid.setName("wall.grid");
        holder = style.panel(new BorderLayout(), style.bg());
        holder.add(grid, BorderLayout.NORTH);
        empty = style.label("No bot feeds yet. Turn on swarmCenterFeed on the bots and point them at this directory.",
                style.muted(), Font.PLAIN, 13f);
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setName("wall.empty");
        holder.add(empty, BorderLayout.CENTER);
        scroll = new JScrollPane(holder);
        scroll.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        scroll.getViewport().setBackground(style.bg());
        scroll.setBackground(style.bg());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setName("wall");
        AbstractAction clearFocusAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                select(null);
            }
        };
        scroll.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "focus-clear");
        scroll.getActionMap().put("focus-clear", clearFocusAction);

        roster = new RosterTableView(style, commands);
        roster.onInspect(this::select);
        roster.onOpenOnMap(name -> onOpen.accept(name));
        roster.onMultiSelectChanged(this::replaceMultiSelection);

        cluster = new ClusterHeatmapView(style, commands);
        cluster.onInspect(this::select);
        cluster.onOpenOnMap(name -> onOpen.accept(name));
        cluster.onMultiSelectChanged(this::replaceMultiSelection);

        centerHost = new ViewportHost();
        centerHost.setBackground(style.bg());
        centerHost.setOpaque(true);
        centerHost.setName("wall.tiers");
        centerHost.add(scroll, DensityPolicy.Tier.CARDS.wire());
        centerHost.add(roster, DensityPolicy.Tier.ROSTER.wire());
        centerHost.add(cluster, DensityPolicy.Tier.CLUSTER.wire());
        centerHost.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                reevaluateDensity();
            }
        });

        focus = new FocusPanel(style, commands, () -> select(null), name -> onOpen.accept(name));
        root = style.panel(new BorderLayout(), style.bg());
        root.setName("wall.frame");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "focus-clear");
        root.getActionMap().put("focus-clear", clearFocusAction);
        root.add(topBars, BorderLayout.NORTH);
        root.add(centerHost, BorderLayout.CENTER);
        root.add(focus, BorderLayout.EAST);

        switchTier(DensityPolicy.Tier.CARDS, false);
    }

    @Override
    public CenterLayout layout() {
        return CenterLayout.WALL;
    }

    @Override
    public JComponent component() {
        return root;
    }

    public DensityPolicy policy() {
        return policy;
    }

    public DensityPolicy.Tier activeTier() {
        return activeTier == null ? DensityPolicy.Tier.CARDS : activeTier;
    }

    public DensityPolicy.Override densityOverride() {
        return densityOverride;
    }

    public void setDensityOverride(DensityPolicy.Override override) {
        DensityPolicy.Override next = override == null ? DensityPolicy.Override.AUTO : override;
        if (next == densityOverride) {
            return;
        }
        densityOverride = next;
        reevaluateDensity();
        renderActiveTier();
    }

    public void onTierChanged(Consumer<DensityPolicy.Tier> listener) {
        onTierChanged = listener == null ? t -> {} : listener;
    }

    public FleetFilterBar.Filter activeFilter() {
        return filterBar.activeFilter();
    }

    public void setActiveFilter(FleetFilterBar.Filter filter) {
        filterBar.setActiveFilter(filter);
        applyFilterAndRender();
    }

    public String searchQuery() {
        return filterBar.query();
    }

    public void setSearchQuery(String query) {
        filterBar.setQuery(query);
        applyFilterAndRender();
    }

    public Set<String> multiSelected() {
        return Set.copyOf(multiSelected);
    }

    public void setMultiSelected(Collection<String> names) {
        multiSelected.clear();
        if (names != null) {
            for (String n : names) {
                if (n != null && !n.isBlank()) {
                    multiSelected.add(n);
                }
            }
        }
        syncSelectionUI();
    }

    /** The selected bot, or null when the wall is showing the full grid. */
    public String selected() {
        return selected;
    }

    /** Double-click on a tile/row/cell or FocusPanel Open on Map. Switches to the map centered on that bot. */
    public void onOpenOnMap(Consumer<String> open) {
        onOpen = open == null ? name -> {} : open;
    }

    /** Ostinato: P4 Watch / Switch POV callback from wall tiles or the focus panel. */
    public void onWatchPov(Consumer<String> watch) {
        onWatchPov = watch == null ? name -> {} : watch;
        focus.onWatchPov(onWatchPov);
    }

    /** Opens the focus panel on {@code name}, or closes it when {@code name} is null. */
    public void select(String name) {
        selected = name == null || name.isBlank() ? null : name;
        applySelection();
    }

    @Override
    public void update(SwarmView view) {
        last = view == null ? SwarmView.EMPTY : view;
        focus.observe(last);
        if (selected != null && last.bot(selected) == null) {
            selected = null;
        }
        multiSelected.removeIf(name -> last.bot(name) == null);
        filterBar.update(last);
        applyFilterAndRender();
    }

    private void applyFilterAndRender() {
        filteredBots = FleetFilterBar.filter(last, filterBar.activeFilter(), filterBar.query());
        reevaluateDensity();
        renderActiveTier();
        batchBar.update(last, multiSelected);
        applySelection();
    }

    void reevaluateDensity() {
        int w = centerHost.getWidth();
        int h = centerHost.getHeight();
        DensityPolicy.Tier next = policy.evaluate(filteredBots.size(), w, h, densityOverride, activeTier);
        if (next != activeTier) {
            switchTier(next, true);
            renderActiveTier();
        }
    }

    private void switchTier(DensityPolicy.Tier next, boolean notify) {
        activeTier = next;
        tierCards.show(centerHost, next.wire());
        if (next != DensityPolicy.Tier.CARDS && !tiles.isEmpty()) {
            // Ostinato: drop per-bot Swing tiles outside Cards mode so Roster and Cluster stay O(1).
            grid.removeAll();
            tiles.clear();
            shown = List.of();
        }
        centerHost.revalidate();
        centerHost.repaint();
        if (notify) {
            onTierChanged.accept(next);
        }
    }

    private void renderActiveTier() {
        DensityPolicy.Tier tier = activeTier();
        if (tier == DensityPolicy.Tier.CARDS) {
            renderCards();
        } else if (tier == DensityPolicy.Tier.ROSTER) {
            roster.update(last, filteredBots, selected, multiSelected);
        } else {
            cluster.update(last, filteredBots, selected, multiSelected);
        }
    }

    private void renderCards() {
        List<String> names = filteredBots.stream().map(SwarmView.BotState::name).toList();
        if (!names.equals(shown)) {
            grid.removeAll();
            Map<String, TileView> keep = new LinkedHashMap<>();
            for (String n : names) {
                TileView t = tiles.get(n);
                if (t == null) {
                    t = new TileView(style, commands, n);
                    String bot = n;
                    t.onSelect(() -> select(bot));
                    t.onToggleMulti(() -> toggleMulti(bot));
                    t.onOpen(() -> onOpen.accept(bot));
                    t.onWatchPov(() -> onWatchPov.accept(bot));
                }
                keep.put(n, t);
            }
            tiles.clear();
            tiles.putAll(keep);
            for (TileView t : tiles.values()) {
                grid.add(t.root);
            }
            shown = names;
            empty.setText(last.bots().isEmpty()
                    ? "No bot feeds yet. Turn on swarmCenterFeed on the bots and point them at this directory."
                    : "No bots match the active filter.");
            empty.setVisible(names.isEmpty());
            grid.revalidate();
            grid.repaint();
        }
        for (SwarmView.BotState b : filteredBots) {
            TileView t = tiles.get(b.name());
            if (t != null) {
                t.update(last, b);
            }
        }
    }

    private void toggleMulti(String bot) {
        if (!multiSelected.remove(bot)) {
            multiSelected.add(bot);
        }
        syncSelectionUI();
    }

    private void replaceMultiSelection(Set<String> next) {
        multiSelected.clear();
        if (next != null) {
            multiSelected.addAll(next);
        }
        syncSelectionUI();
    }

    private void selectFiltered() {
        multiSelected.clear();
        for (SwarmView.BotState b : filteredBots) {
            multiSelected.add(b.name());
        }
        syncSelectionUI();
    }

    private void selectAlerts() {
        multiSelected.clear();
        for (SwarmView.BotState b : last.bots()) {
            TileStatus s = Alerts.status(b, last.alertsFor(b.name()));
            if (s.alert()) {
                multiSelected.add(b.name());
            }
        }
        syncSelectionUI();
    }

    private void clearMultiSelection() {
        multiSelected.clear();
        syncSelectionUI();
    }

    private void syncSelectionUI() {
        batchBar.update(last, multiSelected);
        if (activeTier() == DensityPolicy.Tier.CARDS) {
            for (Map.Entry<String, TileView> e : tiles.entrySet()) {
                e.getValue().setMultiSelected(multiSelected.contains(e.getKey()));
            }
        } else if (activeTier() == DensityPolicy.Tier.ROSTER) {
            roster.update(last, filteredBots, selected, multiSelected);
        } else {
            cluster.update(last, filteredBots, selected, multiSelected);
        }
    }

    private void applySelection() {
        if (activeTier() == DensityPolicy.Tier.CARDS) {
            for (Map.Entry<String, TileView> e : tiles.entrySet()) {
                e.getValue().setSelected(e.getKey().equals(selected));
                e.getValue().setMultiSelected(multiSelected.contains(e.getKey()));
            }
        } else if (activeTier() == DensityPolicy.Tier.ROSTER) {
            roster.update(last, filteredBots, selected, multiSelected);
        } else {
            cluster.update(last, filteredBots, selected, multiSelected);
        }
        focus.show(last, selected);
        root.revalidate();
        root.repaint();
    }

    /** The tile for a bot, for tests. */
    TileView tile(String bot) {
        return tiles.get(bot);
    }

    RosterTableView rosterView() {
        return roster;
    }

    ClusterHeatmapView clusterView() {
        return cluster;
    }

    /** Re-evaluates viewport-relative density on layout passes too (including headless resize tests). */
    private final class ViewportHost extends JPanel {
        private int lastW = -1;
        private int lastH = -1;

        ViewportHost() {
            super(tierCards);
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            if (w > 0 && h > 0 && (w != lastW || h != lastH)) {
                lastW = w;
                lastH = h;
                reevaluateDensity();
            }
            super.doLayout();
        }
    }

    /** Columns follow the viewport width; rows scroll. */
    private final class Grid extends JPanel implements Scrollable {
        Grid() {
            super(new GridLayout(0, 3, 12, 12));
        }

        @Override
        public void doLayout() {
            int cols = Math.max(1, (scroll.getViewport().getWidth() + 12) / (TILE_WIDTH + 12));
            GridLayout g = (GridLayout) getLayout();
            if (g.getColumns() != cols) {
                g.setColumns(cols);
            }
            super.doLayout();
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(16, r.height - 32); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}

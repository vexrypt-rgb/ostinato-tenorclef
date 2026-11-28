package baritone.swarm.center.app;

import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileRoute;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.feed.Alerts;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Ostinato: Tier 2 virtualized roster view ({@link DensityPolicy.Tier#ROSTER}). Renders an entire fleet inside a
 * single {@link JTable} ({@code O(1)} Swing components), sorted exceptions-first
 * ({@code ✖ BAD -> ▲ WARN -> ● OK -> ○ IDLE}), with multi-select checkboxes, inline {@code HeldLease} status,
 * vitals, coordinates, TPS/ping, and single-click row Cancel.
 *
 * <p>Names: {@code wall.roster}, {@code wall.roster.table}.
 */
final class RosterTableView extends JPanel {

    static final int COL_SELECT = 0;
    static final int COL_BOT = 1;
    static final int COL_STATUS = 2;
    static final int COL_TASK = 3;
    static final int COL_LEASE = 4;
    static final int COL_VITALS = 5;
    static final int COL_COORDS = 6;
    static final int COL_PERF = 7;
    static final int COL_CANCEL = 8;

    private static final String[] COLUMNS = {
            "Sel", "Bot", "Status", "Task", "Lease", "Vitals", "Coords", "TPS / Ping", "Action"
    };

    private static final int[] WIDTHS = {34, 118, 118, 190, 145, 118, 138, 112, 68};

    private final AppStyle style;
    private final Commands commands;
    private final Model model = new Model();
    private final JTable table;
    private SwarmView last = SwarmView.EMPTY;
    private List<Row> rows = List.of();
    private Set<String> multiSelected = new LinkedHashSet<>();
    private String inspected;
    private int anchorRow = -1;
    private Consumer<String> onInspect = n -> {};
    private Consumer<String> onOpen = n -> {};
    private Consumer<Set<String>> onMultiSelect = s -> {};

    record Row(SwarmView.BotState bot, TileStatus status, String task, String lease, String vitals,
               boolean lowVitals, String coords, String perf, String cancelTarget) {}

    RosterTableView(AppStyle style, Commands commands) {
        super(new BorderLayout());
        this.style = style;
        this.commands = commands;
        setName("wall.roster");
        setBackground(style.bg());
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        table = new JTable(model);
        table.setName("wall.roster.table");
        table.setFocusable(false);
        table.setRowHeight(DensityPolicy.DEFAULT_ROSTER_ROW_HEIGHT);
        table.setBackground(style.surface());
        table.setForeground(style.text());
        table.setGridColor(style.line());
        table.setSelectionBackground(style.raised());
        table.setSelectionForeground(style.text());
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.getTableHeader().setReorderingAllowed(false);

        JTableHeader header = table.getTableHeader();
        header.setBackground(style.raised());
        header.setForeground(style.muted());
        header.setFont(AppStyle.font(Font.BOLD, 11f));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, style.line()));

        for (int i = 0; i < WIDTHS.length; i++) {
            TableColumn col = table.getColumnModel().getColumn(i);
            col.setPreferredWidth(WIDTHS[i]);
            if (i == COL_SELECT) {
                col.setMaxWidth(40);
                col.setMinWidth(30);
                col.setCellRenderer(new CheckRenderer());
            } else if (i == COL_CANCEL) {
                col.setMaxWidth(76);
                col.setMinWidth(60);
                col.setCellRenderer(new ActionRenderer());
            } else {
                col.setCellRenderer(new CellRenderer());
            }
        }

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                int rowIdx = table.rowAtPoint(e.getPoint());
                int colIdx = table.columnAtPoint(e.getPoint());
                if (rowIdx < 0 || rowIdx >= rows.size()) {
                    return;
                }
                Row row = rows.get(rowIdx);
                String bot = row.bot().name();
                if (colIdx == COL_SELECT) {
                    toggleMulti(bot);
                    anchorRow = rowIdx;
                    return;
                }
                if (colIdx == COL_CANCEL) {
                    cancelBot(row);
                    return;
                }
                if (e.isShiftDown() && anchorRow >= 0 && anchorRow < rows.size()) {
                    selectRange(anchorRow, rowIdx);
                } else {
                    anchorRow = rowIdx;
                }
                onInspect.accept(bot);
                if (e.getClickCount() >= 2) {
                    onOpen.accept(bot);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setColumnHeaderView(header);
        scroll.setBackground(style.bg());
        scroll.getViewport().setBackground(style.surface());
        scroll.setBorder(BorderFactory.createLineBorder(style.line(), 1));
        scroll.getVerticalScrollBar().setUnitIncrement(DensityPolicy.DEFAULT_ROSTER_ROW_HEIGHT);
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

    void update(SwarmView view, List<SwarmView.BotState> filtered, String inspectedBot, Set<String> selected) {
        last = view == null ? SwarmView.EMPTY : view;
        inspected = inspectedBot;
        multiSelected = selected == null ? new LinkedHashSet<>() : new LinkedHashSet<>(selected);
        List<SwarmView.BotState> sorted = sortExceptionsFirst(last, filtered);
        List<Row> built = new ArrayList<>(sorted.size());
        for (SwarmView.BotState b : sorted) {
            TileStatus status = Alerts.status(b, last.alertsFor(b.name()));
            String step = TileDetails.progress(b.feed().path());
            String task = TileText.task(b.feed()) + (step.isEmpty() ? "" : " (" + step + ")");
            String lease = FleetFilterBar.leaseSummary(last, b);
            int hp = b.feed().health();
            int food = b.feed().hunger();
            String vitals = "HP " + (hp < 0 ? "n/a" : hp + "/20") + " · F " + (food < 0 ? "n/a" : food + "/20");
            boolean lowVitals = (hp >= 0 && hp <= Alerts.LOW_HEALTH) || (food >= 0 && food <= Alerts.HUNGRY);
            String coords = compactCoords(b.feed());
            String perf = compactPerf(b.feed().perf());
            String cancelTarget = TileText.cancelTarget(b.feed().executions());
            built.add(new Row(b, status, task, lease.isEmpty() ? "—" : lease, vitals, lowVitals, coords, perf,
                    cancelTarget));
        }
        rows = List.copyOf(built);
        model.fireTableDataChanged();
    }

    JTable table() {
        return table;
    }

    int rowCount() {
        return rows.size();
    }

    String botAtRow(int row) {
        return row >= 0 && row < rows.size() ? rows.get(row).bot().name() : null;
    }

    /** Sorts bots exceptions-first: {@code ✖ BAD -> ▲ WARN -> ● OK -> ○ IDLE}, then name ascending. */
    public static List<SwarmView.BotState> sortExceptionsFirst(SwarmView view, List<SwarmView.BotState> bots) {
        if (bots == null || bots.isEmpty()) {
            return List.of();
        }
        SwarmView safe = view == null ? SwarmView.EMPTY : view;
        List<SwarmView.BotState> copy = new ArrayList<>(bots);
        copy.sort(Comparator
                .comparingInt((SwarmView.BotState b) -> severityRank(Alerts.status(b, safe.alertsFor(b.name())).level()))
                .thenComparing(b -> b.name().toLowerCase(Locale.ROOT)));
        return copy;
    }

    static int severityRank(TileStatus.Level level) {
        return switch (level) {
            case BAD -> 0;
            case WARN -> 1;
            case OK -> 2;
            case IDLE -> 3;
        };
    }

    private void toggleMulti(String bot) {
        Set<String> next = new LinkedHashSet<>(multiSelected);
        if (!next.remove(bot)) {
            next.add(bot);
        }
        multiSelected = next;
        onMultiSelect.accept(next);
        model.fireTableDataChanged();
    }

    private void selectRange(int from, int to) {
        int a = Math.min(from, to);
        int b = Math.max(from, to);
        Set<String> next = new LinkedHashSet<>(multiSelected);
        for (int i = a; i <= b; i++) {
            next.add(rows.get(i).bot().name());
        }
        multiSelected = next;
        onMultiSelect.accept(next);
        model.fireTableDataChanged();
    }

    private void cancelBot(Row row) {
        String bot = row.bot().name();
        ButtonModel form = cancelForm();
        if (form != null && row.cancelTarget() != null) {
            commands.press(form, Map.of("id", row.cancelTarget()), bot, null);
        } else {
            String target = TileRoute.target(bot, last.hostBot());
            commands.line(target == null ? "cancel" : "swarm send " + target + " cancel", bot, null);
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

    private static String compactCoords(BotFeed f) {
        BotFeed.Pos p = f.pos();
        return p == null ? "not in world" : p.blockX() + ", " + p.blockY() + ", " + p.blockZ() + " (" + p.dim() + ")";
    }

    private static String compactPerf(BotFeed.Perf p) {
        String tps = p.tpsX10() < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", p.tpsX10() / 10.0);
        String ping = p.pingMs() < 0 ? "n/a" : p.pingMs() + "ms";
        return tps + " tps · " + ping;
    }

    private final class Model extends AbstractTableModel {
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == COL_SELECT ? Boolean.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row r = rows.get(rowIndex);
            return switch (columnIndex) {
                case COL_SELECT -> multiSelected.contains(r.bot().name());
                case COL_BOT -> r.bot().name();
                case COL_STATUS -> r.status().badge();
                case COL_TASK -> r.task();
                case COL_LEASE -> r.lease();
                case COL_VITALS -> r.vitals();
                case COL_COORDS -> r.coords();
                case COL_PERF -> r.perf();
                case COL_CANCEL -> "Cancel";
                default -> "";
            };
        }
    }

    private Color rowBackground(int rowIdx) {
        Row r = rows.get(rowIdx);
        boolean isInspected = inspected != null && inspected.equalsIgnoreCase(r.bot().name());
        boolean isChecked = multiSelected.contains(r.bot().name());
        if (isInspected || isChecked) {
            return style.raised();
        }
        return style.surface();
    }

    private final class CheckRenderer extends JCheckBox implements TableCellRenderer {
        CheckRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(true);
            setFocusable(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            setSelected(Boolean.TRUE.equals(value));
            setBackground(rowBackground(row));
            setForeground(style.text());
            return this;
        }
    }

    private final class CellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            JLabel c = (JLabel) super.getTableCellRendererComponent(t, value, false, false, row, column);
            Row r = rows.get(row);
            c.setBackground(rowBackground(row));
            c.setFont(AppStyle.font(column == COL_BOT || column == COL_STATUS ? Font.BOLD : Font.PLAIN, 11f));
            c.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            if (column == COL_STATUS) {
                c.setForeground(style.level(r.status().level()));
            } else if (column == COL_VITALS && r.lowVitals()) {
                c.setForeground(style.warn());
            } else if (column == COL_LEASE && !"—".equals(r.lease())) {
                c.setForeground(style.accent());
            } else if (column == COL_COORDS || column == COL_PERF) {
                c.setForeground(style.muted());
            } else {
                c.setForeground(style.text());
            }
            return c;
        }
    }

    private final class ActionRenderer extends JLabel implements TableCellRenderer {
        ActionRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(true);
            setFont(AppStyle.font(Font.PLAIN, 11f));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            setText("Cancel");
            setBackground(style.raised());
            setForeground(style.text());
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(style.line(), 1),
                    BorderFactory.createEmptyBorder(2, 4, 2, 4)));
            return this;
        }
    }
}
